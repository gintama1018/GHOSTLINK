# GhostLink — Zero-RF Air-Gapped File Transfer Protocol

<p align="center">
  <img src="files%20(8)/GhostLink-v0.2/architecture.svg" alt="GhostLink Architecture" width="750"/>
</p>

<p align="center">
  <strong>Move arbitrary data between physical smartphones with strictly ZERO radio frequencies active.</strong><br>
  No WiFi · No Bluetooth · No Cellular · No NFC — Light, Sound, and Magnetism only.
</p>

---

## 🚀 Hackathon Release (v0.2.1)

- **Production APK**: [**`GhostLink-v0.2.apk`**](GhostLink-v0.2.apk)
- **Zero-RF Android Manifest**: Strictly 0 RF radio permissions requested.

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
│ (6 FPS QR Matrix Loop) │    (Visual Spectrum)    │ (CameraX Live Stream)  │
├────────────────────────┤                         ├────────────────────────┤
│ Speaker Diaphragm      │ ── Air Pressure Waves ──>│ Microphone MEMS        │ (Acoustic Fallback)
│ (AudioTrack 18.5/19.5k)│    (Acoustic Spectrum)  │ (AudioRecord Goertzel) │
└────────────────────────┘                         └────────────────────────┘
```

1. **Magnetic Induction Handshake (Motor $\rightarrow$ Magnetometer)**
   - Transmits an 8-byte pairing token via physical micro-vibrations ($5\text{–}40\ \mu\text{T}$ magnetic fluctuation).
   - The receiving phone's 3-axis Hall-effect magnetometer extracts the sync pulse and 32-bit ephemeral seed.
   - Handshake duration: **~8–12 seconds at contact (<2 cm)**.

2. **Optical Photon Stream (Screen $\rightarrow$ Camera)**
   - Renders 250-byte encrypted chunks as high-density QR frames (Versions 9–11, Error Correction Level L).
   - CameraX live video feed decodes incoming frames at **6 FPS** in a cyclic carousel.
   - Sustained throughput: **~1.0–2.5 KB/s**.

3. **Ultrasonic Acoustic Fallback (Speaker $\rightarrow$ Microphone)**
   - Dual-tone BFSK ($f_0 = 18.5\text{ kHz}, f_1 = 19.5\text{ kHz}$) with 15 ms symbol window and Hanning smoothing to eliminate clicks.
   - Microphones capture near-inaudible sound waves and decode bits via Goertzel discrete energy bins.
   - **Enforced 128 KB acoustic safety cap**.

---

## 🛡️ F7 Cryptographic Gate (AES-GCM-256 + HKDF)

Physical channels trade RF interception for optical/acoustic line-of-sight. GhostLink eliminates this attack surface with **F7 Authenticated Payload Encryption**:
1. Handshake seed is expanded into a 256-bit symmetric session key via `HKDF-SHA256`.
2. The entire file is encrypted with `AES-256-GCM` (12-byte IV + 16-byte authentication tag) **before** chunking.
3. The chunker, QR display, and audio layers never handle plaintext. Optical or audio recordings yield only ciphertext.

---

## 📦 Binary Wire Protocol (14-Byte Header)

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                          chunk_index                          |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                          total_chunks                         |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|         payload_length        |        chunk_checksum         |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|       (CRC32 continued)       |       payload bytes ...       |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

---

## 📱 How to Run & Demo

1. Install `GhostLink-v0.2.apk` on two Android phones.
2. Enable **Airplane Mode** on both phones (verify WiFi, Bluetooth, and Mobile Data are OFF).
3. **Phone B (Receiver)**: Tap **Receive Mode** to open the live camera viewfinder.
4. **Phone A (Sender)**: Tap **Send Test Secret** to broadcast the encrypted QR stream.
5. Aim Phone B's camera at Phone A's screen. Watch the chunk progress bar reach 100% and decrypt with zero bit errors!

---

## 🏗️ Building from Source

```bash
cd android
./gradlew assembleDebug
# Output located at: android/app/build/outputs/apk/debug/app-debug.apk
```
