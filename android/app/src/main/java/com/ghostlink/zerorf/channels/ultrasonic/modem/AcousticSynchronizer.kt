package com.ghostlink.zerorf.channels.ultrasonic.modem

/**
 * Real-time bit-level synchronizer utilizing cross-correlation against the Barker preamble.
 */
class AcousticSynchronizer {

    private val syncBits = ArrayList<Int>().apply {
        // Sync preamble: 0xA5 (10100101), 0x5A (01011010), 0x7E (01111110)
        for (b in AcousticFramer.SYNC_PREAMBLE_BYTES) {
            for (bitOffset in 7 downTo 0) {
                add(((b.toInt() and 0xFF) shr bitOffset) and 1)
            }
        }
    }

    /**
     * Finds the starting index of the synchronization preamble in bitBuffer.
     * Returns -1 if not found. Allows up to 1 bit error for channel noise tolerance.
     */
    fun findSyncIndex(bitBuffer: List<Int>): Int {
        val limit = bitBuffer.size - syncBits.size
        if (limit < 0) return -1

        for (i in 0..limit) {
            var mismatches = 0
            for (j in syncBits.indices) {
                if (bitBuffer[i + j] != syncBits[j]) {
                    mismatches++
                    if (mismatches > 1) break
                }
            }
            if (mismatches <= 1) {
                return i
            }
        }
        return -1
    }

    val syncLengthBits: Int
        get() = syncBits.size
}
