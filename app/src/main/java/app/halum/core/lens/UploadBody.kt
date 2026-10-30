package app.halum.core.lens

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * `v1/crupload` takes a multipart body, not a raw protobuf: the reference
 * (chrome-lens-ocr) posts two parts, `encoded_image` (the serialised
 * ServerRequest) and `processed_image_dimensions`. Keeping the framing here
 * means the wire shape is testable and the HTTP code stays boring.
 */
object UploadBody {
    const val BOUNDARY = "halum-8f2c6a1d4b7e"
    const val CONTENT_TYPE = "multipart/form-data; boundary=$BOUNDARY"
    const val IMAGE_FIELD = "encoded_image"
    const val DIMENSIONS_FIELD = "processed_image_dimensions"

    fun build(
        request: ByteArray,
        width: Int,
        height: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream(request.size + 512)
        part(out, IMAGE_FIELD, "image/jpeg", request)
        part(out, DIMENSIONS_FIELD, "text/plain", "$width,$height".toByteArray())
        out.write("--$BOUNDARY--\r\n".toByteArray())
        return out.toByteArray()
    }

    private fun part(
        out: ByteArrayOutputStream,
        name: String,
        contentType: String,
        body: ByteArray,
    ) {
        out.write("--$BOUNDARY\r\n".toByteArray())
        out.write("Content-Disposition: form-data; name=\"$name\"\r\n".toByteArray())
        out.write("Content-Type: $contentType\r\n\r\n".toByteArray())
        out.write(body)
        out.write("\r\n".toByteArray())
    }

    /** Extracts a named part's payload; used by tests and the capture build. */
    fun extractPart(
        stream: InputStream,
        name: String,
    ): ByteArray? {
        val all = stream.readBytes()
        val text = String(all, Charsets.ISO_8859_1)
        val header = "name=\"$name\""
        var cursor = 0
        while (true) {
            val start = text.indexOf(header, cursor)
            if (start < 0) return null
            val bodyStart = text.indexOf("\r\n\r\n", start)
            if (bodyStart < 0) return null
            val from = bodyStart + 4
            val boundary = text.indexOf("\r\n--$BOUNDARY", from)
            if (boundary < 0) return null
            return all.copyOfRange(from, boundary)
        }
    }
}
