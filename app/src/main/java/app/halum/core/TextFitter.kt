package app.halum.core

/**
 * D11: text is placed at 8–10 sp inside bubble bounds; when even the smallest
 * size does not fit, the text is ellipsised rather than allowed to spill into
 * the artwork (a spill looks like a rendering bug, an ellipsis looks
 * intentional).
 *
 * Widths are measured by [Measure], an em-based approximation by default so
 * this stays pure JVM; the app substitutes a Paint-backed measurement.
 */
object TextFitter {
    const val MIN_SP = 8f
    const val MAX_SP = 10f
    const val LINE_GAP = 1.15f

    fun interface Measure {
        /** Width of [text] at [sizeSp], in the same units as the bubble box. */
        fun width(
            text: String,
            sizeSp: Float,
        ): Float
    }

    /** Average glyph width ≈ 0.62 em for the mixed Latin/Bengali overlay. */
    val EM_MEASURE =
        Measure { text, sizeSp ->
            text.codePoints().count().toFloat() * 0.62f * sizeSp
        }

    data class Fitted(
        val text: String,
        val sizeSp: Float,
        val lines: List<String>,
        val truncated: Boolean,
    )

    data class EllipsisResult(
        val text: String,
        val truncated: Boolean,
    )

    fun fit(
        text: String,
        boxWidthPx: Float,
        boxHeightPx: Float,
        measure: Measure = EM_MEASURE,
    ): Fitted {
        var size = MAX_SP
        while (size >= MIN_SP) {
            val lines = wrap(text, boxWidthPx, size, measure)
            if (lines.size * size * LINE_GAP <= boxHeightPx) {
                return Fitted(lines.joinToString("\n"), size, lines, false)
            }
            size -= 1f
        }
        val lines = wrap(text, boxWidthPx, MIN_SP, measure)
        val maxLines = (boxHeightPx / (MIN_SP * LINE_GAP)).toInt().coerceAtLeast(1)
        if (lines.size <= maxLines) {
            return Fitted(lines.joinToString("\n"), MIN_SP, lines, false)
        }
        val kept = lines.take(maxLines).toMutableList()
        // A kept line can still overflow horizontally (one very long word).
        for (i in kept.indices) {
            kept[i] = ellipsise(kept[i], boxWidthPx, MIN_SP, measure).text
        }
        return Fitted(kept.joinToString("\n"), MIN_SP, kept, true)
    }

    fun ellipsise(
        text: String,
        maxWidthPx: Float,
        sizeSp: Float,
        measure: Measure = EM_MEASURE,
    ): EllipsisResult {
        if (measure.width(text, sizeSp) <= maxWidthPx) return EllipsisResult(text, false)
        val ellipsis = "…"
        val cp = text.codePoints().toArray()
        for (n in cp.size - 1 downTo 0) {
            val candidate = String(cp, 0, n).trimEnd() + ellipsis
            if (measure.width(candidate, sizeSp) <= maxWidthPx) return EllipsisResult(candidate, true)
        }
        return EllipsisResult(ellipsis, true)
    }

    private fun wrap(
        text: String,
        widthPx: Float,
        sizeSp: Float,
        measure: Measure,
    ): List<String> {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return listOf("")
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (measure.width(candidate, sizeSp) <= widthPx || current.isEmpty()) {
                current = StringBuilder(candidate)
            } else {
                lines += current.toString()
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }
}
