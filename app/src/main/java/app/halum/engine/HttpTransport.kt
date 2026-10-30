package app.halum.engine

import app.halum.core.LensProtocol
import app.halum.core.lens.LensTransport
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
    override fun post(
        bytes: ByteArray,
        @Suppress("UNUSED_PARAMETER") width: Int,
        @Suppress("UNUSED_PARAMETER") height: Int,
    ): ByteArray {
        // The body is the bare serialised ServerRequest. The reference
        // (chrome-lens-ocr `_sendProtoRequest`) posts raw protobuf with
        // Content-Type: application/x-protobuf and no query params; wrapping
        // it in multipart is what made Lens answer 400. Dimensions travel
        // inside the proto's ImageMetadata, so they are not framed here.
        val body = bytes
        val conn = (URL(endpoint).openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Content-Type", "application/x-protobuf")
            conn.setRequestProperty("User-Agent", LensProtocol.UA)
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("Accept-Encoding", "gzip, deflate")
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            conn.setRequestProperty("X-Goog-Api-Key", LensProtocol.API_KEY)
            cookies()?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Cookie", it) }
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }

            val status = conn.responseCode
            conn.headerFields["Set-Cookie"]?.let { values ->
                if (values.isNotEmpty()) storeCookies(values.joinToString("; ") { it.substringBefore(';') })
            }
            if (status !in 200..299) {
                // The error body is gzipped too; decoding it is the only way
                // to see why Lens refused the request.
                val raw = readErrorBytes(conn)
                val protoReason =
                    runCatching { LensProtocol.decode(raw, 0, 0) }
                        .exceptionOrNull()
                        ?.message
                        ?.takeIf { it.startsWith("lens error") }
                val detail = protoReason ?: printable(raw)
                Log.e(TAG, "LENS_OCR: http $status $detail")
                // Carry the reason so the toast names it instead of just "400".
                throw LensTransport.TransportException("lens http $status: ${detail.take(200)}", status)
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

    /** Raw, decompressed error body; empty when there is none. */
    private fun readErrorBytes(conn: HttpURLConnection): ByteArray =
        runCatching {
            val raw = conn.errorStream ?: return ByteArray(0)
            if (conn.contentEncoding?.contains("gzip") == true) {
                GZIPInputStream(raw).use { it.readBytes() }
            } else {
                raw.use { it.readBytes() }
            }
        }.getOrElse { ByteArray(0) }

    /** Log-safe rendering: one bounded line, no control bytes. */
    private fun printable(bytes: ByteArray): String =
        String(bytes, Charsets.UTF_8)
            .take(400)
            .replace(Regex("[\\p{Cntrl}&&[^\\n]]"), "")
            .replace('\n', ' ')
            .trim()

    private companion object {
        const val TAG = "LensHttp"
    }
}
