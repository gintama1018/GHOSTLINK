package com.ghostlink.zerorf.ui

import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ghostlink.zerorf.channels.magnetic.MagneticPacket
import com.ghostlink.zerorf.channels.magnetic.MagnetometerReceiver
import com.ghostlink.zerorf.channels.magnetic.VibrationTransmitter
import com.ghostlink.zerorf.channels.optical.QrFrameDecoder
import com.ghostlink.zerorf.channels.optical.QrFrameEncoder
import com.ghostlink.zerorf.channels.ultrasonic.AudioFskDemodulator
import com.ghostlink.zerorf.channels.ultrasonic.AudioFskModulator
import com.ghostlink.zerorf.manager.ChannelManager
import com.ghostlink.zerorf.manager.TransparentEta
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * High-aesthetic Dark UI for GhostLink Zero-RF Transfer.
 */
class MainActivity : AppCompatActivity(), ChannelManager.ChannelEventListener {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var channelManager: ChannelManager

    // Transceiver hardware layers
    private lateinit var vibrationTransmitter: VibrationTransmitter
    private lateinit var magnetometerReceiver: MagnetometerReceiver
    private lateinit var audioModulator: AudioFskModulator
    private lateinit var audioDemodulator: AudioFskDemodulator
    private lateinit var cameraExecutor: ExecutorService

    // UI elements
    private lateinit var statusText: TextView
    private lateinit var channelBadge: TextView
    private lateinit var etaText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var qrImageView: ImageView
    private lateinit var previewView: PreviewView
    private lateinit var logView: TextView
    private lateinit var stageLabel: TextView

    private var isTransmittingQr = false
    private var cameraProvider: ProcessCameraProvider? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            channelManager = ChannelManager(this)
            cameraExecutor = Executors.newSingleThreadExecutor()

            vibrationTransmitter = VibrationTransmitter(this)
            magnetometerReceiver = MagnetometerReceiver(this) { packet ->
                handler.post {
                    log("Magnetic handshake detected! VerCaps: ${packet.verCaps}")
                    channelManager.onMagneticHandshakeReceived(packet)
                }
            }
            audioModulator = AudioFskModulator()
            audioDemodulator = AudioFskDemodulator { packet ->
                handler.post {
                    channelManager.onPacketReceived(packet)
                }
            }

