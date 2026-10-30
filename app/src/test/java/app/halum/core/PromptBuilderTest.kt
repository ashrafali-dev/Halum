package app.halum.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {
    @Test
    fun `preamble is verbatim D10`() {
        assertEquals(
            "Translate each numbered block from Japanese (or Korean) to Bengali. " +
                "Reply ONLY with lines starting with [N]. Do NOT add introduction or " +
                "explanation. Preserve order, punctuation, honorifics.",
            PromptBuilder.PREAMBLE,
        )
    }

    @Test
    fun `blocks are numbered from one in order`() {
        val prompt = PromptBuilder.build(listOf("こんにちは", "さようなら"))
        assertTrue(prompt.startsWith(PromptBuilder.PREAMBLE))
        assertTrue(prompt.endsWith("[1] こんにちは\n[2] さようなら"))
    }

    @Test
    fun `chapter prompt carries the character sheet`() {
        val prompt = PromptBuilder.buildChapter(listOf("아"), "민수 = Minsu\n지영 = Jiyoung")
        assertTrue(prompt.contains("Characters:"))
        assertTrue(prompt.contains("민수 = Minsu"))
        assertTrue(prompt.endsWith("[1] 아"))
    }

    @Test
    fun `empty character sheet adds no section`() {
        assertEquals(
            PromptBuilder.build(listOf("a")),
            PromptBuilder.buildChapter(listOf("a"), "   "),
        )
    }
}
