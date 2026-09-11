package com.ghostlink.zerorf.core

import java.nio.ByteBuffer
import java.util.BitSet

/**
 * Hardened ReassemblyEngine buffers received chunks, tracks progress via a 32-bit bitmap,
 * validates session bounds, prevents memory exhaustion attacks, and enforces timeouts.
 */
class ReassemblyEngine(
    val totalChunks: Long,
    val sessionId: Long = 0L
) {
    init {
        require(totalChunks in 1..ProtocolConstants.MAX_TOTAL_CHUNKS) {
            "Total chunks ($totalChunks) exceeds maximum safety limit (${ProtocolConstants.MAX_TOTAL_CHUNKS})"
        }
    }

    private val chunkStore = arrayOfNulls<ByteArray>(totalChunks.toInt())
    private val receivedBitmap = BitSet(totalChunks.toInt())
    private var totalBytesBuffered: Long = 0L
    private val startTimeMs = System.currentTimeMillis()
    private var lastChunkTimeMs = startTimeMs

    var receivedCount = 0
        private set

    val progressFraction: Float
        get() = if (totalChunks > 0) receivedCount.toFloat() / totalChunks else 0f

    val progressPercent: Int
        get() = (progressFraction * 100).toInt()

    val isComplete: Boolean
        get() = receivedCount.toLong() == totalChunks

    val isExpired: Boolean
        get() = (System.currentTimeMillis() - lastChunkTimeMs) > ProtocolConstants.MAX_REASSEMBLY_TIMEOUT_MS

    /**
     * Ingests a validated packet.
     * Returns true if it was a newly received chunk, false if duplicate, invalid, or mismatched session.
     */
    @Synchronized
    fun addPacket(packet: Packet): Boolean {
        if (isExpired) return false
        if (sessionId != 0L && packet.sessionId != 0L && packet.sessionId != sessionId) {
            return false // Mismatched session
        }
        if (packet.chunkIndex >= totalChunks) return false
        val idx = packet.chunkIndex.toInt()

        if (!receivedBitmap.get(idx)) {
            val chunkSize = packet.payload.size
            if (totalBytesBuffered + chunkSize > ProtocolConstants.MAX_TRANSFER_BYTES) {
                throw IllegalStateException("Transfer exceeds maximum allowed storage (${ProtocolConstants.MAX_TRANSFER_BYTES} B)")
            }

            receivedBitmap.set(idx)
            chunkStore[idx] = packet.payload
            totalBytesBuffered += chunkSize
            receivedCount++
            lastChunkTimeMs = System.currentTimeMillis()
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
        check(totalBytesBuffered <= Int.MAX_VALUE) { "Payload size exceeds 2 GB buffer limit" }

        val out = ByteBuffer.allocate(totalBytesBuffered.toInt())
        for (chunk in chunkStore) {
            checkNotNull(chunk) { "Missing chunk during assembly" }
            out.put(chunk)
        }
        return out.array()
    }
}
