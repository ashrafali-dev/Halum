package app.halum.core.seg

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * YOLO11n-seg post-processing, Android-free so it is unit-tested with fixed
 * arrays on the JVM (the reference implementation is `tools/integration_test.py`
 * — the two must agree, see [SegDecoderTest]).
 *
 * Model contract (measured in MODELS.md):
 *   detections  (1, 37, 8400) float32 — cx, cy, w, h, 1 class score, 32 coeffs
 *   mask proto  (1, 32, 160, 160) in ONNX or (1, 160, 160, 32) in NHWC TFLite exports
 * Coordinates are in the 640×640 letterboxed space; [Letterbox] maps back.
 */
object SegDecoder {
    const val SEG_DIM = 32
    const val PROTO = 160
    const val COLS = 8400
    const val NUM_CHANNELS = 4 + 1 + SEG_DIM
    const val OUTPUT = 640

    /** Confidence floor shared with the Python reference (`0.25`). */
    const val CONF_MIN = 0.25f
    const val NMS_IOU = 0.45f

    /** class scores are probabilities unless the exporter emitted logits. */
    fun prob(v: Float): Float = if (v in 0f..1f) v else (1.0 / (1.0 + exp(-v.toDouble()))).toFloat()

    /** 640×640 letterbox parameters for a w×h page. */
    data class Letterbox(
        val scale: Float,
        val padX: Int,
        val padY: Int,
        val resizedW: Int,
        val resizedH: Int,
    ) {
        companion object {
            fun of(
                width: Int,
                height: Int,
                size: Int = OUTPUT,
            ): Letterbox {
                val scale = min(size.toFloat() / width, size.toFloat() / height)
                val rw = (width * scale).roundToInt()
                val rh = (height * scale).roundToInt()
                return Letterbox(scale, (size - rw) / 2, (size - rh) / 2, rw, rh)
            }
        }
    }

    /** One detection in page pixels, with its prototype coefficients. */
    data class Detection(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val score: Float,
        val coeffs: FloatArray,
    ) {
        val width: Float get() = x2 - x1
        val height: Float get() = y2 - y1
        val area: Float get() = width * height

        override fun equals(other: Any?): Boolean =
            other is Detection &&
                x1 == other.x1 &&
                y1 == other.y1 &&
                x2 == other.x2 &&
                y2 == other.y2 &&
                score == other.score

        override fun hashCode(): Int {
            var h = x1.hashCode()
            h = 31 * h + y1.hashCode()
            h = 31 * h + x2.hashCode()
            h = 31 * h + y2.hashCode()
            return 31 * h + score.hashCode()
        }
    }

    /**
     * Decodes the detector output into page-pixel boxes, highest score first,
     * with NMS at [NMS_IOU]. The export carries **no** NMS: without it every
     * bubble appears 3–4 times (see BUGS.md, run 40989519314).
     */
    fun decode(
        detections: FloatArray,
        letterbox: Letterbox,
    ): List<Detection> {
        require(detections.size == NUM_CHANNELS * COLS) {
            "expected ${NUM_CHANNELS * COLS} floats, got ${detections.size}"
        }
        val raw = ArrayList<Detection>()
        for (col in 0 until COLS) {
            val score = prob(detections[index(4, col)])
            if (score < CONF_MIN) continue
            val cx = detections[index(0, col)]
            val cy = detections[index(1, col)]
            val bw = detections[index(2, col)]
            val bh = detections[index(3, col)]
            val x1 = (cx - bw / 2 - letterbox.padX) / letterbox.scale
            val y1 = (cy - bh / 2 - letterbox.padY) / letterbox.scale
            val x2 = (cx + bw / 2 - letterbox.padX) / letterbox.scale
            val y2 = (cy + bh / 2 - letterbox.padY) / letterbox.scale
            val coeffs = FloatArray(SEG_DIM) { detections[index(5 + it, col)] }
            raw += Detection(x1, y1, x2, y2, score, coeffs)
        }
        raw.sortWith(compareByDescending<Detection> { it.score }.thenBy { it.x1 }.thenBy { it.y1 })
        val kept = ArrayList<Detection>(raw.size)
        for (det in raw) {
            if (kept.none { iou(it, det) >= NMS_IOU }) kept += det
        }
        return kept
    }

    /** Channel-major flat index, matching the (1, C, 8400) export. */
    private fun index(
        channel: Int,
        col: Int,
    ): Int = channel * COLS + col

    fun iou(
        a: Detection,
        b: Detection,
    ): Float {
        val ix1 = max(a.x1, b.x1)
        val iy1 = max(a.y1, b.y1)
        val ix2 = min(a.x2, b.x2)
        val iy2 = min(a.y2, b.y2)
        val iw = ix2 - ix1
        val ih = iy2 - iy1
        if (iw <= 0f || ih <= 0f) return 0f
        val inter = iw * ih
        val union = a.area + b.area - inter
        return if (union <= 0f) 0f else inter / union
    }

    /**
     * Prototype → page mask. `proto` may be channel-first (32, 160, 160) or
     * channel-last (160, 160, 32), depending on the TFLite export.
     *
     * Returns a page-sized binary mask (1 = bubble) and its pixel area.
     */
    class MaskResult(
        val mask: ByteArray,
        val width: Int,
        val height: Int,
        val areaPx: Int,
    )

    fun maskFor(
        det: Detection,
        proto: FloatArray,
        pageWidth: Int,
        pageHeight: Int,
        letterbox: Letterbox,
        threshold: Float = 0.5f,
        /** True when proto is exported as [1, 160, 160, 32] instead of channel-first. */
        protoChannelsLast: Boolean = false,
    ): MaskResult {
        require(proto.size == SEG_DIM * PROTO * PROTO) {
            "expected ${SEG_DIM * PROTO * PROTO} proto floats, got ${proto.size}"
        }
        val out = ByteArray(pageWidth * pageHeight)
        var filled = 0
        // Only the part of the page that survived the letterbox is meaningful.
        val px1 = max(0f, min(det.x1, pageWidth.toFloat()))
        val py1 = max(0f, min(det.y1, pageHeight.toFloat()))
        val px2 = max(0f, min(det.x2, pageWidth.toFloat()))
        val py2 = max(0f, min(det.y2, pageHeight.toFloat()))
        for (py in py1.toInt() until py2.toInt()) {
            val ny = ((py * letterbox.scale + letterbox.padY) / (OUTPUT / PROTO.toFloat())).toInt()
            if (ny !in 0 until PROTO) continue
            val rowBase = ny * PROTO
            for (px in px1.toInt() until px2.toInt()) {
                val nx = ((px * letterbox.scale + letterbox.padX) / (OUTPUT / PROTO.toFloat())).toInt()
                if (nx !in 0 until PROTO) continue
                val cell = rowBase + nx
                var acc = 0f
                for (c in 0 until SEG_DIM) {
                    acc += det.coeffs[c] * proto[prototypeIndex(c, cell, protoChannelsLast)]
                }
                if (sigmoid(acc) >= threshold) {
                    out[py * pageWidth + px] = 1
                    filled++
                }
            }
        }
        return MaskResult(out, pageWidth, pageHeight, filled)
    }

    private fun prototypeIndex(channel: Int, cell: Int, channelsLast: Boolean): Int =
        if (channelsLast) cell * SEG_DIM + channel else channel * PROTO * PROTO + cell

    fun sigmoid(v: Float): Float = (1.0 / (1.0 + exp(-v.toDouble()))).toFloat()
}
