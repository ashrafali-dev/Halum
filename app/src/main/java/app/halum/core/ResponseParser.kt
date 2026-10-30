package app.halum.core

/**
 * Parses the chatbot's `[N] translated` reply (D6).
 *
 * Two modes, tried in order:
 *  1. markers — every block must be found exactly once; anything else is
 *     reported as a partial so the caller can fall back to one-block sends.
 *  2. positional — no markers at all but line count matches block count.
 *
 * Never invents text: a block that is missing stays null and is listed in
 * [Result.missing]. Partial output is useful (place what exists); guessing is
 * not (wrong text in the wrong bubble).
 */
object ResponseParser {
    private val MARKER = Regex("""^\s*\[(\d{1,3})]\s*(.*)$""")

    data class Result(
        /** Size = block count; null = no translation for that block. */
        val translations: List<String?>,
        val mode: Mode,
        val missing: List<Int>,
    ) {
        val complete: Boolean get() = missing.isEmpty()
    }

    enum class Mode { MARKERS, POSITIONAL, NONE }

    fun parse(
        reply: String,
        blockCount: Int,
    ): Result {
        if (blockCount <= 0) return Result(emptyList(), Mode.NONE, emptyList())
        val lines = reply.lines().map { it.trim() }.filter { it.isNotEmpty() }

        val byMarker = mutableMapOf<Int, StringBuilder>()
        var sawMarker = false
        for (line in lines) {
            val m = MARKER.find(line) ?: continue
            sawMarker = true
            val n = m.groupValues[1].toIntOrNull() ?: continue
            if (n !in 1..blockCount) continue
            val text = m.groupValues[2].trim()
            if (text.isEmpty()) continue
            byMarker
                .getOrPut(n) { StringBuilder() }
                .let {
                    if (it.isNotEmpty()) it.append(' ')
                    it.append(text)
                }
        }
        if (sawMarker) {
            val values = (1..blockCount).map { byMarker[it]?.toString() }
            val missing = (1..blockCount).filter { values[it - 1] == null }
            return Result(values, Mode.MARKERS, missing)
        }

        if (lines.size == blockCount) {
            return Result(lines, Mode.POSITIONAL, emptyList())
        }
        return Result(List(blockCount) { null }, Mode.NONE, (1..blockCount).toList())
    }
}
