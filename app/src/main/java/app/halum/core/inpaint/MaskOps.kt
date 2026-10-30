package app.halum.core.inpaint

import app.halum.core.InpaintRouter

/**
 * Mask bookkeeping shared by every inpaint method, Android-free so the
 * fill-ratio assertion (D4) is unit-tested without OpenCV.
 *
 * Masks are page-sized, one byte per pixel: 0 = keep, 1 = hole. D4 requires
 * the **whole bubble mask** to be filled, not just the text box, so the
 * result's filled area is asserted against the input mask area.
 */
object MaskOps {
    /** Opaque white; the "nothing to sample" answer for [ringColour]. */
    const val WHITE = 0xFFFFFFFF.toInt()

    /** Pixels set to `hole` in a page-sized mask. */
    fun area(mask: ByteArray): Int {
        var n = 0
        for (b in mask) if (b.toInt() != 0) n++
        return n
    }

    /**
     * Bounding box of the set pixels, or null when the mask is empty.
     * Returned as x1, y1, x2, y2 with x2/y2 exclusive.
     */
    fun bounds(
        mask: ByteArray,
        width: Int,
        height: Int,
    ): IntArray? {
        var x1 = width
        var y1 = height
        var x2 = -1
        var y2 = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (mask[row + x].toInt() == 0) continue
                if (x < x1) x1 = x
                if (x > x2) x2 = x
                if (y < y1) y1 = y
                if (y > y2) y2 = y
            }
        }
        return if (x2 < 0) null else intArrayOf(x1, y1, x2 + 1, y2 + 1)
    }

    /** D4: `filledPx / maskPx >= 0.90`, expressed through [InpaintRouter]. */
    fun fillOk(
        maskPx: Int,
        filledPx: Int,
    ): Boolean = InpaintRouter.fillOk(InpaintRouter.Mask(maskPx, filledPx))

    /**
     * Mean absolute difference (0..255) between two page-sized ARGB ints
     * inside the mask. D14.3 measured MI-GAN's real answer at ~48 — a large
     * value is evidence of *change*, which is what we assert, not an error.
     */
    fun meanAbsDiffInsideMask(
        before: IntArray,
        after: IntArray,
        mask: ByteArray,
    ): Float {
        var sum = 0L
        var n = 0
        for (i in mask.indices) {
            if (mask[i].toInt() == 0) continue
            val a = before[i]
            val b = after[i]
            sum += kotlin.math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)).toLong()
            sum += kotlin.math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)).toLong()
            sum += kotlin.math.abs((a and 0xFF) - (b and 0xFF)).toLong()
            n += 3
        }
        return if (n == 0) 0f else sum.toFloat() / n
    }

    /** Mean colour of the ring just outside the mask, used by solid fill. */
    fun ringColour(
        pixels: IntArray,
        mask: ByteArray,
        width: Int,
        height: Int,
        ring: Int = 4,
    ): Int {
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                if (mask[i].toInt() == 0 && isNearMask(mask, width, height, x, y, ring)) {
                    val p = pixels[i]
                    r += (p shr 16) and 0xFF
                    g += (p shr 8) and 0xFF
                    b += p and 0xFF
                    n++
                }
            }
        }
        if (n == 0) return WHITE
        return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    private fun isNearMask(
        mask: ByteArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        ring: Int,
    ): Boolean {
        for (dy in -ring..ring) {
            val ny = y + dy
            if (ny !in 0 until height) continue
            for (dx in -ring..ring) {
                val nx = x + dx
                if (nx !in 0 until width) continue
                if (mask[ny * width + nx].toInt() != 0) return true
            }
        }
        return false
    }
}
