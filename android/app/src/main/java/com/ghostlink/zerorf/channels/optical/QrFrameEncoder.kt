package com.ghostlink.zerorf.channels.optical

import android.graphics.Bitmap
import android.graphics.Color
import com.ghostlink.zerorf.core.Packet
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.EnumMap

/**
 * Encodes GhostLink 14-byte framed packets into high-density QR code Bitmaps.
 * Mapped to QR Version 9-11 with Error Correction Level L.
 */
object QrFrameEncoder {

    fun encodePacketToBitmap(packet: Packet, sizePx: Int = 512): Bitmap {
        val rawWireBytes = packet.serialize()
        // Use ISO-8859-1 encoding to safely map all 256 binary byte values into characters
        val content = String(rawWireBytes, Charsets.ISO_8859_1)

        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L)
            put(EncodeHintType.CHARACTER_SET, "ISO-8859-1")
            put(EncodeHintType.MARGIN, 2)
        }

        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)

        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }

        return bitmap
    }
}
