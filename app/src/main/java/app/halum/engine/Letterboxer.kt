package app.halum.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import app.halum.core.seg.SegDecoder

/**
 * The 640×640 RGB uint8 input the bubble model expects: the page scaled with
 * aspect preserved, centred on a grey (114) canvas — the same transform as
 * `tools/integration_test.py`, so the recall numbers match.
 */
object Letterboxer {
    const val PAD = 114

    data class Result(
        val rgb: ByteArray,
        val letterbox: SegDecoder.Letterbox,
    )

    fun toInput(
        source: Bitmap,
        size: Int = SegDecoder.OUTPUT,
    ): Result {
        val box = SegDecoder.Letterbox.of(source.width, source.height, size)
        val canvas = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(canvas).apply {
            drawColor(Color.rgb(PAD, PAD, PAD))
            drawBitmap(
                source,
                Rect(0, 0, source.width, source.height),
                Rect(box.padX, box.padY, box.padX + box.resizedW, box.padY + box.resizedH),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
        }
        val pixels = IntArray(size * size)
        canvas.getPixels(pixels, 0, size, 0, 0, size, size)
        canvas.recycle()
        // NHWC uint8 RGB, the layout the exported tflite declares.
        val rgb = ByteArray(size * size * 3)
        var o = 0
        for (p in pixels) {
            rgb[o++] = ((p shr 16) and 0xFF).toByte()
            rgb[o++] = ((p shr 8) and 0xFF).toByte()
            rgb[o++] = (p and 0xFF).toByte()
        }
        return Result(rgb, box)
    }
}
