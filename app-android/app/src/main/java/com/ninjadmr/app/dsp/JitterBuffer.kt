package com.ninjadmr.app.dsp

/**
 * Receive-side jitter buffer.
 *
 * DMR carries 60 ms bursts (480 samples) that may arrive late or bunched; the
 * buffer absorbs network jitter before handing frames to the playback track.
 * The web client used a ~100 ms cushion; we target ~150 ms.
 *
 * Frames are only released once [cushionFrames] are queued, and the depth is
 * capped at [maxFrames] (with clock-drift recovery dropping the oldest frames).
 */
class JitterBuffer(
    val frameSamples: Int,
    private val cushionFrames: Int = 3,
    private val maxFrames: Int = 12,
) {
    private val queue = ArrayDeque<ShortArray>()
    private var primed = false

    @Synchronized
    fun put(frame: ShortArray) {
        while (queue.size >= maxFrames) queue.removeAt(0)
        queue.addLast(frame)
    }

    @Synchronized
    fun poll(): ShortArray? {
        if (!primed) {
            if (queue.size < cushionFrames) return null
            primed = true
        }
        if (queue.isEmpty()) {
            primed = false
            return null
        }
        return queue.removeAt(0)
    }

    @Synchronized
    fun depth(): Int = queue.size

    @Synchronized
    fun reset() {
        queue.clear()
        primed = false
    }
}
