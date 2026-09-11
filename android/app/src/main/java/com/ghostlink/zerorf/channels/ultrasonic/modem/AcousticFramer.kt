package com.ghostlink.zerorf.channels.ultrasonic.modem

import com.ghostlink.zerorf.core.Packet

/**
 * AcousticFramer wraps binary wire data into physical acoustic frames with
 * Barker-13 synchronization preamble and Hamming(8,4) Forward Error Correction.
 */
object AcousticFramer {

    // Barker-13 sequence: optimal aperiodic autocorrelation (+13 at peak, <= 1 elsewhere)
    val BARKER_13_BITS = intArrayOf(1, 1, 1, 1, 1, 0, 0, 1, 1, 0, 1, 0, 1)
    const val DELIMITER_BYTE: Byte = 0x7E.toByte() // 01111110

    /**
     * Builds physical acoustic frame bytes for a packet under a specific mode.
     * Frame layout:
     * [ 16 bits: Barker-13 sequence (1111 1001 1010 1000 = 0xF9, 0xA8) ]
     * [ 8 bits : Delimiter 0x7E ]
     * [ 8 bits : Mode ID (0, 1, 2) ]
     * [ 2N bytes: FEC-encoded packet bytes via Hamming(8,4) SEC-DED ]
     */
    fun framePacket(packet: Packet, mode: AcousticMode = AcousticMode.MODE_0_BFSK): ByteArray {
        val serializedPacket = packet.serialize()
        val fecEncoded = FecCodec.encodeBytes(serializedPacket)

        // Preamble: 2 bytes Barker-13 padded, 1 byte delimiter, 1 byte mode ID
        val preambleBytes = byteArrayOf(0xF9.toByte(), 0xA8.toByte(), DELIMITER_BYTE, mode.modeId.toByte())

        val frame = ByteArray(preambleBytes.size + fecEncoded.size)
        System.arraycopy(preambleBytes, 0, frame, 0, preambleBytes.size)
        System.arraycopy(fecEncoded, 0, frame, preambleBytes.size, fecEncoded.size)
        return frame
    }
}
