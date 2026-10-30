package app.halum.engine

import app.halum.core.LensProtocol
import app.halum.core.lens.LensTransport
import app.halum.core.lens.UploadBody
import app.halum.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * D14.16: plain `HttpURLConnection` with the reference UA and content type —
 * no OkHttp. The endpoint is undocumented, and deviating from the UA is a
 * known cause of 400/429.
 *
 * Cookies are supplied by Android's `CookieManager` via [cookies]/[storeCookies]
 * so the client does not own a jar implementation of its own.
 */
class HttpTransport(
    private val endpoint: String = LensProtocol.ENDPOINT,
    private val cookies: () -> String? = { null },
    private val storeCookies: (String) -> Unit = {},
    private val timeoutMs: Int = 30_000,
) : LensTransport {
    override fun post(bytes: ByteArray): ByteArray {
        val body = UploadBody.build(bytes, 0, 0)
        val conn = (URL("$endpoint?stcs=${authTime()}").openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Content-Type", UploadBody.CONTENT_TYPE)
            conn.setRequestProperty("User-Agent", LensProtocol.UA)
            conn.setRequestProperty("Accept-Encoding", "gzip")
            conn.setRequestProperty("X-Goog-Api-Key", LensProtocol.API_KEY)
            cookies()?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Cookie", it) }
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }

            val status = conn.responseCode
            conn.headerFields["Set-Cookie"]?.let { values ->
                if (values.isNotEmpty()) storeCookies(values.joinToString("; ") { it.substringBefore(';') })
            }
            if (status !in 200..299) {
                val detail =
                    conn.errorStream
                        ?.bufferedReader()
                        ?.use { it.readText().take(300) }
                        .orEmpty()
                Log.e(TAG, "LENS_OCR: http $status $detail")
                throw LensTransport.TransportException("lens http $status", status)
            }
            val raw = conn.inputStream
            val stream = if (conn.contentEncoding?.contains("gzip") == true) GZIPInputStream(raw) else raw
            return stream.use { it.readBytes() }
        } catch (e: LensTransport.TransportException) {
            throw e
        } catch (e: java.io.IOException) {
            throw LensTransport.TransportException("lens io: ${e.message}", 0, e)
        } finally {
            conn.disconnect()
        }
    }

    /** Lens expects a millisecond timestamp; seconds break the request. */
    private fun authTime(): Long = System.currentTimeMillis()

    private companion object {
        const val TAG = "LensHttp"
    }
}
