package com.ghostlink.zerorf.transport

/**
 * Diagnostic and engineering telemetry emitted by physical transports.
 */
data class TransportTelemetry(
    val transportType: TransportType,
    val instantaneousBps: Double = 0.0,
    val averageBps: Double = 0.0,
    val goodputBps: Double = 0.0,
    val framesTransmitted: Long = 0L,
    val framesReceived: Long = 0L,
    val framesDropped: Long = 0L,
    val crcFailures: Long = 0L,
    val estimatedSnrDb: Float = 0f,
    val activeModulation: String = "N/A"
)

enum class TransportType {
    OPTICAL,
    ACOUSTIC,
    MAGNETIC
}
