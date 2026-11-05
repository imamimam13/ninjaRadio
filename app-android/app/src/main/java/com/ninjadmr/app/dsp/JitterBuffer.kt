package com.ninjadmr.app.dsp

/**
 * Receive-side jitter buffer.
 *
 * DMR carries 60 ms bursts (480 samples). Buffering absorbs network arrival jitter
 * smoothly without artificial starvation stalls or audio unpriming clicks.
 */
class JitterBuffer(
    val frameSamples: Int = 480,
    private val cushionFrames: Int = 1,
    private val maxFrames: Int = 32,
) {
    private val queue = ArrayDeque<ShortArray>()
    private var primed = false

    @Synchronized
    fun put(frame: ShortArray) {
        while (queue.size >= maxFrames) {
            queue.removeFirstOrNull()
        }
        queue.addLast(frame)
    }

    @Synchronized
    fun poll(): ShortArray? {
        if (!primed) {
            if (queue.size < cushionFrames) return null
            primed = true
        }
        return queue.removeFirstOrNull()
    }

    @Synchronized
    fun depth(): Int = queue.size

    @Synchronized
    fun reset() {
        queue.clear()
        primed = false
    }
}
