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
     * [ 8 bits : Mode ID (0, 1, 2, 3, 4, 5, 6) ]
     * [ Encoded bytes: Header (Rate 1/2 Hamming) + Payload (Layered FEC) ]
     */
    fun framePacket(
        packet: Packet,
        mode: AcousticMode = AcousticMode.MODE_0_BFSK,
        payloadFecScheme: FecCodec.FecScheme = if (mode.isMultiCarrier) FecCodec.FecScheme.RATE_PASSTHROUGH else FecCodec.FecScheme.RATE_1_2_HAMMING
    ): ByteArray {
        val serialized = packet.serialize()
        val headerBytes = serialized.copyOfRange(0, Packet.HEADER_SIZE)
        val payloadBytes = serialized.copyOfRange(Packet.HEADER_SIZE, serialized.size)

        // Header is ALWAYS protected with Rate 1/2 Hamming(8,4) SEC-DED (48 bytes output)
        val encodedHeader = FecCodec.encodeBytes(headerBytes)

        // Payload is encoded according to the payloadFecScheme
        val encodedPayload = FecCodec.encodePayload(payloadBytes, payloadFecScheme)

        // Preamble: 2 bytes Barker-13 padded, 1 byte delimiter, 1 byte mode ID
        val preambleBytes = byteArrayOf(0xF9.toByte(), 0xA8.toByte(), DELIMITER_BYTE, mode.modeId.toByte())

        val frame = ByteArray(preambleBytes.size + encodedHeader.size + encodedPayload.size)
        var offset = 0
        System.arraycopy(preambleBytes, 0, frame, offset, preambleBytes.size)
        offset += preambleBytes.size
        System.arraycopy(encodedHeader, 0, frame, offset, encodedHeader.size)
        offset += encodedHeader.size
        System.arraycopy(encodedPayload, 0, frame, offset, encodedPayload.size)
        return frame
    }
}
