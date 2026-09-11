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

    var localKeyPair: java.security.KeyPair = CryptoEngine.generateEphemeralKeyPair()
        private set

    var peerPublicKey: java.security.PublicKey? = null
        private set

    var securityLevel: CryptoEngine.SecurityLevel = CryptoEngine.SecurityLevel.UNAUTHENTICATED_ECDH
        private set

    var sasCode: String? = null
        private set

    var magneticSalt: ByteArray? = null
        private set

    val localPublicKeyBytes: ByteArray
        get() = CryptoEngine.serializePublicKey(localKeyPair.public)

    var isMagneticHandshakeVerified: Boolean = false
        private set

    var currentFileName: String = "secret_payload.txt"
        private set

    fun setPeerPublicKey(peerPubKeyBytes: ByteArray, isSasVerified: Boolean = false) {
        val pubKey = CryptoEngine.deserializePublicKey(peerPubKeyBytes)
        this.peerPublicKey = pubKey
        this.securityLevel = if (isSasVerified) {
            CryptoEngine.SecurityLevel.SAS_AUTHENTICATED_ECDH
        } else {
            CryptoEngine.SecurityLevel.UNAUTHENTICATED_ECDH
        }
    }

    /**
     * SENDER: Initiates transfer of a file.
     * Generates an ephemeral cryptographic keypair, computes ECDH shared secret Z,
     * and derives session keys via HKDF-SHA256 with transcript binding.
     */
    fun startSender(fileName: String, fileBytes: ByteArray, channel: BulkChannel = BulkChannel.OPTICAL) {
        this.currentFileName = fileName
        this.activeBulkChannel = channel
        setState(State.HANDSHAKE, "Generating ephemeral cryptographic keys & computing ECDH shared secret...")

        // 1. Generate fresh ephemeral NIST P-256 KeyPair for this session
        this.localKeyPair = CryptoEngine.generateEphemeralKeyPair()
        val senderPubBytes = localPublicKeyBytes

        // 2. Resolve Peer Public Key:
        // If peerPublicKey already paired (e.g. from scanning Receiver's QR), use it for 2-Way Authenticated ECDH.
        // Otherwise, generate ephemeral receiver key for 1-way broadcast mode.
        val isTwoWay = (this.peerPublicKey != null)
        val receiverPub: java.security.PublicKey
        val receiverPubBytes: ByteArray
        if (isTwoWay) {
            receiverPub = this.peerPublicKey!!
            receiverPubBytes = CryptoEngine.serializePublicKey(receiverPub)
            this.securityLevel = if (this.securityLevel == CryptoEngine.SecurityLevel.SAS_AUTHENTICATED_ECDH) {
                CryptoEngine.SecurityLevel.SAS_AUTHENTICATED_ECDH
            } else if (isMagneticHandshakeVerified) {
                CryptoEngine.SecurityLevel.CONTACT_BOUND_ECDH
            } else {
                CryptoEngine.SecurityLevel.UNAUTHENTICATED_ECDH
            }
        } else {
            val ephemeralRecipient = CryptoEngine.generateEphemeralKeyPair()
            receiverPub = ephemeralRecipient.public
            receiverPubBytes = CryptoEngine.serializePublicKey(receiverPub)
            this.securityLevel = CryptoEngine.SecurityLevel.UNAUTHENTICATED_ECDH
        }

        // 3. Compute True ECDH Shared Secret: Z = ECDH(sk_A, pk_B)
        val sharedSecret = CryptoEngine.computeSharedSecret(localKeyPair.private, receiverPub)

        // 4. Build Domain-Separated Transcript Info
        val dummySessionId = (System.currentTimeMillis() and 0xFFFFFFFFL).coerceAtLeast(1L)
        val transcriptInfo = CryptoEngine.buildTranscriptInfo(
            protocolVersion = ProtocolConstants.WIRE_VERSION,
            sessionId = dummySessionId,
            senderPubKey = senderPubBytes,
            receiverPubKey = receiverPubBytes,
            role = "SENDER",
            bulkMode = channel.name
        )

        // 5. Derive Session Keys via HKDF-SHA256 with Transcript Binding & Optional Magnetic Salt
        val keys = CryptoEngine.deriveSessionKeys(sharedSecret, salt = magneticSalt, transcriptInfo = transcriptInfo)
        this.activeSessionKeys = keys
        this.sasCode = CryptoEngine.computeSasCode(senderPubBytes, receiverPubBytes, keys.sessionId)

        // 6. Pack filename metadata before encryption: [2B length] [name UTF-8] [fileBytes]
        val nameBytes = fileName.toByteArray(Charsets.UTF_8)
        val metaBuffer = ByteBuffer.allocate(2 + nameBytes.size + fileBytes.size).order(ByteOrder.BIG_ENDIAN)
        metaBuffer.putShort(nameBytes.size.toShort())
        metaBuffer.put(nameBytes)
        metaBuffer.put(fileBytes)
        val bundledPayload = metaBuffer.array()

        // 7. F7 Gate: Encrypt entire plaintext bundle with AES-GCM-256
        val ciphertext = CryptoEngine.encrypt(bundledPayload, keys, 0L)

        // 8. Split ciphertext into chunks based on physical channel
        val chunkSize = when (channel) {
            BulkChannel.OPTICAL -> ProtocolConstants.OPTICAL_CHUNK_SIZE
            BulkChannel.ULTRASONIC -> ProtocolConstants.ACOUSTIC_CHUNK_SIZE
        }

        val rawPackets = Chunker.chunk(ciphertext, chunkSize, channel == BulkChannel.ULTRASONIC)

        // 9. Prepend Key Agreement Header to chunk 0:
        // [ 1 byte: flags (0x01 = 2-Way ECDH, 0x00 = 1-Way Broadcast) ]
        // [ 65 bytes: senderPubKey (pk_A) ]
        // [ 65 bytes: receiverPubKey (pk_B) ]
        // [ chunk 0 payload ]
        val finalPackets = ArrayList<Packet>(rawPackets.size)
        val keyHeaderSize = 1 + senderPubBytes.size + receiverPubBytes.size
        for (i in rawPackets.indices) {
            val p = rawPackets[i]
            if (i == 0) {
                val chunk0Buf = ByteBuffer.allocate(keyHeaderSize + p.payload.size)
                chunk0Buf.put(if (isTwoWay) 0x01.toByte() else 0x00.toByte())
                chunk0Buf.put(senderPubBytes)
                chunk0Buf.put(receiverPubBytes)
                chunk0Buf.put(p.payload)
                finalPackets.add(Packet.create(0L, p.totalChunks, chunk0Buf.array(), sessionId = keys.sessionId))
            } else {
                finalPackets.add(Packet.create(p.chunkIndex, p.totalChunks, p.payload, sessionId = keys.sessionId))
            }
        }

        this.outboundPackets = finalPackets
        this.currentLoopIndex = 0
        val sasNotice = if (isTwoWay) " · SAS: $sasCode" else ""
        setState(State.TRANSFER, "Streaming ${finalPackets.size} encrypted chunks via $channel ($sasNotice)")
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
        this.localKeyPair = CryptoEngine.generateEphemeralKeyPair()
        this.securityLevel = CryptoEngine.SecurityLevel.UNAUTHENTICATED_ECDH
        this.sasCode = null
        setState(State.TRANSFER, "Listening on $channel channel. Ephemeral key ready.")
    }

    /**
     * RECEIVER: Ingests 8-byte magnetic handshake packet from sender.
     */
    fun onMagneticHandshakeReceived(packet: MagneticPacket) {
        this.magneticSalt = packet.saltSeed
        this.isMagneticHandshakeVerified = true
        if (peerPublicKey != null && activeSessionKeys == null) {
            val sharedSecret = CryptoEngine.computeSharedSecret(localKeyPair.private, peerPublicKey!!)
            val senderPubBytes = CryptoEngine.serializePublicKey(peerPublicKey!!)
            val transcript = CryptoEngine.buildTranscriptInfo(
                protocolVersion = ProtocolConstants.WIRE_VERSION,
                sessionId = 0L,
                senderPubKey = senderPubBytes,
                receiverPubKey = localPublicKeyBytes,
                role = "SENDER",
                bulkMode = activeBulkChannel.name
            )
            val keys = CryptoEngine.deriveSessionKeys(sharedSecret, salt = magneticSalt, transcriptInfo = transcript)
            this.activeSessionKeys = keys
            this.securityLevel = CryptoEngine.SecurityLevel.CONTACT_BOUND_ECDH
        }
        setState(State.NEGOTIATE, "Magnetic contact token bound (<2cm). Ready on $activeBulkChannel channel...")
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

        // Handle chunk 0 public key / key agreement
        val actualPayload: ByteArray
        if (packet.chunkIndex == 0L) {
            if (packet.payload.size >= 131) { // 1 flag + 65 sender + 65 receiver
                val senderPubBytes = packet.payload.copyOfRange(1, 66)
                val receiverPubBytes = packet.payload.copyOfRange(66, 131)
                actualPayload = packet.payload.copyOfRange(131, packet.payload.size)

                if (activeSessionKeys == null) {
                    val senderPub = CryptoEngine.deserializePublicKey(senderPubBytes)
                    this.peerPublicKey = senderPub

                    val isDirectMatch = localPublicKeyBytes.contentEquals(receiverPubBytes)
                    val privKey = if (isDirectMatch) {
                        this.securityLevel = if (isMagneticHandshakeVerified) {
                            CryptoEngine.SecurityLevel.CONTACT_BOUND_ECDH
                        } else {
                            CryptoEngine.SecurityLevel.SAS_AUTHENTICATED_ECDH
                        }
                        localKeyPair.private
                    } else {
                        this.securityLevel = CryptoEngine.SecurityLevel.UNAUTHENTICATED_ECDH
                        localKeyPair.private
                    }

                    // Compute True ECDH Shared Secret: Z = ECDH(sk_B, pk_A)
                    val sharedSecret = CryptoEngine.computeSharedSecret(privKey, senderPub)

                    val transcriptInfo = CryptoEngine.buildTranscriptInfo(
                        protocolVersion = ProtocolConstants.WIRE_VERSION,
                        sessionId = packet.sessionId,
                        senderPubKey = senderPubBytes,
                        receiverPubKey = receiverPubBytes,
                        role = "SENDER",
                        bulkMode = activeBulkChannel.name
                    )

                    val keys = CryptoEngine.deriveSessionKeys(sharedSecret, salt = magneticSalt, transcriptInfo = transcriptInfo)
                    this.activeSessionKeys = keys
                    this.sasCode = CryptoEngine.computeSasCode(senderPubBytes, receiverPubBytes, keys.sessionId)
                }
            } else if (packet.payload.size >= 65) {
                val senderPubBytes = packet.payload.copyOfRange(0, 65)
                actualPayload = packet.payload.copyOfRange(65, packet.payload.size)
                if (activeSessionKeys == null) {
                    val senderPub = CryptoEngine.deserializePublicKey(senderPubBytes)
                    this.peerPublicKey = senderPub
                    val sharedSecret = CryptoEngine.computeSharedSecret(localKeyPair.private, senderPub)
                    val keys = CryptoEngine.deriveSessionKeys(sharedSecret, salt = magneticSalt)
                    this.activeSessionKeys = keys
                    this.sasCode = CryptoEngine.computeSasCode(senderPubBytes, localPublicKeyBytes, keys.sessionId)
                }
            } else {
                actualPayload = packet.payload
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
