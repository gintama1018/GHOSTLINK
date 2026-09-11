package com.ghostlink.zerorf.ui

import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
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
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.log10

/**
 * GhostLink Zero-RF Transceiver UI.
 * - Bulk Channels: OPTICAL (Screen ⇢ Camera) & ACOUSTIC (Speaker ⇢ Mic)
 * - Handshake Layer: MAGNETIC (Motor ⇢ Magnetometer with live uT Oscilloscope)
 * - Per-Session Cryptographic Seed: Fresh SecureRandom seed on every transfer
 * - Media Picker & Automatic Downloads Storage
 */
class MainActivity : AppCompatActivity(), ChannelManager.ChannelEventListener {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var channelManager: ChannelManager

    // Hardware transceiver layers
    private lateinit var vibrationTransmitter: VibrationTransmitter
    private lateinit var magnetometerReceiver: MagnetometerReceiver
    private lateinit var audioModulator: AudioFskModulator
    private lateinit var audioDemodulator: AudioFskDemodulator
    private lateinit var cameraExecutor: ExecutorService

    // Active bulk channel
    private var currentBulkChannel = ChannelManager.BulkChannel.OPTICAL

    // Payload state
    private var selectedFileName = "secret_briefing.txt"
    private var selectedFileBytes = "CLASSIFIED AIR-GAP INTEL:\nZero-RF active.\nTarget: Lab Node Alpha.\nIntegrity: 100% CRC32.".toByteArray(Charsets.UTF_8)

    // UI elements
    private lateinit var statusText: TextView
    private lateinit var handshakeBadge: TextView
    private lateinit var etaText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var qrImageView: ImageView
    private lateinit var previewView: PreviewView
    private lateinit var scopeView: SensorScopeView
    private lateinit var logView: TextView
    private lateinit var stageLabel: TextView
    private lateinit var fileInfoText: TextView
    private lateinit var decryptedOutputText: TextView

    private lateinit var tabOptical: Button
    private lateinit var tabAcoustic: Button

    private var isTransmitting = false
    private var cameraProvider: ProcessCameraProvider? = null

    // Media File Picker Launcher
    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val bytes = inputStream?.readBytes()
                inputStream?.close()

