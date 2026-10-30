package app.halum.core.lens

import app.halum.core.LensProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** AC2 replay path + the rate limit and retry rules that guard the endpoint. */
class LensOcrClientTest {
    private val fixturesDir =
        File(System.getProperty("halum.fixtures") ?: "../../fixtures")

    private fun fixture(name: String) = File(fixturesDir, "$name.bin").readBytes()

    private fun request() =
        LensProtocol.Request(
            uuid = 1,
            sequenceId = 1,
            imageSequenceId = 1,
            width = 800,
            height = 1200,
            targetLanguage = "ja",
            region = "US",
            timeZone = "Asia/Dhaka",
            jpeg = byteArrayOf(1, 2, 3),
        )

    @Test
    fun `replay transport drives the real decoder`() {
        val client = LensOcrClient(ReplayTransport(listOf(fixture("lens_ja"))))
        val blocks = client.recognize(request(), 800, 1200)

        assertEquals(5, blocks.size)
        assertEquals("ja", blocks[0].script)
        assertTrue(blocks.all { it.text.isNotEmpty() })
    }

    @Test
    fun `korean fixture replays through the client too`() {
        val client = LensOcrClient(ReplayTransport(listOf(fixture("lens_ko"))))
        assertEquals(3, client.recognize(request(), 800, 1200).size)
    }

    @Test
    fun `the panel size reaches the transport`() {
        var seen: Pair<Int, Int>? = null
        val transport =
            object : LensTransport {
                override fun post(
                    bytes: ByteArray,
                    width: Int,
                    height: Int,
                ): ByteArray {
                    seen = width to height
                    return fixture("lens_ko")
                }
            }

        LensOcrClient(transport).recognize(request(), 800, 1200)

        assertEquals(800 to 1200, seen)
    }

    @Test
    fun `a transient failure is retried once`() {
        var calls = 0
        val transport =
            object : LensTransport {
                override fun post(
                    bytes: ByteArray,
                    width: Int,
                    height: Int,
                ): ByteArray {
                    calls++
                    if (calls == 1) throw LensTransport.TransportException("boom", 503)
                    return fixture("lens_ko")
                }
            }

        val blocks = LensOcrClient(transport).recognize(request(), 800, 1200)

        assertEquals(2, calls)
        assertEquals(3, blocks.size)
    }

    @Test
    fun `a 4xx is not retried`() {
        var calls = 0
        val transport =
            object : LensTransport {
                override fun post(
                    bytes: ByteArray,
                    width: Int,
                    height: Int,
                ): ByteArray {
                    calls++
                    throw LensTransport.TransportException("bad request", 400)
                }
            }

        runCatching { LensOcrClient(transport).recognize(request(), 800, 1200) }

        assertEquals(1, calls)
    }

    @Test
    fun `the rate limit stops the sixty-first call in a minute`() {
        var now = 0L
        val limiter = RateLimiter(perMinute = 60, clock = { now })
        repeat(60) { limiter.acquire() }
        now += 1_000

        val ex = runCatching { limiter.acquire() }.exceptionOrNull()

        assertTrue("expected LensException, got $ex", ex is LensProtocol.LensException)
    }

    @Test
    fun `the window slides`() {
        var now = 0L
        val limiter = RateLimiter(perMinute = 2, clock = { now })
        limiter.acquire()
        limiter.acquire()
        now += RateLimiter.WINDOW_MS

        limiter.acquire() // both t=0 calls are exactly one window old
        assertEquals(1, limiter.used())
    }

    @Test
    fun `the serialised request reaches the transport verbatim`() {
        // Lens takes the bare protobuf, not a multipart envelope (D5,
        // chrome-lens-ocr `_sendProtoRequest`). A wrapper here is a 400.
        var seen: ByteArray? = null
        val transport =
            object : LensTransport {
                override fun post(
                    bytes: ByteArray,
                    width: Int,
                    height: Int,
                ): ByteArray {
                    seen = bytes
                    return fixture("lens_ko")
                }
            }

        val sent = request()

        LensOcrClient(transport).recognize(sent, 800, 1200)

        assertEquals(sent.encode().toList(), seen!!.toList())
    }
}
