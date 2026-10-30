package app.halum.core

import app.halum.core.Proto.Reader
import app.halum.core.Proto.Writer

/** One OCR line. All values are already in page pixels (D5). */
data class OcrBlock(
    val text: String,
    /** x1, y1, x2, y2 in page pixels. */
    val bbox: FloatArray,
    /** Lens content language, e.g. "ja" / "ko"; empty when not reported. */
    val script: String = "",
    val conf: Float = 1f,
)

object LensProtocol {
    /** lensfrontend-pa.googleapis.com/v1/crupload (chrome-lens-ocr, MIT). */
    const val ENDPOINT = "https://lensfrontend-pa.googleapis.com/v1/crupload"

    /** Public key embedded in chrome-lens-ocr; not a secret. */
    const val API_KEY = "AIzaSyDr2UxVnv_U85AbhhY8XSHSIavUW0DC-sY"

    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** Enum values below are copied from the chrome-lens-ocr .proto sources; see NOTICE.md. */
    private const val PLATFORM_WEB = 3L
    private const val SURFACE_CHROMIUM = 4L
    private const val FILTER_AUTO = 7L
    private const val COORD_NORMALIZED = 1L

    class LensException(
        message: String,
    ) : RuntimeException(message)

    /** Request fields, pre-serialised bytes too, so tests can compare exactly. */
    data class Request(
        val uuid: Long,
        val sequenceId: Int,
        val imageSequenceId: Int,
        val width: Int,
        val height: Int,
        val targetLanguage: String,
        val region: String,
        val timeZone: String,
        val jpeg: ByteArray,
    ) {
        fun encode(): ByteArray {
            val requestId = Proto.Writer()
                .writeVarint(1, uuid)
                .writeVarint(2, sequenceId.toLong())
                .writeVarint(3, imageSequenceId.toLong())
                .toByteArray()

            val locale = Proto.Writer()
                .writeString(1, targetLanguage)
                .writeString(2, region)
                .writeString(3, timeZone)
                .toByteArray()

            val filter = Proto.Writer().writeVarint(1, FILTER_AUTO).toByteArray()
            val filters = Proto.Writer().writeMessage(1, filter).toByteArray()

            val clientContext = Proto.Writer()
                .writeVarint(1, PLATFORM_WEB)
                .writeVarint(2, SURFACE_CHROMIUM)
                .writeMessage(4, locale)
                .writeMessage(17, filters)
                .toByteArray()

            val requestContext = Proto.Writer()
                .writeMessage(3, requestId)
                .writeMessage(4, clientContext)
                .toByteArray()

            val metadata = Proto.Writer()
                .writeVarint(1, width.toLong())
                .writeVarint(2, height.toLong())
                .toByteArray()

            val payload = Proto.Writer().writeBytes(1, jpeg).toByteArray()
            val imageData = Proto.Writer()
                .writeMessage(1, payload)
                .writeMessage(3, metadata)
                .toByteArray()

            val objects = Proto.Writer()
                .writeMessage(1, requestContext)
                .writeMessage(3, imageData)
                .toByteArray()

            return Proto.Writer().writeMessage(1, objects).toByteArray()
        }
    }

    /** Decodes a ServerResponse into blocks in Lens order (paragraph → line). */
    fun decode(
        response: ByteArray,
        imageWidth: Int,
        imageHeight: Int,
    ): List<OcrBlock> {
        val root = Reader(response)
        var blocks: List<OcrBlock>? = null
        var error: String? = null
        while (root.hasMore()) {
            val tag = root.readTag()
            when (tag ushr 3) {
                1 -> error = decodeError(root.readLengthDelimited())
                2 -> blocks = decodeObjects(root.readLengthDelimited(), imageWidth, imageHeight)
                else -> root.skip(tag and 7)
            }
        }
        error?.let { throw LensException(it) }
        return blocks ?: emptyList()
    }

    private fun decodeError(r: Reader): String {
        var type = 0L
        var body = ""
        while (r.hasMore()) {
            val tag = r.readTag()
            when (tag ushr 3) {
                1 -> type = r.readVarint()
                2 -> body = r.readString()
                else -> r.skip(tag and 7)
            }
        }
        return "lens error $type${if (body.isEmpty()) "" else ": $body"}"
    }

    private fun decodeObjects(
        r: Reader,
        width: Int,
        height: Int,
    ): List<OcrBlock> {
        var out = emptyList<OcrBlock>()
        while (r.hasMore()) {
            val tag = r.readTag()
            when (tag ushr 3) {
                3 -> out = decodeText(r.readLengthDelimited(), width, height)
                else -> r.skip(tag and 7)
            }
        }
        return out
    }

