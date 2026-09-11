package com.ghostlink.zerorf.channels.ultrasonic.modem

/**
 * Multi-stage physical-layer synchronizer for acoustic frames.
 *
 * Employs:
 * 1. Discrete Barker-13 cross-correlation: R(i) = sum(x[i+j] * B[j]) >= 11
 * 2. Frame delimiter verification (0x7E) with <= 1 bit error tolerance
 * 3. Mode ID plausibility check (0..2)
 */
class AcousticSynchronizer {

    companion object {
        // Barker-13 bipolar sequence: +1, +1, +1, +1, +1, -1, -1, +1, +1, -1, +1, -1, +1
        val BARKER_13 = intArrayOf(1, 1, 1, 1, 1, -1, -1, 1, 1, -1, 1, -1, 1)
        const val CORRELATION_THRESHOLD = 11 // Allows 1 bit error (13 - 2 = 11)
        const val SYNC_TOTAL_BITS = 16 + 8 + 8 // 16 bits (Barker+pad) + 8 bits (delimiter) + 8 bits (modeId)
        val EXPECTED_DELIMITER = intArrayOf(0, 1, 1, 1, 1, 1, 1, 0) // 0x7E
    }

    /**
     * Searches for valid synchronization preamble in incoming bitBuffer.
     * Returns Pair(payloadStartBitIndex, modeId), or null if no valid lock.
     */
    fun findSyncIndex(bitBuffer: List<Int>): Pair<Int, Int>? {
        if (bitBuffer.size < SYNC_TOTAL_BITS + 16) return null

        val limit = bitBuffer.size - SYNC_TOTAL_BITS
        for (i in 0..limit) {
            // Stage 1: Barker-13 Cross-Correlation
            var correlation = 0
            for (j in BARKER_13.indices) {
                val bitVal = if (bitBuffer[i + j] == 1) 1 else -1
                correlation += bitVal * BARKER_13[j]
            }

            if (correlation >= CORRELATION_THRESHOLD) {
                // Stage 2: Delimiter validation at i + 16 (0x7E = 01111110)
                val delimiterStart = i + 16
                var delimiterMismatches = 0
                for (d in 0..7) {
                    if (bitBuffer[delimiterStart + d] != EXPECTED_DELIMITER[d]) {
                        delimiterMismatches++
                    }
                }

                if (delimiterMismatches <= 1) {
                    // Stage 3: Mode ID plausibility at i + 24
                    val modeStart = i + 24
                    var modeId = 0
                    for (m in 0..7) {
                        modeId = (modeId shl 1) or bitBuffer[modeStart + m]
                    }

                    if (modeId in 0..6) {
                        val payloadStartBit = i + SYNC_TOTAL_BITS
                        return Pair(payloadStartBit, modeId)
                    }
                }
            }
        }
        return null
    }

    val syncHeaderLengthBits: Int
        get() = SYNC_TOTAL_BITS
}
