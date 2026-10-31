package app.halum.engine

import android.graphics.Bitmap
import app.halum.core.seg.SegDecoder
import app.halum.model.ModelStore
import app.halum.model.Models
import app.halum.util.Log
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.FloatBuffer
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
    /** True when the exported TFLite proto tensor is [1, 160, 160, 32] (NHWC). */
    private val protoChannelsLast: Boolean,
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
        // TFLite treats primitive arrays as shaped tensors and implicitly
        // resizes them. A flat FloatArray becomes rank 1, changing the model
        // input from [1,640,640,3] to [1228800] and making PAD fail (4 != 1).
        // Buffer inputs/outputs are flat payloads and preserve model shapes.
        // FloatBuffer.wrap shares the arrays; it does not copy them.
        interpreter.runForMultipleInputsOutputs(
            arrayOf(FloatBuffer.wrap(input.rgb)),
            mapOf(
                0 to FloatBuffer.wrap(detections),
                1 to FloatBuffer.wrap(proto),
            ),
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
            val m =
                SegDecoder.maskFor(
                    det,
                    proto,
                    page.width,
                    page.height,
                    box,
                    protoChannelsLast = protoChannelsLast,
                )
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
            val interpreter = Interpreter(mmap(model.file), options)
            val inputShape = interpreter.getInputTensor(0).shape()
            val expectedInputShape = intArrayOf(1, SegDecoder.OUTPUT, SegDecoder.OUTPUT, 3)
            if (!inputShape.contentEquals(expectedInputShape)) {
                interpreter.close()
                throw IllegalStateException(
                    "bubble input shape " + inputShape.contentToString() +
                        " != " + expectedInputShape.contentToString(),
                )
            }

            if (interpreter.outputTensorCount != 2) {
                val count = interpreter.outputTensorCount
                interpreter.close()
                throw IllegalStateException("bubble outputs=" + count + " expected=2")
            }
            val detectionShape = interpreter.getOutputTensor(0).shape()
            val expectedDetectionShape = intArrayOf(1, SegDecoder.NUM_CHANNELS, SegDecoder.COLS)
            if (!detectionShape.contentEquals(expectedDetectionShape)) {
                interpreter.close()
                throw IllegalStateException(
                    "bubble detection output shape " + detectionShape.contentToString() +
                        " != " + expectedDetectionShape.contentToString(),
                )
            }

            val protoShape = interpreter.getOutputTensor(1).shape()
            val protoChannelsLast =
                when {
                    protoShape.contentEquals(
                        intArrayOf(1, SegDecoder.SEG_DIM, SegDecoder.PROTO, SegDecoder.PROTO),
                    ) -> false
                    protoShape.contentEquals(
                        intArrayOf(1, SegDecoder.PROTO, SegDecoder.PROTO, SegDecoder.SEG_DIM),
                    ) -> true
                    else -> {
                        interpreter.close()
                        throw IllegalStateException(
                            "bubble proto output shape unsupported: " +
                                protoShape.contentToString(),
                        )
                    }
                }

            val detector = BubbleDetector(interpreter, protoChannelsLast)
            Log.i(
                TAG,
                "loaded " + model.file.name + " " + model.size + " bytes " +
                    "input=" + inputShape.contentToString() +
                    " protoLayout=" + if (protoChannelsLast) "NHWC" else "NCHW",
            )
            return detector
        }

        private fun mmap(file: java.io.File): MappedByteBuffer =
            FileInputStream(file).channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())

        private const val TAG = "BubbleDetector"
    }
}
