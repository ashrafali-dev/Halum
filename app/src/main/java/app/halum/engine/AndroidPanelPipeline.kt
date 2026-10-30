package app.halum.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.halum.core.ChatBridge
import app.halum.core.OcrBlock
import app.halum.core.pipeline.PanelPipeline
import app.halum.util.Log
import java.io.ByteArrayOutputStream

/**
 * D19 Step 2: binds the verified engines to [PanelPipeline]'s ports so the
 * orchestrators talk to one object.
 *
 * The bitmap carries both the pixels (for Lens upload and bubble detection)
 * and the pixel space every box is expressed in. Nothing here re-implements
 * engine arithmetic; it only converts between Android types and core types.
 */
class AndroidPanelPipeline(
    private val lens: LensOcrClientPort,
    private val detector: BubbleDetector?,
    private val bridge: ChatBridge,
) {
    interface LensOcrClientPort {
        fun recognize(
            jpeg: ByteArray,
            width: Int,
            height: Int,
        ): List<OcrBlock>
    }

    private val pipeline =
        PanelPipeline(
            lens =
                object : PanelPipeline.LensClient {
                    override fun recognize(panel: PanelPipeline.CapturedPanel) =
                        lens.recognize(panel.image, panel.width, panel.height)
                },
            bubbles =
                object : PanelPipeline.BubbleDetector {
                    override fun detect(panel: PanelPipeline.CapturedPanel): List<PanelPipeline.DetectedBubble> {
                        val det = detector ?: return emptyList()
                        val bitmap =
                            BitmapFactory.decodeByteArray(panel.image, 0, panel.image.size)
                                ?: return emptyList()
                        val (detections, box) = det.detect(bitmap)
                        val bubbles = det.masks(detections, box, bitmap)
                        bitmap.recycle()
                        Log.i(TAG, "BUBBLES: ${bubbles.size} scored ${detections.size}")
                        return bubbles.map {
                            PanelPipeline.DetectedBubble(
                                app.halum.core.BubbleClassifier
                                    .Box(it.x1, it.y1, it.x2, it.y2),
                                it.mask,
                            )
                        }
                    }
                },
            translator =
                object : PanelPipeline.Translator {
                    override fun translate(blocks: List<String>): List<String?> {
                        val outcome = bridge.translate(blocks)
                        Log.i(TAG, "BRIDGE: state=${outcome.state} attempts=${outcome.attempts}")
                        return outcome.translations
                    }
                },
        )

    data class PanelOutcome(
        val placements: List<PanelPipeline.Placement>,
        val bubbles: Int,
        val ocrBlocks: Int,
        val translated: Int,
        val totalMs: Long,
        val width: Int,
        val height: Int,
    )

    /** Runs the per-panel flow on a decoded bitmap, returning overlay placements. */
    fun process(bitmap: Bitmap): PanelOutcome {
        val bytes = toJpeg(bitmap)
        val result =
            pipeline.process(
                PanelPipeline.CapturedPanel(bitmap.width, bitmap.height, bytes),
            )
        Log.i(
            TAG,
            "PIPE_DONE: panel=${bitmap.width}x${bitmap.height} bubbles=${result.bubbles} " +
                "placed=${result.placements.size} totalMs=${result.totalMs}",
        )
        return PanelOutcome(
            result.placements,
            result.bubbles,
            result.ocrBlocks,
            result.translated,
            result.totalMs,
            bitmap.width,
            bitmap.height,
        )
    }

    private fun toJpeg(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return out.toByteArray()
    }

    private companion object {
        const val TAG = "PanelPipeline"

        // Lens re-encodes anyway; 90 keeps strokes crisp without bloat.
        const val JPEG_QUALITY = 90
    }
}
