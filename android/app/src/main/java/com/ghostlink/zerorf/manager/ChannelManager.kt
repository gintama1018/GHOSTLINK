package com.ghostlink.zerorf.manager

import com.ghostlink.zerorf.channels.magnetic.MagneticPacket
import com.ghostlink.zerorf.core.Chunker
import com.ghostlink.zerorf.core.CryptoEngine
import com.ghostlink.zerorf.core.Packet
import com.ghostlink.zerorf.core.ReassemblyEngine
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

    enum class ChannelMode {
        OPTICAL,       // Screen ⇢ Camera
        ULTRASONIC,    // Speaker ⇢ Mic
        MAGNETIC       // Motor ⇢ Magnetometer
    }

    interface ChannelEventListener {
        fun onStateChanged(newState: State, message: String)
        fun onProgressUpdate(fraction: Float, speedBps: Double)
        fun onFileReady(fileName: String, fileBytes: ByteArray)
        fun onError(error: String)
    }

    companion object {
        val DEFAULT_SEED = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())
    }

    var currentState: State = State.IDLE
        private set

    var activeChannel: ChannelMode = ChannelMode.OPTICAL
        private set

    private var sessionKey: ByteArray? = null
    private var reassemblyEngine: ReassemblyEngine? = null
    private var outboundPackets: List<Packet>? = null
    private var currentLoopIndex = 0

    var currentFileName: String = "secret_payload.txt"
        private set

    /**
     * SENDER: Initiates transfer of a file with embedded metadata (filename + raw bytes).
     */
    fun startSender(fileName: String, fileBytes: ByteArray, mode: ChannelMode = ChannelMode.OPTICAL) {
        this.currentFileName = fileName
        this.activeChannel = mode
        setState(State.HANDSHAKE, "Initiating physical link ($mode)...")

        sessionKey = CryptoEngine.deriveSessionKey(DEFAULT_SEED)

        // Pack filename metadata before encryption:
        // [ 2 bytes: name length N ] [ N bytes: name UTF-8 ] [ fileBytes ]
        val nameBytes = fileName.toByteArray(Charsets.UTF_8)
        val metaBuffer = ByteBuffer.allocate(2 + nameBytes.size + fileBytes.size).order(ByteOrder.BIG_ENDIAN)
        metaBuffer.putShort(nameBytes.size.toShort())
        metaBuffer.put(nameBytes)
        metaBuffer.put(fileBytes)
        val bundledPayload = metaBuffer.array()

        // F7 Gate: Encrypt plaintext BEFORE chunking
        val ciphertext = CryptoEngine.encrypt(bundledPayload, sessionKey!!)

        // Determine chunk size based on physical layer
        val chunkSize = when (mode) {
            ChannelMode.OPTICAL -> Chunker.OPTICAL_DEFAULT_CHUNK_SIZE
            ChannelMode.ULTRASONIC -> Chunker.ULTRASONIC_DEFAULT_CHUNK_SIZE
            ChannelMode.MAGNETIC -> 8 // Tiny chunks for magnetic
        }

        outboundPackets = Chunker.chunk(ciphertext, chunkSize, mode == ChannelMode.ULTRASONIC)
        setState(State.TRANSFER, "Streaming ${outboundPackets!!.size} encrypted chunks via $mode...")
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
     * RECEIVER: Starts listening mode on specified physical channel.
     */
    fun startReceiver(mode: ChannelMode = ChannelMode.OPTICAL) {
        this.activeChannel = mode
        if (sessionKey == null) {
            sessionKey = CryptoEngine.deriveSessionKey(DEFAULT_SEED)
        }
        setState(State.TRANSFER, "Listening on $mode physical channel...")
    }

    /**
     * RECEIVER: Ingests 8-byte magnetic handshake packet from sender.
     */
    fun onMagneticHandshakeReceived(packet: MagneticPacket) {
        sessionKey = CryptoEngine.deriveSessionKey(packet.saltSeed)
        setState(State.NEGOTIATE, "Handshake completed via magnetic contact. Seed derived.")
        setState(State.TRANSFER, "Ready on $activeChannel channel...")
    }

    /**
     * RECEIVER: Ingests decoded packet from active physical channel (Camera, Mic, or Magnetometer).
     */
    fun onPacketReceived(packet: Packet) {
        if (currentState != State.TRANSFER && currentState != State.NEGOTIATE) {
            currentState = State.TRANSFER
        }

        if (sessionKey == null) {
            sessionKey = CryptoEngine.deriveSessionKey(DEFAULT_SEED)
        }

        if (reassemblyEngine == null || reassemblyEngine!!.totalChunks != packet.totalChunks) {
            reassemblyEngine = ReassemblyEngine(packet.totalChunks)
            setState(State.TRANSFER, "Receiving chunks: ${packet.chunkIndex + 1}/${packet.totalChunks}")
        }

        val engine = reassemblyEngine!!
        val isNew = engine.addPacket(packet)

        if (isNew) {
            val speed = when (activeChannel) {
                ChannelMode.OPTICAL -> TransparentEta.OPTICAL_BYTE_RATE
                ChannelMode.ULTRASONIC -> TransparentEta.ULTRASONIC_BYTE_RATE
                ChannelMode.MAGNETIC -> 5.0
            }
            listener.onProgressUpdate(engine.progressFraction, speed)
        }

        if (engine.isComplete) {
            setState(State.VERIFY, "100% chunks received (${engine.totalChunks}/${engine.totalChunks}). Verifying CRC32 and AES-GCM tag...")
            try {
                val fullCiphertext = engine.assemble()
                val decryptedBundled = CryptoEngine.decrypt(fullCiphertext, sessionKey!!)

                // Unpack metadata: [2B length] [Name] [FileBytes]
                val buf = ByteBuffer.wrap(decryptedBundled).order(ByteOrder.BIG_ENDIAN)
                val nameLen = buf.short.toInt() and 0xFFFF
                val nameBytes = ByteArray(nameLen)
                buf.get(nameBytes)
                val receivedName = String(nameBytes, Charsets.UTF_8)
                val fileContentBytes = ByteArray(decryptedBundled.size - 2 - nameLen)
                buf.get(fileContentBytes)

                setState(State.DONE, "Transfer complete! Verified $receivedName (${fileContentBytes.size} bytes).")
                listener.onFileReady(receivedName, fileContentBytes)
            } catch (e: Exception) {
                setState(State.ERROR, "Verification failed: ${e.message}")
                listener.onError("Decryption failed: ${e.message}")
            }
        }
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
