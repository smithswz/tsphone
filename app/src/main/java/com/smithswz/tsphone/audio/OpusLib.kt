package com.smithswz.tsphone.audio

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.ptr.PointerByReference
import java.nio.IntBuffer

/**
 * Minimal JNA binding to libopus (bundled per-ABI in jniLibs). Only the
 * functions the app needs: 48 kHz mono frame encode/decode.
 */
interface OpusLib : Library {

    fun opus_encoder_create(
        sampleRate: Int,
        channels: Int,
        application: Int,
        error: IntBuffer
    ): PointerByReference?

    fun opus_encoder_destroy(encoder: PointerByReference)

    fun opus_encode(
        encoder: PointerByReference,
        pcm: ShortArray,
        frameSize: Int,
        data: ByteArray,
        maxDataBytes: Int
    ): Int

    fun opus_decoder_create(
        sampleRate: Int,
        channels: Int,
        error: IntBuffer
    ): PointerByReference?

    fun opus_decoder_destroy(decoder: PointerByReference)

    fun opus_decode(
        decoder: PointerByReference,
        data: ByteArray,
        len: Int,
        pcm: ShortArray,
        frameSize: Int,
        decodeFec: Int
    ): Int

    fun opus_strerror(code: Int): String

    companion object {
        const val OPUS_OK = 0

        // Opus application types
        const val OPUS_APPLICATION_VOIP = 2048
        const val OPUS_APPLICATION_AUDIO = 2049

        fun instance(): OpusLib = Native.load("opus", OpusLib::class.java)
    }
}
