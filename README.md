# GhostLink — Zero-RF Air-Gapped File Transfer Protocol

<p align="center">
  <img src="files%20(8)/GhostLink-v0.2/architecture.svg" alt="GhostLink Architecture" width="750"/>
</p>

<p align="center">
  <strong>Move arbitrary data between physical smartphones with strictly ZERO radio frequencies active.</strong><br>
  No WiFi · No Bluetooth · No Cellular · No NFC — Light, Sound, and Magnetism only.
</p>

---

## 🚀 Engineering Build (v0.2.3 Hardened Release)

- **Production APK**: [**`GhostLink-v0.2.apk`**](GhostLink-v0.2.apk)
- **Zero-RF Android Manifest**: Strictly 0 RF radio permissions requested.
- **Wire Protocol**: Version 2 with 24-byte header, pre-allocation Header CRC-16 gate, and Payload CRC-32.
- **Cryptographic Core**: Ephemeral ECDH (NIST P-256) key exchange + HKDF-SHA256 key expansion + AES-GCM-256 authenticated encryption with per-chunk deterministic nonces.
- **Acoustic Modem**: Multi-mode physical acoustic layer (BFSK, 4-FSK, 8-FSK) with Barker-13 cross-correlation and Hamming(8,4) SEC-DED forward error correction.
- **Filesystem Security**: Path Traversal (CWE-22) neutralization with canonical containment verification and atomic `.part` disk writes.

---

## ⚡ The Physics & Hardware Channels

GhostLink exploits the physical sensors and actuators already built into commodity smartphones:

```
[Phone A: Transmitter]                             [Phone B: Receiver]
┌────────────────────────┐                         ┌────────────────────────┐
│ Vibration Motor        │ ── Magnetic Induction ──>│ 3-Axis Magnetometer    │ (Contact Handshake)
│ (VibrationEffect PWM)  │    (Delta-B: 5-40 uT)   │ (SensorManager ~100Hz) │
├────────────────────────┤                         ├────────────────────────┤
│ Screen Display         │ ── Optical Photon Stream─>│ Camera CMOS Lens       │ (Primary Bulk Stream)
│ (10 FPS QR Matrix Loop)│    (Visual Spectrum)    │ (CameraX Live Stream)  │
├────────────────────────┤                         ├────────────────────────┤
│ Speaker Diaphragm      │ ── Air Pressure Waves ──>│ Microphone MEMS        │ (Acoustic Modem)
│ (AudioTrack 17.2-20.7k)│    (Acoustic Spectrum)  │ (Goertzel Filter Bank) │
└────────────────────────┘                         └────────────────────────┘
```

### 1. Magnetic Induction Handshake (Motor $\rightarrow$ Magnetometer)
- Transmits an 8-byte pairing token via physical micro-vibrations ($5\text{–}40\ \mu\text{T}$ magnetic fluctuation).
- The receiving phone's 3-axis Hall-effect magnetometer extracts the sync pulse and 32-bit ephemeral seed.
- Handshake duration: **~8–12 seconds at contact (<2 cm)**.

### 2. Optical Photon Stream (Screen $\rightarrow$ Camera)
- Renders 250-byte encrypted chunks as high-density QR frames (Versions 9–11, Error Correction Level L).
- Optimized CameraX pipeline constrained to `POSSIBLE_FORMATS=[QR_CODE]` running at **10 FPS** with in-memory bitmap cache.
- Sustained throughput: **~2.0–2.5 KB/s**.

### 3. Multi-Mode Ultrasonic Acoustic Channel (Speaker $\rightarrow$ Microphone)
- **Mode 0 (Robust BFSK)**: $f_0 = 18.5\text{ kHz}, f_1 = 19.5\text{ kHz}$, 15 ms symbol window, ~8.3 B/s goodput. Maximizes penetration through harsh acoustic noise (SNR $\le 5\text{ dB}$).
- **Mode 1 (4-FSK + FEC)**: 17.5, 18.5, 19.5, 20.5 kHz, 10 ms symbol window, 100 baud, ~12.5–25 B/s goodput with Hamming(8,4) SEC-DED.
- **Mode 2 (8-FSK + FEC)**: 17.2–20.7 kHz (500 Hz spacing), 8 ms symbol window, 125 baud, ~20.8–47 B/s goodput with Hamming(8,4) SEC-DED.
- **Barker-13 Preamble**: Cross-correlation synchronization pattern `[+1, +1, +1, +1, +1, -1, -1, +1, +1, -1, +1, -1, +1]` locks frame boundaries even with single-bit errors.
- **Hanning Windowing**: Raised cosine ramps eliminate audible speaker clicks.
- **Bounded Capacity**: Enforces 128 KB acoustic safety limit to prevent resource starvation.

