package app.halum.core.inpaint

import app.halum.core.InpaintRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskOpsTest {
    private fun mask(
        width: Int,
        height: Int,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
    ): ByteArray {
        val m = ByteArray(width * height)
        for (y in y1 until y2) for (x in x1 until x2) m[y * width + x] = 1
        return m
    }

    @Test
    fun `area counts set pixels only`() {
        val m = mask(10, 10, 2, 2, 5, 5)
        assertEquals(9, MaskOps.area(m))
    }

    @Test
    fun `an empty mask has no bounds`() {
        assertNull(MaskOps.bounds(ByteArray(100), 10, 10))
    }

    @Test
    fun `bounds are exclusive on the far edge`() {
        val m = mask(10, 10, 2, 3, 6, 8)
        assertEquals(listOf(2, 3, 6, 8), MaskOps.bounds(m, 10, 10)!!.toList())
    }

    @Test
    fun `fill ratio has to reach ninety percent`() {
        assertTrue(MaskOps.fillOk(1000, 900))
        assertFalse(MaskOps.fillOk(1000, 899))
    }

    @Test
    fun `residual only counts pixels inside the mask`() {
        val before = intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt())
        val after = intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt())
        val m = byteArrayOf(1, 0, 0)

        assertEquals(255f, MaskOps.meanAbsDiffInsideMask(before, after, m), 0.01f)
    }

    @Test
    fun `ring colour is sampled from outside the hole`() {
        val w = 8
        val h = 8
        val pixels = IntArray(w * h) { 0xFF102030.toInt() }
        val m = mask(w, h, 3, 3, 5, 5) // small hole in the middle

        val c = MaskOps.ringColour(pixels, m, w, h)

        assertEquals(0x10, (c shr 16) and 0xFF)
        assertEquals(0x20, (c shr 8) and 0xFF)
        assertEquals(0x30, c and 0xFF)
    }

    @Test
    fun `a full-frame mask has no ring, so it falls back to white`() {
        val w = 4
        val h = 4
        val pixels = IntArray(w * h) { 0xFF000000.toInt() }
        val m = ByteArray(w * h) { 1 }

        assertEquals(MaskOps.WHITE, MaskOps.ringColour(pixels, m, w, h))
    }

    @Test
    fun `router picks the method the decision table says`() {
        assertEquals(InpaintRouter.Method.TELEA, InpaintRouter.decide(InpaintRouter.Mask(14_999), true))
        assertEquals(InpaintRouter.Method.MI_GAN, InpaintRouter.decide(InpaintRouter.Mask(15_000), true))
        assertEquals(
            InpaintRouter.Method.SOLID_FILL,
            InpaintRouter.decide(InpaintRouter.Mask(15_000), false),
        )
    }
}
