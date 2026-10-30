package app.halum.core.lens

import app.halum.core.LensProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
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
    fun `a transient failure is retried once`() {
        var calls = 0
        val transport =
            object : LensTransport {
                override fun post(bytes: ByteArray): ByteArray {
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
                override fun post(bytes: ByteArray): ByteArray {
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

        limiter.acquire() // the oldest call has aged out
        assertEquals(2, limiter.used())
    }

    @Test
    fun `upload body frames the request and the dimensions`() {
        val body = UploadBody.build(byteArrayOf(0x0A, 0x01, 0x02), 800, 1200)

        val image = UploadBody.extractPart(ByteArrayInputStream(body), UploadBody.IMAGE_FIELD)
        val dims = UploadBody.extractPart(ByteArrayInputStream(body), UploadBody.DIMENSIONS_FIELD)

        assertEquals(listOf(0x0A.toByte(), 0x01, 0x02), image!!.toList())
        assertEquals("800,1200", String(dims!!))
    }

    @Test
    fun `upload body is one well-formed multipart document`() {
        val body = String(UploadBody.build(ByteArray(4), 10, 10), Charsets.ISO_8859_1)

        assertTrue(body.startsWith("--${UploadBody.BOUNDARY}\r\n"))
        assertTrue(body.endsWith("--${UploadBody.BOUNDARY}--\r\n"))
        assertEquals(2, Regex("name=\"").findAll(body).count())
    }
}
