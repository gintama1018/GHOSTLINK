package com.ghostlink.zerorf.manager

import com.ghostlink.zerorf.channels.magnetic.MagneticPacket
import com.ghostlink.zerorf.core.Chunker
import com.ghostlink.zerorf.core.CryptoEngine
import com.ghostlink.zerorf.core.Packet
import com.ghostlink.zerorf.core.ProtocolConstants
import com.ghostlink.zerorf.core.ReassemblyEngine
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Coordinates GhostLink Zero-RF transfer lifecycle across physical layers.
 * Bulk channels: OPTICAL (Screen ⇢ Camera) and ACOUSTIC (Speaker ⇢ Mic).
 * Handshake layer: MAGNETIC (Vibration Motor ⇢ Magnetometer).
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

    enum class BulkChannel {
        OPTICAL,       // Screen ⇢ Camera (~2.5 KB/s)
        ULTRASONIC     // Speaker ⇢ Mic (~8.5–47 B/s)
    }

    interface ChannelEventListener {
        fun onStateChanged(newState: State, message: String)
        fun onProgressUpdate(fraction: Float, speedBps: Double, receivedChunks: Int, totalChunks: Long)
        fun onFileReady(fileName: String, fileBytes: ByteArray)
        fun onError(error: String)
    }

    var currentState: State = State.IDLE
        private set

    var activeBulkChannel: BulkChannel = BulkChannel.OPTICAL
        private set

    var activeSessionKeys: CryptoEngine.SessionKeys? = null
        private set

    private var reassemblyEngine: ReassemblyEngine? = null
    private var outboundPackets: List<Packet>? = null
    private var currentLoopIndex = 0

    var isMagneticHandshakeVerified: Boolean = false
        private set

    var currentFileName: String = "secret_payload.txt"
        private set

    /**
     * SENDER: Initiates transfer of a file.
     * Generates an ephemeral cryptographic keypair and derives session keys via HKDF-SHA256.
     */
    fun startSender(fileName: String, fileBytes: ByteArray, channel: BulkChannel = BulkChannel.OPTICAL) {
        this.currentFileName = fileName
        this.activeBulkChannel = channel
        setState(State.HANDSHAKE, "Generating ephemeral cryptographic keys & preparing transfer...")

        // 1. Generate Ephemeral NIST P-256 KeyPair
        val keyPair = CryptoEngine.generateEphemeralKeyPair()
        val pubKeyBytes = CryptoEngine.serializePublicKey(keyPair.public)

        // 2. Derive Session Keys via HKDF-SHA256
        val keys = CryptoEngine.deriveSessionKeys(pubKeyBytes)
        this.activeSessionKeys = keys

        // 3. Pack filename metadata before encryption: [2B length] [name UTF-8] [fileBytes]
        val nameBytes = fileName.toByteArray(Charsets.UTF_8)
        val metaBuffer = ByteBuffer.allocate(2 + nameBytes.size + fileBytes.size).order(ByteOrder.BIG_ENDIAN)
        metaBuffer.putShort(nameBytes.size.toShort())
        metaBuffer.put(nameBytes)
        metaBuffer.put(fileBytes)
        val bundledPayload = metaBuffer.array()

        // 4. F7 Gate: Encrypt entire plaintext bundle with AES-GCM-256
        val ciphertext = CryptoEngine.encrypt(bundledPayload, keys, 0L)

        // 5. Split ciphertext into chunks based on physical channel
        val chunkSize = when (channel) {
            BulkChannel.OPTICAL -> ProtocolConstants.OPTICAL_CHUNK_SIZE
            BulkChannel.ULTRASONIC -> ProtocolConstants.ACOUSTIC_CHUNK_SIZE
        }

        val rawPackets = Chunker.chunk(ciphertext, chunkSize, channel == BulkChannel.ULTRASONIC)

        // 6. Prepend 65-byte uncompressed public key to chunk 0 so receiver can derive identical session keys
        val finalPackets = ArrayList<Packet>(rawPackets.size)
        for (i in rawPackets.indices) {
            val p = rawPackets[i]
            if (i == 0) {
                val chunk0Buf = ByteBuffer.allocate(pubKeyBytes.size + p.payload.size)
                chunk0Buf.put(pubKeyBytes)
                chunk0Buf.put(p.payload)
                finalPackets.add(Packet.create(0L, p.totalChunks, chunk0Buf.array(), sessionId = keys.sessionId))
            } else {
                finalPackets.add(Packet.create(p.chunkIndex, p.totalChunks, p.payload, sessionId = keys.sessionId))
            }
        }

        this.outboundPackets = finalPackets
        this.currentLoopIndex = 0
        setState(State.TRANSFER, "Streaming ${finalPackets.size} encrypted chunks via $channel...")
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
     * RECEIVER: Starts listening on specified bulk channel.
     */
    fun startReceiver(channel: BulkChannel = BulkChannel.OPTICAL) {
        this.activeBulkChannel = channel
        this.reassemblyEngine = null
        this.activeSessionKeys = null
        this.isMagneticHandshakeVerified = false
        setState(State.TRANSFER, "Listening on $channel channel...")
    }

    /**
     * RECEIVER: Ingests 8-byte magnetic handshake packet from sender.
     */
    fun onMagneticHandshakeReceived(packet: MagneticPacket) {
        val seed = packet.saltSeed
        val keys = CryptoEngine.deriveSessionKeys(seed)
        this.activeSessionKeys = keys
        this.isMagneticHandshakeVerified = true
        setState(State.NEGOTIATE, "Magnetic contact handshake verified! Session ID: 0x${keys.sessionId.toString(16)}")
        setState(State.TRANSFER, "Ready on $activeBulkChannel channel...")
    }

    /**
     * RECEIVER: Ingests decoded packet from active physical channel (Camera or Mic).
     */
    fun onPacketReceived(packet: Packet) {
        if (currentState == State.DONE) return

        if (currentState != State.TRANSFER && currentState != State.NEGOTIATE) {
            currentState = State.TRANSFER
        }

        // Initialize reassembly engine if needed
        if (reassemblyEngine == null) {
            reassemblyEngine = ReassemblyEngine(packet.totalChunks, packet.sessionId)
            setState(State.TRANSFER, "Receiving chunks: 0/${packet.totalChunks} (0%)")
        } else if (reassemblyEngine!!.totalChunks != packet.totalChunks) {
            // Mismatched totalChunks from foreign stream — do NOT discard in-progress chunks!
            return
        }

        val engine = reassemblyEngine!!
        if (engine.isExpired) {
            reassemblyEngine = null
            setState(State.ERROR, "Reassembly session expired due to inactivity.")
            listener.onError("Transfer timed out.")
            return
        }

        // Handle chunk 0 public key / seed extraction
        val actualPayload: ByteArray
        if (packet.chunkIndex == 0L) {
            if (packet.payload.size < 65) {
                // If smaller than 65 bytes, check if 4-byte seed mode
                if (packet.payload.size >= 4) {
                    val seed = packet.payload.copyOfRange(0, 4)
                    if (activeSessionKeys == null) {
                        activeSessionKeys = CryptoEngine.deriveSessionKeys(seed)
                    }
                    actualPayload = packet.payload.copyOfRange(4, packet.payload.size)
                } else {
                    listener.onError("Malformed chunk 0 (less than 4-byte seed)")
                    return
                }
            } else {
                val pubKeyBytes = packet.payload.copyOfRange(0, 65)
                if (activeSessionKeys == null) {
                    activeSessionKeys = CryptoEngine.deriveSessionKeys(pubKeyBytes)
                }
                actualPayload = packet.payload.copyOfRange(65, packet.payload.size)
            }
        } else {
            actualPayload = packet.payload
        }

        val strippedPacket = Packet(
            sessionId = packet.sessionId,
            chunkIndex = packet.chunkIndex,
            totalChunks = packet.totalChunks,
            flags = packet.flags,
            payloadLength = actualPayload.size,
            checksum = packet.checksum,
            payload = actualPayload
        )

        val isNew = engine.addPacket(strippedPacket)

        if (isNew) {
            val speed = when (activeBulkChannel) {
                BulkChannel.OPTICAL -> TransparentEta.OPTICAL_BYTE_RATE
                BulkChannel.ULTRASONIC -> TransparentEta.ULTRASONIC_BYTE_RATE
            }
            listener.onProgressUpdate(engine.progressFraction, speed, engine.receivedCount, engine.totalChunks)
        }

        if (engine.isComplete) {
            setState(State.VERIFY, "100% chunks received. Verifying CRC32 and AES-GCM-256 tag...")
            val keys = activeSessionKeys
            if (keys == null) {
                setState(State.ERROR, "Session keys missing. Handshake not established.")
                listener.onError("Cannot decrypt: Session keys missing.")
                return
            }

            try {
                val fullCiphertext = engine.assemble()
                val decryptedBundled = CryptoEngine.decrypt(fullCiphertext, keys, 0L)

                // Unpack metadata: [2B length] [Name] [FileBytes]
                val buf = ByteBuffer.wrap(decryptedBundled).order(ByteOrder.BIG_ENDIAN)
                val nameLen = buf.short.toInt() and 0xFFFF
                val nameBytes = ByteArray(nameLen)
                buf.get(nameBytes)
                val receivedName = String(nameBytes, Charsets.UTF_8)
                val fileContentBytes = ByteArray(decryptedBundled.size - 2 - nameLen)
                buf.get(fileContentBytes)

                reassemblyEngine = null
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
        activeSessionKeys = null
        isMagneticHandshakeVerified = false
        reassemblyEngine = null
        outboundPackets = null
        currentLoopIndex = 0
    }
}
