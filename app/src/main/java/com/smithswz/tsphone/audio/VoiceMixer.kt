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
    val data: ByteArray,
    val packetId: Int
)

/**
 * Receive pipeline. ts3j's voice handler only enqueues [VoiceFrame]s; the
 * mixer thread handles UDP reordering (per-client sequence dedup + a small
 * jitter hold), decodes with persistent per-client Opus decoders, conceals
 * lost packets via Opus PLC, mixes one 20 ms frame per active client and
 * writes it to AudioTrack. Also tracks who is currently speaking.
 */
class VoiceMixer(
    context: Context,
    private val codec: OpusCodec,
    settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val playbackReference: PlaybackReference,
    private val echoCanceller: EchoCanceller
) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val frameQueue = ArrayBlockingQueue<VoiceFrame>(512)
    private val lastActive = ConcurrentHashMap<Int, Long>()
    private val pending = ConcurrentHashMap<Int, ArrayDeque<VoiceFrame>>()
    private val lastPlayedId = ConcurrentHashMap<Int, Int>()
    private val lastPlayedAt = ConcurrentHashMap<Int, Long>()
    private val lastPcm = ConcurrentHashMap<Int, ShortArray>()
    private val concealStep = ConcurrentHashMap<Int, Int>()

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

    /** The mic's session id — the AEC playback reference. */
    @Volatile
    private var referenceSessionId = 0

    private var lastLog = 0L

    /** Cumulative frames written to the current track (reference keying). */
    private var writePos = 0L

    /** Wall clock of the last track write (write pacing). */
    private var lastWriteMs = 0L

    /** Voice frames offered by ts3j (receive-path loss diagnostic). */
    @Volatile
    private var offeredCount = 0L

    private data class MixDiagnostics(
        var muted: Long = 0,
        var noTrack: Long = 0,
        var idle: Long = 0,
        var paced: Long = 0,
        var noDecode: Long = 0,
        var writes: Long = 0,
        var badWrites: Long = 0,
        var exceptions: Long = 0
    )

    private var mixDiagnostics = MixDiagnostics()

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

    fun setReferenceSessionId(sessionId: Int) {
        referenceSessionId = sessionId
    }

    /** Called from ts3j's connection thread — never blocks. */
    fun offer(frame: VoiceFrame) {
        if (running) {
            offeredCount++
            frameQueue.offer(frame)
        }
    }

    fun start() {
        running = true
        synchronized(trackLock) {
            audioTrack = createTrack()
            if (audioTrack != null) playbackReference.resetForTrack()
        }
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
        playbackReference.clear()
        pending.clear()
        lastActive.clear()
        lastPlayedId.clear()
        lastPlayedAt.clear()
        lastPcm.clear()
        concealStep.clear()
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
                val deque = pending.getOrPut(frame.clientId) { ArrayDeque() }
                synchronized(deque) {
                    if (deque.size < MAX_PENDING_FRAMES) deque.addLast(frame)
                }
            }
            mixAndWrite()
            updateSpeakingState()
            val now = System.currentTimeMillis()
            if (now - lastLog > 10_000) {
                Log.w(
                    "TSPhone",
                    "mixer: pending=${pending.keys.size} speaking=${_speakingClients.value} " +
                        "writePos=${writePos / OpusCodec.FRAME_SIZE} " +
                        "offered=${offeredCount} " +
                        "muted=${mixDiagnostics.muted} noTrack=${mixDiagnostics.noTrack} " +
                        "idle=${mixDiagnostics.idle} paced=${mixDiagnostics.paced} " +
                        "noDecode=${mixDiagnostics.noDecode} " +
                        "writes=${mixDiagnostics.writes} bad=${mixDiagnostics.badWrites} " +
                        "ex=${mixDiagnostics.exceptions}"
                )
                mixDiagnostics = MixDiagnostics()
                lastLog = now
            }
        }
    }

    /** Mixes one frame per recently-active client and writes it out. */
    private fun mixAndWrite() {
        if (outputMuted) {
            mixDiagnostics.muted++
            return // output mute: drop frames, keep routing intact
        }
        // Hold the track lock during the write so a speaker/earpiece switch
        // cannot release the track mid-write (crashed the mixer thread).
        val track = synchronized(trackLock) { audioTrack }
        if (track == null) {
            mixDiagnostics.noTrack++
            return
        }
        val now = System.currentTimeMillis()
        val actives = pending.keys.filter { cid ->
            (lastActive[cid] ?: 0L) + ACTIVE_WINDOW_MS > now
        }
        // Pace writes to real time: at most one 20 ms frame per 20 ms of wall
        // clock, no matter how fast frames arrive. Without this, a network
        // burst drains into the track at loop speed — the speaker plays some
        // frames while others only ever sit in the reference ring, and the
        // playout latency wobbles. The pending deques are the jitter buffer.
        if (now - lastWriteMs < WRITE_INTERVAL_MS) {
            mixDiagnostics.paced++
            return
        }

        val mix = ShortArray(OpusCodec.FRAME_SIZE)
        var anyPcm = false
        val gain = 1.0 / sqrt(actives.size.toDouble())
        for (cid in actives) {
            val deque = pending[cid] ?: continue
            var pcm: ShortArray? = null
            synchronized(deque) {
                if (deque.size >= JITTER_FRAMES) {
                    // Enough frames buffered: play the lowest packet id first.
                    val first = deque.minByOrNull { it.packetId }!!
                    deque.remove(first)
                    val lastId = lastPlayedId[cid]
                    val delta = if (lastId == null) 1 else packetDelta(first.packetId, lastId)
                    if (delta <= 0) {
                        // stale/duplicate after reorder — drop, keep the rest
                        return@synchronized
                    }
                    if (delta > 1) {
                        // gap: conceal the missing frames before this one
                        val missing = minOf(delta - 1, MAX_PLC_FRAMES)
                        repeat(missing) {
                            concealFrame(cid)?.let { acc -> accumulate(mix, acc, gain); anyPcm = true }
                        }
                    }
                    lastPlayedId[cid] = first.packetId
                    pcm = codec.decode(cid, first.data)
                    if (pcm != null) {
                        lastPcm[cid] = pcm
                        concealStep[cid] = 0
                    }
                    lastPlayedAt[cid] = now
                } else if (deque.isEmpty() &&
                    now - (lastActive[cid] ?: 0) < PLC_WINDOW_MS &&
                    now - (lastPlayedAt[cid] ?: 0) >= PLC_INTERVAL_MS
                ) {
                    // Active but nothing arrived — conceal one frame.
                    pcm = concealFrame(cid)
                    lastPlayedAt[cid] = now
                }
            }
            if (pcm != null) {
                accumulate(mix, pcm, gain)
                anyPcm = true
            }
        }
        // Always write at the paced 50 fps — real audio when available,
        // silence otherwise. Writing only on arrivals drains the track buffer
        // between speech bursts, so the playout delay wobbles by up to the
        // buffer size and the AEC reference never holds a fixed delay.
        // The silence frame also keeps the reference stream continuous, which
        // an adaptive filter needs (a gappy reference keeps resetting it).
        if (!anyPcm) mixDiagnostics.noDecode++
        try {
            val written = synchronized(trackLock) { track.write(mix, 0, mix.size) }
            if (written > 0) {
                mixDiagnostics.writes++
                lastWriteMs = now
                // Push exactly what was enqueued — one reference entry per
                // written frame, keyed by play index.
                val ref = if (written < mix.size) mix.copyOfRange(0, written) else mix
                playbackReference.push(writePos, ref)
                writePos += written
            } else {
                mixDiagnostics.badWrites++
                Log.w("TSPhone", "track.write returned $written")
            }
        } catch (e: IllegalStateException) {
            mixDiagnostics.exceptions++
            // track released by a speaker/earpiece switch — next iteration uses the new one
        }
    }

    private fun silenceFrame(): ShortArray = SILENCE

    /**
     * Frame-repetition concealment: repeats the last decoded frame with a
     * fading attenuation (0.8 → 0.6 → 0.4 → 0.2) so a lost packet becomes a
     * smooth decay instead of a click. Also nudges the Opus decoder state so
     * the next real frame decodes cleanly.
     */
    private fun concealFrame(clientId: Int): ShortArray? {
        val last = lastPcm[clientId] ?: return null
        val step = concealStep[clientId] ?: 0
        if (step >= CONCEAL_STEPS) return null
        codec.advanceDecoderState(clientId)
        concealStep[clientId] = step + 1
        val attenuation = CONCEAL_ATTENUATION[step]
        return ShortArray(OpusCodec.FRAME_SIZE) { i -> (last[i] * attenuation).toInt().toShort() }
    }

    private fun accumulate(mix: ShortArray, frame: ShortArray, gain: Double) {
        for (i in mix.indices) {
            val v = mix[i] + (frame[i] * gain).toInt()
            mix[i] = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    /** ushort packet-id delta; negative/zero means stale or duplicate. */
    private fun packetDelta(id: Int, last: Int): Int {
        val d = (id - last) and 0xFFFF
        return if (d < 32768) d else d - 65536
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
        echoCanceller.reset() // the acoustic echo path changed
        synchronized(trackLock) {
            audioTrack?.runCatching { stop() }
            audioTrack?.release()
            audioTrack = createTrack()
            if (audioTrack != null) playbackReference.resetForTrack() // new track = new epoch
        }
        if (audioTrack == null) Log.w("TSPhone", "track recreate failed (speakerOn=$speakerOn)")
    }

    /**
     * Speaker: media stream in normal mode (loudspeaker). Earpiece: voice
     * communication stream in communication mode without the speakerphone
     * flag (routes to the handset earpiece, like a phone call). The track is
     * created on the mic's audio session so the AEC uses it as reference.
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
        private const val MAX_PENDING_FRAMES = 12  // ~240 ms burst headroom on lossy links
        private const val JITTER_FRAMES = 1        // play arrivals immediately; PLC fills gaps
        private const val MAX_PLC_FRAMES = 6       // conceal at most 120 ms
        private const val PLC_WINDOW_MS = 250L     // conceal only shortly after a burst
        private const val PLC_INTERVAL_MS = 20L    // one concealed frame per 20 ms
        private const val ACTIVE_WINDOW_MS = 1_000L
        private const val SPEAKING_TIMEOUT_MS = 500L
        private const val CONCEAL_STEPS = 4
        private val CONCEAL_ATTENUATION = doubleArrayOf(0.8, 0.6, 0.4, 0.2)
        private const val WRITE_INTERVAL_MS = 20L

        /** Companion constant: safe to reference during property init. */
        private val SILENCE = ShortArray(OpusCodec.FRAME_SIZE)
    }
}
