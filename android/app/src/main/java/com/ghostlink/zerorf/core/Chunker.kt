package com.ghostlink.zerorf.core

import kotlin.math.ceil

/**
 * Splits arbitrary encrypted binary payloads into fixed-size chunks for physical transmission.
 * Enforces the 128 KB acoustic cap on ultrasonic transfers.
 */
object Chunker {
    const val OPTICAL_DEFAULT_CHUNK_SIZE = 250 // bytes payload (maps to QR V10 Level L)
    const val ULTRASONIC_DEFAULT_CHUNK_SIZE = 32 // bytes payload
    const val ULTRASONIC_MAX_FILE_SIZE = 128 * 1024 // 128 KB hard cap

    fun chunk(
        payload: ByteArray,
        chunkSize: Int = OPTICAL_DEFAULT_CHUNK_SIZE,
        isUltrasonic: Boolean = false
    ): List<Packet> {
        if (isUltrasonic && payload.size > ULTRASONIC_MAX_FILE_SIZE) {
            throw IllegalArgumentException(
                "Payload size (${payload.size} bytes) exceeds the 128 KB Ultrasonic channel safety cap."
            )
        }

        val totalChunks = ceil(payload.size.toDouble() / chunkSize).toLong()
        val packets = ArrayList<Packet>(totalChunks.toInt())

        for (i in 0 until totalChunks) {
            val start = (i * chunkSize).toInt()
            val end = minOf(start + chunkSize, payload.size)
            val chunkBytes = payload.copyOfRange(start, end)
            packets.add(Packet.create(chunkIndex = i, totalChunks = totalChunks, payload = chunkBytes))
        }

        return packets
    }
}
