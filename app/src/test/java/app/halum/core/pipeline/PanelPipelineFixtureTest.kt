package app.halum.core.pipeline

import app.halum.core.BubbleClassifier
import app.halum.core.ChatBridge
import app.halum.core.OcrBlock
import app.halum.core.ResponseParser
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * D19 Step 2: the real per-panel flow over one fixture, end to end.
 *
 * Lens is a stub that returns the fixture's OCR blocks; the bubble detector
 * returns the fixture's boxes; the translator drives a real [ChatBridge] over a
 * scripted [ChatBridge.ChatPage] whose reply is the fixture's `[N]` block (so
 * BatchPlanner, PromptBuilder, ResponseParser and the state machine all run for
 * real). The overlay placements are written to `build/pipeline/overlay.json` as
 * the step's evidence.
 */
class PanelPipelineFixtureTest {
    private class ScriptedPage(
        private val reply: String,
    ) : ChatBridge.ChatPage {
        val prompts = mutableListOf<String>()
        private var visible = false

        override fun isReady() = true

        override fun inject(text: String) {
            prompts += text
        }

        override fun lastReply(): String? {
            if (prompts.isEmpty()) return null
            visible = true
            return if (visible) reply else null
        }

        override fun newConversation() {
            visible = false
        }
    }

    private class NoSleepClock : ChatBridge.Clock {
        var now = 0L

        override fun nowMillis() = now

        override fun sleep(millis: Long) {
            now += millis
        }
    }

    /** Adapter that parses the bridge reply with the production parser. */
    private class BridgeTranslator(
        private val bridge: ChatBridge,
    ) : PanelPipeline.Translator {
        override fun translate(blocks: List<String>): List<String?> {
            val outcome = bridge.translate(blocks)
            // ChatBridge already returns per-block text; surface it as-is.
            return outcome.translations
        }
    }

    private fun fixture(): JSONObject {
        val dir = System.getProperty("halum.fixtures") ?: error("halum.fixtures not set")
        return JSONObject(File(dir, "pipeline/panel_ko.json").readText())
    }

    @Test
    fun `one fixture panel runs end to end and writes overlay json`() {
        val fx = fixture()
        val w = fx.getJSONObject("panel").getInt("width")
        val h = fx.getJSONObject("panel").getInt("height")

        val ocr =
            fx.getJSONArray("ocr").let { arr ->
                List(arr.length()) { i ->
                    val o = arr.getJSONObject(i)
                    val b = o.getJSONArray("bbox")
                    OcrBlock(
                        text = o.getString("text"),
                        bbox = floatArrayOf(b.getDouble(0).toFloat(), b.getDouble(1).toFloat(), b.getDouble(2).toFloat(), b.getDouble(3).toFloat()),
                        script = o.getString("script"),
                    )
                }
            }
        val bubbles =
            fx.getJSONArray("bubbles").let { arr ->
                List(arr.length()) { i ->
                    val b = arr.getJSONArray(i)
                    PanelPipeline.DetectedBubble(
                        BubbleClassifier.Box(b.getDouble(0).toFloat(), b.getDouble(1).toFloat(), b.getDouble(2).toFloat(), b.getDouble(3).toFloat()),
                        ByteArray(w * h),
                    )
                }
            }

        val page = ScriptedPage(fx.getString("reply"))
        val bridge = ChatBridge(page, NoSleepClock())
        val pipeline =
            PanelPipeline(
                lens = object : PanelPipeline.LensClient {
                    override fun recognize(panel: PanelPipeline.CapturedPanel) = ocr
                },
                bubbles = object : PanelPipeline.BubbleDetector {
                    override fun detect(panel: PanelPipeline.CapturedPanel) = bubbles
                },
                translator = BridgeTranslator(bridge),
                clock = { 0L },
            )

        val result = pipeline.process(PanelPipeline.CapturedPanel(w, h, ByteArray(0)))

        val expected = fx.getJSONObject("expected")
        assertEquals("placements", expected.getInt("placements"), result.placements.size)
        assertEquals("bubbles", bubbles.size, result.bubbles)
        assertEquals(
            "small",
            expected.getInt("small"),
            result.placements.count { it.isSmall },
        )
        assertTrue("every placement has text", result.placements.all { it.text.isNotBlank() })

        // Evidence: overlay.json in the shape OverlayView.show consumes.
        writeOverlayJson(result, w, h)
        println(
            "PIPE_DONE: panel=${w}x$h bubbles=${result.bubbles} " +
                "placed=${result.placements.size} totalMs=${result.totalMs}",
        )
    }

    @Test
    fun `the fixture reply is a well-formed marker batch`() {
        val reply = fixture().getString("reply")
        val parsed = ResponseParser.parse(reply, 3)
        assertEquals(ResponseParser.Mode.MARKERS, parsed.mode)
        assertEquals(3, parsed.translations.count { it != null })
    }

    private fun writeOverlayJson(
        result: PanelPipeline.PanelResult,
        w: Int,
        h: Int,
    ) {
        val out = JSONObject()
        out.put("panelWidth", w)
        out.put("panelHeight", h)
        out.put("bubbles", result.bubbles)
        out.put("translated", result.translated)
        val arr = JSONArray()
        for (p in result.placements) {
            arr.put(
                JSONObject().apply {
                    put("x1", p.box.x1.toDouble())
                    put("y1", p.box.y1.toDouble())
                    put("x2", p.box.x2.toDouble())
                    put("y2", p.box.y2.toDouble())
                    put("text", p.text)
                    put("isSmall", p.isSmall)
                },
            )
        }
        out.put("placements", arr)
        val dir = File("build/pipeline")
        dir.mkdirs()
        File(dir, "overlay.json").writeText(out.toString(2) + "\n")
    }
}
