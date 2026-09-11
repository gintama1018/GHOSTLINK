package com.ghostlink.zerorf.channels.ultrasonic.modem

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Configurable Acoustic Multi-Carrier (OFDM) Physical Layer Engine.
 *
 * Implements:
 * 1. Orthogonal Subcarrier Synthesis (8, 16, 32, 64 subcarriers)
 * 2. QPSK (2 bits/sym) and 16-QAM (4 bits/sym) Constellation Mapping
 * 3. Matched Cyclic Prefix (CP) for direct multipath echo immunity (T_CP >= tau_max)
 * 4. Pilot-aided Channel Estimation & Zero-Forcing Frequency Equalization (FEQ)
 * 5. Constellation Decision Slicer
 */
class AcousticMultiCarrierPhy(
    val mode: AcousticMode = AcousticMode.MODE_3_OFDM_16_QPSK,
    val cyclicPrefixMs: Double = 1.0 // Matched to channel delay spread
) {
    companion object {
        const val SAMPLE_RATE = AcousticMode.SAMPLE_RATE
        val SQRT_2 = sqrt(2.0)
        val SQRT_10 = sqrt(10.0)

        // Known pilot symbols for channel estimation (+1 + 1j)
        val PILOT_I = 1.0 / SQRT_2
        val PILOT_Q = 1.0 / SQRT_2
    }

    val numSubcarriers: Int = mode.frequenciesHz.size
    val subcarrierFreqs: DoubleArray = mode.frequenciesHz
    val bitsPerSubcarrier: Int = if (mode.bitsPerSymbol / numSubcarriers >= 4) 4 else 2

    val fftDurationMs: Double = mode.symbolDurationMs.toDouble() - cyclicPrefixMs
    val fftSamples: Int = ((SAMPLE_RATE * fftDurationMs) / 1000.0).toInt().coerceAtLeast(64)
    val cpSamples: Int = ((SAMPLE_RATE * cyclicPrefixMs) / 1000.0).toInt().coerceAtLeast(16)
    val totalSymbolSamples: Int = fftSamples + cpSamples

    // Pilot subcarrier allocation (e.g. 2 pilots for 16 carriers, 4 for 32 carriers)
    val pilotIndices: Set<Int> = when {
        numSubcarriers >= 32 -> setOf(2, 10, 18, 26)
        numSubcarriers >= 16 -> setOf(2, 13)
        else -> setOf(1)
    }

    val dataSubcarrierCount: Int = numSubcarriers - pilotIndices.size
    val bitsPerOfdmSymbol: Int = dataSubcarrierCount * bitsPerSubcarrier

    data class Complex(val re: Double, val im: Double) {
        operator fun plus(o: Complex) = Complex(re + o.re, im + o.im)
        operator fun minus(o: Complex) = Complex(re - o.re, im - o.im)
        operator fun times(o: Complex) = Complex(re * o.re - im * o.im, re * o.im + im * o.re)
        operator fun div(o: Complex): Complex {
            val denom = o.re * o.re + o.im * o.im + 1e-9
            return Complex((re * o.re + im * o.im) / denom, (im * o.re - re * o.im) / denom)
        }
        val magSq: Double get() = re * re + im * im
    }

    /**
     * Maps raw bits to complex symbols for all subcarriers (inserting pilots at designated indices).
     */
    fun mapBitsToSymbols(bitBuffer: List<Int>, bitOffset: Int): Array<Complex> {
        val symbols = Array(numSubcarriers) { Complex(0.0, 0.0) }
        var currentBitIdx = bitOffset

        for (k in 0 until numSubcarriers) {
            if (k in pilotIndices) {
                // Fixed pilot symbol for channel estimation
                symbols[k] = Complex(PILOT_I, PILOT_Q)
            } else {
                if (bitsPerSubcarrier == 4) {
                    // 16-QAM mapping (Gray coded)
                    val b0 = if (currentBitIdx < bitBuffer.size) bitBuffer[currentBitIdx++] else 0
                    val b1 = if (currentBitIdx < bitBuffer.size) bitBuffer[currentBitIdx++] else 0
                    val b2 = if (currentBitIdx < bitBuffer.size) bitBuffer[currentBitIdx++] else 0
                    val b3 = if (currentBitIdx < bitBuffer.size) bitBuffer[currentBitIdx++] else 0

                    val iVal = qamLevel(b0, b1) / SQRT_10
                    val qVal = qamLevel(b2, b3) / SQRT_10
                    symbols[k] = Complex(iVal, qVal)
                } else {
                    // QPSK mapping
                    val b0 = if (currentBitIdx < bitBuffer.size) bitBuffer[currentBitIdx++] else 0
                    val b1 = if (currentBitIdx < bitBuffer.size) bitBuffer[currentBitIdx++] else 0

                    val iVal = (if (b0 == 1) 1.0 else -1.0) / SQRT_2
                    val qVal = (if (b1 == 1) 1.0 else -1.0) / SQRT_2
                    symbols[k] = Complex(iVal, qVal)
                }
            }
        }
        return symbols
    }

    private fun qamLevel(b0: Int, b1: Int): Double {
        return when ((b0 shl 1) or b1) {
            0 -> -3.0
            1 -> -1.0
            2 -> 3.0
            else -> 1.0
        }
    }

    /**
     * Synthesizes time-domain PCM samples for one OFDM symbol with Cyclic Prefix.
     */
    fun synthesizeOfdmSymbol(symbols: Array<Complex>): ShortArray {
        val out = ShortArray(totalSymbolSamples)
        val fftBlock = DoubleArray(fftSamples)

        // IFFT / Subcarrier multi-tone synthesis
        val norm = 28000.0 / sqrt(numSubcarriers.toDouble())
        for (n in 0 until fftSamples) {
            var sample = 0.0
            for (k in 0 until numSubcarriers) {
                val freq = subcarrierFreqs[k]
                val angle = 2.0 * PI * freq * n / SAMPLE_RATE
                val cosVal = cos(angle)
                val sinVal = sin(angle)
                // s[n] = Re{ X[k] * e^(j 2pi fk n / Fs) } = I*cos - Q*sin
                sample += symbols[k].re * cosVal - symbols[k].im * sinVal
            }
            fftBlock[n] = sample * norm
        }

        // 1. Copy last cpSamples as Cyclic Prefix to the beginning
        val cpStartInFft = fftSamples - cpSamples
        for (i in 0 until cpSamples) {
            val sVal = fftBlock[cpStartInFft + i].toInt().coerceIn(-32768, 32767)
            out[i] = sVal.toShort()
        }

        // 2. Copy the useful FFT block
        for (i in 0 until fftSamples) {
            val sVal = fftBlock[i].toInt().coerceIn(-32768, 32767)
            out[cpSamples + i] = sVal.toShort()
        }

        return out
    }

    /**
     * Demodulates one OFDM block: strips CP, correlates orthogonal subcarriers,
     * performs pilot-aided channel estimation and Zero-Forcing equalization, and slices bits.
     */
    fun demodulateOfdmBlock(samples: ShortArray, offset: Int): List<Int> {
        if (offset + totalSymbolSamples > samples.size) return emptyList()

        // 1. Strip Cyclic Prefix (skip first cpSamples)
        val usefulStart = offset + cpSamples

        // 2. Extract received complex subcarriers Y[k] over the FFT window
        val rxSymbols = Array(numSubcarriers) { Complex(0.0, 0.0) }
        val norm = 2.0 / (fftSamples * 28000.0 / sqrt(numSubcarriers.toDouble()))

        for (k in 0 until numSubcarriers) {
            var sumRe = 0.0
            var sumIm = 0.0
            val freq = subcarrierFreqs[k]
            for (n in 0 until fftSamples) {
                val s = samples[usefulStart + n].toDouble()
                val angle = 2.0 * PI * freq * n / SAMPLE_RATE
                // Correlation with e^(-j 2pi fk n / Fs)
                sumRe += s * cos(angle)
                sumIm -= s * sin(angle)
            }
            rxSymbols[k] = Complex(sumRe * norm, sumIm * norm)
        }

        // 3. Pilot-aided Channel Estimation
        // Estimate H at pilot subcarriers: H[p] = Y[p] / X_pilot[p]
        var avgHRe = 0.0
        var avgHIm = 0.0
        val pilotRef = Complex(PILOT_I, PILOT_Q)

        for (p in pilotIndices) {
            val h = rxSymbols[p] / pilotRef
            avgHRe += h.re
            avgHIm += h.im
        }
        avgHRe /= pilotIndices.size.coerceAtLeast(1)
        avgHIm /= pilotIndices.size.coerceAtLeast(1)
        val channelEst = Complex(avgHRe, avgHIm)

        // 4. Frequency-Domain Equalization (Zero-Forcing) and Constellation Slicing
        val decodedBits = ArrayList<Int>()
        for (k in 0 until numSubcarriers) {
            if (k in pilotIndices) continue // Skip pilot symbols

            // Equalize: X_hat[k] = Y[k] / H_hat
            val eqSymbol = if (channelEst.magSq > 1e-4) {
                rxSymbols[k] / channelEst
            } else {
                rxSymbols[k]
            }

            if (bitsPerSubcarrier == 4) {
                // 16-QAM Slicing
                val normI = eqSymbol.re * SQRT_10
                val normQ = eqSymbol.im * SQRT_10
                val (b0, b1) = sliceQamLevel(normI)
                val (b2, b3) = sliceQamLevel(normQ)
                decodedBits.add(b0)
                decodedBits.add(b1)
                decodedBits.add(b2)
                decodedBits.add(b3)
            } else {
                // QPSK Slicing
                decodedBits.add(if (eqSymbol.re >= 0) 1 else 0)
                decodedBits.add(if (eqSymbol.im >= 0) 1 else 0)
            }
        }
        return decodedBits
    }

    private fun sliceQamLevel(level: Double): Pair<Int, Int> {
        return when {
            level < -2.0 -> Pair(0, 0)
            level < 0.0 -> Pair(0, 1)
            level < 2.0 -> Pair(1, 1)
            else -> Pair(1, 0)
        }
    }
}
