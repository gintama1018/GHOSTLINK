package com.ghostlink.zerorf.channels.optical

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.ghostlink.zerorf.core.Packet
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.EnumMap

/**
 * CameraX ImageAnalysis analyzer that decodes continuous QR frames and reconstructs Packets.
 */
class QrFrameDecoder(
    private val onPacketDecoded: (Packet) -> Unit
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
            put(DecodeHintType.TRY_HARDER, true)
            put(DecodeHintType.CHARACTER_SET, "ISO-8859-1")
        }
        setHints(hints)
    }

    override fun analyze(image: ImageProxy) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)

        val width = image.width
        val height = image.height

        val source = PlanarYUVLuminanceSource(
            data, width, height, 0, 0, width, height, false
        )
        val bitmap = BinaryBitmap(HybridBinarizer(source))

        try {
            val result = reader.decodeWithState(bitmap)
            val rawWireBytes = result.text.toByteArray(Charsets.ISO_8859_1)
            val packet = Packet.deserialize(rawWireBytes)
            onPacketDecoded(packet)
        } catch (_: Exception) {
            // Frame not detected or checksum mismatch in this video frame
        } finally {
            reader.reset()
            image.close()
        }
    }
}
