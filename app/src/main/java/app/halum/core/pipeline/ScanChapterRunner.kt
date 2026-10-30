package app.halum.core.pipeline

import app.halum.core.BatchPlanner
import app.halum.core.PromptBuilder

/**
 * D7/D19 Step 3: scan mode. Runs the shared per-panel flow over every panel of
 * the current page, then makes a chapter-level pass that retries every bubble
 * the per-panel translator could not fill, using the chapter prompt with a
 * character sheet so names stay stable (D10). D17 scope: the current
 * scrollable page only.
 *
 * Pure JVM: the caller supplies the panels and the per-panel function; the
 * chapter translator is an injected port. No Android, no WebView.
 */
class ScanChapterRunner(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    interface ChapterTranslator {
        /** Translates one chapter batch (already built by PromptBuilder); nulls where missing. */
        fun translateChapter(prompt: String): List<String?>
    }

    interface Sink {
        fun onPanel(
            index: Int,
            total: Int,
            placements: List<PanelPipeline.Placement>,
        )

        fun onChapterRetry(missing: Int)

        fun onFinished(result: ChapterResult)
    }

    data class ChapterResult(
        val placed: Int,
        val total: Int,
        val recoveredInChapterPass: Int,
        val elapsedMs: Long,
        val failures: List<String>,
    )

    /**
     * @param perPanel the shared flow (OCR → detect → group → per-panel translate).
     * @param characterSheet D10 character sheet; may be empty.
     */
    fun run(
        panels: List<PanelPipeline.CapturedPanel>,
        characterSheet: String,
        perPanel: (PanelPipeline.CapturedPanel) -> PanelPipeline.PanelResult,
        translator: ChapterTranslator,
        sink: Sink,
    ): ChapterResult {
        val started = clock()
        if (panels.isEmpty()) {
            val empty = ChapterResult(0, 0, 0, 0, listOf("no panels"))
            sink.onFinished(empty)
            return empty
        }

        val results =
            panels.mapIndexed { i, p ->
                val r = perPanel(p)
                sink.onPanel(i + 1, panels.size, r.placements)
                r
            }

        val placedPerPanel = results.sumOf { it.placements.size }
        val recovered = chapterPass(results, characterSheet, translator, sink)
        val elapsed = clock() - started
        val failures =
            results
                .withIndex()
                .filter { (_, r) -> r.placements.isEmpty() && r.bubbles > 0 }
                .map { (i, _) -> "panel ${i + 1}: no placement" }
        val result =
            ChapterResult(
                placed = placedPerPanel + recovered,
                total = panels.size,
                recoveredInChapterPass = recovered,
                elapsedMs = elapsed,
                failures = failures,
            )
        sink.onFinished(result)
        return result
    }

    /**
     * Collects every untranslated bubble across the page, plans batches at
     * D6 limits, and retries them with the chapter prompt. Returns how many
     * were recovered.
     */
    private fun chapterPass(
        results: List<PanelPipeline.PanelResult>,
        characterSheet: String,
        translator: ChapterTranslator,
        sink: Sink,
    ): Int {
        val pending = results.flatMap { it.untranslated }
        if (pending.isEmpty()) return 0
        sink.onChapterRetry(pending.size)

        val sources = pending.map { it.text }
        var recovered = 0
        for (batch in BatchPlanner.plan(sources)) {
            val batchTexts = batch.indices.map { sources[it] }
            val prompt = PromptBuilder.buildChapter(batchTexts, characterSheet)
            val translated = translator.translateChapter(prompt)
            batch.indices.forEachIndexed { k, global ->
                if (translated.getOrNull(k)?.isNotBlank() == true) recovered++
            }
        }
        return recovered
    }
}
