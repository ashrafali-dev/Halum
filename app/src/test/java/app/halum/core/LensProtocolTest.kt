package app.halum.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * AC2: replay the committed Lens protobuf fixtures through the real decoder.
 * The fixtures are genuine bytes from the official jspb bindings
 * (fixtures/lens/README.md), so this checks our reader against the reference
 * implementation, not against itself.
 */
class LensProtocolTest {
    private fun fixture(name: String): ByteArray = File(fixturesDir, name).readBytes()

    // org.json:json is a unit-test dependency; android.jar's copy throws.
    private fun expected(name: String): JSONObject =
        JSONObject(File(fixturesDir, name).readText())

    private val fixturesDir: File
        get() {
            val prop = System.getProperty("halum.fixtures")
            val root = if (prop != null) File(prop) else File("../../fixtures")
            assertTrue("fixtures dir missing: ${root.absolutePath}", root.isDirectory)
            return root
        }

    private fun assertAgainst(name: String) {
        val exp = expected("$name.expected.json")
        val blocks = LensProtocol.decode(fixture("$name.bin"), exp.getInt("width"), exp.getInt("height"))
        val want = exp.getJSONArray("blocks")
        assertEquals("$name block count", want.length(), blocks.size)
        for (i in 0 until want.length()) {
            val w = want.getJSONObject(i)
            assertEquals("$name[$i].text", w.getString("text"), blocks[i].text)
            assertEquals("$name[$i].script", w.getString("script"), blocks[i].script)
            assertTrue("$name[$i] text not empty", blocks[i].text.isNotEmpty())
            val box = w.getJSONArray("bbox")
            for (k in 0 until 4) {
                assertEquals(
                    "$name[$i].bbox[$k]",
                    box.getDouble(k).toFloat(),
                    blocks[i].bbox[k],
                    0.01f,
                )
            }
        }
    }

    @Test
    fun `japanese fixture decodes to expected lines`() = assertAgainst("lens_ja")

    @Test
    fun `korean fixture decodes to expected lines`() = assertAgainst("lens_ko")

    @Test
    fun `chapter order is preserved across paragraphs and lines`() {
        val exp = expected("lens_ja.expected.json")
        val blocks = LensProtocol.decode(fixture("lens_ja.bin"), 800, 1200)
        val want = exp.getJSONArray("blocks")
        val expectedOrder = (0 until want.length()).map { want.getJSONObject(it).getString("text") }
        assertEquals(expectedOrder, blocks.map { it.text })
        assertTrue(blocks.size >= 5)
    }

    @Test
    fun `request encodes the field layout the endpoint expects`() {
        val req =
            LensProtocol.Request(
                uuid = 123L,
                sequenceId = 1,
                imageSequenceId = 1,
                width = 800,
                height = 1200,
                targetLanguage = "ja",
                region = "US",
                timeZone = "Asia/Dhaka",
                jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x01),
            )
        val root = Proto.Reader(req.encode())
        assertEquals(1, root.readTag() ushr 3) // ServerRequest.objects_request = 1
        val objects = root.readLengthDelimited()
        assertEquals(1, objects.readTag() ushr 3) // objects.request_context = 1
        val ctx = objects.readLengthDelimited()
        assertEquals(3, ctx.readTag() ushr 3) // request_context.request_id = 3
        val rid = ctx.readLengthDelimited()
        assertEquals(1, rid.readTag() ushr 3) // request_id.uuid = 1 (uint64)
        assertEquals(123L, rid.readVarint())
        assertEquals(2, rid.readTag() ushr 3) // request_id.sequence_id = 2
        assertEquals(4, ctx.readTag() ushr 3) // request_context.client_context = 4
        val client = ctx.readLengthDelimited()
        assertEquals(1, client.readTag() ushr 3) // ClientContext.platform = 1
        assertEquals(3L, client.readVarint()) // PLATFORM_WEB
        assertEquals(2, client.readTag() ushr 3) // ClientContext.surface = 2
        assertEquals(4L, client.readVarint()) // SURFACE_CHROMIUM
        assertEquals(3, objects.readTag() ushr 3) // objects.image_data = 3
        val image = objects.readLengthDelimited()
        assertEquals(1, image.readTag() ushr 3) // ImageData.payload = 1
        val payload = image.readLengthDelimited()
        assertEquals(1, payload.readTag() ushr 3) // ImagePayload.image_bytes = 1
        assertEquals(4, payload.readBytes().size)
        assertEquals(3, image.readTag() ushr 3) // ImageData.image_metadata = 3
        val meta = image.readLengthDelimited()
        assertEquals(1, meta.readTag() ushr 3)
        assertEquals(800L, meta.readVarint())
        assertEquals(2, meta.readTag() ushr 3)
        assertEquals(1200L, meta.readVarint())
    }

    @Test
    fun `error response is surfaced, not swallowed`() {
        val error = Proto.Writer().writeVarint(1, 7).toByteArray() // ErrorType
        val bytes = Proto.Writer().writeMessage(1, error).toByteArray()
        val ex =
            runCatching { LensProtocol.decode(bytes, 100, 100) }
                .exceptionOrNull()
        assertTrue(ex is LensProtocol.LensException)
        assertTrue(ex!!.message!!.contains("lens error"))
    }

    @Test
    fun `empty response decodes to no blocks`() {
        assertEquals(emptyList<OcrBlock>(), LensProtocol.decode(ByteArray(0), 100, 100))
    }

    @Test
    fun `unknown fields do not break decoding`() {
        // The fixture is a bare ObjectsResponse (as emitted by the reference
        // client); wrap it in a real ServerResponse and add future fields.
        val jspb = fixture("lens_ko.bin")
        val wrapped =
            Proto.Writer()
                .writeMessage(2, jspb) // objects_response
                .writeString(5, "future-field")
                .writeVarint(9, 3)
                .toByteArray()
        assertEquals(3, LensProtocol.decode(wrapped, 800, 1200).size)
        // An unknown field *inside* objects_response must be skipped too.
        val inner = Proto.Reader(jspb)
        val textField = inner.readLengthDelimited() // objectsResponse.text = 3
        val objects =
            Proto.Writer()
                .writeMessage(11, byteArrayOf(1, 2)) // future field
                .writeMessage(3, textField.toByteArray())
                .toByteArray()
        val nested = Proto.Writer().writeMessage(2, objects).toByteArray()
        assertEquals(3, LensProtocol.decode(nested, 800, 1200).size)
    }

    @Test
    fun `fixture files are the expected kind`() {
        val json = expected("lens_ja.expected.json")
        val arr: JSONArray = json.getJSONArray("blocks")
        assertEquals(5, arr.length())
        assertEquals("ja", arr.getJSONObject(0).getString("script"))
    }
}