    private fun decodeText(
        r: Reader,
        width: Int,
        height: Int,
    ): List<OcrBlock> {
        var layout: Reader? = null
        var language = ""
        while (r.hasMore()) {
            val tag = r.readTag()
            when (tag ushr 3) {
                1 -> layout = r.readLengthDelimited()
                2 -> language = r.readString()
                else -> r.skip(tag and 7)
            }
        }
        val blocks = mutableListOf<OcrBlock>()
        val l = layout ?: return blocks
        while (l.hasMore()) {
            val tag = l.readTag()
            when (tag ushr 3) {
                1 -> blocks += decodeParagraphText(l.readLengthDelimited(), width, height)
                else -> l.skip(tag and 7)
            }
        }
        return blocks.map { it.copy(script = language.ifEmpty { it.script }) }
    }

    private fun decodeParagraphText(
        p: Reader,
        width: Int,
        height: Int,
    ): List<OcrBlock> {
        val lines = mutableListOf<Reader>()
        var paragraphBox: FloatArray? = null
        var paragraphLang = ""
        while (p.hasMore()) {
            val tag = p.readTag()
            when (tag ushr 3) {
                2 -> lines.add(p.readLengthDelimited())
                3 -> paragraphBox = decodeGeometry(p.readLengthDelimited())
                5 -> paragraphLang = p.readString()
                else -> p.skip(tag and 7)
            }
        }
        val result = mutableListOf<OcrBlock>()
        for (line in lines) {
            var text = ""
            var lineBox: FloatArray? = null
            val words = mutableListOf<Pair<String, String>>()
            while (line.hasMore()) {
                val tag = line.readTag()
                when (tag ushr 3) {
                    1 -> words.add(decodeWord(line.readLengthDelimited()))
                    2 -> lineBox = decodeGeometry(line.readLengthDelimited())
                    else -> line.skip(tag and 7)
                }
            }
            text = joinWords(words)
            if (text.isEmpty()) continue
            val box = lineBox ?: paragraphBox ?: FloatArray(4) { 0f }
            result +=
                OcrBlock(
                    text = text,
                    bbox = toPixels(box, width, height),
                    script = paragraphLang,
                )
        }
        return result
    }

    /** Word: 2 = plain text, 3 = text separator (chrome-lens-ocr join rule). */
    private fun decodeWord(w: Reader): Pair<String, String> {
        var text = ""
        var sep = ""
        while (w.hasMore()) {
            val tag = w.readTag()
            when (tag ushr 3) {
                2 -> text = w.readString()
                3 -> sep = w.readString()
                else -> w.skip(tag and 7)
            }
        }
        return text to sep
    }

    private fun joinWords(words: List<Pair<String, String>>): String {
        val sb = StringBuilder()
        for ((i, w) in words.withIndex()) {
            sb.append(w.first)
            when {
                w.second.isNotEmpty() -> sb.append(w.second)
                i < words.size - 1 -> sb.append(' ')
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    /** Geometry: 1 = bounding box {1..4 centre/size, 5 rotation, 6 coordinate type}. */
    private fun decodeGeometry(g: Reader): FloatArray? {
        while (g.hasMore()) {
            val tag = g.readTag()
            if (tag ushr 3 == 1) {
                val box = g.readLengthDelimited()
                val v = FloatArray(6)
                while (box.hasMore()) {
                    val btag = box.readTag()
                    val field = btag ushr 3
                    if (field in 1..6 && btag and 7 == Proto.WIRE_FIXED32) {
                        v[field - 1] = box.readFixed32()
                    } else {
                        box.skip(btag and 7)
                    }
                }
                // 0/1 = normalized, 2 = already image pixels.
                return if (v[5].toInt() == COORD_NORMALIZED.toInt() || v[5] == 0f) {
                    v
                } else {
                    // Image coords: emit as normalised so toPixels is uniform.
                    null
                }
            } else {
                g.skip(tag and 7)
            }
        }
        return null
    }

    private fun toPixels(
        box: FloatArray,
        width: Int,
        height: Int,
    ): FloatArray {
        val w = box[2] * width
        val h = box[3] * height
        val x = box[0] * width - w / 2f
        val y = box[1] * height - h / 2f
        return floatArrayOf(x, y, x + w, y + h)
    }
}
