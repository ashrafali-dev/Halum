package app.halum.core.pipeline

/**
 * D7: realtime mode processes the single visible panel, latest-wins. A new
 * request cancels the in-flight one; when a newer panel arrives the older
 * result is discarded rather than placed.
 *
 * The coordinator is pure: the caller supplies a [PanelPipeline] and sinks the
 * placements into OverlayView. The "latest wins" rule is a generation counter,
 * so nothing races on threads.
 */
class RealtimeRunner(
    private val pipeline: PanelPipeline,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    interface Sink {
        fun onPlacements(
            placements: List<PanelPipeline.Placement>,
            panelWidth: Int,
            panelHeight: Int,
        )

        fun onFailed(reason: String)
    }

    data class Metrics(
        val panelWidth: Int,
        val panelHeight: Int,
        val bubbles: Int,
        val placed: Int,
        val totalMs: Long,
        val currentGen: Long,
    )

    /** Monotonic generation; a bump means an older result must be dropped. */
    var generation: Long = 0
        private set

    @Volatile
    private var cancelledUpTo: Long = 0

    /** Called on scroll / new panel: the current work is stale. */
    fun cancel() {
        cancelledUpTo = generation
    }

    /**
     * Runs one panel and delivers its placements unless a newer request
     * (cancel or another [run]) arrived first. Synchronous for testability;
     * the app calls it on a worker thread.
     */
    fun run(
        panel: PanelPipeline.CapturedPanel,
        sink: Sink,
    ): Metrics? {
        val myGen = ++generation
        val started = clock()
        val result = pipeline.process(panel)
        val elapsed = clock() - started
        if (myGen <= cancelledUpTo || myGen != generation) {
            // A newer panel superseded this one; do not paint stale text (D9).
            return null
        }
        if (result.placements.isEmpty()) {
            sink.onFailed("no placement")
            return null
        }
        sink.onPlacements(result.placements, panel.width, panel.height)
        return Metrics(
            panelWidth = panel.width,
            panelHeight = panel.height,
            bubbles = result.bubbles,
            placed = result.placements.size,
            totalMs = elapsed,
            currentGen = myGen,
        )
    }
}
