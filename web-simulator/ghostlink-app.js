/**
 * GhostLink — Zero-RF Interactive Protocol Engine
 * Full browser implementation adhering strictly to PRD v0.2, system-design.md, and plan.txt.
 */

// --- 1. CRC32 & CRC16 TABLES ---
const CRC32_TABLE = new Uint32Array(256);
for (let i = 0; i < 256; i++) {
  let c = i;
  for (let k = 0; k < 8; k++) {
    c = ((c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1));
  }
  CRC32_TABLE[i] = c >>> 0;
}

function computeCRC32(bytes) {
  let crc = 0xFFFFFFFF;
  for (let i = 0; i < bytes.length; i++) {
    crc = (crc >>> 8) ^ CRC32_TABLE[(crc ^ bytes[i]) & 0xFF];
  }
  return ((crc ^ 0xFFFFFFFF) >>> 0);
}

function computeCRC16(bytes) {
  let crc = 0xFFFF;
  for (let i = 0; i < bytes.length; i++) {
    crc ^= (bytes[i] & 0xFF) << 8;
    for (let j = 0; j < 8; j++) {
      if ((crc & 0x8000) !== 0) {
        crc = ((crc << 1) ^ 0x1021) & 0xFFFF;
      } else {
        crc = (crc << 1) & 0xFFFF;
      }
    }
  }
  return crc & 0xFFFF;
}

// --- 2. 14-BYTE PACKET WIRE PROTOCOL ---
const HEADER_SIZE = 14;

class Packet {
  constructor(chunkIndex, totalChunks, payload) {
    this.chunkIndex = chunkIndex;
    this.totalChunks = totalChunks;
    this.payload = payload;
    this.payloadLength = payload.length;
    this.checksum = computeCRC32(payload);
  }

  serialize() {
    const buffer = new ArrayBuffer(HEADER_SIZE + this.payload.length);
    const view = new DataView(buffer);
    view.setUint32(0, this.chunkIndex, false); // Big-Endian
    view.setUint32(4, this.totalChunks, false);
    view.setUint16(8, this.payloadLength, false);
    view.setUint32(10, this.checksum, false);
    new Uint8Array(buffer, HEADER_SIZE).set(this.payload);
    return new Uint8Array(buffer);
  }

  static deserialize(wireBytes) {
    if (wireBytes.length < HEADER_SIZE) {
      throw new Error(`Packet shorter than 14 bytes: ${wireBytes.length}`);
    }
    const view = new DataView(wireBytes.buffer, wireBytes.byteOffset, wireBytes.byteLength);
    const chunkIndex = view.getUint32(0, false);
    const totalChunks = view.getUint32(4, false);
    const payloadLength = view.getUint16(8, false);
    const checksum = view.getUint32(10, false);

    if (wireBytes.length !== HEADER_SIZE + payloadLength) {
      throw new Error(`Packet length mismatch. Header: ${payloadLength}, Total: ${wireBytes.length - HEADER_SIZE}`);
    }

    const payload = wireBytes.subarray(HEADER_SIZE, HEADER_SIZE + payloadLength);
    const calculatedChecksum = computeCRC32(payload);
    if (calculatedChecksum !== checksum) {
      throw new Error(`CRC32 mismatch! Header: ${checksum}, Computed: ${calculatedChecksum}`);
    }

    const packet = new Packet(chunkIndex, totalChunks, payload);
    packet.checksum = checksum;
    return packet;
  }
}

// --- 3. 8-BYTE MAGNETIC HANDSHAKE ---
const SYNC_BYTE = 0xA5;

class MagneticPacket {
  constructor(verCaps, saltSeed) {
    this.preamble = SYNC_BYTE;
    this.verCaps = verCaps;
    this.saltSeed = saltSeed; // 4 bytes
    const crcData = new Uint8Array([SYNC_BYTE, verCaps, saltSeed[0], saltSeed[1], saltSeed[2], saltSeed[3]]);
    this.crc16 = computeCRC16(crcData);
  }

  serialize() {
    const buffer = new ArrayBuffer(8);
    const view = new DataView(buffer);
    view.setUint8(0, this.preamble);
    view.setUint8(1, this.verCaps);
    new Uint8Array(buffer, 2, 4).set(this.saltSeed);
    view.setUint16(6, this.crc16, false);
    return new Uint8Array(buffer);
  }

