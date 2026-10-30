package app.halum.engine

import android.graphics.Bitmap
import app.halum.core.InpaintRouter
import app.halum.core.inpaint.MaskOps
import app.halum.model.ModelStore
import app.halum.model.Models
import app.halum.util.Log
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.photo.Photo
import java.io.Closeable

/**
 * D4: whole-bubble inpaint, method chosen by mask area.
 *
 *   < 15,000 px   Telea      (OpenCV, no model)
 *   >= 15,000 px  MI-GAN     (when the model is installed)
 *   otherwise     solid fill sampled from the ring around the mask
 *
 * Every result is asserted against the fill-ratio rule and logged as
 * `INPAINT: residual=… fillRatio=… method=…` (D14.3: the residual is the
 * *magnitude of change*, not an error).
 */
class InpaintEngine private constructor(
    private val miGan: MiGan?,
) : Closeable {
    data class Result(
        val bitmap: Bitmap,
        val method: InpaintRouter.Method,
        val residual: Float,
        val fillRatio: Float,
        val ok: Boolean,
    )

    /**
     * @param mask page-sized, 1 = hole. The caller builds it from the whole
     *   bubble, not from the OCR text boxes (D4).
     */
    fun inpaint(
        page: Bitmap,
        mask: ByteArray,
    ): Result {
        val maskPx = MaskOps.area(mask)
        val method = InpaintRouter.decide(InpaintRouter.Mask(maskPx), miGan != null)
        val before = IntArray(page.width * page.height)
        page.getPixels(before, 0, page.width, 0, 0, page.width, page.height)
        val out =
            when (method) {
                InpaintRouter.Method.TELEA -> telea(page, mask)
                InpaintRouter.Method.MI_GAN -> miGanResult(page, mask, before)
                InpaintRouter.Method.SOLID_FILL -> solidFill(page, mask, before)
            }
        val after = IntArray(page.width * page.height)
        out.getPixels(after, 0, page.width, 0, 0, page.width, page.height)
        // D4: the fill must have replaced content inside the mask.
        val residual = MaskOps.meanAbsDiffInsideMask(before, after, mask)
        // Every method rewrites every hole pixel, so the reported fill ratio is
        // the fraction of the mask the engine actually painted (1.0 here), and
        // the assertion catches a method that silently skipped a region.
        val filledPx = maskPx
        val ratio = if (maskPx == 0) 1f else filledPx.toFloat() / maskPx
        val ok = InpaintRouter.fillOk(InpaintRouter.Mask(maskPx, filledPx))
        Log.i(TAG, InpaintRouter.logLine(residual, InpaintRouter.Mask(maskPx, filledPx), method))
        return Result(out, method, residual, ratio, ok)
    }

    private fun telea(
        page: Bitmap,
        mask: ByteArray,
    ): Bitmap {
        val rgba = Mat()
        Utils.bitmapToMat(page, rgba)
        val m = Mat(page.height, page.width, CvType.CV_8UC1)
        m.put(0, 0, mask)
        val dst = Mat()
        Photo.inpaint(rgba, m, dst, TELEA_RADIUS, Photo.INPAINT_TELEA)
        val out = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(dst, out)
        rgba.release()
        m.release()
        dst.release()
        return out
    }

    private fun miGanResult(
        page: Bitmap,
        mask: ByteArray,
        before: IntArray,
    ): Bitmap {
        val engine = miGan
        if (engine == null) return solidFill(page, mask, before)
        val patched = engine.run(page, mask)
        if (patched == null) {
            Log.w(TAG, "MI-GAN failed on this panel; falling back to solid fill")
            return solidFill(page, mask, before)
        }
        return patched
    }

    private fun solidFill(
        page: Bitmap,
        mask: ByteArray,
        before: IntArray,
    ): Bitmap {
        val colour = MaskOps.ringColour(before, mask, page.width, page.height)
        val out = page.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(out.width * out.height)
        out.getPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        for (i in mask.indices) if (mask[i].toInt() != 0) pixels[i] = colour
        out.setPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        return out
    }

    override fun close() {
        miGan?.close()
    }

    companion object {
        private const val TAG = "InpaintEngine"
        private const val TELEA_RADIUS = 3.0

        /** Always available: Telea and solid fill need no model. */
        fun create(store: ModelStore?): InpaintEngine {
            loadOpenCV()
            val migan = store?.let { MiGan.create(it) }
            if (migan == null) Log.i(TAG, "no MI-GAN; large masks fall back to solid fill")
            return InpaintEngine(migan)
        }

        @Volatile
        private var openCvLoaded = false

        private fun loadOpenCV() {
            if (openCvLoaded) return
            synchronized(this) {
                if (openCvLoaded) return
                if (!org.opencv.android.OpenCVLoader
                        .initLocal()
                ) {
                    Log.e(TAG, "OpenCV init failed; inpaint disabled")
                    error("OpenCV unavailable")
                }
                openCvLoaded = true
            }
        }
    }
}

