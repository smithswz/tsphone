package com.smithswz.tsphone.audio

/**
 * Frame-index ring of everything the mixer writes to the track. The mic
 * thread drains it strictly in FIFO order (one entry per capture frame), so
 * the reference Speex sees is exactly the played stream — the desktop client
 * does the same with a queue sized to the sound-card buffer. No clocks are
 * involved: whatever constant lag the pipeline has (track buffer + thread
 * jitter) is absorbed by Speex's 200 ms adaptive filter, and the mixer paces
 * its writes to real time so the lag stays constant.
 *
 * Writer: mixer thread (push/resetForTrack). Reader: mic thread
 * (nextReference, 50 Hz). All methods synchronized; returned ShortArrays are
 * never mutated after push.
 */
class PlaybackReference {

    companion object {
        const val RING_CAPACITY = 128            // 2.56 s at 20 ms
    }

    data class Match(
        val frame: ShortArray?,      // reference for the capture frame; null → feed silence
        val frameIndex: Long?,       // ring entry used (diagnostics; null when unmatched)
        val biasMs: Int              // diagnostics only (kept for log stability)
    )

    private class Entry(val frameIndex: Long, val frame: ShortArray)

    // --- ring (entries sorted by frameIndex, monotonically increasing) ---
    private val ring = arrayOfNulls<Entry>(RING_CAPACITY)
    private var ringHead = 0
    private var ringSize = 0

    /** Fresh track epoch: clears the ring. */
    @Synchronized
    fun resetForTrack() {
        ringHead = 0
        ringSize = 0
    }

    @Synchronized
    fun clear() = resetForTrack()

    /**
     * One reference entry per written frame — never decimated. ringHead is
     * always the index of the OLDEST entry (the drain head); pushes land at
     * the first free slot after the newest entry. When full, the oldest is
     * overwritten — only possible if the mic is far behind, which bounded
     * bursts cannot cause.
     */
    @Synchronized
    fun push(frameIndex: Long, frame: ShortArray) {
        val slot = (ringHead + ringSize) % RING_CAPACITY
        ring[slot] = Entry(frameIndex, frame)
        if (ringSize < RING_CAPACITY) {
            ringSize++
        } else {
            ringHead = (ringHead + 1) % RING_CAPACITY
        }
    }

    /**
     * Next played frame, FIFO order. One entry per capture frame; null when
     * the ring is empty (nothing playing) → the caller feeds silence.
     */
    @Synchronized
    fun nextReference(): Match {
        if (ringSize == 0) return Match(null, null, 0)
        val e = ring[ringHead]!!
        ringHead = (ringHead + 1) % RING_CAPACITY
        ringSize--
        return Match(e.frame, e.frameIndex, 0)
    }
}
