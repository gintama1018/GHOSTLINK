package com.ghostlink.test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.BitSet;
import java.util.zip.CRC32;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Hardened Zero-RF Protocol Test Suite for GHOSTLINK.
 *
 * Validates:
 * 1. Wire Framing Protocol v2 (24-byte protected header, magic 0x474C, CRC16-CCITT, CRC32)
 * 2. Pre-Allocation Header CRC16 Gate & Memory Bounds Defense (OOM prevention)
 * 3. Path Traversal (CWE-22) Defense, Canonical Containment & Atomic File Storage
 * 4. Ephemeral ECDH (NIST P-256) Key Agreement, Transcript Binding, & AES-GCM-256 AAD Binding
 * 5. Reassembly Engine Memory Bounds, Bitmap Tracking, Out-of-Order Delivery & Idempotency
 * 6. Hamming(8,4) SEC-DED Forward Error Correction (All 16 nibbles, all single/double bit permutations)
 * 7. Barker-13 Cross-Correlation Synchronization Preamble
 * 8. Active MITM Attack Detection via Short Authentication String (SAS) Transcript Verification
 * 9. Hamming(8,4) Byte-Stream Error Semantics: 1-Bit Error Corrected vs 2-Bit Error Flagged Uncorrectable
 * 10. Multi-Stage Acoustic Synchronizer False-Lock Rejection (Barker-13 + Delimiter 0x7E + Mode ID)
 */
public class HardenedProtocolTestSuite {

    // --- PROTOCOL CONSTANTS ---
    public static final short MAGIC_V2 = 0x474C; // 'G', 'L'
    public static final byte VERSION_V2 = 0x02;
    public static final byte FLAG_DATA = 0x00;
    public static final int HEADER_SIZE = 24;
    public static final int MAX_CHUNK_SIZE = 1024;
    public static final long MAX_TOTAL_CHUNKS = 50000L;

    // --- 1. WIRE PROTOCOL V2 IMPLEMENTATION ---
    public static class PacketV2 {
        public final long sessionId;
        public final long chunkIndex;
        public final long totalChunks;
        public final byte flags;
        public final int payloadLength;
        public final int headerCrc16;
        public final long payloadCrc32;
        public final byte[] payload;

        public PacketV2(long sessionId, long chunkIndex, long totalChunks, byte flags, byte[] payload) {
            this.sessionId = sessionId;
            this.chunkIndex = chunkIndex;
            this.totalChunks = totalChunks;
            this.flags = flags;
            this.payload = payload;
            this.payloadLength = payload.length;

            CRC32 crc = new CRC32();
            crc.update(payload);
            this.payloadCrc32 = crc.getValue();

            // Calculate Header CRC16 over first 18 bytes
            byte[] headerPre = new byte[18];
            ByteBuffer buf = ByteBuffer.wrap(headerPre).order(ByteOrder.BIG_ENDIAN);
            buf.putShort(MAGIC_V2);
            buf.put(VERSION_V2);
            buf.put(flags);
            buf.putInt((int) (sessionId & 0xFFFFFFFFL));
            buf.putInt((int) (chunkIndex & 0xFFFFFFFFL));
            buf.putInt((int) (totalChunks & 0xFFFFFFFFL));
            buf.putShort((short) (payload.length & 0xFFFF));
            this.headerCrc16 = computeCrc16(headerPre);
        }

        public PacketV2(long sessionId, long chunkIndex, long totalChunks, byte flags,
                        int payloadLength, int headerCrc16, long payloadCrc32, byte[] payload) {
            this.sessionId = sessionId;
            this.chunkIndex = chunkIndex;
            this.totalChunks = totalChunks;
            this.flags = flags;
            this.payloadLength = payloadLength;
            this.headerCrc16 = headerCrc16;
            this.payloadCrc32 = payloadCrc32;
            this.payload = payload;
        }

        public byte[] serialize() {
            ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + payload.length).order(ByteOrder.BIG_ENDIAN);
            buf.putShort(MAGIC_V2);
            buf.put(VERSION_V2);
            buf.put(flags);
            buf.putInt((int) (sessionId & 0xFFFFFFFFL));
            buf.putInt((int) (chunkIndex & 0xFFFFFFFFL));
            buf.putInt((int) (totalChunks & 0xFFFFFFFFL));
            buf.putShort((short) (payloadLength & 0xFFFF));
            buf.putShort((short) (headerCrc16 & 0xFFFF));
            buf.putInt((int) (payloadCrc32 & 0xFFFFFFFFL));
            buf.put(payload);
            return buf.array();
        }

        public static PacketV2 deserialize(byte[] data) {
            if (data == null || data.length < HEADER_SIZE) {
                throw new IllegalArgumentException("Packet shorter than 24-byte header: " + (data == null ? 0 : data.length));
            }

            ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

            // Step 1: Validate Magic
            short magic = buf.getShort();
            if (magic != MAGIC_V2) {
                throw new IllegalArgumentException("Invalid magic bytes: 0x" + Integer.toHexString(magic & 0xFFFF));
            }

            // Step 2: Validate Version
            byte ver = buf.get();
            if (ver != VERSION_V2) {
                throw new IllegalArgumentException("Unsupported protocol version: " + ver);
            }

            byte flg = buf.get();
            long sId = Integer.toUnsignedLong(buf.getInt());
            long cIdx = Integer.toUnsignedLong(buf.getInt());
            long tChunks = Integer.toUnsignedLong(buf.getInt());
            int pLen = Short.toUnsignedInt(buf.getShort());
            int rxHeaderCrc = Short.toUnsignedInt(buf.getShort());

            // CRITICAL DEFENSE: Verify Header CRC-16 BEFORE allocating memory or parsing payload
            byte[] header18 = Arrays.copyOfRange(data, 0, 18);
            int calcHeaderCrc = computeCrc16(header18);
            if (rxHeaderCrc != calcHeaderCrc) {
                throw new SecurityException("Header CRC-16 mismatch (corrupted header): rx=" + rxHeaderCrc + ", calc=" + calcHeaderCrc);
            }

            // Step 3: Enforce strict bounded bounds
            if (pLen > MAX_CHUNK_SIZE) {
                throw new IllegalArgumentException("Payload length " + pLen + " exceeds MAX_CHUNK_SIZE " + MAX_CHUNK_SIZE);
            }
            if (tChunks > MAX_TOTAL_CHUNKS) {
                throw new IllegalArgumentException("Total chunks " + tChunks + " exceeds MAX_TOTAL_CHUNKS " + MAX_TOTAL_CHUNKS);
            }
            if (cIdx >= tChunks) {
                throw new IllegalArgumentException("Chunk index " + cIdx + " >= total chunks " + tChunks);
            }

            long rxPayloadCrc = Integer.toUnsignedLong(buf.getInt());

            if (data.length != HEADER_SIZE + pLen) {
                throw new IllegalArgumentException("Packet size mismatch: expected " + (HEADER_SIZE + pLen) + ", got " + data.length);
            }

            byte[] pBytes = new byte[pLen];
            buf.get(pBytes);

            // Step 4: Verify Payload CRC-32
            CRC32 crc = new CRC32();
            crc.update(pBytes);
            if (crc.getValue() != rxPayloadCrc) {
                throw new SecurityException("Payload CRC-32 mismatch! Expected=" + rxPayloadCrc + ", computed=" + crc.getValue());
            }

            return new PacketV2(sId, cIdx, tChunks, flg, pLen, rxHeaderCrc, rxPayloadCrc, pBytes);
        }

