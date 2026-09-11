package com.ghostlink.zerorf.channels.ultrasonic

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import com.ghostlink.zerorf.core.Packet
import kotlin.math.PI
import kotlin.math.cos

/**
 * AudioFskDemodulator captures PCM audio and applies Goertzel frequency energy detection.
 * Matches 18,000 Hz (Space / 0) and 19,000 Hz (Mark / 1).
 * Features a bit-level sliding correlator for the 16-bit physical preamble (0xA5, 0x5A).
 */
class AudioFskDemodulator(
    private val onAudioEnergyUpdate: ((energy: Float) -> Unit)? = null,
    private val onPacketDecoded: (Packet) -> Unit
) {
    companion object {
        const val SAMPLE_RATE = 44100
        var freqSpace = 18000.0 // Hz
        var freqMark = 19000.0  // Hz
        const val WINDOW_SIZE = (SAMPLE_RATE * 15) / 1000 // 15 ms window = 661 samples

        // 16-bit Preamble matching AudioFskModulator.PREAMBLE (0xA5, 0x5A)
        val SYNC_BITS = intArrayOf(1, 0, 1, 0, 0, 1, 0, 1, 0, 1, 0, 1, 1, 0, 1, 0)
    }

    private var audioRecord: AudioRecord? = null
    @Volatile
    private var isRecording = false
    private var workerThread: Thread? = null

    private var coeffSpace = 2.0 * cos(2.0 * PI * freqSpace / SAMPLE_RATE)
    private var coeffMark = 2.0 * cos(2.0 * PI * freqMark / SAMPLE_RATE)

    private val bitBuffer = ArrayList<Int>()

    fun setFrequencies(space: Double, mark: Double) {
        freqSpace = space
        freqMark = mark
        coeffSpace = 2.0 * cos(2.0 * PI * freqSpace / SAMPLE_RATE)
        coeffMark = 2.0 * cos(2.0 * PI * freqMark / SAMPLE_RATE)
    }

    fun startListening() {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBufferSize, WINDOW_SIZE * 4)

        // Try UNPROCESSED first (disables noise suppression filters that kill ultrasound), then VOICE_RECOGNITION, then MIC
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
            val audioBuffer = ShortArray(WINDOW_SIZE)
            while (isRecording) {
                val read = audioRecord?.read(audioBuffer, 0, WINDOW_SIZE) ?: 0
                if (read == WINDOW_SIZE) {
                    val energySpace = goertzelEnergy(audioBuffer, coeffSpace)
                    val energyMark = goertzelEnergy(audioBuffer, coeffMark)
                    val totalEnergy = (energySpace + energyMark).toFloat()

                    onAudioEnergyUpdate?.invoke(totalEnergy)

                    // Relative decision with minimum noise floor threshold
                    val noiseFloor = 1.0e5
                    if (energySpace > noiseFloor || energyMark > noiseFloor) {
                        val bit = if (energyMark > energySpace) 1 else 0
                        processBit(bit)
                    }
                }
            }
        }.apply { start() }
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
    private fun processBit(bit: Int) {
        bitBuffer.add(bit)

        // Only search for packets when buffer has at least preamble + 14-byte header
        if (bitBuffer.size >= SYNC_BITS.size + Packet.HEADER_SIZE * 8) {
            val syncIdx = findSyncPreamble()
            if (syncIdx != -1) {
                val startBit = syncIdx + SYNC_BITS.size
                val availableBits = bitBuffer.size - startBit
                if (availableBits >= Packet.HEADER_SIZE * 8) {
                    // Extract candidate 14-byte header
                    val headerBytes = extractBytes(startBit, Packet.HEADER_SIZE)
                    if (headerBytes != null) {
                        // In 14-byte wire frame: bytes 8 and 9 are uint16 payloadLength
                        val pLen = ((headerBytes[8].toInt() and 0xFF) shl 8) or (headerBytes[9].toInt() and 0xFF)
                        val totalExpectedBits = (Packet.HEADER_SIZE + pLen) * 8

                        if (availableBits >= totalExpectedBits) {
                            val fullPacketBytes = extractBytes(startBit, Packet.HEADER_SIZE + pLen)
                            if (fullPacketBytes != null) {
                                try {
                                    val packet = Packet.deserialize(fullPacketBytes)
                                    onPacketDecoded(packet)
                                    // Successfully decoded packet, advance buffer past this packet
                                    val removeUpTo = minOf(bitBuffer.size, startBit + totalExpectedBits)
                                    bitBuffer.subList(0, removeUpTo).clear()
                                    return
                                } catch (_: Exception) {
                                    // Corrupt or false sync, advance past this sync index
                                    bitBuffer.subList(0, syncIdx + 1).clear()
                                }
                            }
                        }
                    }
                }
            }
        }

        // Bounded ring buffer: prevent unbounded growth
        if (bitBuffer.size > 2048) {
            bitBuffer.subList(0, 512).clear()
        }
    }

    private fun findSyncPreamble(): Int {
        val limit = bitBuffer.size - SYNC_BITS.size
        for (i in 0..limit) {
            var match = true
            for (j in SYNC_BITS.indices) {
                if (bitBuffer[i + j] != SYNC_BITS[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
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