                var name = "selected_file.bin"
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIdx != -1) {
                        name = cursor.getString(nameIdx)
                    }
                }

                if (bytes != null && bytes.isNotEmpty()) {
                    selectedFileName = name
                    selectedFileBytes = bytes
                    fileInfoText.text = "Selected: $name (${(bytes.size / 1024.0).format(1)} KB)"
                    updateEtaDisplay()
                    log("File loaded: $name (${bytes.size} bytes)")
                }
            } catch (e: Exception) {
                log("Error reading file: ${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            channelManager = ChannelManager(this)
            cameraExecutor = Executors.newSingleThreadExecutor()

            vibrationTransmitter = VibrationTransmitter(this)

            // Magnetometer receiver with live telemetry for oscilloscope
            magnetometerReceiver = MagnetometerReceiver(
                context = this,
                onSampleUpdate = { rawMag, delta ->
                    handler.post {
                        if (scopeView.visibility == View.VISIBLE && currentBulkChannel == ChannelManager.BulkChannel.OPTICAL) {
                            val pulseText = if (delta > 4.5f) "🔥 PULSE: ${delta.format(1)} uT" else "${rawMag.format(1)} uT (Ambient)"
                            scopeView.pushSample(delta * 8f, pulseText)
                        }
                    }
                },
                onPacketReceived = { packet ->
                    handler.post {
                        handshakeBadge.text = "HANDSHAKE: VERIFIED (MAGNETIC)"
                        handshakeBadge.setTextColor(Color.parseColor("#22C55E"))
                        log("Magnetic handshake verified! Fresh seed: 0x${packet.crc16.toString(16)}")
                        channelManager.onMagneticHandshakeReceived(packet)
                    }
                }
            )

            audioModulator = AudioFskModulator()

            // Acoustic demodulator with live energy telemetry
            audioDemodulator = AudioFskDemodulator(
                onAudioEnergyUpdate = { energy ->
                    handler.post {
                        if (scopeView.visibility == View.VISIBLE && currentBulkChannel == ChannelManager.BulkChannel.ULTRASONIC) {
                            val db = if (energy > 1.0) (10 * log10(energy.toDouble())).toFloat() else 0f
                            scopeView.pushSample(db * 0.8f, "${db.format(1)} dB (18.5 kHz)")
                        }
                    }
                },
                onPacketDecoded = { packet ->
                    handler.post {
                        channelManager.onPacketReceived(packet)
                    }
                }
            )

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
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0D1321"))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 32)
        }

        // Header Title
        val title = TextView(this).apply {
            text = "GhostLink"
            textSize = 28f
            setTextColor(Color.parseColor("#E9EDF7"))
            typeface = android.graphics.Typeface.SERIF
        }
        val subtitle = TextView(this).apply {
            text = "Zero-RF Hardware Protocol — Light · Sound · Magnetism"
            textSize = 12f
            setTextColor(Color.parseColor("#7385AC"))
            setPadding(0, 4, 0, 16)
        }
        root.addView(title)
        root.addView(subtitle)

        // Bulk Channel Switcher Tabs
        val tabLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 12)
        }
        tabOptical = createTabButton("👁️ Optical QR (Bulk)", Color.parseColor("#F0A93E"), true) {
            setBulkChannel(ChannelManager.BulkChannel.OPTICAL)
        }
        tabAcoustic = createTabButton("🔊 Ultrasonic (Acoustic)", Color.parseColor("#4CD9D0"), false) {
            setBulkChannel(ChannelManager.BulkChannel.ULTRASONIC)
        }
        tabLayout.addView(tabOptical)
        tabLayout.addView(tabAcoustic)
        root.addView(tabLayout)

        // Handshake Status Indicator
        handshakeBadge = TextView(this).apply {
            text = "HANDSHAKE: READY (Touch for Magnetometer / Auto via Stream)"
            textSize = 11f
            setTextColor(Color.parseColor("#B18CFF"))
            setBackgroundColor(Color.parseColor("#141C31"))
            setPadding(16, 8, 16, 8)
        }
        root.addView(handshakeBadge)

        // Stage Container (Holds Camera Preview, QR Stream, and Live Scope)
        val stage = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#141C31"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 500
            ).apply {
                setMargins(0, 14, 0, 0)
            }
        }

        // Live Camera Preview
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }
        stage.addView(previewView)

        // Live Hardware Oscilloscope View
        scopeView = SensorScopeView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }
        stage.addView(scopeView)

        // QR Frame Display
        qrImageView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(480, 480, Gravity.CENTER)
            visibility = View.GONE
        }
        stage.addView(qrImageView)

        // Stage Prompt Label
        stageLabel = TextView(this).apply {
            text = "HARDWARE TRANSCEIVER STAGE\nTap 'Transmit (Tx)' to broadcast\nor 'Receive (Rx)' to start physical sensors"
            textSize = 12f
            setTextColor(Color.parseColor("#7385AC"))
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        stage.addView(stageLabel)
        root.addView(stage)

        // Status & Progress
        statusText = TextView(this).apply {
            text = "State: IDLE (Strict Airplane Mode)"
            textSize = 13.5f
            setTextColor(Color.parseColor("#E9EDF7"))
            setPadding(0, 14, 0, 4)
        }
        etaText = TextView(this).apply {
            text = "ETA: Ready"
            textSize = 11.5f
            setTextColor(Color.parseColor("#7385AC"))
            setPadding(0, 0, 0, 8)
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.VISIBLE
        }
        root.addView(statusText)
        root.addView(etaText)
        root.addView(progressBar)

        // File Selection Row
        fileInfoText = TextView(this).apply {
            text = "Selected: $selectedFileName (${(selectedFileBytes.size / 1024.0).format(1)} KB)"
            textSize = 12f
            setTextColor(Color.parseColor("#E9EDF7"))
            setBackgroundColor(Color.parseColor("#141C31"))
            setPadding(16, 12, 16, 12)
        }
        root.addView(fileInfoText)

        val fileBtn = Button(this).apply {
            text = "📁 Pick Real File / Image to Send"
            setBackgroundColor(Color.parseColor("#1D253E"))
            setTextColor(Color.parseColor("#4CD9D0"))
            setOnClickListener {
                filePickerLauncher.launch("*/*")
            }
        }
        root.addView(fileBtn)

        // Action Buttons (Send / Receive)
        val btnLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 14, 0, 14)
        }
        val btnSend = Button(this).apply {
            text = "Transmit (Tx)"
            setBackgroundColor(Color.parseColor("#26304C"))
            setTextColor(Color.parseColor("#E9EDF7"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { startSendFlow() }
        }
        val btnReceive = Button(this).apply {
            text = "Receive (Rx)"
            setBackgroundColor(Color.parseColor("#26304C"))
            setTextColor(Color.parseColor("#E9EDF7"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { startReceiveFlow() }
        }
        btnLayout.addView(btnSend)
        btnLayout.addView(btnReceive)
        root.addView(btnLayout)

        // Decrypted Output Box
        decryptedOutputText = TextView(this).apply {
            text = "Awaiting reception... Decrypted file content will appear here and save to Downloads/GhostLink/."
            textSize = 11f
            setTextColor(Color.parseColor("#A3B3D4"))
            typeface = android.graphics.Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#080D18"))
            setPadding(16, 16, 16, 16)
        }
        root.addView(decryptedOutputText)

        // Log Console
        logView = TextView(this).apply {
            text = "[System] GhostLink v0.2.3 Core Initialized.\n"
            textSize = 10f
            setTextColor(Color.parseColor("#7385AC"))
            typeface = android.graphics.Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#04070D"))
            setPadding(16, 12, 16, 12)
        }
        root.addView(logView)

        scroll.addView(root)
        setContentView(scroll)

        updateEtaDisplay()
    }

    private fun createTabButton(label: String, color: Int, isActive: Boolean, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 11f
            setTextColor(if (isActive) color else Color.parseColor("#7385AC"))
            setBackgroundColor(Color.parseColor("#141C31"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(4, 0, 4, 0)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun setBulkChannel(channel: ChannelManager.BulkChannel) {
        currentBulkChannel = channel
        tabOptical.setTextColor(if (channel == ChannelManager.BulkChannel.OPTICAL) Color.parseColor("#F0A93E") else Color.parseColor("#7385AC"))
        tabAcoustic.setTextColor(if (channel == ChannelManager.BulkChannel.ULTRASONIC) Color.parseColor("#4CD9D0") else Color.parseColor("#7385AC"))

        if (channel == ChannelManager.BulkChannel.ULTRASONIC) {
            scopeView.channelColor = Color.parseColor("#4CD9D0")
            scopeView.sensorLabel = "ACOUSTIC MICROPHONE FFT (18.5/19.5 kHz)"
        } else {
            scopeView.channelColor = Color.parseColor("#B18CFF")
            scopeView.sensorLabel = "MAGNETOMETER INDUCTION (uT)"
        }

        updateEtaDisplay()
        log("Bulk channel set to $channel")
    }

    private fun updateEtaDisplay() {
        val eta = when (currentBulkChannel) {
            ChannelManager.BulkChannel.OPTICAL -> TransparentEta.calculateOpticalEta(selectedFileBytes.size.toLong())
            ChannelManager.BulkChannel.ULTRASONIC -> TransparentEta.calculateUltrasonicEta(selectedFileBytes.size.toLong())
        }
        etaText.text = "Transparent ETA (F6): ${eta.formattedTime} (${eta.speedDescription})"
    }

    private fun startSendFlow() {
        try {
            stopCamera()
            isTransmitting = true
            stageLabel.visibility = View.GONE
            previewView.visibility = View.GONE

            log("Initiating transmission: $selectedFileName (${selectedFileBytes.size} bytes) via $currentBulkChannel...")

            // 1. ChannelManager generates fresh per-session SecureRandom seed and encrypts with AES-GCM
            channelManager.startSender(selectedFileName, selectedFileBytes, currentBulkChannel)
            val sessionSeed = channelManager.activeSessionSeed!!
            log("Cryptographic seed generated: 0x${sessionSeed.joinToString("") { "%02x".format(it) }}")

            // 2. Transmit magnetic contact pulse with this exact fresh seed via vibration motor
            val hsPacket = MagneticPacket.create(0x11, sessionSeed)
            vibrationTransmitter.transmit(hsPacket)
            log("Vibrating motor with 8-byte pairing token (contact <2cm)...")

            // 3. Start bulk transmission
            when (currentBulkChannel) {
                ChannelManager.BulkChannel.OPTICAL -> {
                    scopeView.visibility = View.GONE
                    qrImageView.visibility = View.VISIBLE
                    startQrCarouselLoop()
                }
                ChannelManager.BulkChannel.ULTRASONIC -> {
                    qrImageView.visibility = View.GONE
                    scopeView.visibility = View.VISIBLE
                    startAcousticBroadcastLoop()
                }
            }
        } catch (e: Exception) {
            log("Send error: ${e.message}")
        }
    }

    private fun startQrCarouselLoop() {
        if (!isTransmitting) return
        try {
            val packet = channelManager.getNextOutboundPacket()
            if (packet != null) {
                val bitmap = QrFrameEncoder.encodePacketToBitmap(packet, 480)
                qrImageView.setImageBitmap(bitmap)
                statusText.text = "Broadcasting QR Chunk ${packet.chunkIndex + 1}/${packet.totalChunks}"
            }
        } catch (e: Exception) {
            log("Render error: ${e.message}")
        }
        handler.postDelayed({ startQrCarouselLoop() }, 166) // 6 FPS
    }

    private fun startAcousticBroadcastLoop() {
        if (!isTransmitting) return
        Thread {
            while (isTransmitting && currentBulkChannel == ChannelManager.BulkChannel.ULTRASONIC) {
                val packet = channelManager.getNextOutboundPacket() ?: break
                handler.post {
                    statusText.text = "Playing Ultrasonic Tone Burst #${packet.chunkIndex + 1}/${packet.totalChunks} (~8.5 B/s)..."
                }
                audioModulator.playPacket(packet)
                Thread.sleep(80)
            }
        }.start()
    }

    private fun startReceiveFlow() {
        try {
            isTransmitting = false
            stageLabel.visibility = View.GONE
            qrImageView.visibility = View.GONE

            log("Entering Receive Mode on $currentBulkChannel channel...")
            channelManager.startReceiver(currentBulkChannel)

            // Always start magnetometer so contact handshake is continuously detected
            magnetometerReceiver.startListening()

            when (currentBulkChannel) {
                ChannelManager.BulkChannel.OPTICAL -> {
                    scopeView.visibility = View.GONE
                    previewView.visibility = View.VISIBLE
                    audioDemodulator.stopListening()

                    if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        startCameraScanner()
                    } else {
                        ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.CAMERA), 101)
                    }
                }
                ChannelManager.BulkChannel.ULTRASONIC -> {
                    stopCamera()
                    previewView.visibility = View.GONE
                    scopeView.visibility = View.VISIBLE

                    if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        audioDemodulator.startListening()
                        statusText.text = "Listening for ultrasonic tone bursts via microphone..."
                    } else {
                        ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO), 102)
                    }
                }
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
                statusText.text = "Camera Live. Point lens at Sender's screen..."
                log("Camera scanner bound to video feed.")
            } catch (e: Exception) {
                log("Camera init error: ${e.message}")
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
            logView.append("[$msg]\n")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (currentBulkChannel == ChannelManager.BulkChannel.OPTICAL) startCameraScanner()
        } else if (requestCode == 102 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (currentBulkChannel == ChannelManager.BulkChannel.ULTRASONIC) audioDemodulator.startListening()
        }
    }

    // --- Channel Manager Callbacks ---
    override fun onStateChanged(newState: ChannelManager.State, message: String) {
        statusText.text = "State: $newState — $message"
        log("$newState: $message")
    }

    override fun onProgressUpdate(fraction: Float, speedBps: Double) {
        val percent = (fraction * 100).toInt()
        progressBar.progress = percent
        val kbps = speedBps / 1024.0
        etaText.text = "Progress: $percent% (${speedBps.format(1)} B/s · ${kbps.format(2)} KB/s)"
        statusText.text = "Receiving chunks: $percent% complete"
    }

    override fun onFileReady(fileName: String, fileBytes: ByteArray) {
        stopCamera()
        log("SUCCESS: Received $fileName (${fileBytes.size} bytes). Verifying...")

        // Save file to Downloads/GhostLink/
        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val ghostLinkDir = File(downloadsDir, "GhostLink")
            if (!ghostLinkDir.exists()) ghostLinkDir.mkdirs()

            val targetFile = File(ghostLinkDir, fileName)
            FileOutputStream(targetFile).use { it.write(fileBytes) }

            log("Saved to: ${targetFile.absolutePath}")
            Toast.makeText(this, "Received & Saved: $fileName!", Toast.LENGTH_LONG).show()

            // Display content if text/ascii
            val isText = fileBytes.all { it in 9..126 || it == 10.toByte() || it == 13.toByte() }
            if (isText) {
                decryptedOutputText.text = "FILE: $fileName (${fileBytes.size} B)\n\n" + String(fileBytes, Charsets.UTF_8)
            } else {
                decryptedOutputText.text = "BINARY FILE RECEIVED & SAVED:\nName: $fileName\nSize: ${fileBytes.size} bytes\nPath: ${targetFile.absolutePath}"
            }
        } catch (e: Exception) {
            log("Save error: ${e.message}")
            decryptedOutputText.text = "Received $fileName (${fileBytes.size} bytes). Direct save failed: ${e.message}"
        }

        statusText.text = "Transfer Complete! $fileName verified with 0 bit errors."
    }

    override fun onError(error: String) {
        log("ERROR: $error")
        Toast.makeText(this, "Error: $error", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        isTransmitting = false
        stopCamera()
        try {
            cameraExecutor.shutdown()
            vibrationTransmitter.stop()
            magnetometerReceiver.stopListening()
            audioModulator.stop()
            audioDemodulator.stopListening()
        } catch (_: Exception) {}
    }

    private fun Double.format(digits: Int) = java.lang.String.format("%.${digits}f", this)
    private fun Float.format(digits: Int) = java.lang.String.format("%.${digits}f", this)
}
