package com.ninjadmr.app.dsp

/**
 * Receive-side jitter buffer.
 *
 * DMR carries 60 ms bursts (480 samples) that may arrive late or bunched.
 * Buffering 3 frames (~180 ms) prevents AudioTrack buffer underruns (which cause
 * loud audio pops / stuttering).
 */
class JitterBuffer(
    val frameSamples: Int,
    private val cushionFrames: Int = 3,
    private val maxFrames: Int = 16,
) {
    private val queue = ArrayDeque<ShortArray>()
    private var primed = false
    private var underflowCount = 0

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
            underflowCount = 0
        }
        if (queue.isEmpty()) {
            underflowCount++
            // Only unprime after multiple consecutive starvation ticks so brief jitter
            // doesn't trigger repeated re-priming clicks
            if (underflowCount > 4) {
                primed = false
            }
            return null
        }
        underflowCount = 0
        return queue.removeAt(0)
    }

    @Synchronized
    fun depth(): Int = queue.size

    @Synchronized
    fun reset() {
        queue.clear()
        primed = false
        underflowCount = 0
    }
}
