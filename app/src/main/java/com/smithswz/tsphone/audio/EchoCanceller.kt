package com.smithswz.tsphone.audio

import android.util.Log
import com.sun.jna.ptr.PointerByReference
import java.nio.IntBuffer

/**
 * SpeexDSP acoustic echo canceller — the same algorithm the desktop TeamSpeak
 * client uses. Speex has no internal delay estimation: the reference [Match]
 * fed by the mic thread is already sample-aligned to the capture (play clock
 * in [PlaybackReference]), so the MDF filter converges within a second. One
 * 20 ms frame (960 samples @ 48 kHz) per call.
 */
class EchoCanceller {

    companion object {
        private const val TAG = "TSPhone"
        private const val FRAME_SIZE = OpusCodec.FRAME_SIZE
        private const val FILTER_LENGTH = 9600   // 200 ms echo tail @ 48 kHz
        private const val SAMPLE_RATE = 48000

        /** Companion constant: safe to reference during property init. */
        private val SILENCE = ShortArray(FRAME_SIZE)
    }

    private val lib = SpeexDspLib.instance()
    private var state: PointerByReference? = null

    fun start() {
        synchronized(this) {
            if (state != null) return
            val created = lib.speex_echo_state_init(FRAME_SIZE, FILTER_LENGTH)
            if (created == null) {
                Log.w(TAG, "speex_echo_state_init failed")
                return
            }
            lib.speex_echo_ctl(
                created,
                SpeexDspLib.SPEEX_ECHO_SET_SAMPLING_RATE,
                IntBuffer.wrap(intArrayOf(SAMPLE_RATE))
            )
            state = created
            Log.w(TAG, "Speex AEC initialized (frame=$FRAME_SIZE filter=$FILTER_LENGTH)")
        }
    }

    fun stop() {
        synchronized(this) {
            state?.let { lib.speex_echo_state_destroy(it) }
            state = null
        }
    }

    /** The echo path changed (speaker/earpiece switch) — drop the filter. */
    fun reset() {
        synchronized(this) {
            state?.let { lib.speex_echo_state_reset(it) }
            reductionEma = 1.0
        }
    }

    /**
     * Cancels the matched playout reference from the near-end capture. When
     * no reference is available (nothing playing), the near signal passes
     * through with a silence reference — Speex just does not adapt.
     */
    fun process(near: ShortArray, match: PlaybackReference.Match): ShortArray {
        val h = synchronized(this) { state } ?: return near
        val out = ShortArray(FRAME_SIZE)
        lib.speex_echo_cancellation(h, near, match.frame ?: silenceFrame(), out)
        // Track the out/in energy ratio for diagnostics.
        val inE = energy(near)
        if (inE > 0.0) {
            val ratio = Math.sqrt(energy(out) / inE)
            reductionEma = reductionEma * 0.9 + ratio * 0.1
        }
        val now = System.currentTimeMillis()
        if (now - lastLog > 5_000) {
            val ref = match.frame ?: silenceFrame()
            Log.w(
                TAG,
                "aec: in=${rms(near)} ref=${rms(ref)} out=${rms(out)} " +
                    "ratio=${"%.2f".format(reductionEma)} refIdx=${match.frameIndex}"
            )
            lastLog = now
        }
        return out
    }

    private var reductionEma = 1.0
    private var lastLog = 0L

    private fun energy(frame: ShortArray): Double {
        var sum = 0.0
        for (i in frame.indices) sum += frame[i].toDouble() * frame[i]
        return sum
    }

    private fun rms(frame: ShortArray): Int {
        return if (frame.isEmpty()) 0
        else Math.sqrt(energy(frame) / frame.size).toInt()
    }

    private fun silenceFrame(): ShortArray = SILENCE
}
