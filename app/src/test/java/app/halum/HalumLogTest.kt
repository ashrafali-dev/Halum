package app.halum

import app.halum.util.HalumLog
import app.halum.util.Log
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The copy-paste log must hold every line, chain to logcat, and stay bounded. */
class HalumLogTest {
    private val previous = Log.sink

    @Before
    fun install() {
        HalumLog.install(Log.androidSink)
        HalumLog.clear()
    }

    @After
    fun restore() {
        Log.sink = previous
        HalumLog.clear()
    }

    @Test
    fun `installed sink records the line with tag and message`() {
        Log.i("LENS_OCR", "blocks=3")
        val line = HalumLog.dump().lines().single()
        assertTrue(line, line.endsWith("[I] LENS_OCR: blocks=3"))
        assertTrue(line, line.matches(Regex("""\d{2}:\d{2}:\d{2}\.\d{3} .*""")))
    }

    @Test
    fun `dump is oldest first, one line per call`() {
        Log.i("A", "one")
        Log.w("B", "two")
        val lines = HalumLog.dump().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith("A: one"))
        assertTrue(lines[1].endsWith("B: two"))
    }

    @Test
    fun `errorCount counts errors but not warnings`() {
        Log.w("A", "warn")
        Log.e("B", "err")
        assertEquals(1, HalumLog.errorCount())
    }

    @Test
    fun `ring drops the oldest line once full`() {
        repeat(2_001) { Log.d("T", "line $it") }
        val lines = HalumLog.dump().lines()
        assertEquals(2_000, lines.size)
        assertFalse(lines.first().endsWith(": line 0"))
        assertTrue(lines.last().endsWith(": line 2000"))
    }
}
