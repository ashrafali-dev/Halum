package app.halum.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseParserTest {
    @Test
    fun `numbered reply maps one-to-one`() {
        val r = ResponseParser.parse("[1] এক\n[2] দুই\n[3] তিন", 3)
        assertEquals(ResponseParser.Mode.MARKERS, r.mode)
        assertEquals(listOf("এক", "দুই", "তিন"), r.translations)
        assertTrue(r.complete)
    }

    @Test
    fun `out-of-order markers keep their meaning`() {
        val r = ResponseParser.parse("[3] three\n[1] one\n[2] two", 3)
        assertEquals(listOf("one", "two", "three"), r.translations)
    }

    @Test
    fun `wrapped lines are joined per block`() {
        val reply = "[1] প্রথম লাইন\n    দ্বিতীয় লাইন\n[2] দুই"
        val r = ResponseParser.parse(reply, 2)
        assertEquals("প্রথম লাইন দ্বিতীয় লাইন", r.translations[0])
        assertEquals("দুই", r.translations[1])
    }

    @Test
    fun `missing blocks are reported, not invented`() {
        val r = ResponseParser.parse(
            "Sure! Here you go:\n[1] one\n[3] three\nHope that helps.",
            3,
        )
        assertEquals(listOf("one", null, "three"), r.translations)
        assertEquals(listOf(1), r.missing)
        assertFalse(r.complete)
    }

    @Test
    fun `markers outside the block range are ignored`() {
        val r = ResponseParser.parse("[0] zero\n[1] one\n[9] nine", 2)
        assertEquals(listOf("one", null), r.translations)
    }

    @Test
    fun `no markers but matching line count uses positional mode`() {
        val r = ResponseParser.parse("এক\nদুই\nতিন", 3)
        assertEquals(ResponseParser.Mode.POSITIONAL, r.mode)
        assertEquals(listOf("এক", "দুই", "তিন"), r.translations)
    }

    @Test
    fun `no markers and wrong line count yields nothing`() {
        val r = ResponseParser.parse("এক\nদুই", 3)
        assertEquals(ResponseParser.Mode.NONE, r.mode)
        assertEquals(3, r.missing.size)
        r.translations.forEach { assertNull(it) }
    }

    @Test
    fun `empty block list is trivially complete`() {
        val r = ResponseParser.parse("anything", 0)
        assertTrue(r.complete)
        assertEquals(ResponseParser.Mode.NONE, r.mode)
    }
}
