package com.ghostlink.zerorf.channels.ultrasonic.modem

/**
 * Forward Error Correction (FEC) Codec implementing Hamming(8,4) SEC-DED
 * (Single Error Correction, Double Error Detection).
 *
 * Corrects single-bit errors per nibble caused by room reverberation / acoustic multipath.
 */
object FecCodec {

    /**
     * Encodes 4 data bits (nibble 0..15) into an 8-bit Hamming codeword.
     */
    fun encodeNibble(data: Int): Int {
        val d0 = (data shr 0) and 1
        val d1 = (data shr 1) and 1
        val d2 = (data shr 2) and 1
        val d3 = (data shr 3) and 1

        val p1 = d0 xor d1 xor d3
        val p2 = d0 xor d2 xor d3
        val p3 = d1 xor d2 xor d3

        var codeword7 = (d3 shl 6) or (d2 shl 5) or (d1 shl 4) or (p3 shl 3) or (d0 shl 2) or (p2 shl 1) or p1
        val overallParity = Integer.bitCount(codeword7) % 2
        return (overallParity shl 7) or codeword7
    }

    /**
     * Decodes an 8-bit Hamming codeword back into a 4-bit nibble.
     * Returns Pair(correctedData, wasCorrected).
     */
    fun decodeNibble(codeword: Int): Pair<Int, Boolean> {
        val c = codeword and 0xFF
        val overallParityRx = (c shr 7) and 1
        var c7 = c and 0x7F
        val overallParityCalc = Integer.bitCount(c7) % 2
        val parityError = (overallParityRx != overallParityCalc)

        val b1 = (c7 shr 0) and 1
        val b2 = (c7 shr 1) and 1
        val b3 = (c7 shr 2) and 1
        val b4 = (c7 shr 3) and 1
        val b5 = (c7 shr 4) and 1
        val b6 = (c7 shr 5) and 1
        val b7 = (c7 shr 6) and 1

        val s1 = b1 xor b3 xor b5 xor b7
        val s2 = b2 xor b3 xor b6 xor b7
        val s3 = b4 xor b5 xor b6 xor b7
        val syndrome = (s3 shl 2) or (s2 shl 1) or s1

        var wasCorrected = false
        if (syndrome != 0) {
            if (parityError) {
                // Single bit error in c7 at position (syndrome - 1)
                c7 = c7 xor (1 shl (syndrome - 1))
                wasCorrected = true
            }
        } else if (parityError) {
            // Parity bit itself had error
            wasCorrected = true
        }

        val d0 = (c7 shr 2) and 1
        val d1 = (c7 shr 4) and 1
        val d2 = (c7 shr 5) and 1
        val d3 = (c7 shr 6) and 1
        val nibble = (d3 shl 3) or (d2 shl 2) or (d1 shl 1) or d0
        return Pair(nibble, wasCorrected)
    }

    /**
     * Encodes N raw bytes into 2N FEC-protected bytes (2 Hamming codewords per byte).
     */
    fun encodeBytes(input: ByteArray): ByteArray {
        val out = ByteArray(input.size * 2)
        var outIdx = 0
        for (b in input) {
            val highNibble = (b.toInt() ushr 4) and 0x0F
            val lowNibble = b.toInt() and 0x0F
            out[outIdx++] = encodeNibble(highNibble).toByte()
            out[outIdx++] = encodeNibble(lowNibble).toByte()
        }
        return out
    }

    /**
     * Decodes 2N FEC-protected bytes back into N raw bytes.
     * Returns Pair(decodedBytes, totalErrorsCorrected).
     */
    fun decodeBytes(encoded: ByteArray): Pair<ByteArray, Int> {
        val out = ByteArray(encoded.size / 2)
        var errors = 0
        var outIdx = 0
        var i = 0
        while (i < encoded.size - 1) {
            val (high, err1) = decodeNibble(encoded[i].toInt() and 0xFF)
            val (low, err2) = decodeNibble(encoded[i + 1].toInt() and 0xFF)
            if (err1) errors++
            if (err2) errors++
            out[outIdx++] = ((high shl 4) or low).toByte()
            i += 2
        }
        return Pair(out, errors)
    }
}
