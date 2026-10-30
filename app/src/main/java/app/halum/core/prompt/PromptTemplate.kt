package app.halum.core.prompt

import app.halum.core.PromptBuilder

/**
 * Q2: the translation prompt is user-editable at global, per-chapter and
 * per-bubble levels. This is the pure core: it renders a template by
 * substituting `{VARS}` and reports which variables were resolved, so the
 * Android side only owns persistence and UI.
 *
 * Templates are the D10 text by default; a user may edit them freely as long
 * as they keep the `{BLOCKS}` placeholder, otherwise the batch body is
 * appended automatically.
 */
object PromptTemplate {
    const val DEFAULT =
        "Translate each numbered block from Japanese (or Korean) to Bengali. " +
            "Reply ONLY with lines starting with [N]. Do NOT add introduction or " +
            "explanation. Preserve order, punctuation, honorifics.\n\n{BLOCKS}"

    val VARIABLES =
        listOf("MANGA_TITLE", "CHAPTER", "SOURCE_LANG", "TARGET_LANG", "BLOCKS", "DIRECTION")

    class Render(
        val text: String,
        /** Variables whose placeholder was present and replaced. */
        val resolved: List<String>,
        /** Variables present in the template but with no value supplied. */
        val unresolved: List<String>,
    )

    /**
     * Substitutes `{NAME}` for every entry in [vars], then fills `{BLOCKS}`
     * with numbered `[N] text` lines. Missing values leave the placeholder and
     * are reported in [Render.unresolved] so the caller can log them.
     */
    fun render(
        template: String,
        vars: Map<String, String>,
        blocks: List<String>,
    ): Render {
        val body =
            blocks.withIndex().joinToString("\n") { (i, b) -> "[${i + 1}] $b" }
        val all = vars + mapOf("BLOCKS" to body)
        var out = template
        val resolved = mutableListOf<String>()
        val unresolved = mutableListOf<String>()
        for (name in VARIABLES) {
            val token = "{$name}"
            if (!out.contains(token)) continue
            val value = all[name]
            if (value.isNullOrBlank()) {
                unresolved += name
            } else {
                out = out.replace(token, value)
                resolved += name
            }
        }
        // A template without {BLOCKS} still needs the batch body, or the
        // chatbot gets instructions and no text.
        if (!template.contains("{BLOCKS}")) out = "$out\n\n$body"
        return Render(out, resolved, unresolved)
    }

    /** D22/D10: the spec text as the reset target, kept in one place. */
    fun specDefault(): String = "${PromptBuilder.PREAMBLE}\n\n{BLOCKS}"
}
