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
     * Supports both single-carrier FSK and multi-carrier OFDM synthesis.
     */
    fun synthesizePcm(packet: Packet): ShortArray {
        val frameBytes = AcousticFramer.framePacket(packet, activeMode)
        val mode = activeMode

        if (mode.isMultiCarrier) {
            return synthesizeOfdmPcm(frameBytes, mode)
        } else {
            return synthesizeFskPcm(frameBytes, mode)
        }
    }

    private fun synthesizeFskPcm(frameBytes: ByteArray, mode: AcousticMode): ShortArray {
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

    private fun synthesizeOfdmPcm(frameBytes: ByteArray, mode: AcousticMode): ShortArray {
        val phy = com.ghostlink.zerorf.channels.ultrasonic.modem.AcousticMultiCarrierPhy(mode)
        val bitList = ArrayList<Int>(frameBytes.size * 8)
        for (b in frameBytes) {
            val v = b.toInt() and 0xFF
            for (i in 7 downTo 0) {
                bitList.add((v shr i) and 1)
            }
        }

        val bitsPerOfdm = phy.bitsPerOfdmSymbol
        val totalOfdmSymbols = (bitList.size + bitsPerOfdm - 1) / bitsPerOfdm
        val totalSamples = totalOfdmSymbols * phy.totalSymbolSamples
        val pcm = ShortArray(totalSamples)

        var pcmOffset = 0
        var bitOffset = 0
        for (symIdx in 0 until totalOfdmSymbols) {
            val complexSymbols = phy.mapBitsToSymbols(bitList, bitOffset)
            bitOffset += bitsPerOfdm

            val ofdmSamples = phy.synthesizeOfdmSymbol(complexSymbols)
            System.arraycopy(ofdmSamples, 0, pcm, pcmOffset, ofdmSamples.size)
            pcmOffset += ofdmSamples.size
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
        val bufferSize = maxOf(minBufferSize, minPcmSize * 4)

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
     * Streams a packet into the active AudioTrack buffer without introducing dead silence.
     * AudioTrack in MODE_STREAM buffers samples and plays them seamlessly back-to-back.
     */
    fun streamPacket(packet: Packet) {
        val pcm = synthesizePcm(packet)
        isPlaying = true

        try {
            val track = getOrCreateAudioTrack(pcm.size)
            // Blocking write into audio track hardware buffer - no artificial Thread.sleep!
            track.write(pcm, 0, pcm.size)
        } catch (_: Exception) {}
    }

    /**
     * Backwards-compatible synchronous packet player without the artificial 100ms sleep.
     */
    fun playPacket(packet: Packet) {
        streamPacket(packet)
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