  static deserialize(bytes) {
    if (bytes.length !== 8) throw new Error("Magnetic packet must be 8 bytes");
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    const pre = view.getUint8(0);
    if (pre !== SYNC_BYTE) throw new Error(`Invalid sync byte: 0x${pre.toString(16)}`);
    const vc = view.getUint8(1);
    const seed = bytes.subarray(2, 6);
    const rxCrc = view.getUint16(6, false);
    const calcCrc = computeCRC16(new Uint8Array([pre, vc, seed[0], seed[1], seed[2], seed[3]]));
    if (rxCrc !== calcCrc) throw new Error(`Magnetic CRC16 mismatch: ${rxCrc} vs ${calcCrc}`);
    const pkt = new MagneticPacket(vc, seed);
    pkt.crc16 = rxCrc;
    return pkt;
  }
}

// --- 4. CRYPTO ENGINE (SubtleCrypto HKDF + AES-GCM) ---
class CryptoEngine {
  static async deriveKey(seed4Bytes) {
    const enc = new TextEncoder();
    const fixedSalt = enc.encode("GhostLinkSalt2026");
    const info = enc.encode("GhostLink-Session-v1");

    // Import seed as raw key material
    const baseKey = await crypto.subtle.importKey(
      "raw",
      seed4Bytes,
      "HKDF",
      false,
      ["deriveKey"]
    );

    // Derive 256-bit AES-GCM key
    return await crypto.subtle.deriveKey(
      {
        name: "HKDF",
        hash: "SHA-256",
        salt: fixedSalt,
        info: info
      },
      baseKey,
      { name: "AES-GCM", length: 256 },
      false,
      ["encrypt", "decrypt"]
    );
  }

  static async encrypt(plaintextBytes, cryptoKey) {
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const ciphertext = await crypto.subtle.encrypt(
      { name: "AES-GCM", iv: iv },
      cryptoKey,
      plaintextBytes
    );
    const out = new Uint8Array(12 + ciphertext.byteLength);
    out.set(iv, 0);
    out.set(new Uint8Array(ciphertext), 12);
    return out;
  }

  static async decrypt(ivAndCiphertext, cryptoKey) {
    const iv = ivAndCiphertext.subarray(0, 12);
    const ciphertext = ivAndCiphertext.subarray(12);
    const decrypted = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: iv },
      cryptoKey,
      ciphertext
    );
    return new Uint8Array(decrypted);
  }
}

// --- 5. APPLICATION STATE & CONTROLLER ---
const state = {
  activeChannel: "optical",
  isTransferring: false,
  isAudioMuted: true,
  audioContext: null,
  outboundPackets: [],
  currentOutboundIdx: 0,
  receivedChunks: new Map(),
  totalChunks: 25,
  cryptoKey: null,
  transferTimer: null,
  speedBps: 1500,
  transferredBytes: 0,
  totalBytes: 0
};

// UI Elements
const els = {
  btnOptical: document.getElementById("btnOptical"),
  btnUltrasonic: document.getElementById("btnUltrasonic"),
  btnMagnetic: document.getElementById("btnMagnetic"),
  stageStatus: document.getElementById("stageStatus"),
  telemetryMode: document.getElementById("telemetryMode"),
  telemetrySpeed: document.getElementById("telemetrySpeed"),
  statChannel: document.getElementById("statChannel"),
  statSpeed: document.getElementById("statSpeed"),
  statTransferred: document.getElementById("statTransferred"),
  statEta: document.getElementById("statEta"),
  progressBar: document.getElementById("progressBar"),
  senderStreamInfo: document.getElementById("senderStreamInfo"),
  receiverStreamInfo: document.getElementById("receiverStreamInfo"),
  chunkMatrix: document.getElementById("chunkMatrix"),
  qrCanvas: document.getElementById("qrCanvas"),
  linkSvg: document.getElementById("linkSvg"),
  secretInput: document.getElementById("secretInput"),
  outputPlaintext: document.getElementById("outputPlaintext"),
  consoleLog: document.getElementById("consoleLog"),
  btnStartTransfer: document.getElementById("btnStartTransfer"),
  btnAudioToggle: document.getElementById("btnAudioToggle"),
  audioToggleText: document.getElementById("audioToggleText"),
  audioToggleIcon: document.getElementById("audioToggleIcon"),
  btnReset: document.getElementById("btnReset"),
  honestMemoText: document.getElementById("honestMemoText")
};

