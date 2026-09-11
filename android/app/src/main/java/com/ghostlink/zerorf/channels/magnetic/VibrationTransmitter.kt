package com.ghostlink.zerorf.channels.magnetic

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * VibrationTransmitter pulses the phone's vibration motor to transmit the 8-byte handshake packet.
 * Pulse modulation scheme:
 * - Bit 0: 60 ms ON, 60 ms OFF
 * - Bit 1: 120 ms ON, 60 ms OFF
 */
class VibrationTransmitter(private val context: Context) {

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    /**
     * Converts an 8-byte MagneticPacket into an array of millisecond timings for VibrationEffect.
     */
    fun buildPulseWaveform(packet: MagneticPacket): Pair<LongArray, IntArray> {
        val serialized = packet.serialize()
        // Waveform starts with 0 ms delay
        val timings = ArrayList<Long>()
        val amplitudes = ArrayList<Int>()

        timings.add(0L)
        amplitudes.add(0)

        // Lead-in sync pulse (150 ms ON, 100 ms OFF) to alert receiver
        timings.add(150L)
        amplitudes.add(255)
        timings.add(100L)
        amplitudes.add(0)

        for (byte in serialized) {
            for (bitIdx in 7 downTo 0) {
                val bit = ((byte.toInt() and 0xFF) shr bitIdx) and 1
                if (bit == 1) {
                    timings.add(120L) // 120ms ON
                    amplitudes.add(255)
                    timings.add(60L)  // 60ms OFF
                    amplitudes.add(0)
                } else {
                    timings.add(60L)  // 60ms ON
                    amplitudes.add(255)
                    timings.add(60L)  // 60ms OFF
                    amplitudes.add(0)
                }
            }
        }

        return Pair(timings.toLongArray(), amplitudes.toIntArray())
    }

    /**
     * Transmits the 8-byte handshake packet via vibration pulses.
     */
    fun transmit(packet: MagneticPacket) {
        val (timings, amplitudes) = buildPulseWaveform(packet)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(timings, -1)
        }
    }

    fun stop() {
        vibrator.cancel()
    }
}
