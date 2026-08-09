package com.smithswz.tsphone.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
    private val codec: OpusCodec
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
        val minBuffer = AudioRecord.getMinBufferSize(
            OpusCodec.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuffer, OpusCodec.FRAME_SIZE * 2 * 4)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
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
        record.startRecording()

        recordThread = Thread({
            val frame = ShortArray(OpusCodec.FRAME_SIZE)
            var framesEncoded = 0L
            var lastLog = 0L
            while (!Thread.currentThread().isInterrupted) {
                val read = record.read(frame, 0, frame.size)
                if (read != frame.size) continue
                speaking = vad.process(frame) && !muted
                if (speaking) {
                    applyGain(frame)
                    currentFrame.set(codec.encode(application(), frame))
                    framesEncoded++
                } else {
                    currentFrame.set(null)
                }
                val now = System.currentTimeMillis()
                if (now - lastLog > 5000) {
                    Log.i("TSPhone", "mic: speaking=$speaking framesEncoded=$framesEncoded")
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
        speaking = false
        currentFrame.set(null)
    }

    override fun isMuted(): Boolean = muted

    override fun isReady(): Boolean = speaking

    override fun getCodec(): CodecType =
        if (codecQuality == CodecQuality.MUSIC) CodecType.OPUS_MUSIC else CodecType.OPUS_VOICE

    override fun provide(): ByteArray = currentFrame.get() ?: ByteArray(0)

    private fun application(): Int =
        if (codecQuality == CodecQuality.MUSIC) OpusLib.OPUS_APPLICATION_AUDIO else OpusLib.OPUS_APPLICATION_VOIP

    private fun applyGain(frame: ShortArray) {
        if (gain == 1.0f) return
        for (i in frame.indices) {
            val v = frame[i] * gain
            frame[i] = v.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
        }
    }
}
