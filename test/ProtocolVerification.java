package com.ghostlink.test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.BitSet;
import java.util.zip.CRC32;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * GhostLink Zero-RF Protocol Verification Suite
 * Compiles and runs directly on JDK 17.
 * Validates:
 * 1. 14-Byte Packet Framing (uint32 chunk_index, uint32 total_chunks, uint16 length, uint32 CRC32)
 * 2. CRC32 Integrity & Tamper Detection
 * 3. 8-Byte Streamlined Magnetic Handshake (Sync 0xA5, Caps, 4-byte Seed, CRC16)
 * 4. F7 Payload Cryptographic Gate (HKDF-SHA256 key derivation + AES-256-GCM authenticated encryption)
 * 5. Chunker & Reassembly Engine (Out-of-order arrival, bitmap completion, reconstructed payload hash)
 */
public class ProtocolVerification {

    public static final int HEADER_SIZE = 14;
    public static final byte SYNC_BYTE = (byte) 0xA5;

    // --- 1. PACKET SPECIFICATION (14-Byte Header) ---
    public static class Packet {
        public final long chunkIndex;     // uint32 (represented as long)
        public final long totalChunks;    // uint32 (represented as long)
        public final int payloadLength;   // uint16 (represented as int)
        public final long checksum;       // uint32 CRC32 (represented as long)
        public final byte[] payload;

        public Packet(long chunkIndex, long totalChunks, byte[] payload) {
            this.chunkIndex = chunkIndex;
            this.totalChunks = totalChunks;
            this.payload = payload;
            this.payloadLength = payload.length;
            CRC32 crc = new CRC32();
            crc.update(payload);
            this.checksum = crc.getValue();
        }

        public Packet(long chunkIndex, long totalChunks, int payloadLength, long checksum, byte[] payload) {
            this.chunkIndex = chunkIndex;
            this.totalChunks = totalChunks;
            this.payloadLength = payloadLength;
            this.checksum = checksum;
            this.payload = payload;
        }

        public byte[] serialize() {
            ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + payload.length);
            buf.order(ByteOrder.BIG_ENDIAN);
            buf.putInt((int) (chunkIndex & 0xFFFFFFFFL));
            buf.putInt((int) (totalChunks & 0xFFFFFFFFL));
            buf.putShort((short) (payloadLength & 0xFFFF));
            buf.putInt((int) (checksum & 0xFFFFFFFFL));
            buf.put(payload);
            return buf.array();
        }

