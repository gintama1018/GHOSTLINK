package com.ghostlink.zerorf.manager

import com.ghostlink.zerorf.channels.magnetic.MagneticPacket
import com.ghostlink.zerorf.core.Chunker
import com.ghostlink.zerorf.core.CryptoEngine
import com.ghostlink.zerorf.core.Packet
import com.ghostlink.zerorf.core.ReassemblyEngine
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/**
 * Coordinates GhostLink Zero-RF transfer lifecycle across physical layers.
 * Bulk channels: OPTICAL (Screen ⇢ Camera) and ULTRASONIC (Speaker ⇢ Mic).
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
        OPTICAL,       // Screen ⇢ Camera (~1.0–2.5 KB/s)
        ULTRASONIC     // Speaker ⇢ Mic (~8.5 B/s, max 128 KB)
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

    // True per-session cryptographic seed (Generated fresh via SecureRandom for each transfer)
    var activeSessionSeed: ByteArray? = null
        private set

    private var sessionKey: ByteArray? = null
    private var reassemblyEngine: ReassemblyEngine? = null
    private var outboundPackets: List<Packet>? = null
    private var currentLoopIndex = 0

    var isMagneticHandshakeVerified: Boolean = false
        private set

    var currentFileName: String = "secret_payload.txt"
        private set

    /**
     * SENDER: Initiates transfer of a file.
     * Generates a fresh 4-byte SecureRandom seed for this transfer session.
     */
    fun startSender(fileName: String, fileBytes: ByteArray, channel: BulkChannel = BulkChannel.OPTICAL) {
        this.currentFileName = fileName
        this.activeBulkChannel = channel
        setState(State.HANDSHAKE, "Generating cryptographic session seed & initiating transfer...")

        // 1. Generate fresh per-session SecureRandom seed (4 bytes / 32 bits of entropy)
        val seed = ByteArray(4).apply { SecureRandom().nextBytes(this) }
        this.activeSessionSeed = seed
        this.sessionKey = CryptoEngine.deriveSessionKey(seed)

        // 2. Pack filename metadata before encryption:
        // [ 2 bytes: name length N ] [ N bytes: name UTF-8 ] [ fileBytes ]
        val nameBytes = fileName.toByteArray(Charsets.UTF_8)
        val metaBuffer = ByteBuffer.allocate(2 + nameBytes.size + fileBytes.size).order(ByteOrder.BIG_ENDIAN)
        metaBuffer.putShort(nameBytes.size.toShort())
        metaBuffer.put(nameBytes)
        metaBuffer.put(fileBytes)
        val bundledPayload = metaBuffer.array()

        // 3. F7 Gate: Encrypt entire plaintext bundle with session key BEFORE chunking
        val ciphertext = CryptoEngine.encrypt(bundledPayload, sessionKey!!)

        // 4. Split ciphertext into chunks based on physical channel
        val chunkSize = when (channel) {
            BulkChannel.OPTICAL -> Chunker.OPTICAL_DEFAULT_CHUNK_SIZE
            BulkChannel.ULTRASONIC -> Chunker.ULTRASONIC_DEFAULT_CHUNK_SIZE
        }

        val rawPackets = Chunker.chunk(ciphertext, chunkSize, channel == BulkChannel.ULTRASONIC)

        // 5. Prepend the 4-byte session seed to chunk 0 payload so optical / audio receiver can extract it
        val finalPackets = ArrayList<Packet>(rawPackets.size)
        for (i in rawPackets.indices) {
            val p = rawPackets[i]
            if (i == 0) {
                // Chunk 0 payload: [4 bytes: activeSessionSeed] + [ciphertext chunk 0 bytes]
                val chunk0Buf = ByteBuffer.allocate(4 + p.payload.size)
                chunk0Buf.put(seed)
                chunk0Buf.put(p.payload)
                finalPackets.add(Packet.create(0L, p.totalChunks, chunk0Buf.array()))
            } else {
                finalPackets.add(p)
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
        this.sessionKey = null
        this.activeSessionSeed = null
        this.isMagneticHandshakeVerified = false
        setState(State.TRANSFER, "Listening on $channel channel...")
    }

    /**
     * RECEIVER: Ingests 8-byte magnetic handshake packet from sender.
     */
    fun onMagneticHandshakeReceived(packet: MagneticPacket) {
        val seed = packet.saltSeed
        this.activeSessionSeed = seed
        this.sessionKey = CryptoEngine.deriveSessionKey(seed)
        this.isMagneticHandshakeVerified = true
        setState(State.NEGOTIATE, "Magnetic contact handshake verified! 32-bit seed received.")
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
            reassemblyEngine = ReassemblyEngine(packet.totalChunks)
            setState(State.TRANSFER, "Receiving chunks: 0/${packet.totalChunks} (0%)")
        } else if (reassemblyEngine!!.totalChunks != packet.totalChunks) {
            // Mismatched totalChunks from foreign or stale stream — do NOT discard in-progress chunks!
            return
        }

        val engine = reassemblyEngine!!

        // Handle chunk 0 seed extraction
        val actualPayload: ByteArray
        if (packet.chunkIndex == 0L) {
            if (packet.payload.size < 4) {
                listener.onError("Malformed chunk 0 (less than 4-byte seed)")
                return
            }
            val seed = packet.payload.copyOfRange(0, 4)
            if (sessionKey == null) {
                // If magnetic handshake wasn't used, derive key from optical/audio chunk 0 seed
                activeSessionSeed = seed
                sessionKey = CryptoEngine.deriveSessionKey(seed)
            }
            // Strip the 4-byte seed before buffering chunk 0 ciphertext
            actualPayload = packet.payload.copyOfRange(4, packet.payload.size)
        } else {
            actualPayload = packet.payload
        }

        val strippedPacket = Packet(
            chunkIndex = packet.chunkIndex,
            totalChunks = packet.totalChunks,
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
            if (sessionKey == null) {
                setState(State.ERROR, "Session key missing. Handshake not established.")
                listener.onError("Cannot decrypt: Session key missing.")
                return
            }

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
        sessionKey = null
        activeSessionSeed = null
        isMagneticHandshakeVerified = false
        reassemblyEngine = null
        outboundPackets = null
        currentLoopIndex = 0
    }
}
