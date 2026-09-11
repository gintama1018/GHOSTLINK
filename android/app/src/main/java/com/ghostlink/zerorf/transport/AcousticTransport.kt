package com.ghostlink.zerorf.transport

import com.ghostlink.zerorf.channels.ultrasonic.AudioFskDemodulator
import com.ghostlink.zerorf.channels.ultrasonic.AudioFskModulator
import com.ghostlink.zerorf.channels.ultrasonic.modem.AcousticMode
import com.ghostlink.zerorf.core.Packet
import com.ghostlink.zerorf.core.ProtocolConstants

/**
 * Adaptive Acoustic Modem Transport (Speaker ⇢ Microphone).
 */
class AcousticTransport(
    val modulator: AudioFskModulator = AudioFskModulator(),
    val demodulator: AudioFskDemodulator = AudioFskDemodulator(onPacketDecoded = {})
) : PhysicalTransport {

    override val transportType: TransportType = TransportType.ACOUSTIC
    override val isAvailable: Boolean = true
    override val defaultChunkSize: Int = ProtocolConstants.ACOUSTIC_CHUNK_SIZE

    private var framesSent = 0L
    private var framesRecv = 0L
    private var lastSnrDb = 0f

    fun setMode(mode: AcousticMode) {
        modulator.activeMode = mode
        demodulator.setMode(mode)
    }

    override fun startTransmitter(packets: List<Packet>) {
        framesSent = 0L
    }

    fun transmitPacketSynchronously(packet: Packet) {
        modulator.playPacket(packet)
        framesSent++
    }

    override fun startReceiver(onPacketReceived: (Packet) -> Unit) {
        framesRecv = 0L
        demodulator.startListening()
    }

    fun updateSnr(snrDb: Float) {
        lastSnrDb = snrDb
    }

    fun onPacketDecoded(_packet: Packet) {
        framesRecv++
    }

    override fun stop() {
        modulator.stop()
        demodulator.stopListening()
    }

    override fun getTelemetry(): TransportTelemetry {
        return TransportTelemetry(
            transportType = TransportType.ACOUSTIC,
            instantaneousBps = modulator.activeMode.nominalBps,
            averageBps = modulator.activeMode.nominalBps * 0.85,
            goodputBps = modulator.activeMode.nominalBps * 0.70,
            framesTransmitted = framesSent,
            framesReceived = framesRecv,
            estimatedSnrDb = lastSnrDb,
            activeModulation = modulator.activeMode.description
        )
    }
}
