package app.halum.core.pipeline

import app.halum.core.BubbleClassifier
import app.halum.core.OcrBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D19 Step 2: the panel pipeline over fakes. This proves the wiring and the
 * ordering rules without Android, OpenCV or a model; the real engines are
 * exercised by `tools/engine_verify.py` and the app adapters.
 */
class PanelPipelineTest {
    private val panel = PanelPipeline.CapturedPanel(100, 200, ByteArray(0))

    private fun ocr(
        text: String,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
    ) = OcrBlock(text, floatArrayOf(x1, y1, x2, y2), "ja")

    private fun bubble(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
    ) = PanelPipeline.DetectedBubble(BubbleClassifier.Box(x1, y1, x2, y2), ByteArray(100 * 200))

    private fun pipeline(
        ocrBlocks: List<OcrBlock>,
        detected: List<PanelPipeline.DetectedBubble>,
        now: () -> Long = { 0L },
        translate: (List<String>) -> List<String?>,
    ) = PanelPipeline(
        lens =
            object : PanelPipeline.LensClient {
                override fun recognize(panel: PanelPipeline.CapturedPanel) = ocrBlocks
            },
        bubbles =
            object : PanelPipeline.BubbleDetector {
                override fun detect(panel: PanelPipeline.CapturedPanel) = detected
            },
        translator =
            object : PanelPipeline.Translator {
                override fun translate(blocks: List<String>) = translate(blocks)
            },
        clock = now,
    )

    @Test
    fun `lines inside a bubble are joined and translated as one block`() {
        val ocr = listOf(ocr("こんにちは", 10f, 10f, 90f, 30f), ocr("世界", 10f, 35f, 90f, 55f))
        val b = listOf(bubble(0f, 0f, 100f, 80f))
        var seen: List<String> = emptyList()

        val result =
            pipeline(ocr, b) { blocks ->
                seen = blocks
                listOf("[1] হ্যালো বিশ্ব")
            }.process(panel)

        assertEquals(listOf("こんにちは 世界"), seen)
        assertEquals(1, result.placements.size)
        assertEquals("হ্যালো বিশ্ব", result.placements[0].text)
        assertEquals(2, result.ocrBlocks)
        assertEquals(1, result.bubbles)
    }

    @Test
    fun `a bubble without a translation is left out`() {
        val ocr = listOf(ocr("x", 10f, 10f, 20f, 20f))
        val b = listOf(bubble(0f, 0f, 100f, 80f))

        // The chatbot returned nothing for the only block.
        val result = pipeline(ocr, b) { null }.process(panel)

        assertTrue(result.placements.isEmpty())
        assertEquals(1, result.bubbles)
        assertEquals(0, result.translated)
    }

    @Test
    fun `small bubbles keep their isSmall flag for 8 sp placement`() {
        val ocr = listOf(ocr("whisper", 10f, 10f, 40f, 20f))
        // width 50 < 60 -> small
        val b = listOf(bubble(0f, 0f, 50f, 40f))

        val result = pipeline(ocr, b) { listOf("ফিসফিস") }.process(panel)

        assertEquals(1, result.placements.size)
        assertTrue(result.placements[0].isSmall)
    }

    @Test
    fun `an empty panel does no translation work`() {
        var called = false
        val result =
            pipeline(emptyList(), emptyList()) {
                called = true
                emptyList()
            }.process(panel)

        assertEquals(0, result.placements.size)
        assertTrue("translator must not be called with no text", !called)
    }

    @Test
    fun `metrics report elapsed time from the injected clock`() {
        var t = 100L
        val ocr = listOf(ocr("a", 10f, 10f, 20f, 20f))
        val b = listOf(bubble(0f, 0f, 100f, 80f))
        val p =
            pipeline(
                ocr,
                b,
                now = {
                    val v = t
                    t += 250
                    v
                },
                translate = { listOf("ক") },
            )
        val result = p.process(panel)
        assertEquals(250L, result.totalMs)
    }
}
