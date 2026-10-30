package app.halum.core.pipeline

import app.halum.core.BubbleClassifier
import app.halum.core.OcrBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeRunnerTest {
    private val panel = PanelPipeline.CapturedPanel(100, 200, ByteArray(0))

    private fun pipeline(placements: Int) =
        PanelPipeline(
            lens =
                object : PanelPipeline.LensClient {
                    override fun recognize(panel: PanelPipeline.CapturedPanel) =
                        List(placements) { OcrBlock("t$it", floatArrayOf(10f, 10f + it * 20f, 90f, 25f + it * 20f)) }
                },
            bubbles =
                object : PanelPipeline.BubbleDetector {
                    override fun detect(panel: PanelPipeline.CapturedPanel) =
                        List(placements) {
                            PanelPipeline.DetectedBubble(
                                BubbleClassifier.Box(0f, it * 20f, 100f, 20f + it * 20f),
                                ByteArray(100 * 200),
                            )
                        }
                },
            translator =
                object : PanelPipeline.Translator {
                    override fun translate(blocks: List<String>) = blocks.map { "বাংলা" }
                },
            clock = { 0L },
        )

    private class CaptureSink : RealtimeRunner.Sink {
        var placed = -1
        var failed: String? = null

        override fun onPlacements(
            placements: List<PanelPipeline.Placement>,
            panelWidth: Int,
            panelHeight: Int,
        ) {
            placed = placements.size
        }

        override fun onFailed(reason: String) {
            failed = reason
        }
    }

    @Test
    fun `a panel with text paints its placements`() {
        val sink = CaptureSink()
        val m = RealtimeRunner(pipeline(2)).run(panel, sink)
        assertEquals(2, sink.placed)
        assertEquals(2, m!!.placed)
    }

    @Test
    fun `a run whose generation is superseded while processing is dropped`() {
        // Model the race: a pipeline that, while running, is superseded by a
        // newer request. Simulate by cancelling from inside the translator,
        // which runs during process() — after run() bumped the generation.
        lateinit var runner: RealtimeRunner
        val raced =
            PanelPipeline(
                lens =
                    object : PanelPipeline.LensClient {
                        override fun recognize(panel: PanelPipeline.CapturedPanel) =
                            listOf(OcrBlock("t", floatArrayOf(10f, 10f, 90f, 25f)))
                    },
                bubbles =
                    object : PanelPipeline.BubbleDetector {
                        override fun detect(panel: PanelPipeline.CapturedPanel) =
                            listOf(
                                PanelPipeline.DetectedBubble(
                                    BubbleClassifier.Box(0f, 0f, 100f, 40f),
                                    ByteArray(
                                        100 * 200,
                                    ),
                                ),
                            )
                    },
                translator =
                    object : PanelPipeline.Translator {
                        override fun translate(blocks: List<String>): List<String?> {
                            runner.cancel()
                            return listOf("অ")
                        }
                    },
                clock = { 0L },
            )
        runner = RealtimeRunner(raced)
        val sink = CaptureSink()
        val m = runner.run(panel, sink)
        assertNull("superseded result is not delivered", m)
        assertEquals(-1, sink.placed)
    }

    @Test
    fun `cancel then a fresh run is kept`() {
        val runner = RealtimeRunner(pipeline(1))
        runner.cancel()
        val sink = CaptureSink()
        val m = runner.run(panel, sink)
        assertTrue(m != null)
        assertEquals(1, sink.placed)
    }

    @Test
    fun `a panel with no bubble fails without painting`() {
        val sink = CaptureSink()
        val m = RealtimeRunner(pipeline(0)).run(panel, sink)
        assertNull(m)
        assertEquals("no placement", sink.failed)
    }
}

class ScanChapterRunnerTest {
    private fun panel(w: Int = 100) = PanelPipeline.CapturedPanel(w, 200, ByteArray(0))

    private fun result(
        placements: Int,
        untranslated: Int = 0,
    ) = PanelPipeline.PanelResult(
        placements = List(placements) { place() },
        bubbles = placements + untranslated,
        ocrBlocks = placements + untranslated,
        translated = placements,
        totalMs = 0,
        untranslated = List(untranslated) { place(text = "src$it") },
    )

    private fun place(text: String = "x") = PanelPipeline.Placement(BubbleClassifier.Box(0f, 0f, 10f, 10f), text, false)

    private class CollectSink : ScanChapterRunner.Sink {
        var panels = mutableListOf<Int>()
        var retried = -1
        var last: ScanChapterRunner.ChapterResult? = null

        override fun onPanel(
            index: Int,
            total: Int,
            placements: List<PanelPipeline.Placement>,
        ) {
            panels += placements.size
        }

        override fun onChapterRetry(missing: Int) {
            retried = missing
        }

        override fun onFinished(result: ScanChapterRunner.ChapterResult) {
            last = result
        }
    }

    @Test
    fun `every panel is visited in order`() {
        val sink = CollectSink()
        val r =
            ScanChapterRunner { 0L }.run(
                panels = List(3) { panel() },
                characterSheet = "",
                perPanel = { result(1) },
                translator =
                    object : ScanChapterRunner.ChapterTranslator {
                        override fun translateChapter(prompt: String): List<String?> = emptyList()
                    },
                sink = sink,
            )
        assertEquals(listOf(1, 1, 1), sink.panels)
        assertEquals(3, r.placed)
        assertEquals(3, r.total)
    }

    @Test
    fun `the chapter pass retries untranslated bubbles with the character sheet`() {
        val sink = CollectSink()
        var sawSheet = false
        val runner = ScanChapterRunner { 0L }
        val r =
            runner.run(
                panels = listOf(panel(), panel()),
                characterSheet = "철수 = Chul-soo",
                // panel 1 fills, panel 2 misses one bubble
                perPanel = { p -> if (p.width == 100) result(1, untranslated = 1) else result(0) },
                translator =
                    object : ScanChapterRunner.ChapterTranslator {
                        override fun translateChapter(prompt: String): List<String?> {
                            sawSheet = prompt.contains("철수 = Chul-soo")
                            return listOf("পূরণ")
                        }
                    },
                sink = sink,
            )
        assertTrue("chapter prompt carried the sheet", sawSheet)
        assertEquals(2, sink.retried) // two panels each report 1 untranslated
        assertEquals(2, r.recoveredInChapterPass)
        assertEquals(2 + 2, r.placed)
    }

    @Test
    fun `an empty page finishes without work`() {
        val sink = CollectSink()
        val r =
            ScanChapterRunner { 0L }.run(
                panels = emptyList(),
                characterSheet = "",
                perPanel = { error("must not run") },
                translator =
                    object : ScanChapterRunner.ChapterTranslator {
                        override fun translateChapter(prompt: String): List<String?> = emptyList()
                    },
                sink = sink,
            )
        assertEquals(0, r.total)
        assertTrue(r.failures.isNotEmpty())
        assertTrue(sink.last === r)
    }
}
