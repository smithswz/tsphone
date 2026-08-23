package com.smithswz.tsphone.audio

import com.sun.jna.ptr.PointerByReference
import java.nio.IntBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the Opus encoder (used by the mic thread) and per-client decoders
 * (used by the mixer, ticket 06). All native state must be destroyed when the
 * connection ends.
 */
class OpusCodec {

    companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 1
        const val FRAME_SIZE = 960 // 20 ms at 48 kHz
        private const val MAX_ENCODED = 4000
    }

    private val lib = OpusLib.instance()

    private val encoderLock = Any()
    private var encoder: PointerByReference? = null
    private var encoderApplication: Int = -1

    private val decoders = ConcurrentHashMap<Int, PointerByReference>()

    /**
     * Returns the shared encoder, (re)creating it with the requested Opus
     * application type (VOIP for speech, AUDIO for music) when it changed.
     */
    fun encoder(application: Int): PointerByReference {
        synchronized(encoderLock) {
            val current = encoder
            if (current != null && encoderApplication == application) return current
            current?.let { lib.opus_encoder_destroy(it) }
            val error = IntBuffer.allocate(1)
            val created = lib.opus_encoder_create(SAMPLE_RATE, CHANNELS, application, error)
                ?: throw IllegalStateException("opus_encoder_create failed: ${lib.opus_strerror(error[0])}")
            encoder = created
            encoderApplication = application
            return created
        }
    }

    /** Encodes one 20 ms frame; returns the compressed bytes. */
    fun encode(application: Int, pcm: ShortArray): ByteArray {
        val out = ByteArray(MAX_ENCODED)
        val len = lib.opus_encode(encoder(application), pcm, FRAME_SIZE, out, out.size)
        check(len > 0) { "opus_encode failed: ${lib.opus_strerror(len)}" }
        return out.copyOf(len)
    }

    /** Decoder for one client; persistent across frames (Opus requires state). */
    fun decoder(clientId: Int): PointerByReference =
        decoders.getOrPut(clientId) {
            val error = IntBuffer.allocate(1)
            lib.opus_decoder_create(SAMPLE_RATE, CHANNELS, error)
                ?: throw IllegalStateException("opus_decoder_create failed: ${lib.opus_strerror(error[0])}")
        }

    fun decode(clientId: Int, data: ByteArray): ShortArray? {
        val pcm = ShortArray(FRAME_SIZE)
        val decoded = lib.opus_decode(decoder(clientId), data, data.size, pcm, FRAME_SIZE, 0)
        if (decoded <= 0) return null
        return pcm.copyOf(decoded)
    }

    /**
     * Advances the decoder's internal state with a DTX silence frame
     * (0-length packet). Best effort — the decoder simply ignores the call
     * if the packet is rejected. Keeps the state in sync so the next real
     * frame decodes cleanly after a concealed gap.
     */
    fun advanceDecoderState(clientId: Int) {
        runCatching {
            val pcm = ShortArray(FRAME_SIZE)
            lib.opus_decode(decoder(clientId), ByteArray(0), 0, pcm, FRAME_SIZE, 0)
        }
    }

    fun destroyDecoder(clientId: Int) {
        decoders.remove(clientId)?.let { lib.opus_decoder_destroy(it) }
    }

    fun destroy() {
        synchronized(encoderLock) {
            encoder?.let { lib.opus_encoder_destroy(it) }
            encoder = null
            encoderApplication = -1
        }
        decoders.values.forEach { lib.opus_decoder_destroy(it) }
        decoders.clear()
    }
}
