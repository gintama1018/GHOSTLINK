package com.ghostlink.zerorf.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * Real-time hardware sensor oscilloscope for GhostLink.
 * Graphs live micro-Tesla (uT) magnetic field oscillations and acoustic pressure waves.
 */
class SensorScopeView(context: Context) : View(context) {

    private val maxPoints = 80
    private val history = FloatArray(maxPoints)
    private var head = 0

    var channelColor: Int = Color.parseColor("#B18CFF") // Violet by default
    var sensorLabel: String = "MAGNETOMETER (uT)"
    var currentValueText: String = "0.0 uT"

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1D253E")
        strokeWidth = 1.5f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7385AC")
        textSize = 24f
        typeface = android.graphics.Typeface.MONOSPACE
    }

    private val valPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 32f
        typeface = android.graphics.Typeface.MONOSPACE
        isFakeBoldText = true
    }

    private val path = Path()

    fun pushSample(value: Float, text: String) {
        history[head] = value
        head = (head + 1) % maxPoints
        currentValueText = text
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // Background
        canvas.drawColor(Color.parseColor("#080D18"))

        // Grid lines
        for (i in 1..4) {
            val y = h * (i / 5f)
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        // Header labels
        textPaint.color = Color.parseColor("#7385AC")
        canvas.drawText(sensorLabel, 20f, 36f, textPaint)

        valPaint.color = channelColor
        canvas.drawText(currentValueText, 20f, 76f, valPaint)

        // Draw waveform
        linePaint.color = channelColor
        path.reset()

        val midY = h * 0.65f
        val stepX = w / (maxPoints - 1)

        for (i in 0 until maxPoints) {
            val idx = (head + i) % maxPoints
            val sample = history[idx]
            val x = i * stepX
            val y = (midY - sample * 2.2f).coerceIn(90f, h - 10f)

            if (i == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        canvas.drawPath(path, linePaint)
    }
}
