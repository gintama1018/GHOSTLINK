package com.ghostlink.zerorf.channels.ultrasonic

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import com.ghostlink.zerorf.channels.ultrasonic.modem.AcousticFramer
import com.ghostlink.zerorf.channels.ultrasonic.modem.AcousticMode
import com.ghostlink.zerorf.channels.ultrasonic.modem.AcousticSynchronizer
import com.ghostlink.zerorf.core.Packet
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10

/**
 * Adaptive Multi-Mode Acoustic Demodulator.
 * Utilizes a multi-bin Goertzel filter bank, Barker-13 cross-correlation synchronization,
 * and empirical SNR estimation.
 */
class AudioFskDemodulator(
    var activeMode: AcousticMode = AcousticMode.DEFAULT_MODE,
    private val onAudioEnergyUpdate: ((energy: Float, snrDb: Float) -> Unit)? = null,
    private val onPacketDecoded: (Packet) -> Unit
) {
    companion object {
        const val SAMPLE_RATE = AcousticMode.SAMPLE_RATE
    }

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

        val sources = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            intArrayOf(
                MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
            )
        } else {
            intArrayOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
            )
        }

        var record: AudioRecord? = null
        for (src in sources) {
            try {
                val candidate = AudioRecord(
                    src,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    record = candidate
                    break
                } else {
                    candidate.release()
                }
            } catch (_: Exception) {}
        }

        if (record == null) return
        audioRecord = record

        isRecording = true
        synchronized(bitBuffer) { bitBuffer.clear() }
        record.startRecording()

        workerThread = Thread {
            val audioBuffer = ShortArray(windowSize)
            while (isRecording) {
                val read = audioRecord?.read(audioBuffer, 0, windowSize) ?: 0
                if (read == windowSize) {
                    processAudioWindow(audioBuffer)
                }
            }
        }.apply { start() }
    }

    private fun processAudioWindow(samples: ShortArray) {
        val currentCoeffs = coeffs
        val energies = DoubleArray(currentCoeffs.size) { i ->
            goertzelEnergy(samples, currentCoeffs[i])
        }

        var maxEnergy = 0.0
        var maxIdx = 0
        var totalEnergy = 0.0
        for (i in energies.indices) {
            val e = energies[i]
            totalEnergy += e
            if (e > maxEnergy) {
                maxEnergy = e
                maxIdx = i
            }
        }

        val noiseFloor = 1.0e5
        val avgNoise = if (energies.size > 1) (totalEnergy - maxEnergy) / (energies.size - 1) else noiseFloor
        val snrDb = if (avgNoise > 0 && maxEnergy > avgNoise) {
            (10.0 * log10(maxEnergy / avgNoise)).toFloat()
        } else 0f

        onAudioEnergyUpdate?.invoke(totalEnergy.toFloat(), snrDb)

        if (maxEnergy > noiseFloor && snrDb >= 2.0f) {
            // Demodulate symbol value (0 until 2^bitsPerSymbol)
            val bitsPerSymbol = activeMode.bitsPerSymbol
            for (b in (bitsPerSymbol - 1) downTo 0) {
                val bit = (maxIdx shr b) and 1
                synchronized(bitBuffer) {
                    bitBuffer.add(bit)
                }
            }
            checkAndDecodePacket()
        }
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
        val syncIdx = synchronizer.findSyncIndex(bitBuffer)
        if (syncIdx == -1) {
            if (bitBuffer.size > 2048) {
                bitBuffer.subList(0, 512).clear()
            }
            return
        }

        val payloadStartBit = syncIdx + synchronizer.syncLengthBits
        // Read 1-byte modeId (8 bits)
        if (bitBuffer.size < payloadStartBit + 8) return

        val modeByte = extractByte(payloadStartBit)
        if (modeByte.toInt() !in 0..2) return
        val packetStartBit = payloadStartBit + 8

        // Wire format v2 header is 24 bytes (192 bits)
        if (bitBuffer.size < packetStartBit + Packet.HEADER_SIZE * 8) return

        val headerBytes = extractBytes(packetStartBit, Packet.HEADER_SIZE) ?: return

        // In 24-byte wire frame: bytes 14 and 15 are uint16 payloadLength
        val pLen = ((headerBytes[14].toInt() and 0xFF) shl 8) or (headerBytes[15].toInt() and 0xFF)
        if (pLen > 1024) {
            // Malformed length, discard false sync
            bitBuffer.subList(0, syncIdx + 1).clear()
            return
        }

        val totalExpectedBits = (Packet.HEADER_SIZE + pLen) * 8
        if (bitBuffer.size >= packetStartBit + totalExpectedBits) {
            val fullPacketBytes = extractBytes(packetStartBit, Packet.HEADER_SIZE + pLen)
            if (fullPacketBytes != null) {
                try {
                    val packet = Packet.deserialize(fullPacketBytes)
                    onPacketDecoded(packet)
                    val removeUpTo = minOf(bitBuffer.size, packetStartBit + totalExpectedBits)
                    bitBuffer.subList(0, removeUpTo).clear()
                } catch (_: Exception) {
                    // CRC or framing error, advance past this sync
                    bitBuffer.subList(0, syncIdx + 1).clear()
                }
            }
        }
    }

    private fun extractByte(startBit: Int): Int {
        var b = 0
        for (i in 0 until 8) {
            b = (b shl 1) or bitBuffer[startBit + i]
        }
        return b
    }

    private fun extractBytes(startBit: Int, numBytes: Int): ByteArray? {
        if (bitBuffer.size < startBit + numBytes * 8) return null
        val bytes = ByteArray(numBytes)
        for (byteIdx in 0 until numBytes) {
            var b = 0
            for (bitOffset in 0 until 8) {
                b = (b shl 1) or bitBuffer[startBit + byteIdx * 8 + bitOffset]
            }
            bytes[byteIdx] = b.toByte()
        }
        return bytes
    }

    fun stopListening() {
        isRecording = false
        workerThread?.interrupt()
        workerThread = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        synchronized(bitBuffer) { bitBuffer.clear() }
    }
}
