package app.halum.core

/** D10: preamble is verbatim; only the numbered blocks are appended. */
object PromptBuilder {
    const val PREAMBLE =
        "Translate each numbered block from Japanese (or Korean) to Bengali. " +
            "Reply ONLY with lines starting with [N]. Do NOT add introduction or " +
            "explanation. Preserve order, punctuation, honorifics."

    /** `[1] text` lines, one per block, blank line between preamble and body. */
    fun build(blocks: List<String>): String {
        val body =
            blocks.withIndex().joinToString("\n") { (i, text) ->
                "[${i + 1}] $text"
            }
        return "$PREAMBLE\n\n$body"
    }

    /** D7: chapter-level prompt keeps a character sheet so names stay stable. */
    fun buildChapter(blocks: List<String>, characterSheet: String): String {
        val sheet = characterSheet.trim()
        val preamble = if (sheet.isEmpty()) PREAMBLE else "$PREAMBLE\n\nCharacters:\n$sheet"
        val body =
            blocks.withIndex().joinToString("\n") { (i, text) ->
                "[${i + 1}] $text"
            }
        return "$preamble\n\n$body"
    }
}