---

## 🛡️ Security Architecture & Cryptographic Engine

Physical channels trade RF interception for optical/acoustic line-of-sight. GhostLink hardens against eavesdropping, tampering, OOM attacks, and file compromise:

1. **Ephemeral ECDH Key Agreement**: Sender and receiver generate ephemeral NIST P-256 (`secp256r1`) keypairs. The 65-byte uncompressed public key is communicated during pairing/chunk 0.
2. **HKDF-SHA256 Key Expansion**: Derives a 256-bit symmetric AES key, a 12-byte base IV, and an 8-byte session ID using domain-separated salts.
3. **AES-GCM-256 Authenticated Encryption**: Per-chunk unique nonces ($IV = \text{baseIv} \oplus \text{chunkIndex}$) prevent nonce reuse. 128-bit authentication tags verify integrity and prevent bit-flip attacks.
4. **Pre-Allocation Header CRC-16 Gate**: Before allocating any memory or chunk tables, the receiver validates the 16-bit CRC of the header. Corrupted headers from acoustic noise are dropped instantly with 0 memory allocated, eliminating OOM crashes.
5. **Path Traversal (CWE-22) Defense**: Remote filenames are stripped of directory components (`../`, `\`), restricted to alphanumeric/safe characters, verified for canonical containment within the target directory, and written atomically using `.part` temporary files with `sync()`.

---

## 📦 Binary Wire Framing Specification (v2 - 24-Byte Header)

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|       magic ('G','L'=0x474C)  |  ver (0x02)   |  flags (0x00) |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                          session_id                           |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                          chunk_index                          |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                          total_chunks                         |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|         payload_length        |         header_crc16          |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                         payload_crc32                         |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                       payload bytes ...                       |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

---

## 🧪 Verification & Simulation Test Suites

Run directly with JDK 17 (zero Android SDK dependencies required for protocol testing):

```bash
# Compile and run hardened protocol test suite (7/7 tests)
javac -d bin test/HardenedProtocolTestSuite.java test/AcousticChannelSimulation.java
java -cp bin com.ghostlink.test.HardenedProtocolTestSuite

# Run 44.1 kHz PCM AWGN acoustic channel simulation
java -cp bin com.ghostlink.test.AcousticChannelSimulation
```

### Acoustic Channel Simulation Results (AWGN Noise vs Goodput)

| PHY Mode | SNR | Raw BER | FEC BER | PDR (%) | Effective Goodput |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **Mode 0 (BFSK 15ms)** | 20 dB | 0.0000% | 0.0000% | 100.0% | 8.33 B/s |
| **Mode 1 (4-FSK + FEC)** | 20 dB | 0.0000% | 0.0000% | 100.0% | 12.50 B/s |
| **Mode 2 (8-FSK + FEC)** | 20 dB | 0.0000% | 0.0000% | 100.0% | 20.83 B/s |
| **Mode 0 (BFSK 15ms)** | 10 dB | 0.0000% | 0.0000% | 100.0% | 8.33 B/s |
| **Mode 1 (4-FSK + FEC)** | 10 dB | 0.0000% | 0.0000% | 100.0% | 12.50 B/s |
| **Mode 2 (8-FSK + FEC)** | 10 dB | 0.0000% | 0.0000% | 100.0% | 20.83 B/s |
| **Mode 0 (BFSK 15ms)** | 0 dB | 0.0000% | 0.0000% | 100.0% | 8.33 B/s |
| **Mode 1 (4-FSK + FEC)** | 0 dB | 0.0000% | 0.0000% | 100.0% | 12.50 B/s |
| **Mode 2 (8-FSK + FEC)** | 0 dB | 0.0000% | 0.0000% | 100.0% | 20.83 B/s |

---

## 📱 How to Run & Demo

1. Install `GhostLink-v0.2.apk` on two Android devices running Android 8.0 through Android 15.
2. Enable **Airplane Mode** on both devices (verify WiFi, Bluetooth, and Mobile Data are OFF).
3. **Phone B (Receiver)**: Tap **Receive (Rx)** to initialize physical sensors (Camera/Microphone/Magnetometer).
4. **Phone A (Sender)**: Tap **📁 Pick Real File** to load an image, document, or secret text.
5. Tap **Transmit (Tx)** on Phone A.
6. Aim Phone B's camera at Phone A's screen. The carousel streams chunks continuously; the reassembly engine verifies chunk CRC-32s, decrypts via AES-GCM, and atomically saves the file to `Downloads/GhostLink/`.

---

## 🏗️ Building from Source

```bash
cd android
./gradlew assembleDebug
# Output APK: android/app/build/outputs/apk/debug/app-debug.apk
```