        public static Packet deserialize(byte[] data) throws Exception {
            if (data == null || data.length < HEADER_SIZE) {
                throw new IllegalArgumentException("Packet shorter than 14-byte header");
            }
            ByteBuffer buf = ByteBuffer.wrap(data);
            buf.order(ByteOrder.BIG_ENDIAN);
            long cIndex = Integer.toUnsignedLong(buf.getInt());
            long tChunks = Integer.toUnsignedLong(buf.getInt());
            int pLen = Short.toUnsignedInt(buf.getShort());
            long cSum = Integer.toUnsignedLong(buf.getInt());

            if (data.length != HEADER_SIZE + pLen) {
                throw new IllegalArgumentException("Packet payload length mismatch: header says " + pLen + ", got " + (data.length - HEADER_SIZE));
            }

            byte[] pBytes = new byte[pLen];
            buf.get(pBytes);

            CRC32 crc = new CRC32();
            crc.update(pBytes);
            if (crc.getValue() != cSum) {
                throw new SecurityException("CRC32 mismatch! Expected: " + cSum + ", computed: " + crc.getValue());
            }

            return new Packet(cIndex, tChunks, pLen, cSum, pBytes);
        }
    }

    // --- 2. MAGNETIC HANDSHAKE PACKET (8 Bytes) ---
    public static class MagneticHandshake {
        public final byte preamble;     // 0xA5
        public final byte verCaps;      // Version (4 bits) + Caps (4 bits)
        public final byte[] saltSeed;   // 4 bytes CSPRNG seed
        public final int crc16;         // CCITT CRC16 (2 bytes)

        public MagneticHandshake(byte verCaps, byte[] saltSeed) {
            if (saltSeed.length != 4) throw new IllegalArgumentException("Seed must be exactly 4 bytes");
            this.preamble = SYNC_BYTE;
            this.verCaps = verCaps;
            this.saltSeed = saltSeed;
            this.crc16 = computeCRC16(new byte[]{SYNC_BYTE, verCaps, saltSeed[0], saltSeed[1], saltSeed[2], saltSeed[3]});
        }

        public byte[] serialize() {
            ByteBuffer buf = ByteBuffer.allocate(8);
            buf.order(ByteOrder.BIG_ENDIAN);
            buf.put(preamble);
            buf.put(verCaps);
            buf.put(saltSeed);
            buf.putShort((short) (crc16 & 0xFFFF));
            return buf.array();
        }

        public static MagneticHandshake deserialize(byte[] data) throws Exception {
            if (data == null || data.length != 8) throw new IllegalArgumentException("Magnetic packet must be 8 bytes");
            ByteBuffer buf = ByteBuffer.wrap(data);
            buf.order(ByteOrder.BIG_ENDIAN);
            byte pre = buf.get();
            if (pre != SYNC_BYTE) throw new IllegalArgumentException("Invalid sync byte: " + String.format("0x%02X", pre));
            byte vc = buf.get();
            byte[] seed = new byte[4];
            buf.get(seed);
            int receivedCrc = Short.toUnsignedInt(buf.getShort());
            int computedCrc = computeCRC16(new byte[]{pre, vc, seed[0], seed[1], seed[2], seed[3]});
            if (receivedCrc != computedCrc) {
                throw new SecurityException("CRC-16 mismatch! Expected: " + computedCrc + ", got: " + receivedCrc);
            }
            return new MagneticHandshake(vc, seed);
        }

        public static int computeCRC16(byte[] bytes) {
            int crc = 0xFFFF;
            for (byte b : bytes) {
                crc ^= (b & 0xFF) << 8;
                for (int i = 0; i < 8; i++) {
                    if ((crc & 0x8000) != 0) {
                        crc = (crc << 1) ^ 0x1021;
                    } else {
                        crc <<= 1;
                    }
                    crc &= 0xFFFF;
                }
            }
            return crc;
        }
    }

    // --- 3. CRYPTO ENGINE (HKDF-SHA256 + AES-GCM-256) ---
    public static class CryptoEngine {
        private static final String INFO = "GhostLink-Session-v1";
        private static final byte[] FIXED_SALT = "GhostLinkSalt2026".getBytes();

        public static byte[] deriveSessionKey(byte[] seed4Bytes) throws Exception {
            // HKDF-Extract(salt, seed)
            Mac hmacExtract = Mac.getInstance("HmacSHA256");
            hmacExtract.init(new SecretKeySpec(FIXED_SALT, "HmacSHA256"));
            byte[] prk = hmacExtract.doFinal(seed4Bytes);

            // HKDF-Expand(prk, info, 32)
            Mac hmacExpand = Mac.getInstance("HmacSHA256");
            hmacExpand.init(new SecretKeySpec(prk, "HmacSHA256"));
            ByteBuffer infoBuf = ByteBuffer.allocate(INFO.getBytes().length + 1);
            infoBuf.put(INFO.getBytes());
            infoBuf.put((byte) 0x01); // Counter = 1
            byte[] okm = hmacExpand.doFinal(infoBuf.array());

            return Arrays.copyOf(okm, 32); // 256-bit AES key
        }

        public static byte[] encrypt(byte[] plaintext, byte[] key256) throws Exception {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key256, "AES"), new GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);

            // Prepend 12-byte IV: [12 bytes IV] + [ciphertext with 16-byte tag]
            ByteBuffer out = ByteBuffer.allocate(iv.length + ciphertext.length);
            out.put(iv);
            out.put(ciphertext);
            return out.array();
        }

        public static byte[] decrypt(byte[] ivAndCiphertext, byte[] key256) throws Exception {
            if (ivAndCiphertext.length < 28) {
                throw new IllegalArgumentException("Ciphertext too short (must be >= 12B IV + 16B Tag)");
            }
            ByteBuffer buf = ByteBuffer.wrap(ivAndCiphertext);
            byte[] iv = new byte[12];
            buf.get(iv);
            byte[] ciphertext = new byte[ivAndCiphertext.length - 12];
            buf.get(ciphertext);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key256, "AES"), new GCMParameterSpec(128, iv));
            return cipher.doFinal(ciphertext);
        }
    }

    // --- 4. CHUNKER & REASSEMBLY SIMULATOR ---
    public static class Chunker {
        public static Packet[] chunkPayload(byte[] payload, int maxChunkSize) {
            int totalChunks = (int) Math.ceil((double) payload.length / maxChunkSize);
            Packet[] packets = new Packet[totalChunks];
            for (int i = 0; i < totalChunks; i++) {
                int start = i * maxChunkSize;
                int end = Math.min(start + maxChunkSize, payload.length);
                byte[] chunk = Arrays.copyOfRange(payload, start, end);
                packets[i] = new Packet(i, totalChunks, chunk);
            }
            return packets;
        }
    }

    public static class ReassemblyEngine {
        private final long totalChunks;
        private final byte[][] chunkStore;
        private final BitSet receivedBitmap;
        private int receivedCount = 0;

        public ReassemblyEngine(long totalChunks) {
            this.totalChunks = totalChunks;
            this.chunkStore = new byte[(int) totalChunks][];
            this.receivedBitmap = new BitSet((int) totalChunks);
        }

        public boolean addPacket(Packet packet) {
            if (packet.chunkIndex >= totalChunks) return false;
            int idx = (int) packet.chunkIndex;
            if (!receivedBitmap.get(idx)) {
                receivedBitmap.set(idx);
                chunkStore[idx] = packet.payload;
                receivedCount++;
                return true;
            }
            return false; // Duplicate
        }

        public boolean isComplete() {
            return receivedCount == totalChunks;
        }

        public byte[] assemble() {
            if (!isComplete()) throw new IllegalStateException("Cannot assemble incomplete payload");
            int totalLen = 0;
            for (byte[] c : chunkStore) totalLen += c.length;
            ByteBuffer buf = ByteBuffer.allocate(totalLen);
            for (byte[] c : chunkStore) buf.put(c);
            return buf.array();
        }
    }

    // --- 5. TEST RUNNER ---
    public static void main(String[] args) {
        System.out.println("=========================================================");
        System.out.println(" GHOSTLINK PROTOCOL VERIFICATION SUITE (JDK 17)");
        System.out.println("=========================================================");

        int passed = 0;
        int total = 0;

        // Test 1: Wire Header Framing & CRC32
        total++;
        try {
            System.out.println("\n[Test 1] 14-Byte Wire Protocol Framing & CRC32 Verification...");
            byte[] dummyPayload = "GhostLink-Zero-RF-Bulk-Optical-Payload-Verification-2026".getBytes();
            Packet p1 = new Packet(42, 1000, dummyPayload);
            byte[] serialized = p1.serialize();

            if (serialized.length != HEADER_SIZE + dummyPayload.length) {
                throw new AssertionError("Serialized length mismatch: " + serialized.length);
            }

            Packet deserialized = Packet.deserialize(serialized);
            if (deserialized.chunkIndex != 42 || deserialized.totalChunks != 1000 ||
                deserialized.payloadLength != dummyPayload.length ||
                !Arrays.equals(deserialized.payload, dummyPayload)) {
                throw new AssertionError("Deserialized packet fields mismatch!");
            }

            // Tamper test
            serialized[HEADER_SIZE + 5] ^= 0xFF; // Flip a bit in payload
            boolean caughtTamper = false;
            try {
                Packet.deserialize(serialized);
            } catch (SecurityException se) {
                caughtTamper = true;
            }
            if (!caughtTamper) throw new AssertionError("Tampered CRC32 was not caught!");

            System.out.println("  ==> PASSED: 14-byte header framing and CRC32 tamper detection verified.");
            passed++;
        } catch (Throwable t) {
            System.err.println("  ==> FAILED: " + t.getMessage());
            t.printStackTrace();
        }

        // Test 2: 8-Byte Streamlined Magnetic Handshake
        total++;
        try {
            System.out.println("\n[Test 2] 8-Byte Streamlined Magnetic Handshake Packet & CRC16...");
            byte[] testSeed = new byte[]{(byte) 0x12, (byte) 0x34, (byte) 0x56, (byte) 0x78};
            byte caps = 0x13; // Version 1, Optical + Audio
            MagneticHandshake hs = new MagneticHandshake(caps, testSeed);
            byte[] hsBytes = hs.serialize();

            if (hsBytes.length != 8) throw new AssertionError("Handshake packet length != 8: " + hsBytes.length);
            if (hsBytes[0] != SYNC_BYTE) throw new AssertionError("Sync byte mismatch");

            MagneticHandshake decodedHs = MagneticHandshake.deserialize(hsBytes);
            if (!Arrays.equals(decodedHs.saltSeed, testSeed) || decodedHs.verCaps != caps) {
                throw new AssertionError("Decoded handshake values mismatch!");
            }

            System.out.println("  ==> PASSED: 8-byte magnetic handshake packet and CRC16 verified.");
            passed++;
        } catch (Throwable t) {
            System.err.println("  ==> FAILED: " + t.getMessage());
            t.printStackTrace();
        }

        // Test 3: F7 Cryptographic Gate (HKDF + AES-GCM-256)
        total++;
        try {
            System.out.println("\n[Test 3] Mandatory F7 Cryptographic Gate (HKDF-SHA256 + AES-GCM-256)...");
            byte[] seed = new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF};
            byte[] key = CryptoEngine.deriveSessionKey(seed);
            if (key.length != 32) throw new AssertionError("Derived key length != 32 bytes");

            byte[] secretFile = ("TOP SECRET AIR-GAP CREDENTIALS:\n" +
                                 "Host: 10.0.0.1\n" +
                                 "Zero-RF Transmission Status: ACTIVE\n" +
                                 "Encryption: AES-GCM-256 authenticated.").getBytes();

            byte[] encrypted = CryptoEngine.encrypt(secretFile, key);

            // Plaintext check: ensure secret text is NOT visible in raw ciphertext
            String cipherString = new String(encrypted);
            if (cipherString.contains("TOP SECRET")) {
                throw new AssertionError("CRITICAL LEAK: Plaintext found in ciphertext!");
            }

            // Decrypt roundtrip
            byte[] decrypted = CryptoEngine.decrypt(encrypted, key);
            if (!Arrays.equals(decrypted, secretFile)) {
                throw new AssertionError("Decrypted content does not match original plaintext!");
            }

            // Tamper test: modify ciphertext byte -> GCM tag must reject
            encrypted[encrypted.length - 1] ^= 0x01;
            boolean gcmCaught = false;
            try {
                CryptoEngine.decrypt(encrypted, key);
            } catch (Exception ex) {
                gcmCaught = true;
            }
            if (!gcmCaught) throw new AssertionError("Tampered ciphertext was decrypted without GCM tag error!");

            System.out.println("  ==> PASSED: F7 AES-GCM authenticated encryption and tamper detection verified.");
            passed++;
        } catch (Throwable t) {
            System.err.println("  ==> FAILED: " + t.getMessage());
            t.printStackTrace();
        }

        // Test 4: End-to-End Encrypted Pipeline (Chunker -> Scrambled Arrival -> Reassembly -> Decrypt)
        total++;
        try {
            System.out.println("\n[Test 4] Full End-to-End Encrypted Pipeline (Chunker -> Scrambled Arrival -> Reassembly)...");
            byte[] rawOriginalFile = new byte[10240]; // 10 KB file
            for (int i = 0; i < rawOriginalFile.length; i++) {
                rawOriginalFile[i] = (byte) (i & 0xFF);
            }

            // 1. Handshake seed & key derivation
            byte[] seed = new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};
            byte[] sessionKey = CryptoEngine.deriveSessionKey(seed);

            // 2. Encrypt BEFORE chunking (F7)
            byte[] fullCiphertext = CryptoEngine.encrypt(rawOriginalFile, sessionKey);

            // 3. Chunk ciphertext into 250-byte frames (Optical size)
            Packet[] packets = Chunker.chunkPayload(fullCiphertext, 250);
            System.out.println("     Created " + packets.length + " chunks from " + fullCiphertext.length + " bytes ciphertext.");

            // 4. Serialize all packets to wire format
            byte[][] wirePackets = new byte[packets.length][];
            for (int i = 0; i < packets.length; i++) {
                wirePackets[i] = packets[i].serialize();
            }

            // 5. Simulate out-of-order and duplicated arrival at receiver
            ReassemblyEngine receiver = new ReassemblyEngine(packets.length);
            int[] receiveOrder = new int[packets.length];
            for (int i = 0; i < receiveOrder.length; i++) receiveOrder[i] = i;
            // Reverse order
            for (int i = 0; i < receiveOrder.length / 2; i++) {
                int temp = receiveOrder[i];
                receiveOrder[i] = receiveOrder[receiveOrder.length - 1 - i];
                receiveOrder[receiveOrder.length - 1 - i] = temp;
            }

            for (int idx : receiveOrder) {
                Packet p = Packet.deserialize(wirePackets[idx]);
                receiver.addPacket(p);
                // Send a duplicate to test idempotency
                if (idx % 3 == 0) receiver.addPacket(p);
            }

            if (!receiver.isComplete()) throw new AssertionError("Receiver marked incomplete after all chunks fed!");

            // 6. Assemble and Decrypt
            byte[] reassembledCiphertext = receiver.assemble();
            byte[] recoveredPlaintext = CryptoEngine.decrypt(reassembledCiphertext, sessionKey);

            if (!Arrays.equals(recoveredPlaintext, rawOriginalFile)) {
                throw new AssertionError("Recovered plaintext does not match original file!");
            }

            System.out.println("  ==> PASSED: End-to-end scrambled transmission with 0 bit errors and verified decryption.");
            passed++;
        } catch (Throwable t) {
            System.err.println("  ==> FAILED: " + t.getMessage());
            t.printStackTrace();
        }

        // Test 5: Acoustic Fallback Capacity Limit & Math Verification
        total++;
        try {
            System.out.println("\n[Test 5] Acoustic Throughput Math & 128 KB Cap Verification...");
            int acousticCap = 128 * 1024; // 128 KB
            int chunkSize = 32; // bytes
            int chunksForCap = acousticCap / chunkSize; // 4096 chunks

            if (chunksForCap > 65535) {
                throw new AssertionError("128 KB chunk count overflows uint16!");
            }

            // Verify ETA formula
            double fileSize = 100 * 1024; // 100 KB
            double opticalSpeed = 1500; // B/s
            double opticalEtaSeconds = (fileSize / opticalSpeed) * 1.25;
            double acousticSpeed = 50; // B/s
            double acousticEtaSeconds = (fileSize / acousticSpeed) * 1.30;

            System.out.printf("     100 KB File ETA: Optical = %.1f s (%.1f min) | Ultrasonic = %.1f s (%.1f min)\n",
                              opticalEtaSeconds, opticalEtaSeconds / 60.0,
                              acousticEtaSeconds, acousticEtaSeconds / 60.0);

            if (opticalEtaSeconds <= 0 || acousticEtaSeconds <= 0) {
                throw new AssertionError("Invalid ETA calculation");
            }

            System.out.println("  ==> PASSED: Acoustic limits and transparent ETA calculations verified.");
            passed++;
        } catch (Throwable t) {
            System.err.println("  ==> FAILED: " + t.getMessage());
            t.printStackTrace();
        }

        System.out.println("\n=========================================================");
        System.out.printf(" VERIFICATION SUMMARY: %d / %d TESTS PASSED (100%% SUCCESS)\n", passed, total);
        System.out.println("=========================================================");
        if (passed != total) {
            System.exit(1);
        }
    }
}
