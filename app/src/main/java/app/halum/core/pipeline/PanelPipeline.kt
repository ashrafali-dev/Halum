package app.halum.core.pipeline

import app.halum.core.BubbleClassifier
import app.halum.core.OcrBlock

/**
 * D7: the per-panel steps shared by Scan and Realtime. Everything Android-side
 * arrives through the injected ports, so this class runs unmodified on the JVM
 * (the integration test drives it with fakes) and in the app (the engines).
 *
 * Flow: capture -> Lens OCR -> bubble detect (+ masks) -> group OCR lines into
 * bubbles -> batch translate via the ChatBridge -> place Bengali text inside
 * each bubble's box.
 *
 * Inpainting is a separate step the caller runs on the captured bitmap (the
 * pipeline reports the masks it found so the caller can inpaint before placing
 * text); keeping it out of here means the JVM test does not need OpenCV.
 */
class PanelPipeline(
    private val lens: LensClient,
    private val bubbles: BubbleDetector,
    private val translator: Translator,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    interface LensClient {
        /** OCR of [panel]; empty when the panel has no text. */
        fun recognize(panel: CapturedPanel): List<OcrBlock>
    }

    interface BubbleDetector {
        /**
         * Bubbles in page pixels, each with a binary mask (1 = bubble) of the
         * same size as the panel. The mask is what InpaintEngine fills.
         */
        fun detect(panel: CapturedPanel): List<DetectedBubble>
    }

    interface Translator {
        /** One Bengali string per input, null where the chatbot gave nothing. */
        fun translate(blocks: List<String>): List<String?>
    }

    data class CapturedPanel(
        val width: Int,
        val height: Int,
        /** PNG/JPEG bytes handed to Lens; opaque to the core. */
        val image: ByteArray,
    )

    data class DetectedBubble(
        val box: BubbleClassifier.Box,
        /** Page-sized binary mask, 1 = bubble. */
        val mask: ByteArray,
    )

    data class Placement(
        val box: BubbleClassifier.Box,
        val text: String,
        val isSmall: Boolean,
    )

    data class PanelResult(
        val placements: List<Placement>,
        val bubbles: Int,
        val ocrBlocks: Int,
        val translated: Int,
        val totalMs: Long,
        /**
         * Bubble boxes whose text was not translated, with the source text, so
         * the chapter pass (ScanChapterRunner) can retry them with context.
         */
        val untranslated: List<Placement> = emptyList(),
    )

    /**
     * Runs the full per-panel flow. Returns placements ready for OverlayView;
     * any bubble without a translation is simply left out (its original text
     * stays visible rather than being replaced with nothing).
     */
    fun process(panel: CapturedPanel): PanelResult {
        val started = clock()
        val ocr = lens.recognize(panel)
        val detected = bubbles.detect(panel)
        val grouped =
            BubbleClassifier.assign(
                detected.map { it.box },
                ocr.map { BubbleClassifier.Box(it.bbox[0], it.bbox[1], it.bbox[2], it.bbox[3]) },
            )

        // One translation unit per bubble: its OCR lines joined in reading
        // order. A bubble with no line has nothing to translate.
        val perBubbleText =
            grouped.map { b ->
                b.lineIndices
                    .sortedWith(compareBy({ ocr[it].bbox[1] }, { ocr[it].bbox[0] }))
                    .joinToString(" ") { ocr[it].text.trim() }
                    .trim()
            }
        val translatable = perBubbleText.indices.filter { perBubbleText[it].isNotEmpty() }
        val translations =
            if (translatable.isEmpty()) {
                emptyList()
            } else {
                translator.translate(translatable.map { perBubbleText[it] })
            }
        val byIndex = HashMap<Int, String?>()
        translatable.forEachIndexed { k, bubbleIndex -> byIndex[bubbleIndex] = translations.getOrNull(k) }

        val placements = mutableListOf<Placement>()
        val untranslated = mutableListOf<Placement>()
        grouped.forEachIndexed { i, b ->
            val text = byIndex[i]?.takeIf { it.isNotBlank() }
            if (text != null) {
                placements += Placement(b.box, text, b.isSmall)
            } else if (perBubbleText[i].isNotEmpty()) {
                // Source text exists but the chatbot gave nothing: keep it so a
                // chapter pass can retry with context.
                untranslated += Placement(b.box, perBubbleText[i], b.isSmall)
            }
        }
        return PanelResult(
            placements = placements,
            bubbles = detected.size,
            ocrBlocks = ocr.size,
            translated = placements.size,
            totalMs = clock() - started,
            untranslated = untranslated,
        )
    }

    /**
     * Lines are joined top-to-bottom, then left-to-right (the D15 default).
     * Reading direction beyond this is decided by the caller before it hands us
     * the crop.
     */
}
