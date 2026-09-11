package com.ghostlink.zerorf.manager

import kotlin.math.roundToInt

/**
 * Transparent ETA Calculator (PRD F6).
 * Computes realistic transfer duration based on physical layer constraints.
 */
object TransparentEta {
    const val OPTICAL_BYTE_RATE = 1500.0 // B/s sustained
    const val OPTICAL_REDUNDANCY = 1.25

    const val ULTRASONIC_BYTE_RATE = 50.0 // B/s sustained MFSK / 8-12 B/s BFSK
    const val ULTRASONIC_REDUNDANCY = 1.30
    const val ULTRASONIC_MAX_BYTES = 128 * 1024 // 128 KB cap

    data class EtaResult(
        val channel: ChannelType,
        val durationSeconds: Int,
        val isAllowed: Boolean,
        val formattedTime: String,
        val speedDescription: String
    )

    enum class ChannelType {
        OPTICAL,
        ULTRASONIC,
        MAGNETIC
    }

    fun calculateOpticalEta(fileSizeBytes: Long): EtaResult {
        val seconds = ((fileSizeBytes / OPTICAL_BYTE_RATE) * OPTICAL_REDUNDANCY).roundToInt()
        return EtaResult(
            channel = ChannelType.OPTICAL,
            durationSeconds = seconds,
            isAllowed = true,
            formattedTime = formatDuration(seconds),
            speedDescription = "~1.0–2.5 KB/s"
        )
    }

    fun calculateUltrasonicEta(fileSizeBytes: Long): EtaResult {
        if (fileSizeBytes > ULTRASONIC_MAX_BYTES) {
            return EtaResult(
                channel = ChannelType.ULTRASONIC,
                durationSeconds = -1,
                isAllowed = false,
                formattedTime = "Exceeds 128 KB Cap",
                speedDescription = "Acoustic link disabled for files > 128 KB"
            )
        }
        val seconds = ((fileSizeBytes / ULTRASONIC_BYTE_RATE) * ULTRASONIC_REDUNDANCY).roundToInt()
        return EtaResult(
            channel = ChannelType.ULTRASONIC,
            durationSeconds = seconds,
            isAllowed = true,
            formattedTime = formatDuration(seconds),
            speedDescription = "~0.02–0.1 KB/s"
        )
    }

    private fun formatDuration(totalSec: Int): String {
        return if (totalSec < 60) {
            "${totalSec}s"
        } else {
            val mins = totalSec / 60
            val remSec = totalSec % 60
            "${mins}m ${remSec}s"
        }
    }
}
