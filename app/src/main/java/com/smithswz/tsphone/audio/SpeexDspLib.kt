package com.smithswz.tsphone.audio

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.ptr.PointerByReference
import java.nio.IntBuffer

/**
 * JNA binding to the acoustic echo canceller from speexdsp (bundled
 * per-ABI in jniLibs). All signals are 48 kHz mono int16.
 */
interface SpeexDspLib : Library {

    fun speex_echo_state_init(frameSize: Int, filterLength: Int): PointerByReference?

    fun speex_echo_state_destroy(state: PointerByReference)

    fun speex_echo_state_reset(state: PointerByReference)

    fun speex_echo_ctl(state: PointerByReference, request: Int, value: IntBuffer): Int

    /** rec = near-end (mic), play = far-end reference, out = echo-free output. */
    fun speex_echo_cancellation(
        state: PointerByReference,
        rec: ShortArray,
        play: ShortArray,
        out: ShortArray
    )

    companion object {
        const val SPEEX_ECHO_SET_SAMPLING_RATE = 24

        fun instance(): SpeexDspLib = Native.load("speexdsp", SpeexDspLib::class.java)
    }
}