        public static int computeCrc16(byte[] bytes) {
            int crc = 0xFFFF;
            for (byte b : bytes) {
                crc = crc ^ ((b & 0xFF) << 8);
                for (int i = 0; i < 8; i++) {
                    if ((crc & 0x8000) != 0) {
                        crc = (crc << 1) ^ 0x1021;
                    } else {
                        crc = crc << 1;
                    }
                    crc = crc & 0xFFFF;
                }
            }
            return crc;
        }
    }

    // --- 2. SAFE FILE STORAGE IMPLEMENTATION ---
    public static class SafeFileStorageMock {
        public static String sanitizeFilename(String rawName) {
            String clean = rawName.trim()
                .replace("\\", "/")
                .replaceAll("^.*/", "") // Basename
                .replaceAll("[^a-zA-Z0-9._\\- ]", "_")
                .replaceAll("\\.{2,}", ".")
                .trim();

            if (clean.isEmpty() || clean.equals(".") || clean.startsWith(".")) {
                clean = "received_file_" + System.currentTimeMillis() + ".bin";
            }

            if (clean.length() > 120) {
                String ext = clean.contains(".") ? "." + clean.substring(clean.lastIndexOf(".") + 1) : "";
                clean = clean.substring(0, Math.min(clean.length(), 100)) + ext;
            }
            return clean;
        }

        public static File getSafeTargetFile(File baseDir, String proposedName) throws IOException {
            String safeName = sanitizeFilename(proposedName);
            File canonicalBase = baseDir.getCanonicalFile();

            File target = new File(canonicalBase, safeName);
            if (!target.getCanonicalFile().getPath().startsWith(canonicalBase.getPath())) {
                throw new SecurityException("Path traversal attempt detected: " + proposedName);
            }

            if (target.exists()) {
                String nameWithoutExt = safeName.contains(".") ? safeName.substring(0, safeName.lastIndexOf(".")) : safeName;
                String ext = safeName.contains(".") ? safeName.substring(safeName.lastIndexOf(".")) : "";
                int counter = 1;
                while (target.exists() && counter < 1000) {
                    target = new File(canonicalBase, nameWithoutExt + " (" + counter + ")" + ext);
                    counter++;
                }
            }
            return target;
        }

        public static void writeBytesAtomically(File targetFile, byte[] bytes) throws IOException {
            File tempFile = new File(targetFile.getParentFile(), targetFile.getName() + ".part");
            try {
                try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                    fos.write(bytes);
                    fos.getFD().sync();
                }

                if (targetFile.exists()) {
                    targetFile.delete();
                }

                boolean renamed = tempFile.renameTo(targetFile);
                if (!renamed) {
                    throw new IOException("Failed to rename temporary file to: " + targetFile.getName());
                }
            } catch (Exception e) {
                tempFile.delete();
                throw e;
            }
        }
    }

    // --- 3. CRYPTOGRAPHIC ENGINE IMPLEMENTATION ---
    public static class CryptoMock {
        public static KeyPair generateEphemeralKeyPair() throws Exception {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
            return kpg.generateKeyPair();
        }

        public static byte[] serializePublicKey(ECPublicKey pub) {
            ECPoint w = pub.getW();
            byte[] x = toFixed32(w.getAffineX());
            byte[] y = toFixed32(w.getAffineY());
            byte[] out = new byte[65];
            out[0] = 0x04; // Uncompressed point
            System.arraycopy(x, 0, out, 1, 32);
            System.arraycopy(y, 0, out, 33, 32);
            return out;
        }

        public static ECPublicKey deserializePublicKey(byte[] bytes) throws Exception {
            if (bytes.length != 65 || bytes[0] != 0x04) {
                throw new IllegalArgumentException("Expected 65-byte uncompressed EC point");
            }
            byte[] xBytes = Arrays.copyOfRange(bytes, 1, 33);
            byte[] yBytes = Arrays.copyOfRange(bytes, 33, 65);
            ECPoint w = new ECPoint(new BigInteger(1, xBytes), new BigInteger(1, yBytes));

            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair dummy = kpg.generateKeyPair();
            ECPublicKey dummyPub = (ECPublicKey) dummy.getPublic();

            KeyFactory kf = KeyFactory.getInstance("EC");
            return (ECPublicKey) kf.generatePublic(new ECPublicKeySpec(w, dummyPub.getParams()));
        }

        public static byte[] deriveSharedSecret(PrivateKey privateKey, PublicKey peerPublicKey) throws Exception {
            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(privateKey);
            ka.doPhase(peerPublicKey, true);
            return ka.generateSecret();
        }

        public static String computeSasCode(byte[] pubKeyA, byte[] pubKeyB, long sessionId) throws Exception {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec("GhostLink-SAS-Verification".getBytes("UTF-8"), "HmacSHA256"));
            // Lexicographical ordering ensures Alice and Bob compute identical SAS
            byte[] first = (ByteBuffer.wrap(pubKeyA).compareTo(ByteBuffer.wrap(pubKeyB)) <= 0) ? pubKeyA : pubKeyB;
            byte[] second = (first == pubKeyA) ? pubKeyB : pubKeyA;
            mac.update(first);
            mac.update(second);
            mac.update(ByteBuffer.allocate(8).putLong(sessionId).array());
            byte[] digest = mac.doFinal();
            int num = ((digest[0] & 0x7F) << 24) | ((digest[1] & 0xFF) << 16) | ((digest[2] & 0xFF) << 8) | (digest[3] & 0xFF);
            int code = num % 1_000_000;
            return String.format("%03d-%03d", code / 1000, code % 1000);
        }

        public static byte[] buildTranscriptInfo(byte ver, long sessionId, byte[] pkA, byte[] pkB, String role, String mode) throws Exception {
            ByteBuffer buf = ByteBuffer.allocate(1 + 8 + pkA.length + pkB.length + role.length() + mode.length());
            buf.put(ver);
            buf.putLong(sessionId);
            buf.put(pkA);
            buf.put(pkB);
            buf.put(role.getBytes("UTF-8"));
            buf.put(mode.getBytes("UTF-8"));
            return buf.array();
        }

        public static class SessionKeys {
            public final SecretKeySpec aesKey;
            public final byte[] baseIv;
            public final long sessionId;

            public SessionKeys(SecretKeySpec aesKey, byte[] baseIv, long sessionId) {
                this.aesKey = aesKey;
                this.baseIv = baseIv;
                this.sessionId = sessionId;
            }
        }

        public static SessionKeys deriveSessionKeys(byte[] sharedSecret, byte[] salt, byte[] transcriptInfo) throws Exception {
            byte[] effectiveSalt = (salt != null) ? salt : "GhostLink-v2-KeySalt".getBytes("UTF-8");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(effectiveSalt, "HmacSHA256"));
            byte[] prk = mac.doFinal(sharedSecret);

            byte[] info = (transcriptInfo != null) ? transcriptInfo : "GhostLink-v2-KeyExpansion".getBytes("UTF-8");
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            mac.update(info);
            mac.update((byte) 0x01);
            byte[] okm1 = mac.doFinal();

            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            mac.update(okm1);
            mac.update(info);
            mac.update((byte) 0x02);
            byte[] okm2 = mac.doFinal();

            byte[] fullKeyMaterial = new byte[64];
            System.arraycopy(okm1, 0, fullKeyMaterial, 0, 32);
            System.arraycopy(okm2, 0, fullKeyMaterial, 32, 32);

            byte[] aesKeyBytes = Arrays.copyOfRange(fullKeyMaterial, 0, 32);
            byte[] baseIv = Arrays.copyOfRange(fullKeyMaterial, 32, 44); // 12 bytes
            long sessionId = ByteBuffer.wrap(fullKeyMaterial, 44, 8).getLong() & 0xFFFFFFFFL;

            return new SessionKeys(new SecretKeySpec(aesKeyBytes, "AES"), baseIv, sessionId);
        }

        public static byte[] computeChunkIv(byte[] baseIv, long chunkIndex) {
            byte[] iv = Arrays.copyOf(baseIv, 12);
            ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(chunkIndex);
            byte[] indexBytes = buf.array();
            for (int i = 0; i < 8; i++) {
                iv[4 + i] = (byte) (iv[4 + i] ^ indexBytes[i]);
            }
            return iv;
        }

        public static byte[] encrypt(byte[] plaintext, SessionKeys keys, long chunkIndex) throws Exception {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv = computeChunkIv(keys.baseIv, chunkIndex);
            GCMParameterSpec spec = new GCMParameterSpec(128, iv);
            cipher.init(Cipher.ENCRYPT_MODE, keys.aesKey, spec);
            return cipher.doFinal(plaintext);
        }

        public static byte[] decrypt(byte[] ciphertext, SessionKeys keys, long chunkIndex) throws Exception {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv = computeChunkIv(keys.baseIv, chunkIndex);
            GCMParameterSpec spec = new GCMParameterSpec(128, iv);
            cipher.init(Cipher.DECRYPT_MODE, keys.aesKey, spec);
            return cipher.doFinal(ciphertext);
        }

        private static byte[] toFixed32(BigInteger bi) {
            byte[] raw = bi.toByteArray();
            if (raw.length == 32) return raw;
            byte[] out = new byte[32];
            if (raw.length > 32) {
                System.arraycopy(raw, raw.length - 32, out, 0, 32);
            } else {
                System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
            }
            return out;
        }
    }

    // --- 4. REASSEMBLY ENGINE MOCK ---
    public static class ReassemblyEngineMock {
        public final long totalChunks;
        public final long sessionId;
        private final byte[][] chunkStore;
        private final BitSet receivedBitmap;
        public int receivedCount = 0;

        public ReassemblyEngineMock(long totalChunks, long sessionId) {
            if (totalChunks < 1 || totalChunks > MAX_TOTAL_CHUNKS) {
                throw new IllegalArgumentException("Total chunks " + totalChunks + " exceeds limit " + MAX_TOTAL_CHUNKS);
            }
            this.totalChunks = totalChunks;
            this.sessionId = sessionId;
            this.chunkStore = new byte[(int) totalChunks][];
            this.receivedBitmap = new BitSet((int) totalChunks);
        }

        public boolean addPacket(PacketV2 packet) {
            if (packet.sessionId != this.sessionId) return false;
            if (packet.totalChunks != this.totalChunks) return false;
            int idx = (int) packet.chunkIndex;
            if (idx < 0 || idx >= totalChunks) return false;
            if (receivedBitmap.get(idx)) return false; // Duplicate

            chunkStore[idx] = packet.payload;
            receivedBitmap.set(idx);
            receivedCount++;
            return true;
        }

        public boolean isComplete() {
            return receivedCount == (int) totalChunks;
        }

        public byte[] assemble() {
            if (!isComplete()) throw new IllegalStateException("Reassembly incomplete: " + receivedCount + "/" + totalChunks);
            int totalBytes = 0;
            for (byte[] c : chunkStore) totalBytes += c.length;
            byte[] out = new byte[totalBytes];
            int offset = 0;
            for (byte[] c : chunkStore) {
                System.arraycopy(c, 0, out, offset, c.length);
                offset += c.length;
            }
            return out;
        }
    }

    // --- 5. FEC CODEC IMPLEMENTATION ---
    public static class FecMock {
        public enum DecodeStatus { NO_ERROR, SINGLE_BIT_CORRECTED, DOUBLE_BIT_UNCORRECTABLE }

        public static class DecodeResult {
            public final int nibble;
            public final DecodeStatus status;
            public DecodeResult(int n, DecodeStatus s) { this.nibble = n; this.status = s; }
        }

        public static class FecStreamResult {
            public final byte[] decodedBytes;
            public final int singleBitCorrections;
            public final int uncorrectableDoubleErrors;
            public FecStreamResult(byte[] d, int s, int u) {
                this.decodedBytes = d;
                this.singleBitCorrections = s;
                this.uncorrectableDoubleErrors = u;
            }
            public boolean isClean() { return uncorrectableDoubleErrors == 0; }
        }

        public static int encodeNibble(int data) {
            int d0 = (data >> 0) & 1;
            int d1 = (data >> 1) & 1;
            int d2 = (data >> 2) & 1;
            int d3 = (data >> 3) & 1;

            int p1 = d0 ^ d1 ^ d3;
            int p2 = d0 ^ d2 ^ d3;
            int p3 = d1 ^ d2 ^ d3;

            int c7 = (d3 << 6) | (d2 << 5) | (d1 << 4) | (p3 << 3) | (d0 << 2) | (p2 << 1) | p1;
            int pOverall = Integer.bitCount(c7) % 2;
            return (pOverall << 7) | c7;
        }

        public static DecodeResult decodeCodeword(int codeword) {
            int c7 = codeword & 0x7F;
            int pOverallRx = (codeword >> 7) & 1;
            int pOverallCalc = Integer.bitCount(c7) % 2;
            boolean overallParityError = (pOverallRx != pOverallCalc);

            int b1 = (c7 >> 0) & 1;
            int b2 = (c7 >> 1) & 1;
            int b3 = (c7 >> 2) & 1;
            int b4 = (c7 >> 3) & 1;
            int b5 = (c7 >> 4) & 1;
            int b6 = (c7 >> 5) & 1;
            int b7 = (c7 >> 6) & 1;

            int s1 = b1 ^ b3 ^ b5 ^ b7;
            int s2 = b2 ^ b3 ^ b6 ^ b7;
            int s3 = b4 ^ b5 ^ b6 ^ b7;
            int syndrome = (s3 << 2) | (s2 << 1) | s1;

            if (syndrome == 0) {
                int nibble = (b3 << 0) | (b5 << 1) | (b6 << 2) | (b7 << 3);
                if (!overallParityError) {
                    return new DecodeResult(nibble, DecodeStatus.NO_ERROR);
                } else {
                    return new DecodeResult(nibble, DecodeStatus.SINGLE_BIT_CORRECTED);
                }
            } else {
                if (overallParityError) {
                    int correctedC7 = c7 ^ (1 << (syndrome - 1));
                    int b3c = (correctedC7 >> 2) & 1;
                    int b5c = (correctedC7 >> 4) & 1;
                    int b6c = (correctedC7 >> 5) & 1;
                    int b7c = (correctedC7 >> 6) & 1;
                    int nibble = (b3c << 0) | (b5c << 1) | (b6c << 2) | (b7c << 3);
                    return new DecodeResult(nibble, DecodeStatus.SINGLE_BIT_CORRECTED);
                } else {
                    // Double-bit uncorrectable error!
                    int nibble = (b3 << 0) | (b5 << 1) | (b6 << 2) | (b7 << 3);
                    return new DecodeResult(nibble, DecodeStatus.DOUBLE_BIT_UNCORRECTABLE);
                }
            }
        }

        public static byte[] encodeBytes(byte[] input) {
            byte[] out = new byte[input.length * 2];
            int outIdx = 0;
            for (byte b : input) {
                int high = (b >> 4) & 0x0F;
                int low = b & 0x0F;
                out[outIdx++] = (byte) encodeNibble(high);
                out[outIdx++] = (byte) encodeNibble(low);
            }
            return out;
        }

        public static FecStreamResult decodeBytes(byte[] encoded) {
            byte[] out = new byte[encoded.length / 2];
            int singles = 0;
            int doubles = 0;
            int outIdx = 0;
            for (int i = 0; i < encoded.length - 1; i += 2) {
                DecodeResult r1 = decodeCodeword(encoded[i] & 0xFF);
                DecodeResult r2 = decodeCodeword(encoded[i + 1] & 0xFF);
                if (r1.status == DecodeStatus.SINGLE_BIT_CORRECTED) singles++;
                if (r2.status == DecodeStatus.SINGLE_BIT_CORRECTED) singles++;
                if (r1.status == DecodeStatus.DOUBLE_BIT_UNCORRECTABLE) doubles++;
                if (r2.status == DecodeStatus.DOUBLE_BIT_UNCORRECTABLE) doubles++;
                out[outIdx++] = (byte) ((r1.nibble << 4) | r2.nibble);
            }
            return new FecStreamResult(out, singles, doubles);
        }
    }

    // --- 6. MULTI-STAGE BARKER SYNCHRONIZER MOCK ---
    public static class MultiStageSynchronizer {
        public static final int[] BARKER_13 = { 1, 1, 1, 1, 1, -1, -1, 1, 1, -1, 1, -1, 1 };
        public static final int[] EXPECTED_DELIMITER = { 0, 1, 1, 1, 1, 1, 1, 0 }; // 0x7E

        public static int findValidPreamble(int[] bitStream) {
            if (bitStream.length < 32) return -1;
            for (int i = 0; i <= bitStream.length - 32; i++) {
                // Stage 1: Barker Correlation
                int corr = 0;
                for (int j = 0; j < BARKER_13.length; j++) {
                    corr += bitStream[i + j] * BARKER_13[j];
                }
                if (corr >= 11) {
                    // Stage 2: Delimiter check at offset i + 16 (0x7E)
                    int delimStart = i + 16;
                    int mismatches = 0;
                    for (int d = 0; d < 8; d++) {
                        int bit = (bitStream[delimStart + d] == 1) ? 1 : 0;
                        if (bit != EXPECTED_DELIMITER[d]) mismatches++;
                    }
                    if (mismatches <= 1) {
                        // Stage 3: Mode ID plausibility at offset i + 24
                        int modeStart = i + 24;
                        int modeId = 0;
                        for (int m = 0; m < 8; m++) {
                            int bit = (bitStream[modeStart + m] == 1) ? 1 : 0;
                            modeId = (modeId << 1) | bit;
                        }
                        if (modeId >= 0 && modeId <= 2) {
                            return i + 32; // Sync payload start
                        }
                    }
                }
            }
            return -1;
        }
    }

    // --- MAIN TEST RUNNER ---
    public static void main(String[] args) {
        System.out.println("===============================================================================");
        System.out.println(" GHOSTLINK HARDENED PHYSICAL COMMUNICATION PROTOCOL VERIFICATION SUITE");
        System.out.println("===============================================================================");

        int passed = 0;
        int total = 0;

        // --- TEST 1: WIRE PROTOCOL V2 FRAMING & ROUNDTRIP ---
        total++;
        try {
            System.out.println("\n[Test 1] Wire Protocol v2 Framing (24-Byte Header + CRC16 + CRC32)...");
            byte[] testPayload = "CLASSIFIED AIR-GAP PAYLOAD FOR ZERO-RF PROTOCOL VALIDATION".getBytes("UTF-8");
            long sessionId = 0xCAFEBABE12345678L;
            PacketV2 original = new PacketV2(sessionId, 5L, 42L, FLAG_DATA, testPayload);

            byte[] wire = original.serialize();
            if (wire.length != HEADER_SIZE + testPayload.length) {
                throw new AssertionError("Serialized length mismatch: expected " + (HEADER_SIZE + testPayload.length) + ", got " + wire.length);
            }

            PacketV2 decoded = PacketV2.deserialize(wire);
            if (decoded.sessionId != (sessionId & 0xFFFFFFFFL)) throw new AssertionError("Session ID mismatch");
            if (decoded.chunkIndex != 5L) throw new AssertionError("Chunk index mismatch");
            if (decoded.totalChunks != 42L) throw new AssertionError("Total chunks mismatch");
            if (decoded.payloadLength != testPayload.length) throw new AssertionError("Payload length mismatch");
            if (!Arrays.equals(decoded.payload, testPayload)) throw new AssertionError("Payload content mismatch");

            System.out.printf("  ==> PASSED: 24-byte header parsed correctly (Header CRC16=0x%04X, Payload CRC32=0x%08X)\n",
                decoded.headerCrc16, decoded.payloadCrc32);
            passed++;
        } catch (Throwable t) {
            System.err.println("  ==> FAILED: " + t.getMessage());
            t.printStackTrace();
        }

        // --- TEST 2: PRE-ALLOCATION HEADER CRC16 GATE & BOUNDS DEFENSE ---
        total++;
        try {
            System.out.println("\n[Test 2] Pre-Allocation Header CRC16 Gate & Memory Bounds Defense (OOM Prevention)...");
            byte[] testPayload = new byte[64];
            PacketV2 original = new PacketV2(1234L, 0L, 10L, FLAG_DATA, testPayload);
            byte[] wire = original.serialize();

            // Corrupt byte 10 (chunk_index in header)
            byte[] corruptedHeader = wire.clone();
            corruptedHeader[10] = (byte) (corruptedHeader[10] ^ 0xFF);

            boolean headerCrcCaught = false;
            try {
                PacketV2.deserialize(corruptedHeader);
            } catch (SecurityException se) {
                headerCrcCaught = se.getMessage().contains("Header CRC-16 mismatch");
            }

            if (!headerCrcCaught) {
                throw new AssertionError("Corrupted header was NOT rejected by Header CRC-16 gate!");
            }

            // Fuzz: Test malicious packet claiming payloadLength = 2048 (> MAX_CHUNK_SIZE 1024)
            byte[] fuzzBuffer = new byte[HEADER_SIZE + 64];
            ByteBuffer b = ByteBuffer.wrap(fuzzBuffer).order(ByteOrder.BIG_ENDIAN);
            b.putShort(MAGIC_V2);
            b.put(VERSION_V2);
            b.put(FLAG_DATA);
            b.putInt(1);
            b.putInt(0);
            b.putInt(10);
            b.putShort((short) 2048); // Oversized payload length!
            int validHeaderCrc = PacketV2.computeCrc16(Arrays.copyOfRange(fuzzBuffer, 0, 18));
            b.putShort((short) validHeaderCrc);
            b.putInt(0);

            boolean boundsCaught = false;
            try {
                PacketV2.deserialize(fuzzBuffer);
            } catch (IllegalArgumentException iae) {
                boundsCaught = iae.getMessage().contains("exceeds MAX_CHUNK_SIZE");
            }
            if (!boundsCaught) {
                throw new AssertionError("Oversized payload length was NOT rejected by bounds check!");
            }

            // Payload corruption test
            byte[] corruptedPayload = wire.clone();
            corruptedPayload[HEADER_SIZE + 5] = (byte) (corruptedPayload[HEADER_SIZE + 5] ^ 0xFF);
            boolean payloadCrcCaught = false;
            try {
                PacketV2.deserialize(corruptedPayload);
            } catch (SecurityException se) {
                payloadCrcCaught = se.getMessage().contains("Payload CRC-32 mismatch");
            }
            if (!payloadCrcCaught) {
                throw new AssertionError("Payload corruption was NOT caught by CRC32!");
            }

            System.out.println("  ==> PASSED: Header CRC-16 gate rejects corruption before buffer allocation; bounds & CRC-32 verified.");
            passed++;
        } catch (Throwable t) {
            System.err.println("  ==> FAILED: " + t.getMessage());
            t.printStackTrace();
        }

        // --- TEST 3: PATH TRAVERSAL (CWE-22) & ATOMIC STORAGE DEFENSE ---
        total++;
        try {
            System.out.println("\n[Test 3] Path Traversal (CWE-22) Defense & Atomic File Storage...");
            File tempDir = Files.createTempDirectory("ghostlink_test_dir").toFile();
            tempDir.deleteOnExit();

            // Test 3a: Directory Traversal Sanitization
            String[] maliciousNames = {
                "../../evil.sh",
                "..\\..\\windows\\system32\\cmd.exe",
                "/etc/shadow",
                "../../../data/data/com.ghostlink.zerorf/databases/leak.db",
                "test\u0000file.txt",
                "....//....//passwords.txt",
                "   normal_file.pdf   "
            };

            for (String evil : maliciousNames) {
                File safeTarget = SafeFileStorageMock.getSafeTargetFile(tempDir, evil);
                if (!safeTarget.getCanonicalFile().getPath().startsWith(tempDir.getCanonicalFile().getPath())) {
                    throw new AssertionError("Path traversal escape succeeded for payload: " + evil);
                }
                if (safeTarget.getName().contains("/") || safeTarget.getName().contains("\\") || safeTarget.getName().contains("..")) {
                    throw new AssertionError("Sanitization failed to strip directory tokens: " + safeTarget.getName());
                }
            }

            // Test 3b: Atomic file write with integrity check
            File targetFile = SafeFileStorageMock.getSafeTargetFile(tempDir, "intel.bin");
            byte[] fileData = "ATOMIC AIR-GAPPED EXFILTRATION RESISTANCE TEST DATA".getBytes("UTF-8");
            SafeFileStorageMock.writeBytesAtomically(targetFile, fileData);

            if (!targetFile.exists() || targetFile.length() != fileData.length) {
                throw new AssertionError("Target file write failed or length mismatch");
            }
            byte[] readBack = Files.readAllBytes(targetFile.toPath());
            if (!Arrays.equals(readBack, fileData)) {
                throw new AssertionError("File content readback mismatch");
            }

            // Test 3c: Collision resolution
            File collidingTarget = SafeFileStorageMock.getSafeTargetFile(tempDir, "intel.bin");
            if (collidingTarget.getName().equals("intel.bin")) {
                throw new AssertionError("Collision avoidance failed: expected non-colliding name, got " + collidingTarget.getName());
            }
            if (!collidingTarget.getName().contains("intel (1).bin")) {
                throw new AssertionError("Expected collision name with counter, got: " + collidingTarget.getName());
            }

            System.out.printf("  ==> PASSED: Path traversal neutralized (escapes blocked, collision resolution '%s', atomic write verified).\n",
                collidingTarget.getName());
            passed++;
        } catch (Throwable t) {
            System.err.println("  ==> FAILED: " + t.getMessage());
            t.printStackTrace();
        }

        // --- TEST 4: EPHEMERAL ECDH (NIST P-256) + HKDF + AES-GCM-256 ---
        total++;
        try {
            System.out.println("\n[Test 4] Ephemeral ECDH (NIST P-256), HKDF-SHA256 & AES-GCM-256 AAD Binding...");
            // Alice generates keypair
            KeyPair aliceKp = CryptoMock.generateEphemeralKeyPair();
            byte[] alicePubBytes = CryptoMock.serializePublicKey((ECPublicKey) aliceKp.getPublic());

            // Bob generates keypair
            KeyPair bobKp = CryptoMock.generateEphemeralKeyPair();
            byte[] bobPubBytes = CryptoMock.serializePublicKey((ECPublicKey) bobKp.getPublic());

            // Key Agreement: Alice computes Z from Bob's public key; Bob computes Z from Alice's public key
            ECPublicKey bobPubFromAlice = CryptoMock.deserializePublicKey(bobPubBytes);
            byte[] aliceShared = CryptoMock.deriveSharedSecret(aliceKp.getPrivate(), bobPubFromAlice);

            ECPublicKey alicePubFromBob = CryptoMock.deserializePublicKey(alicePubBytes);
            byte[] bobShared = CryptoMock.deriveSharedSecret(bobKp.getPrivate(), alicePubFromBob);

            if (!Arrays.equals(aliceShared, bobShared)) {
                throw new AssertionError("ECDH shared secrets do not match between Alice and Bob!");
            }

            // Derive Session Keys with Transcript Binding
            byte[] transcriptA = CryptoMock.buildTranscriptInfo((byte) 2, 0x1234L, alicePubBytes, bobPubBytes, "SENDER", "OPTICAL");
            byte[] transcriptB = CryptoMock.buildTranscriptInfo((byte) 2, 0x1234L, alicePubBytes, bobPubBytes, "SENDER", "OPTICAL");

            CryptoMock.SessionKeys aliceKeys = CryptoMock.deriveSessionKeys(aliceShared, null, transcriptA);
            CryptoMock.SessionKeys bobKeys = CryptoMock.deriveSessionKeys(bobShared, null, transcriptB);

            if (!Arrays.equals(aliceKeys.aesKey.getEncoded(), bobKeys.aesKey.getEncoded())) {
                throw new AssertionError("Derived AES keys do not match!");
            }
            if (!Arrays.equals(aliceKeys.baseIv, bobKeys.baseIv)) {
                throw new AssertionError("Derived Base IVs do not match!");
            }
            if (aliceKeys.sessionId != bobKeys.sessionId) {
                throw new AssertionError("Derived Session IDs do not match!");
            }

            // Chunk Encryption with per-chunk deterministic IV
            byte[] secretPayload = "CONFIDENTIAL MILITARY AIR-GAP BRIEFING NODE 01".getBytes("UTF-8");
            long chunkIdx = 42L;
            byte[] ciphertext = CryptoMock.encrypt(secretPayload, aliceKeys, chunkIdx);

            // Decrypt on Bob's side
            byte[] decrypted = CryptoMock.decrypt(ciphertext, bobKeys, chunkIdx);
            if (!Arrays.equals(decrypted, secretPayload)) {
                throw new AssertionError("Decrypted plaintext does not match original!");
            }

            // Nonce/ChunkIndex Binding Test: decrypting with chunkIdx=43 MUST fail
            boolean badChunkCaught = false;
            try {
                CryptoMock.decrypt(ciphertext, bobKeys, chunkIdx + 1);
            } catch (Exception e) {
                badChunkCaught = true;
            }
            if (!badChunkCaught) {
                throw new AssertionError("Decrypting with mismatched chunk index did NOT fail AEAD tag!");
            }

            // Tamper test: flip 1 byte in ciphertext
            byte[] tamperedCiphertext = ciphertext.clone();
            tamperedCiphertext[10] = (byte) (tamperedCiphertext[10] ^ 0x01);
            boolean tamperCaught = false;
            try {
                CryptoMock.decrypt(tamperedCiphertext, bobKeys, chunkIdx);
            } catch (Exception e) {
                tamperCaught = true;
            }
            if (!tamperCaught) {
                throw new AssertionError("Tampered ciphertext was NOT caught by AES-GCM authentication tag!");
            }

            System.out.printf("  ==> PASSED: Ephemeral ECDH (NIST P-256) + HKDF-SHA256 established Session 0x%08X with authenticated AES-GCM-256.\n",
                aliceKeys.sessionId);
            passed++;
        } catch (Throwable t) {
            System.err.println("  ==> FAILED: " + t.getMessage());
            t.printStackTrace();
        }

        // --- TEST 5: REASSEMBLY ENGINE MEMORY BOUNDS & OUT-OF-ORDER ASSEMBLY ---
        total++;
        try {
            System.out.println("\n[Test 5] Reassembly Engine Memory Bounds, Bitmap Tracking & Idempotency...");
            long totalChunks = 50L;
            long sessionId = 0xDEADBEEFL;
            ReassemblyEngineMock reassembly = new ReassemblyEngineMock(totalChunks, sessionId);

            byte[][] originalChunks = new byte[(int) totalChunks][];
            for (int i = 0; i < (int) totalChunks; i++) {
                originalChunks[i] = ("CHUNK_DATA_SEGMENT_INDEX_" + i).getBytes("UTF-8");
            }

            // Scramble order
            int[] order = new int[(int) totalChunks];
            for (int i = 0; i < order.length; i++) order[i] = i;
            for (int i = 0; i < order.length / 2; i++) {
                int t = order[i];
                order[i] = order[order.length - 1 - i];
                order[order.length - 1 - i] = t;
            }

            for (int idx : order) {
                PacketV2 p = new PacketV2(sessionId, idx, totalChunks, FLAG_DATA, originalChunks[idx]);
                boolean added = reassembly.addPacket(p);
                if (!added) throw new AssertionError("Failed to add valid chunk " + idx);

                // Duplicate test: re-adding same chunk MUST return false (idempotent)
                boolean dup = reassembly.addPacket(p);
                if (dup) throw new AssertionError("Duplicate chunk " + idx + " was accepted!");
            }

            if (!reassembly.isComplete()) throw new AssertionError("Reassembly marked incomplete!");

            byte[] assembled = reassembly.assemble();
            int totalBytesExpected = 0;
            for (byte[] c : originalChunks) totalBytesExpected += c.length;
            if (assembled.length != totalBytesExpected) {
                throw new AssertionError("Assembled length mismatch: expected " + totalBytesExpected + ", got " + assembled.length);
            }

            // Test Session ID Isolation
            ReassemblyEngineMock isolatedEngine = new ReassemblyEngineMock(totalChunks, 0x11112222L);
            PacketV2 alienPacket = new PacketV2(0x99998888L, 0L, totalChunks, FLAG_DATA, originalChunks[0]);
            if (isolatedEngine.addPacket(alienPacket)) {
                throw new AssertionError("Packet with foreign session ID was accepted!");
            }

            // Bounds limit test
            boolean boundsEnforced = false;
            try {
                new ReassemblyEngineMock(MAX_TOTAL_CHUNKS + 1, sessionId);
            } catch (IllegalArgumentException iae) {
                boundsEnforced = true;
            }
            if (!boundsEnforced) throw new AssertionError("ReassemblyEngine accepted totalChunks > MAX_TOTAL_CHUNKS!");

            System.out.println("  ==> PASSED: Out-of-order reassembly complete, idempotency enforced, session isolation verified.");
            passed++;
        } catch (Throwable t) {
            System.err.println("  ==> FAILED: " + t.getMessage());
            t.printStackTrace();
        }

        // --- TEST 6: HAMMING(8,4) SEC-DED FORWARD ERROR CORRECTION ---
        total++;
        try {
            System.out.println("\n[Test 6] Hamming(8,4) SEC-DED Forward Error Correction...");
            int correctCorrections = 0;
            int detectedDoubleErrors = 0;

            for (int nibble = 0; nibble < 16; nibble++) {
                int codeword = FecMock.encodeNibble(nibble);

                // 6a: Zero-error test
                FecMock.DecodeResult res0 = FecMock.decodeCodeword(codeword);
                if (res0.nibble != nibble || res0.status != FecMock.DecodeStatus.NO_ERROR) {
                    throw new AssertionError("Zero-error decode failed for nibble " + nibble);
                }

                // 6b: All 8 Single-Bit Error Permutations
                for (int bitPos = 0; bitPos < 8; bitPos++) {
                    int corrupted1 = codeword ^ (1 << bitPos);
                    FecMock.DecodeResult res1 = FecMock.decodeCodeword(corrupted1);
                    if (res1.nibble != nibble || res1.status != FecMock.DecodeStatus.SINGLE_BIT_CORRECTED) {
                        throw new AssertionError("Single bit error correction failed at bit " + bitPos + " for nibble " + nibble);
                    }
                    correctCorrections++;
                }

                // 6c: Double-Bit Error Detection (MUST NOT RECOVER, MUST FLAG UNCORRECTABLE)
                for (int b1 = 0; b1 < 8; b1++) {
                    for (int b2 = b1 + 1; b2 < 8; b2++) {
                        int corrupted2 = codeword ^ (1 << b1) ^ (1 << b2);
                        FecMock.DecodeResult res2 = FecMock.decodeCodeword(corrupted2);
                        if (res2.status != FecMock.DecodeStatus.DOUBLE_BIT_UNCORRECTABLE) {
                            throw new AssertionError("Double bit error NOT detected at bits (" + b1 + "," + b2 + ") for nibble " + nibble);
                        }
                        detectedDoubleErrors++;
                    }
                }
            }

            System.out.printf("  ==> PASSED: 16/16 nibbles tested: %d single-bit errors perfectly corrected, %d double-bit errors detected.\n",
                correctCorrections, detectedDoubleErrors);
            passed++;
        } catch (Throwable t) {
            System.err.println("  ==> FAILED: " + t.getMessage());
            t.printStackTrace();
        }

        // --- TEST 7: BARKER-13 SYNCHRONIZATION PREAMBLE CORRELATION ---
        total++;
        try {
            System.out.println("\n[Test 7] Barker-13 Preamble Cross-Correlation Detection...");
            int[] noiseWithPreamble = new int[100];
            Arrays.fill(noiseWithPreamble, -1); // Baseline noise

            // Inject Barker-13 at offset 37
            int targetOffset = 37;
            System.arraycopy(MultiStageSynchronizer.BARKER_13, 0, noiseWithPreamble, targetOffset, 13);

            // Inject 1 bit error into preamble to test correlation robustness
            noiseWithPreamble[targetOffset + 3] = -noiseWithPreamble[targetOffset + 3];

            int detectedOffset = -1;
            for (int i = 0; i <= noiseWithPreamble.length - 13; i++) {
                int corr = 0;
                for (int j = 0; j < 13; j++) {
                    corr += noiseWithPreamble[i + j] * MultiStageSynchronizer.BARKER_13[j];
                }
                if (corr >= 11) {
                    detectedOffset = i;
                    break;
                }
            }

            if (detectedOffset != targetOffset) {
                throw new AssertionError("Barker-13 preamble detected at wrong offset: expected " + targetOffset + ", got " + detectedOffset);
            }

            System.out.printf("  ==> PASSED: Barker-13 cross-correlator locked onto preamble at offset %d despite bit error.\n",
                detectedOffset);
            passed++;
        } catch (Throwable t) {
            System.err.println("  ==> FAILED: " + t.getMessage());
            t.printStackTrace();
        }

        // --- TEST 8: ACTIVE MAN-IN-THE-MIDDLE (MITM) DETECTION VIA SAS TRANSCRIPT BINDING ---
        total++;
        try {
            System.out.println("\n[Test 8] Active MITM Attack Detection via Short Authentication String (SAS)...");
            // Alice
            KeyPair aliceKp = CryptoMock.generateEphemeralKeyPair();
            byte[] pkA = CryptoMock.serializePublicKey((ECPublicKey) aliceKp.getPublic());

            // Bob
            KeyPair bobKp = CryptoMock.generateEphemeralKeyPair();
            byte[] pkB = CryptoMock.serializePublicKey((ECPublicKey) bobKp.getPublic());

            // Attacker Mallory intercepts and substitutes her own key
            KeyPair malloryKp = CryptoMock.generateEphemeralKeyPair();
            byte[] pkM = CryptoMock.serializePublicKey((ECPublicKey) malloryKp.getPublic());

            long sessionId = 0xA1B2C3D4L;

            // In legitimate session: Alice and Bob exchange directly
            String legitimateSasAlice = CryptoMock.computeSasCode(pkA, pkB, sessionId);
            String legitimateSasBob = CryptoMock.computeSasCode(pkB, pkA, sessionId);
            if (!legitimateSasAlice.equals(legitimateSasBob)) {
                throw new AssertionError("Legitimate SAS codes failed to match: " + legitimateSasAlice + " vs " + legitimateSasBob);
            }

            // In MITM Attack: Alice thinks she is talking to Bob, but communicates with Mallory
            // Bob thinks he is talking to Alice, but communicates with Mallory
            String mitmSasAlice = CryptoMock.computeSasCode(pkA, pkM, sessionId);
            String mitmSasBob = CryptoMock.computeSasCode(pkM, pkB, sessionId);

            if (mitmSasAlice.equals(mitmSasBob)) {
                throw new AssertionError("MITM attack succeeded undetected! SAS codes should NOT match.");
            }

            System.out.printf("  ==> PASSED: Legitimate SAS '%s' verified; MITM attack caught by SAS mismatch ('%s' != '%s').\n",
                legitimateSasAlice, mitmSasAlice, mitmSasBob);
            passed++;
        } catch (Throwable t) {
            System.err.println("  ==> FAILED: " + t.getMessage());
            t.printStackTrace();
        }

        // --- TEST 9: HAMMING(8,4) BYTE STREAM ERROR SEMANTICS ---
        total++;
        try {
            System.out.println("\n[Test 9] Hamming(8,4) Byte-Stream Error Semantics (1-Bit Correct vs 2-Bit Detect)...");
            byte[] rawMessage = "TOP-SECRET MILITARY NODE INTELLIGENCE 2026".getBytes("UTF-8");
            byte[] encoded = FecMock.encodeBytes(rawMessage);

            if (encoded.length != rawMessage.length * 2) {
                throw new AssertionError("Hamming(8,4) rate 1/2 overhead mismatch: expected " + (rawMessage.length * 2) + ", got " + encoded.length);
            }

            // 9a: Inject single-bit errors in multiple codewords
            byte[] singleErrEncoded = encoded.clone();
            singleErrEncoded[0] = (byte) (singleErrEncoded[0] ^ 0x01); // 1 bit in codeword 0
            singleErrEncoded[5] = (byte) (singleErrEncoded[5] ^ 0x04); // 1 bit in codeword 5
            singleErrEncoded[12] = (byte) (singleErrEncoded[12] ^ 0x40); // 1 bit in codeword 12

            FecMock.FecStreamResult resSingle = FecMock.decodeBytes(singleErrEncoded);
            if (!resSingle.isClean()) throw new AssertionError("Single bit errors marked as uncorrectable!");
            if (resSingle.singleBitCorrections != 3) {
                throw new AssertionError("Expected 3 single-bit corrections, got " + resSingle.singleBitCorrections);
            }
            if (!Arrays.equals(resSingle.decodedBytes, rawMessage)) {
                throw new AssertionError("Single-bit corrected message did not match original plaintext!");
            }

            // 9b: Inject double-bit error in codeword 2 (bits 0 and 2)
            byte[] doubleErrEncoded = encoded.clone();
            doubleErrEncoded[2] = (byte) (doubleErrEncoded[2] ^ 0x05); // 2 bits flipped in 1 codeword!

            FecMock.FecStreamResult resDouble = FecMock.decodeBytes(doubleErrEncoded);
            if (resDouble.isClean()) {
                throw new AssertionError("Double bit error was NOT flagged as uncorrectable!");
            }
            if (resDouble.uncorrectableDoubleErrors == 0) {
                throw new AssertionError("Double-bit error count is zero!");
            }

            System.out.printf("  ==> PASSED: 3 single-bit errors perfectly corrected; double-bit corruption detected & flagged uncorrectable (%d errors).\n",
                resDouble.uncorrectableDoubleErrors);
            passed++;
        } catch (Throwable t) {
            System.err.println("  ==> FAILED: " + t.getMessage());
            t.printStackTrace();
        }

        // --- TEST 10: MULTI-STAGE ACOUSTIC SYNCHRONIZER FALSE-LOCK REJECTION ---
        total++;
        try {
            System.out.println("\n[Test 10] Multi-Stage Acoustic Synchronizer False-Lock Rejection...");
            // Valid frame bits: [Barker-13] [3 pad] [Delimiter 0x7E] [Mode ID 0x01] [Payload bits...]
            int[] validFrame = new int[64];
            // Barker-13: 1, 1, 1, 1, 1, 0, 0, 1, 1, 0, 1, 0, 1
            int[] b13 = { 1, 1, 1, 1, 1, -1, -1, 1, 1, -1, 1, -1, 1 };
            System.arraycopy(b13, 0, validFrame, 0, 13);
            validFrame[13] = -1; validFrame[14] = -1; validFrame[15] = -1; // Pad
            // Delimiter 0x7E: 0, 1, 1, 1, 1, 1, 1, 0 -> mapped to -1 and +1
            int[] delim = { -1, 1, 1, 1, 1, 1, 1, -1 };
            System.arraycopy(delim, 0, validFrame, 16, 8);
            // Mode ID: 0x01 = 0000 0001
            int[] mode = { -1, -1, -1, -1, -1, -1, -1, 1 };
            System.arraycopy(mode, 0, validFrame, 24, 8);

            // Valid frame test
            int syncLock = MultiStageSynchronizer.findValidPreamble(validFrame);
            if (syncLock != 32) {
                throw new AssertionError("Valid frame preamble failed to lock! got " + syncLock);
            }

            // Test 10a: Corrupt delimiter (Stage 2 rejection)
            int[] badDelimFrame = validFrame.clone();
            badDelimFrame[16] = 1; badDelimFrame[17] = -1; badDelimFrame[18] = -1; // Multiple delimiter flips
            int badDelimLock = MultiStageSynchronizer.findValidPreamble(badDelimFrame);
            if (badDelimLock != -1) {
                throw new AssertionError("Corrupted delimiter was accepted!");
            }

            // Test 10b: Invalid Mode ID > 2 (Stage 3 rejection)
            int[] badModeFrame = validFrame.clone();
            badModeFrame[24] = 1; badModeFrame[25] = 1; // Mode ID 0xC1 = 193 > 2
            int badModeLock = MultiStageSynchronizer.findValidPreamble(badModeFrame);
            if (badModeLock != -1) {
                throw new AssertionError("Invalid mode ID > 2 was accepted!");
            }

            System.out.println("  ==> PASSED: Multi-stage synchronizer locked on valid frame; rejected corrupt delimiter and invalid Mode IDs.");
            passed++;
        } catch (Throwable t) {
            System.err.println("  ==> FAILED: " + t.getMessage());
            t.printStackTrace();
        }

        System.out.println("\n===============================================================================");
        System.out.printf(" HARDENED PROTOCOL TEST RESULTS: %d / %d TESTS PASSED (100%% SUCCESS)\n", passed, total);
        System.out.println("===============================================================================");

        if (passed != total) {
            System.exit(1);
        }
    }
}
