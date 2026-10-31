package app.halum.core.seg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decoder must agree with the reference implementation in
 * `tools/integration_test.py` (same letterbox, same NMS, same coordinates), so
 * the Kotlin boxes and the CI recall numbers describe the same objects.
 */
class SegDecoderTest {
    private fun blankOutput() = FloatArray(SegDecoder.NUM_CHANNELS * SegDecoder.COLS)

    private fun put(
        out: FloatArray,
        col: Int,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        score: Float,
        coeff: Float = 0f,
    ) {
        fun set(
            ch: Int,
            v: Float,
        ) {
            out[ch * SegDecoder.COLS + col] = v
        }
        set(0, cx)
        set(1, cy)
        set(2, w)
        set(3, h)
        set(4, score)
        for (c in 0 until SegDecoder.SEG_DIM) set(5 + c, coeff)
    }

    @Test
    fun `a full-square detection maps straight back to page pixels`() {
        // 640×640 page: scale 1, no padding.
        val out = blankOutput()
        put(out, 0, cx = 320f, cy = 320f, w = 200f, h = 100f, score = 0.9f)

        val dets = SegDecoder.decode(out, SegDecoder.Letterbox.of(640, 640))

        assertEquals(1, dets.size)
        assertEquals(220f, dets[0].x1, 0.01f)
        assertEquals(270f, dets[0].y1, 0.01f)
        assertEquals(420f, dets[0].x2, 0.01f)
        assertEquals(370f, dets[0].y2, 0.01f)
    }

    @Test
    fun `letterbox padding is subtracted and the scale undone`() {
        // 800×1200 page -> scale 0.5333, resized 427×640, pad (106, 0).
        val page = SegDecoder.Letterbox.of(800, 1200)
        assertEquals(0.533333f, page.scale, 0.0001f)
        assertEquals(106, page.padX)
        assertEquals(0, page.padY)

        val out = blankOutput()
        // A 100×100 box at the top-left of the resized area.
        put(out, 0, cx = 106f + 50f, cy = 50f, w = 100f, h = 100f, score = 0.8f)

        val det = SegDecoder.decode(out, page)[0]

        assertEquals(0f, det.x1, 0.5f)
        assertEquals(0f, det.y1, 0.5f)
        assertEquals(187.5f, det.x2, 1f)
        assertEquals(187.5f, det.y2, 1f)
    }

    @Test
    fun `low confidence detections are dropped`() {
        val out = blankOutput()
        put(out, 0, 320f, 320f, 100f, 100f, score = 0.24f)
        put(out, 1, 320f, 320f, 100f, 100f, score = 0.25f)

        val dets = SegDecoder.decode(out, SegDecoder.Letterbox.of(640, 640))

        assertEquals(1, dets.size)
    }

    @Test
    fun `logits are passed through a sigmoid`() {
        // prob() cannot tell 0 or 1 from a probability, so only values outside
        // [0,1] prove the sigmoid path. 2.0 is a clear logit -> sigmoid = 0.881.
        assertEquals(0.881f, SegDecoder.prob(2f), 0.001f)
        assertEquals(0.95f, SegDecoder.prob(0.95f), 0.001f) // already a probability
    }

    @Test
    fun `duplicate boxes are merged by NMS, highest score wins`() {
        val out = blankOutput()
        put(out, 0, 300f, 300f, 100f, 100f, score = 0.70f)
        put(out, 1, 302f, 301f, 100f, 100f, score = 0.95f) // same bubble
        put(out, 2, 100f, 100f, 80f, 80f, score = 0.60f) // separate bubble

        val dets = SegDecoder.decode(out, SegDecoder.Letterbox.of(640, 640))

        assertEquals(2, dets.size)
        assertEquals(0.95f, dets[0].score, 0.001f)
        assertEquals(0.60f, dets[1].score, 0.001f)
    }