// Log helper
function log(msg) {
  const line = `[${new Date().toLocaleTimeString()}] ${msg}`;
  els.consoleLog.innerHTML = `${line}<br>` + els.consoleLog.innerHTML;
}

// Initialize Chunk Matrix Display
function initChunkMatrix(count = 25) {
  els.chunkMatrix.innerHTML = "";
  for (let i = 0; i < count; i++) {
    const div = document.createElement("div");
    div.className = "chunk-block";
    div.id = `chunkBlock_${i}`;
    els.chunkMatrix.appendChild(div);
  }
}

// Channel Configuration Metadata
const CHANNEL_CONFIG = {
  optical: {
    name: "Optical (QR Stream)",
    speed: "~1.0–2.5 KB/s",
    speedVal: 1500,
    accent: "#F0A93E",
    telemetry: "OPTICAL BEAM ACTIVE",
    memo: "Optical is the primary bulk channel. High-density QR frames cycle at 5–8 FPS. Highly directional and immune to RF sniffing, but visible to line-of-sight cameras."
  },
  ultrasonic: {
    name: "Ultrasonic (BFSK Audio)",
    speed: "~0.02–0.1 KB/s",
    speedVal: 50,
    accent: "#4CD9D0",
    telemetry: "ACOUSTIC 18.5/19.5 kHz ACTIVE",
    memo: "Ultrasonic is the fallback bulk channel. Inaudible near-ultrasonic tone bursts (~18.5/19.5 kHz) transfer data when screens are obscured. Strictly capped at 128 KB."
  },
  magnetic: {
    name: "Magnetic (Contact Pulse)",
    speed: "bits/s (Handshake only)",
    speedVal: 5,
    accent: "#B18CFF",
    telemetry: "MAGNETIC FIELD CONTACT (<2cm)",
    memo: "Magnetic contact channel (~5–10 bps) uses the vibration motor and magnetometer at contact distance (<2cm) to exchange pairing IDs and AES-256 session seeds in ~8–12 seconds."
  }
};

function selectChannel(ch) {
  state.activeChannel = ch;
  document.querySelectorAll(".chbtn").forEach(b => b.classList.toggle("active", b.dataset.ch === ch));
  const cfg = CHANNEL_CONFIG[ch];
  els.statChannel.textContent = cfg.name;
  els.statSpeed.textContent = cfg.speed;
  els.statSpeed.style.color = cfg.accent;
  els.telemetryMode.textContent = cfg.telemetry;
  els.telemetrySpeed.textContent = cfg.speed;
  els.honestMemoText.textContent = cfg.memo;
  renderLinkBridge();
}

els.btnOptical.addEventListener("click", () => selectChannel("optical"));
els.btnUltrasonic.addEventListener("click", () => selectChannel("ultrasonic"));
els.btnMagnetic.addEventListener("click", () => selectChannel("magnetic"));

// Audio Synthesis (WebAudio BFSK)
function playTone(freq, durationMs = 30) {
  if (state.isAudioMuted) return;
  try {
    if (!state.audioContext) {
      state.audioContext = new (window.AudioContext || window.webkitAudioContext)();
    }
    const ctx = state.audioContext;
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();

    // Use audible test frequency if in audio mode or near-ultrasound
    osc.type = "sine";
    osc.frequency.setValueAtTime(freq, ctx.currentTime);

    gain.gain.setValueAtTime(0.01, ctx.currentTime);
    gain.gain.linearRampToValueAtTime(0.15, ctx.currentTime + 0.005);
    gain.gain.linearRampToValueAtTime(0.01, ctx.currentTime + (durationMs / 1000));

    osc.connect(gain);
    gain.connect(ctx.destination);

    osc.start();
    osc.stop(ctx.currentTime + (durationMs / 1000));
  } catch (_) {}
}

els.btnAudioToggle.addEventListener("click", () => {
  state.isAudioMuted = !state.isAudioMuted;
  if (!state.isAudioMuted) {
    els.audioToggleText.textContent = "Acoustic Audio: Active (18.5-19.5kHz)";
    els.audioToggleIcon.textContent = "🔊";
    els.btnAudioToggle.style.borderColor = "var(--ultrasonic)";
    if (!state.audioContext) {
      state.audioContext = new (window.AudioContext || window.webkitAudioContext)();
    }
  } else {
    els.audioToggleText.textContent = "Acoustic Audio: Muted";
    els.audioToggleIcon.textContent = "🔈";
    els.btnAudioToggle.style.borderColor = "var(--border)";
  }
});

