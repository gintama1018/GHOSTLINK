package com.ghostlink.zerorf.channels.ultrasonic

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.ghostlink.zerorf.core.Packet
import kotlin.math.PI
import kotlin.math.cos

/**
 * AudioFskDemodulator captures PCM audio from the microphone and applies
 * Goertzel filter energy detection at 18,500 Hz (Space) and 19,500 Hz (Mark).
 */
class AudioFskDemodulator(
    private val onPacketDecoded: (Packet) -> Unit
) {
    companion object {
        const val SAMPLE_RATE = 44100
        const val FREQ_SPACE = 18500.0
        const val FREQ_MARK = 19500.0
        const val WINDOW_SIZE = (SAMPLE_RATE * 15) / 1000 // 15 ms window (661 samples)
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var workerThread: Thread? = null

    // Goertzel coefficients
    private val coeffSpace = 2.0 * cos(2.0 * PI * FREQ_SPACE / SAMPLE_RATE)
    private val coeffMark = 2.0 * cos(2.0 * PI * FREQ_MARK / SAMPLE_RATE)

    private val bitBuffer = ArrayList<Int>()

    fun startListening() {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBufferSize, WINDOW_SIZE * 4)
        )

        isRecording = true
        audioRecord?.startRecording()

        workerThread = Thread {
            val audioBuffer = ShortArray(WINDOW_SIZE)
            while (isRecording) {
                val read = audioRecord?.read(audioBuffer, 0, WINDOW_SIZE) ?: 0
                if (read == WINDOW_SIZE) {
                    val energySpace = goertzelEnergy(audioBuffer, coeffSpace)
                    val energyMark = goertzelEnergy(audioBuffer, coeffMark)

                    // Check if energy exceeds ambient noise floor
                    val threshold = 1.5e8
                    if (energySpace > threshold || energyMark > threshold) {
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

    private fun processBit(bit: Int) {
        bitBuffer.add(bit)
        if (bitBuffer.size >= Packet.HEADER_SIZE * 8) {
            // Check if buffer contains a valid packet
            tryParsePacket()
        }
        // Cap buffer to avoid unbounded growth
        if (bitBuffer.size > 2048) {
            bitBuffer.subList(0, 1024).clear()
        }
    }

    private fun tryParsePacket() {
        val byteCount = bitBuffer.size / 8
        if (byteCount < Packet.HEADER_SIZE) return

        val bytes = ByteArray(byteCount)
        for (i in 0 until byteCount) {
            var b = 0
            for (j in 0 until 8) {
                b = (b shl 1) or bitBuffer[i * 8 + j]
            }
            bytes[i] = b.toByte()
        }

        // Sliding scan for valid packet
        for (offset in 0..bytes.size - Packet.HEADER_SIZE) {
            val candidate = bytes.copyOfRange(offset, bytes.size)
            try {
                val packet = Packet.deserialize(candidate)
                onPacketDecoded(packet)
                bitBuffer.clear()
                return
            } catch (_: Exception) {
                // Keep sliding
            }
        }
    }

    fun stopListening() {
        isRecording = false
        workerThread?.interrupt()
        workerThread = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }
}
