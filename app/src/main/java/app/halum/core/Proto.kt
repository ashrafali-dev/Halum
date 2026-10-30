package app.halum.core

/**
 * Minimal protobuf wire reader/writer.
 *
 * Why not the protobuf runtime: it costs ~1.5 MB of dex and generates Java for
 * a handful of tiny messages; the wire format is three field types. Everything
 * here is pure JVM so `LensProtocol` is testable without Android.
 *
 * Wire types used: varint (0), fixed64 (1), length-delimited (2), fixed32 (5).
 * Floats are fixed32; enums and ints are varint.
 */
object Proto {
    const val WIRE_VARINT = 0
    const val WIRE_FIXED64 = 1
    const val WIRE_LEN = 2
    const val WIRE_FIXED32 = 5

    class ProtoException(
        message: String,
    ) : RuntimeException(message)

    /** Reader over a byte range. Mutates position; never copies. */
    class Reader(
        private val buf: ByteArray,
        private var pos: Int = 0,
        private val end: Int = buf.size,
    ) {
        fun hasMore(): Boolean = pos < end

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (pos >= end) throw ProtoException("truncated varint at $pos")
                val b = buf[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift >= 64) throw ProtoException("varint longer than 64 bits")
            }
        }

        fun readTag(): Int {
            val raw = readVarint()
            if (raw > Int.MAX_VALUE) throw ProtoException("tag out of range: $raw")
            val forked = raw.toInt()
            if (forked ushr 3 == 0) throw ProtoException("field number 0 is invalid")
            return forked
        }

        fun readFixed32(): Float {
            if (pos + 4 > end) throw ProtoException("truncated fixed32 at $pos")
            var bits = 0
            for (i in 0 until 4) bits = bits or ((buf[pos + i].toInt() and 0xFF) shl (8 * i))
            pos += 4
            return Float.fromBits(bits)
        }

        fun readLengthDelimited(): Reader {
            val len = readVarint()
            advance(len, "length")
            return Reader(buf, pos - len.toInt(), pos)
        }

        /** Bounds-checked forward move; the single place that rejects truncation. */
        private fun advance(len: Long, what: String) {
            if (len < 0 || len > end - pos) {
                throw ProtoException("$what $len exceeds ${end - pos} remaining at $pos")
            }
            pos += len.toInt()
        }

        fun readString(): String = String(readLengthDelimited().toByteArray(), Charsets.UTF_8)

        fun readBytes(): ByteArray = readLengthDelimited().toByteArray()

        fun skip(wireType: Int) {
            when (wireType) {
                WIRE_VARINT -> readVarint()
                WIRE_FIXED64 -> advance(8, "fixed64")
                WIRE_LEN -> {
                    val len = readVarint()
                    advance(len, "skip length")
                }
                WIRE_FIXED32 -> advance(4, "fixed32")
                else -> throw ProtoException("unsupported wire type $wireType")
            }
        }

        fun toByteArray(): ByteArray {
            if (pos == 0 && end == buf.size) return buf
            val out = ByteArray(end - pos)
            System.arraycopy(buf, pos, out, 0, out.size)
            return out
        }
    }

    /**
     * Buffered writer. `child` blocks are serialised to their own byte array
     * and written length-delimited; messages here are small so this is simpler
     * than back-patching lengths.
     */
    class Writer {
        private val out = java.io.ByteArrayOutputStream(64)

        fun writeVarint(
            field: Int,
            value: Long,
        ) = apply {
            tag(field, WIRE_VARINT)
            varint(value)
        }

        fun writeString(
            field: Int,
            value: String,
        ) = apply { writeBytes(field, value.toByteArray(Charsets.UTF_8)) }

        fun writeFloat(
            field: Int,
            value: Float,
        ) = apply {
            tag(field, WIRE_FIXED32)
            val bits = value.toRawBits()
            for (i in 0 until 4) out.write((bits ushr (8 * i)) and 0xFF)
        }

        fun writeBytes(
            field: Int,
            value: ByteArray,
        ) = apply {
            tag(field, WIRE_LEN)
            varint(value.size.toLong())
            out.write(value)
        }

        fun writeMessage(
            field: Int,
            value: ByteArray,
        ) = writeBytes(field, value)

        fun toByteArray(): ByteArray = out.toByteArray()

        private fun tag(
            field: Int,
            wireType: Int,
        ) {
            varint((field.toLong() shl 3) or wireType.toLong())
        }

        private fun varint(value: Long) {
            var v = value
            while (true) {
                if (v and 0x7FL.inv() == 0L) {
                    out.write((v and 0x7F).toInt())
                    return
                }
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
        }
    }
}
