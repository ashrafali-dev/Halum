package app.halum

import app.halum.util.Log
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Smoke test that the logging indirection used by every pipeline tag works on a plain JVM. */
class LogSinkTest {
    private val captured = mutableListOf<String>()
    private val previous = Log.sink

    @Before
    fun installSink() {
        Log.sink =
            object : Log.Sink {
                override fun write(priority: Int, tag: String, message: String) {
                    captured += "$priority|$tag|$message"
                }
            }
    }

    @After
    fun restoreSink() {
        Log.sink = previous
    }

    @Test
    fun `info lines keep tag and message verbatim`() {
        Log.i("LENS_OCR", "blocks=3 letters=42 ms=120")
        assertEquals(listOf("4|LENS_OCR|blocks=3 letters=42 ms=120"), captured)
    }

    @Test
    fun `errors append exception class and message`() {
        Log.e("BRIDGE", "send failed", IllegalStateException("not injected"))
        assertTrue(captured.single().endsWith("send failed :: IllegalStateException: not injected"))
    }
}
