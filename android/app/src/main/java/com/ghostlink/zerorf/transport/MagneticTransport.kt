package com.ghostlink.zerorf.transport

import com.ghostlink.zerorf.channels.magnetic.MagneticPacket
import com.ghostlink.zerorf.channels.magnetic.MagnetometerReceiver
import com.ghostlink.zerorf.channels.magnetic.VibrationTransmitter
import com.ghostlink.zerorf.core.Packet

/**
 * Physical Magnetic Induction Transport (Vibration Motor ⇢ Magnetometer).
 * Strictly used for physical contact pairing and key exchange (<2 cm proximity).
 */
class MagneticTransport(
    private val transmitter: VibrationTransmitter,
    private val receiver: MagnetometerReceiver
) : PhysicalTransport {

    override val transportType: TransportType = TransportType.MAGNETIC
    override val isAvailable: Boolean = true
    override val defaultChunkSize: Int = 8 // Fixed 8-byte token size

    private var tokensSent = 0L
    private var tokensRecv = 0L

    fun transmitToken(packet: MagneticPacket) {
        transmitter.transmit(packet)
        tokensSent++
    }

    override fun startTransmitter(packets: List<Packet>) {
        // Bulk data not supported on magnetic channel
    }

    override fun startReceiver(onPacketReceived: (Packet) -> Unit) {
        receiver.startListening()
    }

    fun onTokenReceived() {
        tokensRecv++
    }

    override fun stop() {
        transmitter.stop()
        receiver.stopListening()
    }

    override fun getTelemetry(): TransportTelemetry {
        return TransportTelemetry(
            transportType = TransportType.MAGNETIC,
            instantaneousBps = 0.83,
            averageBps = 0.80,
            goodputBps = 0.70,
            framesTransmitted = tokensSent,
            framesReceived = tokensRecv,
            activeModulation = "Motor Pulse OOK (<2cm Contact)"
        )
    }
}
