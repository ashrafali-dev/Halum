package app.halum.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtoTest {
    @Test
    fun `varint round-trips boundary values`() {
        for (v in listOf(0L, 1L, 127L, 128L, 300L, 16_384L, Int.MAX_VALUE.toLong(), Long.MAX_VALUE)) {
            val bytes = Proto.Writer().writeVarint(1, v).toByteArray()
            val r = Proto.Reader(bytes)
            val tag = r.readTag()
            assertEquals(1, tag ushr 3)
            assertEquals(Proto.WIRE_VARINT, tag and 7)
            assertEquals(v, r.readVarint())
            assertTrue(!r.hasMore())
        }
    }

    @Test
    fun `strings survive non-ascii`() {
        val s = "勇者 は 魔王を倒した。 だが、그래서?"
        val bytes = Proto.Writer().writeString(3, s).toByteArray()
        val r = Proto.Reader(bytes)
        assertEquals(3, r.readTag() ushr 3)
        assertEquals(s, r.readString())
    }

    @Test
    fun `floats are fixed32 and exact for representable values`() {
        for (f in listOf(0f, 1f, 0.5f, 0.25f, 0.75f, -2.5f)) {
            val bytes = Proto.Writer().writeFloat(5, f).toByteArray()
            val r = Proto.Reader(bytes)
            assertEquals(5, r.readTag() ushr 3)
            assertEquals(f, r.readFixed32(), 0f)
        }
    }

    @Test
    fun `nested messages are length delimited`() {
        val inner = Proto.Writer().writeString(1, "x").toByteArray()
        val outer = Proto.Writer().writeMessage(7, inner).writeVarint(8, 9).toByteArray()
        val r = Proto.Reader(outer)
        assertEquals(7, r.readTag() ushr 3)
        val sub = r.readLengthDelimited()
        assertEquals("x", sub.let { s -> s.readTag(); s.readString() })
        assertEquals(8, r.readTag() ushr 3)
        assertEquals(9L, r.readVarint())
    }

    @Test
    fun `unknown fields of every wire type are skipped`() {
        val bytes =
            Proto.Writer()
                .writeVarint(1, 5)
                .writeString(2, "skip")
                .writeFloat(3, 1.5f)
                .writeVarint(4, 42)
                .toByteArray()
        val r = Proto.Reader(bytes)
        var last = 0L
        while (r.hasMore()) {
            val tag = r.readTag()
            if (tag ushr 3 == 4) last = r.readVarint() else r.skip(tag and 7)
        }
        assertEquals(42L, last)
    }

    @Test(expected = Proto.ProtoException::class)
    fun `truncated varint fails loudly`() {
        Proto.Reader(byteArrayOf(0x80.toByte(), 0x80.toByte())).readVarint()
    }

    @Test(expected = Proto.ProtoException::class)
    fun `truncated length-delimited fails loudly`() {
        Proto.Reader(byteArrayOf(0x12, 0x05, 0x61)).readLengthDelimited()
    }

    @Test(expected = Proto.ProtoException::class)
    fun `field number zero is rejected`() {
        Proto.Reader(byteArrayOf(0x00)).readTag()
    }
}
