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
        // Indentation is kept only to tell a wrapped line from a new block.
        val raw = reply.lines().filter { it.isNotBlank() }
        val lines = raw.map { it.trim() }

        val byMarker = mutableMapOf<Int, StringBuilder>()
        var sawMarker = false
        for ((line, original) in lines.zip(raw)) {
            val m = MARKER.find(line)
            if (m != null) {
                sawMarker = true
                val n = m.groupValues[1].toIntOrNull()
                val text = m.groupValues[2].trim()
                if (n != null && n in 1..blockCount) {
                    byMarker.getOrPut(n) { StringBuilder() }
                    if (text.isNotEmpty()) byMarker.getValue(n).appendText(text)
                }
            } else if (original.startsWith(" ") || original.startsWith("\t")) {
                // An indented line continues the block above it; an indented
                // line before any marker is chat noise and is dropped.
                byMarker.keys.maxOrNull()?.let { byMarker.getValue(it).appendText(line) }
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

    private fun StringBuilder.appendText(text: String) {
        if (isNotEmpty()) append(' ')
        append(text)
    }
}
