package app.halum.core.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTemplateTest {
    @Test
    fun `blocks are rendered as numbered lines`() {
        val r =
            PromptTemplate.render(
                PromptTemplate.DEFAULT,
                mapOf("MANGA_TITLE" to "Frieren", "SOURCE_LANG" to "JAPANESE"),
                listOf("一", "二"),
            )
        assertTrue(r.text.contains("[1] 一"))
        assertTrue(r.text.contains("[2] 二"))
        assertTrue(r.resolved.contains("BLOCKS"))
    }

    @Test
    fun `all documented variables resolve when supplied`() {
        val tpl = "{MANGA_TITLE}|{CHAPTER}|{SOURCE_LANG}|{TARGET_LANG}|{DIRECTION}\n{BLOCKS}"
        val r =
            PromptTemplate.render(
                tpl,
                mapOf(
                    "MANGA_TITLE" to "T",
                    "CHAPTER" to "C",
                    "SOURCE_LANG" to "KOREAN",
                    "TARGET_LANG" to "BENGALI",
                    "DIRECTION" to "right_to_left",
                ),
                listOf("가"),
            )
        assertTrue(r.unresolved.isEmpty())
        assertTrue(r.text.startsWith("T|C|KOREAN|BENGALI|right_to_left"))
    }

    @Test
    fun `unresolved variables are reported, not silently dropped`() {
        val r = PromptTemplate.render("{MANGA_TITLE}\n{BLOCKS}", mapOf("CHAPTER" to "1"), listOf("x"))
        assertTrue(r.unresolved.contains("MANGA_TITLE"))
        assertTrue(r.text.contains("{MANGA_TITLE}"))
    }

    @Test
    fun `a template without BLOCKS still gets the batch body`() {
        val r = PromptTemplate.render("Translate this.", emptyMap(), listOf("a", "b"))
        assertTrue(r.text.contains("[1] a"))
        assertTrue(r.text.contains("[2] b"))
    }

    @Test
    fun `spec default matches the D10 preamble`() {
        assertTrue(PromptTemplate.specDefault().startsWith("Translate each numbered block"))
    }
}
