package com.ghostlink.zerorf.channels.ultrasonic.modem

import com.ghostlink.zerorf.core.Packet

/**
 * AcousticFramer wraps binary wire data into physical acoustic frames with Barker-13 synchronization.
 */
object AcousticFramer {

    // Barker-13 sequence: optimal aperiodic autocorrelation (+13 at peak, <= 1 elsewhere)
    val BARKER_13_BITS = intArrayOf(1, 1, 1, 1, 1, 0, 0, 1, 1, 0, 1, 0, 1)

    // Synchronization bytes (Barker sequence packed + delimiter)
    val SYNC_PREAMBLE_BYTES = byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0x7E.toByte())

    /**
     * Builds complete physical acoustic frame bytes for a packet under a specific mode.
     */
    fun framePacket(packet: Packet, mode: AcousticMode = AcousticMode.MODE_0_BFSK): ByteArray {
        val serializedPacket = packet.serialize()

        // [ 3 bytes preamble ] [ 1 byte modeId ] [ packet bytes ]
        val frame = ByteArray(SYNC_PREAMBLE_BYTES.size + 1 + serializedPacket.size)
        System.arraycopy(SYNC_PREAMBLE_BYTES, 0, frame, 0, SYNC_PREAMBLE_BYTES.size)
        frame[SYNC_PREAMBLE_BYTES.size] = mode.modeId.toByte()
        System.arraycopy(serializedPacket, 0, frame, SYNC_PREAMBLE_BYTES.size + 1, serializedPacket.size)
        return frame
    }
}
