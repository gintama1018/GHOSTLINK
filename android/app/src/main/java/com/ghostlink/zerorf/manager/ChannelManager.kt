package com.ghostlink.zerorf.manager

import com.ghostlink.zerorf.channels.magnetic.MagneticPacket
import com.ghostlink.zerorf.core.Chunker
import com.ghostlink.zerorf.core.CryptoEngine
import com.ghostlink.zerorf.core.Packet
import com.ghostlink.zerorf.core.ReassemblyEngine

/**
 * Coordinates GhostLink Zero-RF transfer lifecycle across all physical layers.
 */
class ChannelManager(
    private val listener: ChannelEventListener
) {
    enum class State {
        IDLE,
        HANDSHAKE,
        NEGOTIATE,
        TRANSFER,
        VERIFY,
        DONE,
        ERROR
    }

    interface ChannelEventListener {
        fun onStateChanged(newState: State, message: String)
        fun onProgressUpdate(fraction: Float, speedBps: Double)
        fun onFileReady(fileBytes: ByteArray)
        fun onError(error: String)
    }

    companion object {
        // Paired fallback seed so direct optical point-and-scan works instantly even without magnetic touch
        val DEFAULT_SEED = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())
    }

    var currentState: State = State.IDLE
        private set

    var activeChannel: TransparentEta.ChannelType = TransparentEta.ChannelType.OPTICAL
        private set

    private var sessionKey: ByteArray? = null
    private var reassemblyEngine: ReassemblyEngine? = null
    private var outboundPackets: List<Packet>? = null
    private var currentLoopIndex = 0

    /**
     * SENDER: Initiates transfer of a file.
     */
    fun startSender(fileBytes: ByteArray, customSeed: ByteArray? = null) {
        setState(State.HANDSHAKE, "Initiating contact magnetic handshake & optical broadcast...")

        val seed = customSeed ?: DEFAULT_SEED
        sessionKey = CryptoEngine.deriveSessionKey(seed)

        // F7 Gate: Encrypt plaintext BEFORE chunking
        val ciphertext = CryptoEngine.encrypt(fileBytes, sessionKey!!)

        // Negotiate channel
        setState(State.NEGOTIATE, "Evaluating sensors and channel bandwidth...")
        activeChannel = TransparentEta.ChannelType.OPTICAL

        // Prepare outbound packets
        outboundPackets = Chunker.chunk(ciphertext, Chunker.OPTICAL_DEFAULT_CHUNK_SIZE)
        setState(State.TRANSFER, "Streaming ${outboundPackets!!.size} encrypted frames via Optical QR...")
    }

    /**
     * SENDER: Gets next packet in the cyclic broadcast loop.
     */
    fun getNextOutboundPacket(): Packet? {
        val packets = outboundPackets ?: return null
        if (packets.isEmpty()) return null
        val packet = packets[currentLoopIndex]
        currentLoopIndex = (currentLoopIndex + 1) % packets.size
        return packet
    }

    /**
     * RECEIVER: Starts listening mode on camera / mic / magnetic sensors.
     */
    fun startReceiver() {
        if (sessionKey == null) {
            sessionKey = CryptoEngine.deriveSessionKey(DEFAULT_SEED)
        }
        activeChannel = TransparentEta.ChannelType.OPTICAL
        setState(State.TRANSFER, "Ready. Point camera at sender's screen or touch for magnetic handshake...")
    }

    /**
     * RECEIVER: Ingests 8-byte magnetic handshake packet from sender.
     */
    fun onMagneticHandshakeReceived(packet: MagneticPacket) {
        sessionKey = CryptoEngine.deriveSessionKey(packet.saltSeed)
        setState(State.NEGOTIATE, "Handshake completed via magnetic contact. Seed derived.")
        activeChannel = TransparentEta.ChannelType.OPTICAL
        setState(State.TRANSFER, "Listening on Optical QR channel...")
    }

    /**
     * RECEIVER: Ingests decoded packet from active physical channel (Camera or Mic).
     */
    fun onPacketReceived(packet: Packet) {
        if (currentState != State.TRANSFER && currentState != State.NEGOTIATE) {
            // Auto-transition to transfer if packet arrives
            currentState = State.TRANSFER
        }

        if (sessionKey == null) {
            sessionKey = CryptoEngine.deriveSessionKey(DEFAULT_SEED)
        }

        if (reassemblyEngine == null || reassemblyEngine!!.totalChunks != packet.totalChunks) {
            reassemblyEngine = ReassemblyEngine(packet.totalChunks)
            setState(State.TRANSFER, "Receiving chunks: total ${packet.totalChunks}")
        }

        val engine = reassemblyEngine!!
        val isNew = engine.addPacket(packet)

        if (isNew) {
            listener.onProgressUpdate(engine.progressFraction, TransparentEta.OPTICAL_BYTE_RATE)
        }

        if (engine.isComplete) {
            setState(State.VERIFY, "100% frames received (${engine.totalChunks}/${engine.totalChunks}). Verifying CRC32 and AES-GCM tag...")
            try {
                val fullCiphertext = engine.assemble()
                val decryptedFile = CryptoEngine.decrypt(fullCiphertext, sessionKey!!)
                setState(State.DONE, "Transfer complete! Zero-RF file reconstructed with 0 bit errors.")
                listener.onFileReady(decryptedFile)
            } catch (e: Exception) {
                setState(State.ERROR, "Integrity verification failed: ${e.message}")
                listener.onError("Decryption failed: ${e.message}")
            }
        }
    }

    /**
     * Dynamic fallback triggered when camera is blocked/misaligned.
     */
    fun triggerFallbackToUltrasonic(fileSizeBytes: Long) {
        if (fileSizeBytes > Chunker.ULTRASONIC_MAX_FILE_SIZE) {
            listener.onError("Cannot fallback: file exceeds 128 KB acoustic cap. Re-align camera.")
            return
        }
        activeChannel = TransparentEta.ChannelType.ULTRASONIC
        setState(State.TRANSFER, "Falling back to Ultrasonic acoustic channel (~0.05 KB/s)...")
    }

    private fun setState(state: State, message: String) {
        currentState = state
        listener.onStateChanged(state, message)
    }

    fun reset() {
        currentState = State.IDLE
        sessionKey = null
        reassemblyEngine = null
        outboundPackets = null
        currentLoopIndex = 0
    }
}
