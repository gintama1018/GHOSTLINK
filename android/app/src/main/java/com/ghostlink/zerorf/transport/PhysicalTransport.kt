package com.ghostlink.zerorf.transport

import com.ghostlink.zerorf.core.Packet

/**
 * Standard contract for all physical air-gapped communication channels.
 * Encapsulates transmitter and receiver lifecycle, asynchronous frame flow, and observability.
 */
interface PhysicalTransport {
    val transportType: TransportType
    val isAvailable: Boolean
    val defaultChunkSize: Int

    /**
     * Initiates asynchronous transmission of a packet list.
     */
    fun startTransmitter(packets: List<Packet>)

    /**
     * Initiates listening/reception on this physical channel.
     */
    fun startReceiver(onPacketReceived: (Packet) -> Unit)

    /**
     * Cleanly terminates active transmitter/receiver hardware routines.
     */
    fun stop()

    /**
     * Returns current empirical telemetry metrics.
     */
    fun getTelemetry(): TransportTelemetry
}
