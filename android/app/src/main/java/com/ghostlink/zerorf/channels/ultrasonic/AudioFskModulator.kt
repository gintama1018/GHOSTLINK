package com.ghostlink.zerorf.channels.ultrasonic

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.ghostlink.zerorf.channels.ultrasonic.modem.AcousticFramer
import com.ghostlink.zerorf.channels.ultrasonic.modem.AcousticMode
import com.ghostlink.zerorf.core.Packet
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * High-performance, crash-free Acoustic Modem Modulator.
 * Supports multi-mode FSK (BFSK, 4-FSK, 8-FSK) with Hanning window pulse smoothing.
 */
class AudioFskModulator(
    var activeMode: AcousticMode = AcousticMode.DEFAULT_MODE
) {
    companion object {
        const val SAMPLE_RATE = AcousticMode.SAMPLE_RATE
        const val RAMP_DURATION_MS = 2.0
    }

    private var audioTrack: AudioTrack? = null
    @Volatile
    private var isPlaying = false

    /**
     * Synthesizes 16-bit PCM samples for a packet under activeMode.
     */
    fun synthesizePcm(packet: Packet): ShortArray {
        val frameBytes = AcousticFramer.framePacket(packet, activeMode)
        val mode = activeMode
        val symbolDurationMs = mode.symbolDurationMs
        val samplesPerSymbol = (SAMPLE_RATE * symbolDurationMs) / 1000
        val rampSamples = (SAMPLE_RATE * RAMP_DURATION_MS / 1000).toInt()
        val bitsPerSymbol = mode.bitsPerSymbol
        val freqs = mode.frequenciesHz

        val totalBits = frameBytes.size * 8
        val totalSymbols = (totalBits + bitsPerSymbol - 1) / bitsPerSymbol
        val totalSamples = totalSymbols * samplesPerSymbol
        val pcm = ShortArray(totalSamples)

        var sampleIdx = 0
        var phase = 0.0

        // Extract symbols (bitsPerSymbol bits per step)
        var bitOffset = 0
        for (sIdx in 0 until totalSymbols) {
            var symbolValue = 0
            for (b in 0 until bitsPerSymbol) {
                val currentBitIdx = bitOffset + b
                val byteIdx = currentBitIdx / 8
                val bitInByte = 7 - (currentBitIdx % 8)
                val bit = if (byteIdx < frameBytes.size) {
                    ((frameBytes[byteIdx].toInt() and 0xFF) shr bitInByte) and 1
                } else 0
                symbolValue = (symbolValue shl 1) or bit
            }
            bitOffset += bitsPerSymbol

            val targetFreq = freqs[symbolValue.coerceIn(0, freqs.size - 1)]
            val phaseIncrement = 2.0 * PI * targetFreq / SAMPLE_RATE

            for (s in 0 until samplesPerSymbol) {
                var envelope = 1.0
                if (s < rampSamples) {
                    envelope = 0.5 * (1.0 - cos(PI * s / rampSamples))
                } else if (s > samplesPerSymbol - rampSamples) {
                    val remaining = samplesPerSymbol - s
                    envelope = 0.5 * (1.0 - cos(PI * remaining / rampSamples))
                }

                val sampleVal = (sin(phase) * envelope * 28000.0).toInt().coerceIn(-32768, 32767)
                if (sampleIdx < pcm.size) {
                    pcm[sampleIdx++] = sampleVal.toShort()
                }
                phase += phaseIncrement
                if (phase > 2.0 * PI) phase -= 2.0 * PI
            }
        }
        return pcm
    }

    @Synchronized
    private fun getOrCreateAudioTrack(minPcmSize: Int): AudioTrack {
        val existing = audioTrack
        if (existing != null && existing.state == AudioTrack.STATE_INITIALIZED) {
            return existing
        }

        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBufferSize, minPcmSize * 2)

        val track = AudioTrack.Builder()
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

        audioTrack = track
        track.play()
        return track
    }

    /**
     * Plays tone burst synchronously and yields execution until the frame completes.
     */
    fun playPacket(packet: Packet) {
        val pcm = synthesizePcm(packet)
        isPlaying = true

        try {
            val track = getOrCreateAudioTrack(pcm.size)
            track.write(pcm, 0, pcm.size)

            val durationMs = (pcm.size * 1000L) / SAMPLE_RATE
            Thread.sleep(durationMs + 100L) // Packet duration + 100ms guard interval
        } catch (_: InterruptedException) {
            // Cancelled
        } catch (_: Exception) {}
    }

    fun stop() {
        isPlaying = false
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
    }
}
