package com.ghostlink.zerorf.channels.magnetic

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * MagnetometerReceiver listens to the phone's magnetometer at SENSOR_DELAY_FASTEST (~50-100Hz),
 * cancels baseline drift, detects pulse edges via Schmitt trigger hysteresis,
 * and decodes the 8-byte MagneticPacket.
 */
class MagnetometerReceiver(
    private val context: Context,
    private val onPacketReceived: (MagneticPacket) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    // Signal processing states
    private var lastRawMagnitude = 0.0
    private var filteredValue = 0.0
    private val alpha = 0.85 // High-pass baseline cancellation factor

    // Schmitt trigger hysteresis thresholds (in microTesla above baseline)
    private val highThreshold = 6.0 // uT
    private val lowThreshold = 2.5  // uT
    private var isHigh = false
    private var pulseStartTimeMs = 0L

    // Bit decoding state
    private val receivedBits = ArrayList<Int>()
    private var listening = false

    fun startListening() {
        if (magnetometer != null) {
            listening = true
            receivedBits.clear()
            sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_FASTEST)
        }
    }

    fun stopListening() {
        listening = false
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!listening || event == null || event.sensor.type != Sensor.TYPE_MAGNETIC_FIELD) return

        val bx = event.values[0].toDouble()
        val by = event.values[1].toDouble()
        val bz = event.values[2].toDouble()
        val rawMag = sqrt(bx * bx + by * by + bz * bz)

        if (lastRawMagnitude == 0.0) {
            lastRawMagnitude = rawMag
            return
        }

        // High-pass filter: y[n] = alpha * (y[n-1] + x[n] - x[n-1])
        filteredValue = alpha * (filteredValue + rawMag - lastRawMagnitude)
        lastRawMagnitude = rawMag

        val currentTimeMs = System.currentTimeMillis()
        val absDelta = Math.abs(filteredValue)

        // Schmitt trigger
        if (!isHigh && absDelta >= highThreshold) {
            // Rising edge (pulse start)
            isHigh = true
            pulseStartTimeMs = currentTimeMs
        } else if (isHigh && absDelta <= lowThreshold) {
            // Falling edge (pulse end)
            isHigh = false
            val pulseDuration = currentTimeMs - pulseStartTimeMs

            // Ignore spurious glitch pulses < 35ms
            if (pulseDuration >= 35) {
                // Classify bit duration:
                // Nominal bit 0 is 60ms (accept 35ms to 85ms)
                // Nominal bit 1 is 120ms (accept 86ms to 180ms)
                val bit = if (pulseDuration > 85) 1 else 0
                handleDecodedBit(bit)
            }
        }
    }

    private fun handleDecodedBit(bit: Int) {
        receivedBits.add(bit)

        // Check if we have received a multiple of 8 bits and at least 64 bits (8 bytes)
        if (receivedBits.size >= 64) {
            // Look for sync byte 0xA5 (10100101b) in the stream
            val syncBits = listOf(1, 0, 1, 0, 0, 1, 0, 1)
            val syncIdx = findSubsequence(receivedBits, syncBits)

            if (syncIdx != -1 && receivedBits.size >= syncIdx + 64) {
                val packetBits = receivedBits.subList(syncIdx, syncIdx + 64)
                val bytes = ByteArray(8)
                for (byteIdx in 0 until 8) {
                    var b = 0
                    for (bitOffset in 0 until 8) {
                        b = (b shl 1) or packetBits[byteIdx * 8 + bitOffset]
                    }
                    bytes[byteIdx] = b.toByte()
                }

                try {
                    val packet = MagneticPacket.deserialize(bytes)
                    onPacketReceived(packet)
                    receivedBits.clear()
                } catch (e: Exception) {
                    // CRC failed or noise, drop candidate sync and keep scanning
                    receivedBits.removeAt(syncIdx)
                }
            }
        }
    }

    private fun findSubsequence(list: List<Int>, sub: List<Int>): Int {
        for (i in 0..list.size - sub.size) {
            var match = true
            for (j in sub.indices) {
                if (list[i + j] != sub[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
