package com.smithswz.tsphone

import com.smithswz.tsphone.audio.OpusCodec
import com.smithswz.tsphone.audio.VadProcessor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VadProcessorTest {

    private fun silenceFrame(amplitude: Int = 0) = ShortArray(OpusCodec.FRAME_SIZE) { amplitude.toShort() }

    private fun speechFrame() = ShortArray(OpusCodec.FRAME_SIZE) { (if (it % 2 == 0) 12000 else -12000).toShort() }

    @Test
    fun silenceIsNotSpeech() {
        val vad = VadProcessor(sensitivity = 50)
        repeat(30) {
            assertFalse("frame $it should be silence", vad.process(silenceFrame(50)))
        }
    }

    @Test
    fun loudFrameIsSpeech() {
        val vad = VadProcessor(sensitivity = 50)
        assertTrue(vad.process(speechFrame()))
    }

    @Test
    fun speechContinuesDuringHangover() {
        val vad = VadProcessor(sensitivity = 50)
        vad.process(speechFrame()) // start speech

        // Quiet frames: still speaking for HANGOVER_FRAMES
        for (i in 1..VadProcessor.HANGOVER_FRAMES) {
            assertTrue("hangover frame $i", vad.process(silenceFrame()))
        }
        // One more quiet frame ends it
        assertFalse(vad.process(silenceFrame()))
    }

    @Test
    fun sensitivityMapsThreshold() {
        val sensitive = VadProcessor(sensitivity = 100)
        val insensitive = VadProcessor(sensitivity = 0)
        assertTrue("most sensitive threshold", sensitive.threshold < insensitive.threshold)
    }

    @Test
    fun softSpeechPickedUpAtHighSensitivityOnly() {
        val soft = ShortArray(OpusCodec.FRAME_SIZE) { 2000 }

        val low = VadProcessor(sensitivity = 0)
        assertFalse("least sensitive should miss it", low.process(soft))

        val high = VadProcessor(sensitivity = 100)
        assertTrue("most sensitive should catch it", high.process(soft))
    }
}
