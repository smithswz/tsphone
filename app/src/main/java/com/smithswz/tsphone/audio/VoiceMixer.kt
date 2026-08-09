package com.smithswz.tsphone.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.github.manevolent.ts3j.enums.CodecType
import com.smithswz.tsphone.data.prefs.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/** One voice packet from the TS3 socket (channel or whisper). */
data class VoiceFrame(
    val clientId: Int,
    val codecType: CodecType,
    val data: ByteArray
)

/**
 * Receive pipeline. ts3j's voice handler only enqueues [VoiceFrame]s; a
 * dedicated mixer thread decodes them with persistent per-client Opus
 * decoders, mixes one 20 ms frame per active client and writes it to
 * AudioTrack. Also tracks who is currently speaking for the channel tree.
 */
class VoiceMixer(
    context: Context,
    private val codec: OpusCodec,
    settings: SettingsRepository,
    private val scope: CoroutineScope
) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val frameQueue = ArrayBlockingQueue<VoiceFrame>(512)
    private val lastActive = ConcurrentHashMap<Int, Long>()
    private val pending = ConcurrentHashMap<Int, ArrayDeque<ShortArray>>()

    private val receivedCount = java.util.concurrent.atomic.AtomicLong()
    private val decodedCount = java.util.concurrent.atomic.AtomicLong()
    private var lastLog = 0L

    private val _speakingClients = MutableStateFlow<Set<Int>>(emptySet())
    val speakingClients: StateFlow<Set<Int>> = _speakingClients.asStateFlow()

    private var audioTrack: AudioTrack? = null
    private var mixerThread: Thread? = null
    private val trackLock = Any()

    @Volatile
    private var running = false

    @Volatile
    private var speakerOn = true

    @Volatile
    private var outputMuted = false

    init {
        // Speaker/earpiece changes recreate the track with the right routing.
        scope.launch {
            settings.speakerOn.collect { on ->
                speakerOn = on
                if (running) restartTrack()
            }
        }
        scope.launch { settings.outputMuted.collect { outputMuted = it } }
    }

    /** Called from ts3j's connection thread — never blocks. */
    fun offer(frame: VoiceFrame) {
        if (running) {
            if (receivedCount.getAndIncrement() == 0L) {
                Log.w("TSPhone", "first voice frame: client=${frame.clientId} codec=${frame.codecType} bytes=${frame.data.size}")
            }
            frameQueue.offer(frame)
        }
    }

    fun start() {
        running = true
        synchronized(trackLock) { audioTrack = createTrack() }
        if (audioTrack == null) Log.w("TSPhone", "AudioTrack creation failed — no playback")
        mixerThread = Thread({ loop() }, "ts-mixer").apply { start() }
        Log.i("TSPhone", "voice mixer started (speakerOn=$speakerOn)")
    }

    fun stop() {
        running = false
        mixerThread?.interrupt()
        mixerThread = null
        synchronized(trackLock) {
            audioTrack?.runCatching { stop() }
            audioTrack?.release()
            audioTrack = null
        }
        pending.clear()
        lastActive.clear()
        _speakingClients.value = emptySet()
    }

    private fun loop() {
        while (!Thread.currentThread().isInterrupted && running) {
            // poll throws on interrupt — treat it as the shutdown signal
            val frame = try {
                frameQueue.poll(10, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                break
            }
            if (frame != null) {
                lastActive[frame.clientId] = System.currentTimeMillis()
                val pcm = runCatching { codec.decode(frame.clientId, frame.data) }.onFailure {
                    if (decodedCount.get() == 0L) Log.w("TSPhone", "decode failed: ${it.message}")
                }.getOrNull()
                if (pcm != null) {
                    decodedCount.incrementAndGet()
                    val deque = pending.getOrPut(frame.clientId) { ArrayDeque() }
                    synchronized(deque) {
                        if (deque.size < MAX_PENDING_FRAMES) deque.addLast(pcm)
                    }
                }
            }
            mixAndWrite()
            updateSpeakingState()
            val now = System.currentTimeMillis()
            if (now - lastLog > 10_000) {
                Log.w("TSPhone", "mixer: received=${receivedCount.get()} decoded=${decodedCount.get()} speaking=${_speakingClients.value}")
                lastLog = now
            }
        }
    }

    /** Mixes one frame per recently-active client and writes it out. */
    private fun mixAndWrite() {
        if (outputMuted) return // output mute: drop frames, keep routing intact
        // Hold the track lock during the write so a speaker/earpiece switch
        // cannot release the track mid-write (crashed the mixer thread).
        val track = synchronized(trackLock) { audioTrack } ?: return
        val now = System.currentTimeMillis()
        val actives = pending.keys.filter { cid ->
            (lastActive[cid] ?: 0L) + ACTIVE_WINDOW_MS > now
        }
        if (actives.isEmpty()) return

        val mix = ShortArray(OpusCodec.FRAME_SIZE)
        val gain = 1.0 / sqrt(actives.size.toDouble())
        for (cid in actives) {
            val deque = pending[cid] ?: continue
            val frame = synchronized(deque) { deque.pollFirst() } ?: continue
            for (i in mix.indices) {
                val v = mix[i] + (frame[i] * gain).toInt()
                mix[i] = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
        try {
            synchronized(trackLock) { track.write(mix, 0, mix.size) }
        } catch (e: IllegalStateException) {
            // track released by a speaker/earpiece switch — next iteration uses the new one
        }
    }

    private fun updateSpeakingState() {
        val now = System.currentTimeMillis()
        val speaking = lastActive.entries
            .filter { now - it.value < SPEAKING_TIMEOUT_MS }
            .map { it.key }
            .toSet()
        if (speaking != _speakingClients.value) {
            _speakingClients.value = speaking
        }
    }

    /** Re-routes output after a speaker/earpiece change. */
    private fun restartTrack() {
        synchronized(trackLock) {
            audioTrack?.runCatching { stop() }
            audioTrack?.release()
            audioTrack = createTrack()
        }
        if (audioTrack == null) Log.w("TSPhone", "track recreate failed (speakerOn=$speakerOn)")
    }

    /**
     * Speaker: media stream in normal mode (loudspeaker). Earpiece: voice
     * communication stream in communication mode without the speakerphone
     * flag (routes to the handset earpiece, like a phone call).
     */
    private fun createTrack(): AudioTrack? {
        val earpiece = !speakerOn
        if (earpiece) {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = false
        } else {
            audioManager.mode = AudioManager.MODE_NORMAL
        }
        val minBuffer = AudioTrack.getMinBufferSize(
            OpusCodec.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(
                        if (earpiece) AudioAttributes.USAGE_VOICE_COMMUNICATION
                        else AudioAttributes.USAGE_MEDIA
                    )
                    // MUSIC content forces the loudspeaker on Samsung; SPEECH
                    // content routes to the earpiece there.
                    .setContentType(
                        if (earpiece) AudioAttributes.CONTENT_TYPE_SPEECH
                        else AudioAttributes.CONTENT_TYPE_MUSIC
                    )
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(OpusCodec.SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer, OpusCodec.FRAME_SIZE * 2 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        return if (track.state == AudioTrack.STATE_INITIALIZED) {
            track.play()
            track
        } else {
            track.release()
            null
        }
    }

    companion object {
        private const val MAX_PENDING_FRAMES = 4
        private const val ACTIVE_WINDOW_MS = 1_000L
        private const val SPEAKING_TIMEOUT_MS = 500L
    }
}
