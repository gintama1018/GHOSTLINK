package com.ghostlink.zerorf.channels.ultrasonic

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import com.ghostlink.zerorf.channels.ultrasonic.modem.AcousticFramer
import com.ghostlink.zerorf.channels.ultrasonic.modem.AcousticMode
import com.ghostlink.zerorf.channels.ultrasonic.modem.AcousticSynchronizer
import com.ghostlink.zerorf.channels.ultrasonic.modem.FecCodec
import com.ghostlink.zerorf.core.Packet
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10

/**
 * Adaptive Multi-Mode Acoustic Demodulator.
 * Utilizes a multi-bin Goertzel filter bank, multi-stage Barker-13 cross-correlation,
 * Hamming(8,4) SEC-DED forward error correction, and hysteresis-backed adaptive mode control.
 */
class AudioFskDemodulator(
    var activeMode: AcousticMode = AcousticMode.DEFAULT_MODE,
    private val onAudioEnergyUpdate: ((energy: Float, snrDb: Float) -> Unit)? = null,
    private val onFecTelemetry: ((singleBitFixes: Int, doubleBitErrors: Int) -> Unit)? = null,
    private val onPacketDecoded: (Packet) -> Unit
) {
    companion object {
        const val SAMPLE_RATE = AcousticMode.SAMPLE_RATE
    }

    class AdaptiveModeController(
        var currentMode: AcousticMode = AcousticMode.DEFAULT_MODE,
        val onModeChanged: (AcousticMode) -> Unit = {}
    ) {
        private var highSnrStartTimeMs: Long = 0L
        private var lowSnrStartTimeMs: Long = 0L
        private var lastSwitchTimeMs: Long = 0L

        companion object {
            const val UPGRADE_DWELL_MS = 500L
            const val DOWNGRADE_DWELL_MS = 300L
            const val COOLDOWN_MS = 1500L

            const val SNR_UPGRADE_TO_MODE_1 = 10.0f
            const val SNR_UPGRADE_TO_MODE_2 = 16.0f

            const val SNR_DOWNGRADE_TO_MODE_1 = 13.0f
            const val SNR_DOWNGRADE_TO_MODE_0 = 8.0f
        }

        fun updateSnr(snrDb: Float, nowMs: Long = System.currentTimeMillis()) {
            if (nowMs - lastSwitchTimeMs < COOLDOWN_MS) return

            when (currentMode) {
                AcousticMode.MODE_0_BFSK -> {
                    if (snrDb >= SNR_UPGRADE_TO_MODE_1) {
                        if (highSnrStartTimeMs == 0L) highSnrStartTimeMs = nowMs
                        else if (nowMs - highSnrStartTimeMs >= UPGRADE_DWELL_MS) {
                            currentMode = AcousticMode.MODE_1_4FSK
                            lastSwitchTimeMs = nowMs
                            highSnrStartTimeMs = 0L
                            onModeChanged(currentMode)
                        }
                    } else {
                        highSnrStartTimeMs = 0L
                    }
                }
                AcousticMode.MODE_1_4FSK -> {
                    if (snrDb >= SNR_UPGRADE_TO_MODE_2) {
                        if (highSnrStartTimeMs == 0L) highSnrStartTimeMs = nowMs
                        else if (nowMs - highSnrStartTimeMs >= UPGRADE_DWELL_MS) {
                            currentMode = AcousticMode.MODE_2_8FSK
                            lastSwitchTimeMs = nowMs
                            highSnrStartTimeMs = 0L
                            onModeChanged(currentMode)
                        }
                    } else if (snrDb < SNR_DOWNGRADE_TO_MODE_0) {
                        if (lowSnrStartTimeMs == 0L) lowSnrStartTimeMs = nowMs
                        else if (nowMs - lowSnrStartTimeMs >= DOWNGRADE_DWELL_MS) {
                            currentMode = AcousticMode.MODE_0_BFSK
                            lastSwitchTimeMs = nowMs
                            lowSnrStartTimeMs = 0L
                            onModeChanged(currentMode)
                        }
                    } else {
                        highSnrStartTimeMs = 0L
                        lowSnrStartTimeMs = 0L
                    }
                }
                AcousticMode.MODE_2_8FSK -> {
                    if (snrDb < SNR_DOWNGRADE_TO_MODE_1) {
                        if (lowSnrStartTimeMs == 0L) lowSnrStartTimeMs = nowMs
                        else if (nowMs - lowSnrStartTimeMs >= DOWNGRADE_DWELL_MS) {
                            currentMode = AcousticMode.MODE_1_4FSK
                            lastSwitchTimeMs = nowMs
                            lowSnrStartTimeMs = 0L
                            onModeChanged(currentMode)
                        }
                    } else {
                        lowSnrStartTimeMs = 0L
                    }
                }
                else -> {
                    // Multi-carrier OFDM modes (Modes 3, 4, 5, 6) operate under direct PHY selection
                }
            }
        }
    }

    val adaptiveController = AdaptiveModeController(activeMode) { newMode -> setMode(newMode) }
    var isAdaptiveEnabled = true

    private var audioRecord: AudioRecord? = null
    @Volatile
    private var isRecording = false
    private var workerThread: Thread? = null

    private val synchronizer = AcousticSynchronizer()
    private val bitBuffer = ArrayList<Int>()

    // Precomputed Goertzel coefficients for active mode frequencies
    private var coeffs = computeCoeffs(activeMode)
    private var windowSize = (SAMPLE_RATE * activeMode.symbolDurationMs) / 1000

    fun setMode(mode: AcousticMode) {
        activeMode = mode
        coeffs = computeCoeffs(mode)
        windowSize = (SAMPLE_RATE * mode.symbolDurationMs) / 1000
    }

    private fun computeCoeffs(mode: AcousticMode): DoubleArray {
        return DoubleArray(mode.frequenciesHz.size) { i ->
            2.0 * cos(2.0 * PI * mode.frequenciesHz[i] / SAMPLE_RATE)
        }
    }

    fun startListening() {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBufferSize, windowSize * 4)

        val audioSource = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.MIC
        }

        try {
            audioRecord = AudioRecord(
                audioSource,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            }

            audioRecord?.startRecording()
            isRecording = true
            bitBuffer.clear()

            workerThread = Thread({ processAudioLoop() }, "AcousticDemodulatorThread").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
        } catch (_: SecurityException) {
            // Handled via permission callback in UI
        }
    }

    fun stopListening() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            workerThread?.interrupt()
            workerThread = null
        } catch (_: Exception) {}
    }

    private fun processAudioLoop() {
        val readBuffer = ShortArray(windowSize)
        while (isRecording) {
            var readCount = 0
            while (readCount < windowSize && isRecording) {
                val read = audioRecord?.read(readBuffer, readCount, windowSize - readCount) ?: -1
                if (read > 0) {
                    readCount += read
                } else {
                    break
                }
            }

            if (readCount == windowSize && isRecording) {
                demodulateBlock(readBuffer)
            }
        }
    }

    private fun demodulateBlock(samples: ShortArray) {
        val mode = activeMode
        val numFreqs = mode.frequenciesHz.size
        val energies = DoubleArray(numFreqs)
        var maxEnergy = -1.0
        var bestSym = 0
        var totalEnergy = 0.0

        for (i in 0 until numFreqs) {
            val e = goertzelEnergy(samples, coeffs[i])
            energies[i] = e
            totalEnergy += e
            if (e > maxEnergy) {
                maxEnergy = e
                bestSym = i
            }
        }

        val noiseEnergy = (totalEnergy - maxEnergy).coerceAtLeast(1.0) / (numFreqs - 1).coerceAtLeast(1)
        val snrDb = if (noiseEnergy > 0.0 && maxEnergy > 0.0) {
            (10.0 * log10(maxEnergy / noiseEnergy)).toFloat()
        } else 0f

        onAudioEnergyUpdate?.invoke(maxEnergy.toFloat(), snrDb)

        if (isAdaptiveEnabled) {
            adaptiveController.updateSnr(snrDb)
        }

        // Add bits corresponding to the detected symbol (bitsPerSymbol)
        val bits = mode.bitsPerSymbol
        for (b in bits - 1 downTo 0) {
            bitBuffer.add((bestSym shr b) and 1)
        }

        checkAndDecodePacket()
    }

    private fun goertzelEnergy(samples: ShortArray, coeff: Double): Double {
        var sPrev = 0.0
        var sPrev2 = 0.0
        for (sample in samples) {
            val s = sample.toDouble() + coeff * sPrev - sPrev2
            sPrev2 = sPrev
            sPrev = s
        }
        return sPrev * sPrev + sPrev2 * sPrev2 - coeff * sPrev * sPrev2
    }

    @Synchronized
    private fun checkAndDecodePacket() {
        val syncResult = synchronizer.findSyncIndex(bitBuffer)
        if (syncResult == null) {
            if (bitBuffer.size > 2048) {
                bitBuffer.subList(0, 512).clear()
            }
            return
        }

        val (payloadStartBit, rxModeId) = syncResult

        // 24-byte wire header is ALWAYS encoded with Hamming(8,4) into 48 bytes (384 bits)
        val encodedHeaderBits = Packet.HEADER_SIZE * 2 * 8 // 384 bits
        if (bitBuffer.size < payloadStartBit + encodedHeaderBits) return

        val encodedHeaderBytes = extractBytes(payloadStartBit, Packet.HEADER_SIZE * 2) ?: return
        val headerFec = FecCodec.decodeBytes(encodedHeaderBytes)

        if (!headerFec.isClean) {
            // Uncorrectable double-bit error in header!
            onFecTelemetry?.invoke(headerFec.singleBitCorrections, headerFec.uncorrectableErrors)
            bitBuffer.subList(0, payloadStartBit).clear()
            return
        }

        val decodedHeader = headerFec.decodedBytes
        // Bytes 14 and 15 are uint16 payloadLength
        val pLen = ((decodedHeader[14].toInt() and 0xFF) shl 8) or (decodedHeader[15].toInt() and 0xFF)
        if (pLen > 1024) {
            bitBuffer.subList(0, payloadStartBit).clear()
            return
        }

        val isMultiCarrier = rxModeId >= 3
        val encodedPayloadBytesCount = if (isMultiCarrier) pLen else pLen * 2
        val totalExpectedBits = encodedHeaderBits + encodedPayloadBytesCount * 8

        if (bitBuffer.size >= payloadStartBit + totalExpectedBits) {
            val payloadStartBitIdx = payloadStartBit + encodedHeaderBits
            val payloadEncodedBytes = extractBytes(payloadStartBitIdx, encodedPayloadBytesCount)

            if (payloadEncodedBytes != null) {
                val payloadFec = if (isMultiCarrier) {
                    FecCodec.decodePayload(payloadEncodedBytes, pLen, FecCodec.FecScheme.RATE_PASSTHROUGH)
                } else {
                    FecCodec.decodeBytes(payloadEncodedBytes)
                }

                val totalFixes = headerFec.singleBitCorrections + payloadFec.singleBitCorrections
                val totalErrors = headerFec.uncorrectableErrors + payloadFec.uncorrectableErrors
                onFecTelemetry?.invoke(totalFixes, totalErrors)

                if (!payloadFec.isClean) {
                    // Double bit or uncorrectable error: discard
                    bitBuffer.subList(0, payloadStartBit).clear()
                    return
                }

                try {
                    val fullWireBytes = ByteArray(Packet.HEADER_SIZE + pLen)
                    System.arraycopy(decodedHeader, 0, fullWireBytes, 0, Packet.HEADER_SIZE)
                    System.arraycopy(payloadFec.decodedBytes, 0, fullWireBytes, Packet.HEADER_SIZE, pLen)

                    val packet = Packet.deserialize(fullWireBytes)
                    onPacketDecoded(packet)
                    val removeUpTo = minOf(bitBuffer.size, payloadStartBit + totalExpectedBits)
                    bitBuffer.subList(0, removeUpTo).clear()
                } catch (_: Exception) {
                    bitBuffer.subList(0, payloadStartBit).clear()
                }
            }
        }
    }

    private fun extractBytes(startBit: Int, numBytes: Int): ByteArray? {
        if (bitBuffer.size < startBit + numBytes * 8) return null
        val bytes = ByteArray(numBytes)
        for (byteIdx in 0 until numBytes) {
            var b = 0
            val offset = startBit + byteIdx * 8
            for (i in 0 until 8) {
                b = (b shl 1) or bitBuffer[offset + i]
            }
            bytes[byteIdx] = b.toByte()
        }
        return bytes
    }
}