// Render Link Bridge SVG Visuals
function renderLinkBridge() {
  const svg = els.linkSvg;
  svg.innerHTML = "";
  const ch = state.activeChannel;
  const cfg = CHANNEL_CONFIG[ch];

  if (ch === "optical") {
    for (let i = 0; i < 4; i++) {
      const line = document.createElementNS("http://www.w3.org/2000/svg", "line");
      line.setAttribute("x1", "10");
      line.setAttribute("x2", "270");
      line.setAttribute("y1", `${25 + i * 22}`);
      line.setAttribute("y2", `${25 + i * 22}`);
      line.setAttribute("stroke", cfg.accent);
      line.setAttribute("stroke-width", "2");
      line.setAttribute("stroke-dasharray", "6 8");
      line.setAttribute("opacity", "0.6");
      svg.appendChild(line);
    }
  } else if (ch === "ultrasonic") {
    const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
    let d = "M10,60 ";
    for (let x = 10; x <= 270; x += 5) {
      d += `L${x},${60 + Math.sin(x / 7) * 22} `;
    }
    path.setAttribute("d", d);
    path.setAttribute("stroke", cfg.accent);
    path.setAttribute("fill", "none");
    path.setAttribute("stroke-width", "2");
    svg.appendChild(path);
  } else {
    for (let i = 0; i < 3; i++) {
      const circle = document.createElementNS("http://www.w3.org/2000/svg", "circle");
      circle.setAttribute("cx", "140");
      circle.setAttribute("cy", "60");
      circle.setAttribute("r", `${18 + i * 16}`);
      circle.setAttribute("stroke", cfg.accent);
      circle.setAttribute("fill", "none");
      circle.setAttribute("stroke-width", "1.8");
      circle.setAttribute("opacity", `${0.7 - i * 0.2}`);
      svg.appendChild(circle);
    }
  }
}

// Draw Animated QR Simulator Canvas
function drawQrMatrix(chunkIdx, totalChunks) {
  const ctx = els.qrCanvas.getContext("2d");
  const size = 140;
  ctx.fillStyle = "#04070D";
  ctx.fillRect(0, 0, size, size);

  const gridSize = 14;
  const cellSize = Math.floor(size / gridSize);

  // Seeded pseudo-random pattern based on chunk index
  let seed = (chunkIdx + 1) * 31337;
  function rnd() {
    seed = (seed * 16807) % 2147483647;
    return (seed - 1) / 2147483646;
  }

  // Draw QR corner finders
  ctx.fillStyle = CHANNEL_CONFIG[state.activeChannel].accent;
  // Top-left finder
  ctx.fillRect(4, 4, 28, 28);
  ctx.fillStyle = "#04070D";
  ctx.fillRect(8, 8, 20, 20);
  ctx.fillStyle = CHANNEL_CONFIG[state.activeChannel].accent;
  ctx.fillRect(12, 12, 12, 12);

  // Top-right finder
  ctx.fillRect(size - 32, 4, 28, 28);
  ctx.fillStyle = "#04070D";
  ctx.fillRect(size - 28, 8, 20, 20);
  ctx.fillStyle = CHANNEL_CONFIG[state.activeChannel].accent;
  ctx.fillRect(size - 24, 12, 12, 12);

  // Bottom-left finder
  ctx.fillRect(4, size - 32, 28, 28);
  ctx.fillStyle = "#04070D";
  ctx.fillRect(8, size - 28, 20, 20);
  ctx.fillStyle = CHANNEL_CONFIG[state.activeChannel].accent;
  ctx.fillRect(12, size - 24, 12, 12);

  // Random data cells
  for (let r = 0; r < gridSize; r++) {
    for (let c = 0; c < gridSize; c++) {
      if ((r < 4 && c < 4) || (r < 4 && c > 9) || (r > 9 && c < 4)) continue;
      if (rnd() > 0.45) {
        ctx.fillStyle = CHANNEL_CONFIG[state.activeChannel].accent;
        ctx.fillRect(c * cellSize + 2, r * cellSize + 2, cellSize - 3, cellSize - 3);
      }
    }
  }

  // Overlay chunk index badge
  ctx.fillStyle = "rgba(4, 7, 13, 0.85)";
  ctx.fillRect(20, size - 22, size - 40, 16);
  ctx.fillStyle = "#E9EDF7";
  ctx.font = "9px SF Mono, Consolas, monospace";
  ctx.textAlign = "center";
  ctx.fillText(`F#${chunkIdx + 1} / ${totalChunks}`, size / 2, size - 10);
}

