package com.smithswz.tsphone.audio

/**
 * Energy-based voice activity detection over 20 ms PCM frames.
 *
 * A frame is "speech" when its RMS exceeds the threshold derived from
 * [sensitivity]; once speech starts, [process] keeps reporting speech for
 * [HANGOVER_FRAMES] more frames (~250 ms) so sentence ends don't clip.
 *
 * Pure Kotlin — unit-testable on the JVM.
 */
class VadProcessor(
    sensitivity: Int = 50,
    private val frameSize: Int = OpusCodec.FRAME_SIZE
) {

    var sensitivity: Int = sensitivity
        set(value) {
            field = value.coerceIn(0, 100)
            threshold = MAX_THRESHOLD - (field * (MAX_THRESHOLD - MIN_THRESHOLD) / 100)
        }

    var threshold: Int = 0
        private set

    private var speaking = false
    private var hangoverRemaining = 0

    init {
        threshold = MAX_THRESHOLD - (sensitivity.coerceIn(0, 100) * (MAX_THRESHOLD - MIN_THRESHOLD) / 100)
    }

    /** Feeds one frame; returns true while speech is active (incl. hangover). */
    fun process(frame: ShortArray): Boolean {
        val rms = rms(frame)
        if (rms >= threshold) {
            speaking = true
            hangoverRemaining = HANGOVER_FRAMES
        } else if (hangoverRemaining > 0) {
            hangoverRemaining--
        } else {
            speaking = false
        }
        return speaking
    }

    fun isSpeaking(): Boolean = speaking

    private fun rms(frame: ShortArray): Double {
        if (frame.isEmpty()) return 0.0
        var sum = 0.0
        for (i in 0 until minOf(frame.size, frameSize)) {
            val s = frame[i].toDouble()
            sum += s * s
        }
        return Math.sqrt(sum / minOf(frame.size, frameSize))
    }

    companion object {
        private const val MIN_THRESHOLD = 500   // most sensitive
        private const val MAX_THRESHOLD = 4000  // least sensitive
        const val HANGOVER_FRAMES = 12          // ~250 ms at 20 ms frames
    }
}
