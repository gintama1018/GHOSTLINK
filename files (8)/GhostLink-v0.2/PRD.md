# GhostLink — Zero-RF File Transfer System
### Product Requirements Document (PRD) — v0.1 Draft

---

## 1. Problem Statement

Every mainstream file-sharing method (WiFi Direct, Bluetooth, cellular data, NFC) depends on a radio transceiver. That means:

- No RF hardware or RF disabled → no transfer (airplane mode, damaged radio, locked-down device)
- RF is the most surveilled and jammed layer of a device — it's the first thing monitored, blocked, or exploited in secure/regulated/air-gapped environments
- Every "offline" solution today (AirDrop, Nearby Share, Xender) actually still uses WiFi or BT under the hood — nothing on the market genuinely avoids RF end-to-end

**GhostLink is a file transfer protocol and reference app that moves data between two devices using zero radio-frequency channels** — light, sound, and magnetism instead.

## 2. Why This Matters

- **Air-gapped / high-security contexts**: labs, defense, journalism source protection — anywhere RF is physically disabled or forbidden by policy
- **RF-denied environments**: underground, shielded rooms, disaster zones with jammed spectrum
- **Novelty/product gap**: the underlying physics (visual light comms, acoustic data, magnetic field sensing) is individually well-researched, but no shipped consumer product combines them into one adaptive transfer stack

## 3. Goals

- G1: Transfer arbitrary files between two commodity smartphones with zero RF radios active
- G2: Auto-negotiate the best available channel(s) for a given pair of devices and environment
- G3: Be honest and transparent with the user about expected transfer time before starting
- G4: Reliable delivery — checksum + retransmission, not "best effort and hope"
- G5: Confidentiality independent of the physical channel — payload encrypted end-to-end so a screen recording or audio capture doesn't equal a data breach

## 4. Non-Goals (v1)

- Not trying to beat WiFi/BT on speed — this is a capability play, not a performance play
- Not building a general-purpose mesh network
- Not targeting large media files (v1 target: <5 MB payloads — see §9 for why)

## 5. Target Users

| Segment | Need |
|---|---|
| Security researchers / air-gap operators | Move small secrets (keys, configs, credentials) with provably zero RF surface |
| Hackathon/demo audience | A genuinely novel, physics-backed transfer method — good story, good demo |
| Privacy-conscious users | Transfer without leaving an RF fingerprint (no WiFi probe requests, no BT MAC broadcast) |

## 6. System Overview

Three independent physical channels, one adaptive protocol on top:

1. **Optical Stream (primary, bulk)** — sender animates data as a sequence of QR frames on-screen; receiver's camera reads and reassembles them
2. **Ultrasonic Audio (fallback, bulk)** — speaker-to-mic, inaudible high-frequency tone bursts, used when screens can't be aligned
3. **Magnetic Field Pulse (handshake / tiny payload)** — vibration motor as transmitter, magnetometer as receiver, phones touching/near-touching — used for pairing, authentication, and channel negotiation, not bulk data

Full component and data-flow detail is in `system-design.md`.

## 7. Core Features

| ID | Feature | Description |
|---|---|---|
| F1 | Optical Encoder/Decoder | Chunk file → animated QR stream → camera capture → reassembly |
| F2 | Ultrasonic Encoder/Decoder | Chunk file → FSK-modulated ultrasonic tone → mic capture → reassembly |
| F3 | Magnetic Handshake | Short pulse-pattern exchange over magnetometer for pairing, channel negotiation, and session-key seed exchange |
| F4 | Adaptive Channel Manager | Detects available sensors (camera alignment, ambient noise, proximity) and picks/blends channels automatically |
| F5 | Integrity Layer | Per-chunk checksum, missing-chunk detection, automatic re-request |
| F6 | Transparent ETA | Before transfer starts, shows realistic time estimate based on file size + chosen channel |
| F7 | Payload Encryption | Full file encrypted (AES-GCM) using the handshake-derived session key **before** chunking begins — chunker/optical/ultrasonic layers never see plaintext |

## 8. User Stories

- As a sender, I want to just pick a file and tap "Send" — GhostLink decides the channel for me.
- As a receiver, I want to point my camera at the sender's screen and watch the file assemble live, with a progress bar.
- As a security-conscious user, I want confirmation that no RF radio was active at any point during the transfer.
- As a user in a noisy/dark/misaligned setting, I want the app to fall back to whichever channel still works.

## 9. Constraints & Honest Limitations

This is the brutal-honesty section — no sugarcoating the physics:

- **Optical channel**: ~1–20 KB/s realistic throughput depending on QR density, screen refresh, camera quality. A 5 MB file is **4–80 minutes**. Fine for text/keys/small files, bad for video.
- **Ultrasonic channel**: similar order of magnitude, often worse (~0.5–5 KB/s), highly sensitive to ambient noise and mic quality.
- **Magnetic channel**: single-digit bits/sec to low bytes/sec. Usable only for handshake tokens, not payloads.
- None of these channels are inherently more *secure* than RF — they trade one interception surface (radio sniffing) for others (someone filming the screen, recording the audio). This should be stated honestly in-product, not oversold as "unhackable."
- F7 (Payload Encryption) is what actually closes this gap: even if someone captures the QR stream or the audio, they get ciphertext, not the file. Without F7 shipped, "zero-RF" is a transport-privacy story only, not a confidentiality story — don't market it as the latter until F7 is done.

## 10. Success Metrics

- Successful end-to-end transfer of a 100 KB file with 0 corrupted bytes (integrity, not speed, is the v1 bar)
- Magnetic handshake completes reliably within 3 seconds at <2cm proximity
- Channel Manager correctly falls back to ultrasonic when camera alignment fails, in >90% of test trials

## 11. Out of Scope (v1)

- Files >5 MB
- More than 2 simultaneous peers
- Cross-platform iOS↔Android magnetometer calibration differences (flag as known risk, not solved in v1)

## 12. Risks & Open Questions

- Magnetometer sensitivity and calibration vary a lot across phone models — needs early device-matrix testing
- Ambient magnetic interference (speakers, metal desks) could false-trigger the handshake decoder
- Optical channel needs a stable camera-screen distance/focus — bad UX if users have to "find the sweet spot"
- Is the honest bandwidth ceiling acceptable for the intended use case, or does it kill adoption outside niche/demo contexts?