// Start End-to-End Transfer Flow
async function startZeroRfTransfer() {
  if (state.isTransferring) return;
  state.isTransferring = true;
  els.btnStartTransfer.disabled = true;
  els.outputPlaintext.textContent = "Transfer in progress... Awaiting frames and reassembly.";

  const textPayload = els.secretInput.value;
  const rawBytes = new TextEncoder().encode(textPayload);
  state.totalBytes = rawBytes.length;

  log(`--- Zero-RF Transfer Started: ${rawBytes.length} bytes ---`);

  // 1. STATE: HANDSHAKE (Simulate 8-byte magnetic contact seed exchange)
  els.stageStatus.textContent = "State: HANDSHAKE (Magnetic Contact Pulse)";
  log("Phase 2 Handshake: Transmitting 8-byte pairing token via magnetic pulse (~8-12s)...");

  const saltSeed = crypto.getRandomValues(new Uint8Array(4));
  const handshakePacket = new MagneticPacket(0x13, saltSeed);
  const hsBytes = handshakePacket.serialize();
  log(`Handshake packet framed: Sync=0x${hsBytes[0].toString(16)}, CRC16=0x${handshakePacket.crc16.toString(16)}`);

  // Derive AES-GCM session key via HKDF (F7 Gate)
  state.cryptoKey = await CryptoEngine.deriveKey(saltSeed);
  log("HKDF-SHA256: 256-bit AES-GCM session key derived from handshake seed.");

  // Encrypt plaintext BEFORE chunking
  const fullCiphertext = await CryptoEngine.encrypt(rawBytes, state.cryptoKey);
  log(`F7 Encryption Gate: Plaintext encrypted to ${fullCiphertext.length} bytes ciphertext (IV + Tag).`);

  // 2. STATE: NEGOTIATE & TRANSPARENT ETA (F6)
  els.stageStatus.textContent = "State: NEGOTIATE (Channel & ETA Calculation)";
  const isUltrasonic = state.activeChannel === "ultrasonic";
  const chunkSize = isUltrasonic ? 32 : 64; // Chunks optimized for interactive demo

  // Calculate ETA
  const byteRate = isUltrasonic ? 50 : 1500;
  const redundancy = isUltrasonic ? 1.30 : 1.25;
  const etaSeconds = Math.max(2, Math.round((fullCiphertext.length / byteRate) * redundancy));
  els.statEta.textContent = `~${etaSeconds}s (${state.activeChannel})`;

  // Split ciphertext into 14-byte framed packets
  const totalChunks = Math.ceil(fullCiphertext.length / chunkSize);
  state.totalChunks = totalChunks;
  initChunkMatrix(totalChunks);

  state.outboundPackets = [];
  for (let i = 0; i < totalChunks; i++) {
    const start = i * chunkSize;
    const end = Math.min(start + chunkSize, fullCiphertext.length);
    const chunkBytes = fullCiphertext.slice(start, end);
    state.outboundPackets.push(new Packet(i, totalChunks, chunkBytes));
  }
  log(`Chunker: Created ${totalChunks} packets (14-byte header + CRC32 per frame).`);

  // 3. STATE: TRANSFER
  els.stageStatus.textContent = `State: TRANSFER (${CHANNEL_CONFIG[state.activeChannel].name})`;
  state.receivedChunks.clear();
  state.currentOutboundIdx = 0;
  state.transferredBytes = 0;

  const intervalMs = isUltrasonic ? 250 : 160; // 6 FPS for optical

  state.transferTimer = setInterval(async () => {
    // Cyclic broadcast
    const packet = state.outboundPackets[state.currentOutboundIdx];
    state.currentOutboundIdx = (state.currentOutboundIdx + 1) % state.outboundPackets.length;

    // Sender visual update
    drawQrMatrix(packet.chunkIndex, packet.totalChunks);
    els.senderStreamInfo.textContent = `Broadcasting #${packet.chunkIndex + 1}/${packet.totalChunks}`;

    // Acoustic tone burst simulation
    if (state.activeChannel === "ultrasonic") {
      playTone(packet.chunkIndex % 2 === 0 ? 18500 : 19500, 30);
    }

    // Receiver ingest simulation (with wire serialization / deserialization roundtrip)
    const wireBytes = packet.serialize();
    const rxPacket = Packet.deserialize(wireBytes); // Validates CRC32

    if (!state.receivedChunks.has(rxPacket.chunkIndex)) {
      state.receivedChunks.set(rxPacket.chunkIndex, rxPacket.payload);
      state.transferredBytes += rxPacket.payload.length;

      // Update chunk matrix UI
      const block = document.getElementById(`chunkBlock_${rxPacket.chunkIndex}`);
      if (block) {
        block.classList.add(state.activeChannel === "ultrasonic" ? "received-audio" : "received");
      }

      const fraction = state.receivedChunks.size / state.totalChunks;
      els.progressBar.style.width = `${(fraction * 100).toFixed(0)}%`;
      els.receiverStreamInfo.textContent = `${state.receivedChunks.size} / ${state.totalChunks} chunks`;
      els.statTransferred.textContent = `${(state.transferredBytes / 1024).toFixed(1)} KB / ${(fullCiphertext.length / 1024).toFixed(1)} KB`;
    }

    // 4. Check for 100% completion
    if (state.receivedChunks.size === state.totalChunks) {
      clearInterval(state.transferTimer);

      // 5. STATE: VERIFY & DONE
      els.stageStatus.textContent = "State: VERIFY (CRC32 Check & AES-GCM Tag Auth)";
      log("All chunks received! Assembling full ciphertext buffer...");

      let totalLen = 0;
      for (let i = 0; i < state.totalChunks; i++) {
        totalLen += state.receivedChunks.get(i).length;
      }
      const reassembledCiphertext = new Uint8Array(totalLen);
      let offset = 0;
      for (let i = 0; i < state.totalChunks; i++) {
        const c = state.receivedChunks.get(i);
        reassembledCiphertext.set(c, offset);
        offset += c.length;
      }

      try {
        const decryptedBytes = await CryptoEngine.decrypt(reassembledCiphertext, state.cryptoKey);
        const decryptedText = new TextDecoder().decode(decryptedBytes);

        els.stageStatus.textContent = "State: DONE (Transfer Verified 100%)";
        els.outputPlaintext.textContent = decryptedText;
        log("SUCCESS: AES-GCM authentication passed! Zero-RF file reconstructed with 0 bit errors.");
      } catch (err) {
        els.stageStatus.textContent = "State: ERROR (Authentication Tag Failure)";
        log(`CRITICAL ERROR: Decryption failed - ${err.message}`);
        els.outputPlaintext.textContent = `DECRYPTION ERROR: ${err.message}`;
      }

      state.isTransferring = false;
      els.btnStartTransfer.disabled = false;
    }
  }, intervalMs);
}

