# GhostLink — System Design

## 1. High-Level Overview

GhostLink is a peer-to-peer, fully local system — no server, no network stack at all. Two devices (Sender, Receiver) communicate through one or more of three physical channels: **Optical**, **Ultrasonic**, **Magnetic**. A shared **Channel Manager** on each device decides which channel(s) to use and drives a common transfer protocol on top of whichever physical layer is active.

```
┌─────────────────────────┐                 ┌─────────────────────────┐
│        SENDER APP        │                 │       RECEIVER APP       │
│                           │                 │                          │
│  File → Chunker → Frames │                 │  Frames → Chunks → File │
│                           │                 │                          │
│  ┌─────────────────────┐ │                 │ ┌──────────────────────┐ │
│  │   CHANNEL MANAGER    │◄┼─────magnetic────┼►│   CHANNEL MANAGER    │ │
│  └──────────┬───────────┘ │    handshake    │ └───────────┬──────────┘ │
│             │             │                 │             │            │
│   ┌─────────┼─────────┐   │                 │  ┌──────────┼─────────┐ │
│   ▼         ▼         ▼   │                 │  ▼          ▼         ▼ │
│ Optical  Ultrasonic Magnetic│               │Optical  Ultrasonic Magnetic│
│ Encoder   Encoder    Pulse │                │Decoder   Decoder   Decoder│
└─────────────────────────┘                 └─────────────────────────┘
```

## 2. Components

| Component | Responsibility |
|---|---|
| **Chunker** | Splits input file into fixed-size chunks, attaches chunk-index, total-count, and checksum |
| **Channel Manager** | Runs the state machine (Handshake → Negotiate → Transfer → Verify → Done); picks active channel(s) based on sensor/environment checks |
| **Optical Encoder/Decoder** | Renders chunks as animated QR frames / reads camera feed and decodes them |
| **Ultrasonic Encoder/Decoder** | Modulates chunks as FSK tone bursts / demodulates mic input via frequency analysis |
| **Magnetic Pulse Encoder/Decoder** | Drives vibration motor in a bit-pattern / reads magnetometer to decode it |
| **Reassembly Engine** | Buffers received chunks, detects gaps, requests retransmission, writes final file, verifies full-file checksum |

## 3. Data Flow

1. **Handshake** — devices brought near-touching; magnetic pulse exchange establishes a pairing token and lets each side declare its available channels (camera ok? mic ok? ambient light/noise levels?)
2. **Negotiate** — Channel Manager on each side agrees on the bulk-transfer channel (optical preferred, ultrasonic fallback)
3. **Transfer** — Chunker feeds frames to the chosen Encoder in a loop; Decoder on the other end continuously captures and decodes
4. **Verify** — Reassembly Engine checks which chunk-indices are missing, signals the sender (via the magnetic channel, since it's always-on and low-bandwidth is fine here) to re-loop or highlight specific missing chunks
5. **Done** — full-file checksum confirmed, receiver writes file to disk, both sides show confirmation

## 4. Frame / Packet Format

Each chunk, regardless of physical channel, carries the same logical header before the payload:

```
[ 2 bytes: chunk_index ] [ 2 bytes: total_chunks ] [ 2 bytes: payload_length ]
[ 4 bytes: chunk_checksum (CRC32) ] [ payload bytes... ]
```

- Small header overhead is acceptable given the already-low channel bandwidth — reliability matters more than shaving bytes here
- `total_chunks` lets the receiver know when it has a complete set without needing an explicit "end" signal
- CRC32 per chunk (not just one checksum for the whole file) lets the receiver pinpoint exactly which chunks to re-request rather than redoing the entire transfer

## 5. Channel Comparison

| Channel | Direction | Approx. Throughput | Best Use | Key Failure Mode |
|---|---|---|---|---|
| Optical (QR stream) | Screen → Camera | ~1–20 KB/s | Bulk transfer, primary | Misalignment, poor lighting, camera focus |
| Ultrasonic (FSK audio) | Speaker → Mic | ~0.5–5 KB/s | Bulk fallback | Ambient noise, mic quality, distance |
| Magnetic (pulse) | Motor → Magnetometer | Single-digit bits/s – low bytes/s | Handshake, tiny tokens only | Environmental magnetic interference, motor/sensor calibration variance |

## 6. Error Handling & Retransmission

- Each side maintains a bitmap of received chunk-indices
- Optical/ultrasonic transmitters loop their full frame sequence continuously rather than send-once — receivers simply keep listening until their bitmap is full
- If a transfer stalls (no new chunks received for N seconds), the Reassembly Engine can request a full loop restart via the magnetic channel, since it stays connected throughout
- Full-file checksum as a final gate before writing to disk — never trust chunk-level checks alone

## 7. Security Notes (honest framing)

- Removing RF removes RF-based interception (WiFi sniffing, BT MITM) — it does **not** make the transfer inherently secure
- Optical channel is visible to anyone with a camera line-of-sight to the screen; ultrasonic is theoretically recordable by any nearby microphone (even if inaudible to humans)
- If confidentiality matters, payload should be encrypted **before** chunking (e.g. session key derived during the magnetic handshake), so the physical-layer interception risk doesn't equal a data breach
- This should be stated plainly in-product rather than marketed as "unhackable" — it's a different attack surface, not a smaller one by default
- This is now PRD feature **F7 (Payload Encryption)**, scheduled explicitly in `plan.txt` Phase 2 — not just a design note that quietly never gets built

## 8. Open Design Questions

- Should chunk size be fixed or adaptive per channel (larger for optical, smaller for ultrasonic given lower reliability)?
- Does the magnetic channel stay "open" for the full transfer duration as a low-bandwidth control channel, or only for the initial handshake?
- How does the system behave gracefully if all three channels fail mid-transfer (partial file recovery vs. full restart)?