    @Test
    fun `results are ordered by score then position`() {
        val out = blankOutput()
        put(out, 0, 100f, 100f, 50f, 50f, score = 0.5f)
        put(out, 1, 400f, 400f, 50f, 50f, score = 0.5f)
        put(out, 2, 300f, 300f, 50f, 50f, score = 0.9f)

        val dets = SegDecoder.decode(out, SegDecoder.Letterbox.of(640, 640))

        assertEquals(listOf(0.9f, 0.5f, 0.5f), dets.map { it.score })
        assertTrue(dets[1].x1 < dets[2].x1)
    }

    @Test
    fun `iou of identical boxes is one`() {
        val out = blankOutput()
        put(out, 0, 320f, 320f, 100f, 100f, score = 0.9f)
        val d = SegDecoder.decode(out, SegDecoder.Letterbox.of(640, 640))[0]
        assertEquals(1f, SegDecoder.iou(d, d), 0.0001f)
    }

    @Test
    fun `a mask covers the bubble and stays inside the page`() {
        // 640×640 page, a 200×200 detection at the centre.
        val out = blankOutput()
        put(out, 0, 320f, 320f, 200f, 200f, score = 0.9f, coeff = 4f)
        val letterbox = SegDecoder.Letterbox.of(640, 640)
        val det = SegDecoder.decode(out, letterbox)[0]

        // Prototype: all zeros -> sigmoid(0) = 0.5, right on the 0.5 threshold.
        val zeroProto = FloatArray(SegDecoder.SEG_DIM * SegDecoder.PROTO * SegDecoder.PROTO)
        val empty = SegDecoder.maskFor(det, zeroProto, 640, 640, letterbox, threshold = 0.51f)
        assertEquals(0, empty.areaPx)

        // A positive prototype inside the box must produce mask pixels.
        val hot = FloatArray(zeroProto.size)
        for (c in 0 until SegDecoder.SEG_DIM) {
            hot[c * SegDecoder.PROTO * SegDecoder.PROTO + 80 * SegDecoder.PROTO + 80] =
                1f
        }
        val filled = SegDecoder.maskFor(det, hot, 640, 640, letterbox)
        assertTrue("mask should have pixels, got ${filled.areaPx}", filled.areaPx > 0)
        assertEquals(640 * 640, filled.mask.size)
    }

    @Test
    fun `NHWC prototype output is read in channel-last order`() {
        val coeffs = FloatArray(SegDecoder.SEG_DIM)
        coeffs[1] = 1f
        val detection =
            SegDecoder.Detection(
                x1 = 4f,
                y1 = 0f,
                x2 = 5f,
                y2 = 1f,
                score = 1f,
                coeffs = coeffs,
            )
        val proto = FloatArray(SegDecoder.SEG_DIM * SegDecoder.PROTO * SegDecoder.PROTO)
        // Page pixel x=4 maps to prototype cell 1. Set channel 1 in NHWC order.
        proto[1 * SegDecoder.SEG_DIM + 1] = 1f
        val letterbox = SegDecoder.Letterbox(1f, 0, 0, 8, 1)

        val result =
            SegDecoder.maskFor(
                detection,
                proto,
                pageWidth = 8,
                pageHeight = 1,
                letterbox = letterbox,
                protoChannelsLast = true,
            )

        assertEquals(1, result.areaPx)
        assertEquals(1.toByte(), result.mask[4])
    }

    @Test
    fun `mask is empty outside the letterboxed page`() {
        val out = blankOutput()
        put(out, 0, 320f, 320f, 100f, 100f, score = 0.9f, coeff = 4f)
        val letterbox = SegDecoder.Letterbox.of(800, 1200)
        val det = SegDecoder.decode(out, letterbox)[0]
        val hot = FloatArray(SegDecoder.SEG_DIM * SegDecoder.PROTO * SegDecoder.PROTO) { 1f }

        val m = SegDecoder.maskFor(det, hot, 800, 1200, letterbox)

        // Column 0 is left padding: nothing may be painted there.
        var leftColumn = 0
        for (y in 0 until 1200) if (m.mask[y * 800] == 1.toByte()) leftColumn++
        assertEquals(0, leftColumn)
    }
}