// Reset State
function resetState() {
  if (state.transferTimer) clearInterval(state.transferTimer);
  state.isTransferring = false;
  state.receivedChunks.clear();
  els.btnStartTransfer.disabled = false;
  els.stageStatus.textContent = "State: IDLE (Air-Gap Active)";
  els.progressBar.style.width = "0%";
  els.statTransferred.textContent = "0.0 KB / 0.0 KB";
  els.statEta.textContent = "Ready";
  els.senderStreamInfo.textContent = "Awaiting input";
  els.receiverStreamInfo.textContent = "0 / 0 chunks";
  els.outputPlaintext.textContent = "Awaiting reception... Decrypted output will appear here.";
  initChunkMatrix(25);
  const ctx = els.qrCanvas.getContext("2d");
  ctx.fillStyle = "#04070D";
  ctx.fillRect(0, 0, 140, 140);
  log("[System] State reset to IDLE.");
}

els.btnStartTransfer.addEventListener("click", startZeroRfTransfer);
els.btnReset.addEventListener("click", resetState);

// Initial setup
initChunkMatrix(25);
selectChannel("optical");
const initialCtx = els.qrCanvas.getContext("2d");
initialCtx.fillStyle = "#04070D";
initialCtx.fillRect(0, 0, 140, 140);
