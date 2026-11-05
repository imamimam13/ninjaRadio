package com.ninjadmr.lite.dmr

data class Dmrd(
    val seq: Int,
    val src: Int,
    val dst: Int,
    val peer: Int,
    val slot: Int,
    val isPrivate: Boolean,
    val frameType: Int,
    val dtype: Int,
    val streamId: Int,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Dmrd
        if (seq != other.seq) return false
        if (src != other.src) return false
        if (dst != other.dst) return false
        if (peer != other.peer) return false
        if (slot != other.slot) return false
        if (isPrivate != other.isPrivate) return false
        if (frameType != other.frameType) return false
        if (dtype != other.dtype) return false
        if (streamId != other.streamId) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = seq
        result = 31 * result + src
        result = 31 * result + dst
        result = 31 * result + peer
        result = 31 * result + slot
        result = 31 * result + isPrivate.hashCode()
        result = 31 * result + frameType
        result = 31 * result + dtype
        result = 31 * result + streamId
        result = 31 * result + payload.contentHashCode()
        return result
    }
}
