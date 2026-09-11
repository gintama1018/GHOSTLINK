package com.ghostlink.zerorf.channels.ultrasonic

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.ghostlink.zerorf.core.Packet
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Synthesizes near-ultrasonic BFSK audio tones for fallback bulk transfer.
 * Frequencies:
 * - Bit 0 (Space): 18,500 Hz
 * - Bit 1 (Mark): 19,500 Hz
 * Symbol duration: 15 ms with 2.5 ms Hanning window pulse smoothing to prevent audible clicks.
 */
class AudioFskModulator {

    companion object {
        const val SAMPLE_RATE = 44100
        const val FREQ_SPACE = 18500.0 // Hz (Bit 0)
        const val FREQ_MARK = 19500.0  // Hz (Bit 1)
        const val SYMBOL_DURATION_MS = 15 // ms per symbol (nominal ~67 bps)
        const val SAMPLES_PER_SYMBOL = (SAMPLE_RATE * SYMBOL_DURATION_MS) / 1000
        const val RAMP_SAMPLES = (SAMPLE_RATE * 2.5 / 1000).toInt() // 2.5 ms taper
    }

    private var audioTrack: AudioTrack? = null
    private var isPlaying = false

    /**
     * Synthesizes 16-bit PCM audio samples for a packet's binary wire data.
     */
    fun synthesizePcm(packet: Packet): ShortArray {
        val bytes = packet.serialize()
        val totalBits = bytes.size * 8
        val totalSamples = totalBits * SAMPLES_PER_SYMBOL
        val pcm = ShortArray(totalSamples)

        var sampleIdx = 0
        var phase = 0.0

        for (b in bytes) {
            for (bitOffset in 7 downTo 0) {
                val bit = ((b.toInt() and 0xFF) shr bitOffset) and 1
                val targetFreq = if (bit == 1) FREQ_MARK else FREQ_SPACE
                val phaseIncrement = 2.0 * PI * targetFreq / SAMPLE_RATE

                for (s in 0 until SAMPLES_PER_SYMBOL) {
                    // Hanning window smoothing on edges to prevent clicks
                    var envelope = 1.0
                    if (s < RAMP_SAMPLES) {
                        envelope = 0.5 * (1.0 - cos(PI * s / RAMP_SAMPLES))
                    } else if (s > SAMPLES_PER_SYMBOL - RAMP_SAMPLES) {
                        val remaining = SAMPLES_PER_SYMBOL - s
                        envelope = 0.5 * (1.0 - cos(PI * remaining / RAMP_SAMPLES))
                    }

                    val sampleVal = (sin(phase) * envelope * 30000.0).toInt().coerceIn(-32768, 32767)
                    pcm[sampleIdx++] = sampleVal.toShort()
                    phase += phaseIncrement
                    if (phase > 2.0 * PI) phase -= 2.0 * PI
                }
            }
        }
        return pcm
    }

    fun playPacket(packet: Packet) {
        val pcm = synthesizePcm(packet)
        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBufferSize, pcm.size * 2)

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        isPlaying = true
        audioTrack?.play()
        audioTrack?.write(pcm, 0, pcm.size)
    }

    fun stop() {
        isPlaying = false
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }
}
