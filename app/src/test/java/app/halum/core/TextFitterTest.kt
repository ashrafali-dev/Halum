package app.halum.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFitterTest {
    private val at10 = TextFitter.Measure { t, _ -> t.length * 6.2f } // ≈0.62 em at 10 sp

    @Test
    fun `short text stays at the largest size`() {
        val f = TextFitter.fit("হ্যালো", 100f, 60f, at10)
        assertEquals(10f, f.sizeSp, 0f)
        assertEquals("হ্যালো", f.text)
        assertFalse(f.truncated)
    }

    @Test
    fun `long text shrinks instead of overflowing`() {
        val text = "এটি একটি অনেক বড় বাক্য যা ছোট বুদবুদের ভিতরে ফিট করতে হবে"
        val f = TextFitter.fit(text, 60f, 80f, at10)
        assertTrue(f.sizeSp in 8f..10f)
        assertFalse(f.truncated)
        assertTrue(f.lines.size > 1)
    }

    @Test
    fun `impossible text is ellipsised at the minimum size`() {
        val text = (1..80).joinToString(" ") { "শব্দ$it" }
        val f = TextFitter.fit(text, 50f, 20f, at10)
        assertEquals(8f, f.sizeSp, 0f)
        assertTrue(f.truncated)
        // The wrap limit counts lines but not their width, so every kept line
        // is ellipsised to fit; the last line must at least signal cut text.
        assertTrue(f.lines.size <= 2)
        assertTrue(f.lines.any { it.endsWith("…") })
        f.lines.forEach { assertTrue(at10.width(it, 8f) <= 50f) }
    }

    @Test
    fun `ellipsise keeps the widest prefix that fits`() {
        val r = TextFitter.ellipsise("abcdefghij", 40f, 10f, at10)
        assertTrue(r.truncated)
        assertTrue(at10.width(r.text, 10f) <= 40f)
    }

    @Test
    fun `already fitting text is not ellipsised`() {
        val r = TextFitter.ellipsise("abc", 100f, 10f, at10)
        assertEquals("abc", r.text)
        assertFalse(r.truncated)
    }

    @Test
    fun `wrapping never loses words`() {
        val text = "one two three four five six seven"
        val f = TextFitter.fit(text, 60f, 200f, at10)
        assertEquals(text.split(" "), f.lines.flatMap { it.split(" ") })
    }
}