            setupUI()
            checkPermissions()
        } catch (e: Exception) {
            Toast.makeText(this, "Init error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkPermissions() {
        val permissions = arrayOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO
        )
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        }
    }

    private fun setupUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D1321"))
            setPadding(32, 48, 32, 32)
        }

        val title = TextView(this).apply {
            text = "GhostLink"
            textSize = 28f
            setTextColor(Color.parseColor("#E9EDF7"))
            typeface = android.graphics.Typeface.SERIF
        }
        val subtitle = TextView(this).apply {
            text = "Zero-RF File Transfer — Light · Sound · Magnetism"
            textSize = 12f
            setTextColor(Color.parseColor("#7385AC"))
            setPadding(0, 4, 0, 24)
        }
        root.addView(title)
        root.addView(subtitle)

        // Channel Indicators
        val badgeLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 20)
        }
        channelBadge = TextView(this).apply {
            text = "CHANNEL: OPTICAL"
            textSize = 12f
            setTextColor(Color.parseColor("#F0A93E"))
            setBackgroundColor(Color.parseColor("#141C31"))
            setPadding(16, 8, 16, 8)
        }
        badgeLayout.addView(channelBadge)
        root.addView(badgeLayout)

        // QR / Camera Stage Container
        val stage = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#141C31"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 560
            )
        }

        // Camera Live Preview for Receiver
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }
        stage.addView(previewView)

        // QR Code Display for Sender
        qrImageView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(520, 520, Gravity.CENTER)
            visibility = View.GONE
        }
        stage.addView(qrImageView)

        // Stage Default Label
        stageLabel = TextView(this).apply {
            text = "TRANSCEIVER STAGE READY\nTap 'Send' to broadcast QR frames\nor 'Receive' to open camera scanner"
            textSize = 12f
            setTextColor(Color.parseColor("#7385AC"))
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        stage.addView(stageLabel)

        root.addView(stage)

        // Status & Honest ETA
        statusText = TextView(this).apply {
            text = "State: IDLE (Radios Disabled)"
            textSize = 14f
            setTextColor(Color.parseColor("#E9EDF7"))
            setPadding(0, 16, 0, 4)
        }
        etaText = TextView(this).apply {
            text = "ETA: Ready"
            textSize = 12f
            setTextColor(Color.parseColor("#7385AC"))
            setPadding(0, 0, 0, 12)
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.VISIBLE
        }
        root.addView(statusText)
        root.addView(etaText)
        root.addView(progressBar)

        // Action Buttons
        val btnLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 20, 0, 20)
        }
        val btnSend = Button(this).apply {
            text = "Send Test Secret"
            setBackgroundColor(Color.parseColor("#26304C"))
            setTextColor(Color.parseColor("#E9EDF7"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { startSendFlow() }
        }
        val btnReceive = Button(this).apply {
            text = "Receive Mode"
            setBackgroundColor(Color.parseColor("#26304C"))
            setTextColor(Color.parseColor("#E9EDF7"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { startReceiveFlow() }
        }
        btnLayout.addView(btnSend)
        btnLayout.addView(btnReceive)
        root.addView(btnLayout)

        // Log Console
        logView = TextView(this).apply {
            text = "[Log] GhostLink Core initialized.\n"
            textSize = 11f
            setTextColor(Color.parseColor("#7385AC"))
            typeface = android.graphics.Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#080D18"))
            setPadding(16, 16, 16, 16)
        }
        root.addView(logView)

        setContentView(root)
    }

    private fun startSendFlow() {
        try {
            stopCamera()
            stageLabel.visibility = View.GONE
            previewView.visibility = View.GONE
            qrImageView.visibility = View.VISIBLE

            log("Generating 10 KB test payload...")
            val testPayload = ByteArray(10240) { (it and 0xFF).toByte() }

            // Calculate and show transparent ETA (F6)
            val opticalEta = TransparentEta.calculateOpticalEta(testPayload.size.toLong())
            etaText.text = "ETA: ${opticalEta.formattedTime} (Optical ${opticalEta.speedDescription})"

            // Start channel manager sender pipeline
            channelManager.startSender(testPayload)

            // Transmit 8-byte magnetic handshake pulse via vibration motor
            val hsPacket = MagneticPacket.create(0x11, ChannelManager.DEFAULT_SEED)
            log("Transmitting magnetic handshake pulse (contact <2cm)...")
            vibrationTransmitter.transmit(hsPacket)

            // Start Optical QR frame loop at 6 FPS
            isTransmittingQr = true
            startQrCarouselLoop()
        } catch (e: Exception) {
            log("Send error: ${e.message}")
        }
    }

    private fun startQrCarouselLoop() {
        if (!isTransmittingQr) return
        try {
            val packet = channelManager.getNextOutboundPacket()
            if (packet != null) {
                val bitmap = QrFrameEncoder.encodePacketToBitmap(packet, 500)
                qrImageView.setImageBitmap(bitmap)
                statusText.text = "Streaming QR Chunk ${packet.chunkIndex + 1}/${packet.totalChunks}"
            }
        } catch (e: Exception) {
            log("Frame render error: ${e.message}")
        }
        // 6 FPS = ~166 ms per frame
        handler.postDelayed({ startQrCarouselLoop() }, 166)
    }

    private fun startReceiveFlow() {
        try {
            isTransmittingQr = false
            stageLabel.visibility = View.GONE
            qrImageView.visibility = View.GONE
            previewView.visibility = View.VISIBLE

            log("Entering Receive Mode. Starting Camera Scanner, Magnetometer, & Mic...")
            channelManager.startReceiver()
            magnetometerReceiver.startListening()

            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                audioDemodulator.startListening()
            } else {
                log("Audio permission not granted yet, proceeding with Optical.")
            }

            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startCameraScanner()
            } else {
                log("Camera permission not granted yet, requesting...")
                ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.CAMERA), 101)
            }
        } catch (e: Exception) {
            log("Receive start error: ${e.message}")
        }
    }

    private fun startCameraScanner() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, QrFrameDecoder { packet ->
                            runOnUiThread {
                                channelManager.onPacketReceived(packet)
                            }
                        })
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
                statusText.text = "Camera Active. Point lens at Sender's screen..."
                log("Camera scanner bound to live feed.")
            } catch (e: Exception) {
                log("Camera initialization error: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {}
    }

    private fun log(msg: String) {
        handler.post {
            logView.append("$msg\n")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            for (i in permissions.indices) {
                if (permissions[i] == android.Manifest.permission.CAMERA && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    if (previewView.visibility == View.VISIBLE) {
                        startCameraScanner()
                    }
                }
            }
        }
    }

    // --- Channel Manager Callbacks ---
    override fun onStateChanged(newState: ChannelManager.State, message: String) {
        statusText.text = "State: $newState — $message"
        log("[$newState] $message")
    }

    override fun onProgressUpdate(fraction: Float, speedBps: Double) {
        val percent = (fraction * 100).toInt()
        progressBar.progress = percent
        val kbps = speedBps / 1024.0
        etaText.text = "Progress: $percent% (Speed: ${String.format("%.2f", kbps)} KB/s)"
        statusText.text = "Receiving chunks: $percent% complete"
    }

    override fun onFileReady(fileBytes: ByteArray) {
        stopCamera()
        log("SUCCESS: ${fileBytes.size} bytes reconstructed and decrypted with zero bit errors!")
        Toast.makeText(this, "SUCCESS! Zero-RF Transfer Completed!", Toast.LENGTH_LONG).show()
        statusText.text = "Transfer Complete! ${fileBytes.size} bytes verified."
    }

    override fun onError(error: String) {
        log("ERROR: $error")
        Toast.makeText(this, "Transfer Error: $error", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        isTransmittingQr = false
        stopCamera()
        try {
            cameraExecutor.shutdown()
            vibrationTransmitter.stop()
            magnetometerReceiver.stopListening()
            audioModulator.stop()
            audioDemodulator.stopListening()
        } catch (_: Exception) {}
    }
}
