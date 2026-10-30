package app.halum.engine

import android.graphics.Bitmap
import app.halum.core.seg.SegDecoder
import app.halum.model.ModelStore
import app.halum.model.Models
import app.halum.util.Log
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * D3: YOLO11n-seg bubble detector over the shipped `bubble.tflite`.
 *
 * All the arithmetic (letterbox undo, NMS, prototype masks) lives in
 * [SegDecoder] so it is covered by pure JVM tests; this class only owns the
 * interpreter, its buffers and its lifecycle.
 */
class BubbleDetector private constructor(
    private val interpreter: Interpreter,
) : Closeable {
    data class Bubble(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val score: Float,
        /** Page-pixel binary mask (1 = bubble), width×height. */
        val mask: ByteArray,
        val maskWidth: Int,
        val maskHeight: Int,
        val maskAreaPx: Int,
    ) {
        val width: Float get() = x2 - x1
        val height: Float get() = y2 - y1
        val area: Float get() = width * height
    }

    /** Detections only; masks are built lazily by [maskFor] when needed. */
    fun detect(page: Bitmap): Pair<List<SegDecoder.Detection>, SegDecoder.Letterbox> {
        val input = Letterboxer.toInput(page)
        val detections = FloatArray(SegDecoder.NUM_CHANNELS * SegDecoder.COLS)
        val proto = FloatArray(SegDecoder.SEG_DIM * SegDecoder.PROTO * SegDecoder.PROTO)
        interpreter.runForMultipleInputsOutputs(
            arrayOf(input.rgb),
            mapOf(0 to detections, 1 to proto),
        )
        lastProto = proto
        return SegDecoder.decode(detections, input.letterbox) to input.letterbox
    }

    /** The prototype batch from the most recent [detect] call. */
    private var lastProto: FloatArray? = null

    fun masks(
        detections: List<SegDecoder.Detection>,
        box: SegDecoder.Letterbox,
        page: Bitmap,
    ): List<Bubble> {
        val proto = lastProto ?: return emptyList()
        return detections.map { det ->
            val m = SegDecoder.maskFor(det, proto, page.width, page.height, box)
            Bubble(det.x1, det.y1, det.x2, det.y2, det.score, m.mask, m.width, m.height, m.areaPx)
        }
    }

    override fun close() {
        lastProto = null
        interpreter.close()
    }

    companion object {
        /** Null when this build shipped no bubble model. */
        fun create(store: ModelStore): BubbleDetector? {
            val model =
                store.obtain(Models.BUBBLE) ?: run {
                    Log.w(TAG, "bubble.tflite missing; detection disabled")
                    return null
                }
            val options =
                Interpreter.Options().apply {
                    setNumThreads(if (Runtime.getRuntime().availableProcessors() >= 4) 4 else 2)
                }
            val detector = BubbleDetector(Interpreter(mmap(model.file), options))
            Log.i(TAG, "loaded ${model.file.name} ${model.size} bytes")
            return detector
        }

        private fun mmap(file: java.io.File): MappedByteBuffer =
            FileInputStream(file).channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())

        private const val TAG = "BubbleDetector"
    }
}
