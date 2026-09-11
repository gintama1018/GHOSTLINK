package com.ghostlink.zerorf.channels.magnetic

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * MagnetometerReceiver listens to the phone's magnetometer at SENSOR_DELAY_FASTEST (~50-100Hz),
 * cancels baseline drift, feeds live scope telemetry, and decodes the 8-byte MagneticPacket.
 */
class MagnetometerReceiver(
    context: Context,
    private val onSampleUpdate: ((rawMag: Float, delta: Float) -> Unit)? = null,
    private val onPacketReceived: (MagneticPacket) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private var lastRawMagnitude = 0.0
    private var filteredValue = 0.0
    private val alpha = 0.85

    private val highThreshold = 5.0
    private val lowThreshold = 2.0
    private var isHigh = false
    private var pulseStartTimeMs = 0L

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

        filteredValue = alpha * (filteredValue + rawMag - lastRawMagnitude)
        lastRawMagnitude = rawMag

        val absDelta = Math.abs(filteredValue).toFloat()
        onSampleUpdate?.invoke(rawMag.toFloat(), absDelta)

        val currentTimeMs = System.currentTimeMillis()

        if (!isHigh && absDelta >= highThreshold) {
            isHigh = true
            pulseStartTimeMs = currentTimeMs
        } else if (isHigh && absDelta <= lowThreshold) {
            isHigh = false
            val pulseDuration = currentTimeMs - pulseStartTimeMs

            if (pulseDuration >= 35) {
                val bit = if (pulseDuration > 85) 1 else 0
                handleDecodedBit(bit)
            }
        }
    }

    private fun handleDecodedBit(bit: Int) {
        receivedBits.add(bit)
        if (receivedBits.size >= 64) {
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
                } catch (_: Exception) {
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