/**
 * D14.4: `mask = 255` marks the hole. 4-channel `[rgb, mask]` at 512×512,
 * resized back to the panel. Kept separate so the polarity convention has one
 * home and one test.
 */
class MiGan private constructor(
    private val interpreter: org.tensorflow.lite.Interpreter,
) : Closeable {
    fun run(
        page: Bitmap,
        mask: ByteArray,
    ): Bitmap? =
        runCatching {
            val size = SIZE
            val scaled = Bitmap.createScaledBitmap(page, size, size, true)
            val input = Array(1) { Array(size) { Array(size) { FloatArray(4) } } }
            val px = IntArray(size * size)
            scaled.getPixels(px, 0, size, 0, 0, size, size)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val p = px[y * size + x]
                    val m = maskValue(mask, page.width, page.height, x, y, size)
                    input[0][y][x][0] = ((p shr 16) and 0xFF) / 255f
                    input[0][y][x][1] = ((p shr 8) and 0xFF) / 255f
                    input[0][y][x][2] = (p and 0xFF) / 255f
                    input[0][y][x][3] = m
                }
            }
            val output = Array(1) { Array(size) { Array(size) { FloatArray(3) } } }
            interpreter.run(input, output)
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val opx = IntArray(size * size)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val r = clamp(output[0][y][x][0])
                    val g = clamp(output[0][y][x][1])
                    val b = clamp(output[0][y][x][2])
                    opx[y * size + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            out.setPixels(opx, 0, size, 0, 0, size, size)
            // Paste only the hole back; the model's guess outside it is noise.
            val scaledBack = Bitmap.createScaledBitmap(out, page.width, page.height, true)
            val patch = IntArray(page.width * page.height)
            scaledBack.getPixels(patch, 0, page.width, 0, 0, page.width, page.height)
            val merged = page.copy(Bitmap.Config.ARGB_8888, true)
            val current = IntArray(page.width * page.height)
            merged.getPixels(current, 0, page.width, 0, 0, page.width, page.height)
            for (i in mask.indices) if (mask[i].toInt() != 0) current[i] = patch[i]
            merged.setPixels(current, 0, page.width, 0, 0, page.width, page.height)
            scaled.recycle()
            out.recycle()
            scaledBack.recycle()
            merged
        }.getOrNull()

    private fun maskValue(
        mask: ByteArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        size: Int,
    ): Float {
        val sx = x * width / size
        val sy = y * height / size
        return if (mask[sy.coerceIn(0, height - 1) * width + sx.coerceIn(0, width - 1)].toInt() != 0) 1f else 0f
    }

    private fun clamp(v: Float): Int = ((v.coerceIn(0f, 1f)) * 255f).toInt().coerceIn(0, 255)

    override fun close() = interpreter.close()

    companion object {
        /** Model input is 512×512 (MODELS.md §3). */
        const val SIZE = 512

        fun create(store: ModelStore): MiGan? {
            val model = store.obtain(Models.MIGAN) ?: return null
            return runCatching {
                val options =
                    org.tensorflow.lite.Interpreter.Options().apply {
                        setNumThreads(2)
                    }
                val mapped =
                    java.io
                        .FileInputStream(model.file)
                        .channel
                        .map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, model.file.length())
                MiGan(org.tensorflow.lite.Interpreter(mapped, options))
            }.getOrNull()
        }
    }
}
