package com.ghostlink.zerorf.manager

import kotlin.math.roundToInt

/**
 * Transparent ETA Calculator (PRD F6).
 * Grounded in exact physical layer measurements:
 * - Optical (QR stream @ 6 FPS): ~1,500 B/s sustained
 * - Ultrasonic (BFSK 18.5/19.5 kHz @ 15ms/symbol): ~8.5 B/s sustained
 */
object TransparentEta {
    const val OPTICAL_BYTE_RATE = 2500.0 // B/s sustained (~2.5 KB/s at 10 FPS)
    const val OPTICAL_REDUNDANCY = 1.25

    const val ULTRASONIC_BYTE_RATE = 8.5 // B/s actual physical BFSK throughput (15ms symbol = 67 bps = 8.375 B/s)
    const val ULTRASONIC_REDUNDANCY = 1.30
    const val ULTRASONIC_MAX_BYTES = 128 * 1024 // 128 KB safety cap

    data class EtaResult(
        val channel: ChannelType,
        val durationSeconds: Int,
        val isAllowed: Boolean,
        val formattedTime: String,
        val speedDescription: String
    )

    enum class ChannelType {
        OPTICAL,
        ULTRASONIC
    }

    fun calculateOpticalEta(fileSizeBytes: Long): EtaResult {
        val seconds = ((fileSizeBytes / OPTICAL_BYTE_RATE) * OPTICAL_REDUNDANCY).roundToInt().coerceAtLeast(1)
        return EtaResult(
            channel = ChannelType.OPTICAL,
            durationSeconds = seconds,
            isAllowed = true,
            formattedTime = formatDuration(seconds),
            speedDescription = "~2.5 KB/s (High-Speed Optical QR)"
        )
    }

    fun calculateUltrasonicEta(fileSizeBytes: Long): EtaResult {
        if (fileSizeBytes > ULTRASONIC_MAX_BYTES) {
            return EtaResult(
                channel = ChannelType.ULTRASONIC,
                durationSeconds = -1,
                isAllowed = false,
                formattedTime = "Exceeds 128 KB Cap",
                speedDescription = "Disabled for files > 128 KB (would take >4.5 hours)"
            )
        }
        val seconds = ((fileSizeBytes / ULTRASONIC_BYTE_RATE) * ULTRASONIC_REDUNDANCY).roundToInt().coerceAtLeast(1)
        return EtaResult(
            channel = ChannelType.ULTRASONIC,
            durationSeconds = seconds,
            isAllowed = true,
            formattedTime = formatDuration(seconds),
            speedDescription = "~8.5 B/s (BFSK near-ultrasound)"
        )
    }

    private fun formatDuration(totalSec: Int): String {
        return if (totalSec < 60) {
            "${totalSec}s"
        } else if (totalSec < 3600) {
            val mins = totalSec / 60
            val remSec = totalSec % 60
            "${mins}m ${remSec}s"
        } else {
            val hrs = totalSec / 3600
            val mins = (totalSec % 3600) / 60
            "${hrs}h ${mins}m"
        }
    }
}
