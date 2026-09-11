package com.ghostlink.zerorf.core

import java.nio.ByteBuffer
import java.util.BitSet

/**
 * ReassemblyEngine buffers received chunks, tracks progress via a 32-bit bitmap,
 * detects gaps, and reconstructs the complete encrypted file payload upon 100% completion.
 */
class ReassemblyEngine(val totalChunks: Long) {
    private val chunkStore = arrayOfNulls<ByteArray>(totalChunks.toInt())
    private val receivedBitmap = BitSet(totalChunks.toInt())
    var receivedCount = 0
        private set

    val progressFraction: Float
        get() = if (totalChunks > 0) receivedCount.toFloat() / totalChunks else 0f

    val progressPercent: Int
        get() = (progressFraction * 100).toInt()

    val isComplete: Boolean
        get() = receivedCount.toLong() == totalChunks

    /**
     * Ingests a validated packet.
     * Returns true if it was a newly received chunk, false if duplicate or invalid.
     */
    @Synchronized
    fun addPacket(packet: Packet): Boolean {
        if (packet.chunkIndex >= totalChunks) return false
        val idx = packet.chunkIndex.toInt()

        if (!receivedBitmap.get(idx)) {
            receivedBitmap.set(idx)
            chunkStore[idx] = packet.payload
            receivedCount++
            return true
        }
        return false // Duplicate chunk
    }

    /**
     * Returns list of missing chunk indices for reverse feedback or re-request loop.
     */
    @Synchronized
    fun getMissingIndices(limit: Int = 100): List<Long> {
        val missing = ArrayList<Long>()
        for (i in 0 until totalChunks.toInt()) {
            if (!receivedBitmap.get(i)) {
                missing.add(i.toLong())
                if (missing.size >= limit) break
            }
        }
        return missing
    }

    /**
     * Assembles all chunks in sequence into the complete payload buffer.
     * Throws IllegalStateException if transfer is not yet complete.
     */
    @Synchronized
    fun assemble(): ByteArray {
        check(isComplete) { "Cannot assemble payload: received $receivedCount of $totalChunks chunks" }
        var totalLen = 0
        for (chunk in chunkStore) {
            totalLen += chunk!!.size
        }

        val out = ByteBuffer.allocate(totalLen)
        for (chunk in chunkStore) {
            out.put(chunk!!)
        }
        return out.array()
    }
}
