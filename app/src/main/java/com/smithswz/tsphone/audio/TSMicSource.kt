package com.smithswz.tsphone.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.github.manevolent.ts3j.audio.Microphone
import com.github.manevolent.ts3j.enums.CodecType
import com.smithswz.tsphone.data.prefs.CodecQuality
import com.smithswz.tsphone.data.prefs.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * ts3j [Microphone] implementation: AudioRecord → RMS VAD → gain → Opus
 * encode. The record thread runs continuously while connected; [provide] hands
 * the latest encoded frame to ts3j's poller, and [isReady] flips false when
 * speech (incl. hangover) ends so ts3j emits its end-of-speech packet.
 */
class TSMicSource(
    context: Context,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val codec: OpusCodec,
    private val echoCanceller: EchoCanceller,
    private val playbackReference: PlaybackReference
) : Microphone {

    @Volatile
    private var muted = false

    @Volatile
    private var speaking = false

    private val currentFrame = AtomicReference<ByteArray?>(null)
    private val vad = VadProcessor()

    @Volatile
    private var gain = 1.0f

    @Volatile
    private var codecQuality = CodecQuality.VOICE

    private var recorder: AudioRecord? = null
    private var recordThread: Thread? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    /** The record session the mixer uses as the AEC playback reference. */
    val audioSessionId: Int
        get() = recorder?.audioSessionId ?: 0

    init {
        scope.launch { settings.masterMuted.collect { muted = it } }
        scope.launch {
            combine(settings.vadSensitivity, settings.inputGain) { s, g -> s to g }
                .collect { (s, g) ->
                    vad.sensitivity = s
                    gain = g
                }
        }
        scope.launch { settings.codecQuality.collect { codecQuality = it } }
    }

    fun start() {
        echoCanceller.start()
        val minBuffer = AudioRecord.getMinBufferSize(
            OpusCodec.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuffer, OpusCodec.FRAME_SIZE * 2 * 4)
        // Plain MIC source: on the test Samsung, VOICE_COMMUNICATION silently
        // yields no signal. AEC/noise suppression still attach as session
        // effects (the device's AEC hardware is API-controllable).
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            OpusCodec.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return
        }
        recorder = record
        // Android 10+ requires AEC on the VOICE_COMMUNICATION capture path;
        // enable it (and noise suppression) explicitly on this session.
        attachPreProcessors(record)
        record.startRecording()

        recordThread = Thread({
            val frame = ShortArray(OpusCodec.FRAME_SIZE)
            var framesEncoded = 0L
            var lastLog = 0L
            while (!Thread.currentThread().isInterrupted) {
                val read = record.read(frame, 0, frame.size)
                if (read != frame.size) continue
                // Echo cancellation against the played stream, drained in
                // FIFO order — one entry per capture frame. No clocks needed:
                // Speex's 200 ms filter absorbs the constant pipeline lag.
                val match = playbackReference.nextReference()
                val clean = echoCanceller.process(frame, match)
                speaking = vad.process(clean) && !muted
                if (speaking) {
                    applyGain(clean)
                    currentFrame.set(codec.encode(application(), clean))
                    framesEncoded++
                } else {
                    currentFrame.set(null)
                }
                val now = System.currentTimeMillis()
                if (now - lastLog > 5000) {
                    Log.w(
                        "TSPhone",
                        "mic: speaking=$speaking frames=$framesEncoded refRms=${rmsOf(match.frame)} " +
                            "refIdx=${match.frameIndex} nearRms=${rmsOf(frame)}"
                    )
                    lastLog = now
                }
            }
        }, "ts-mic").apply { start() }
    }


    fun stop() {
        recordThread?.interrupt()
        recordThread = null
        recorder?.runCatching { stop() }
        recorder?.release()
        recorder = null
        noiseSuppressor?.release()
        noiseSuppressor = null
        echoCanceller.stop()
        playbackReference.clear()
        speaking = false
        currentFrame.set(null)
    }

    private fun attachPreProcessors(record: AudioRecord) {
        val sessionId = record.audioSessionId
        // Note: the device AcousticEchoCanceler is deliberately NOT attached —
        // the software Speex AEC needs a clean near-end, and a second (often
        // nonlinear) canceller upstream breaks its adaptation.
        runCatching {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(sessionId)?.let {
                    it.enabled = true
                    noiseSuppressor = it
                    Log.i("TSPhone", "noise suppressor enabled on session $sessionId")
                } ?: Log.w("TSPhone", "noise suppressor create returned null (session $sessionId)")
            } else {
                Log.w("TSPhone", "NoiseSuppressor not available on this device")
            }
        }.onFailure { Log.w("TSPhone", "noise suppressor attach failed: ${it.message}") }
    }

    override fun isMuted(): Boolean = muted

    override fun isReady(): Boolean = speaking

    override fun getCodec(): CodecType =
        if (codecQuality == CodecQuality.MUSIC) CodecType.OPUS_MUSIC else CodecType.OPUS_VOICE

    override fun provide(): ByteArray = currentFrame.get() ?: ByteArray(0)

    private fun application(): Int =
        if (codecQuality == CodecQuality.MUSIC) OpusLib.OPUS_APPLICATION_AUDIO else OpusLib.OPUS_APPLICATION_VOIP

    private fun rmsOf(frame: ShortArray?): Int {
        if (frame == null) return 0
        var sum = 0.0
        for (i in frame.indices) sum += frame[i].toDouble() * frame[i]
        return Math.sqrt(sum / frame.size).toInt()
    }

    private fun applyGain(frame: ShortArray) {
        if (gain == 1.0f) return
        for (i in frame.indices) {
            val v = frame[i] * gain
            frame[i] = v.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
        }
    }

    companion object {
    }
}
