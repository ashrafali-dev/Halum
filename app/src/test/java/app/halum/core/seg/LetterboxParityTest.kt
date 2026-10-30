package app.halum.core.seg

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `tools/integration_test.py` computes the recall numbers with the same
 * letterbox. If the two formulas drift, the app's boxes and CI's boxes are not
 * the same rectangles and AC3 stops meaning anything. These cases are the
 * values the Python function returns (`letterbox(w, h)` in that file).
 */
class LetterboxParityTest {
    private fun check(
        w: Int,
        h: Int,
        scale: Float,
        padX: Int,
        padY: Int,
        rw: Int,
        rh: Int,
    ) {
        val box = SegDecoder.Letterbox.of(w, h)
        assertEquals("scale for $w×$h", scale, box.scale, 0.0001f)
        assertEquals("padX for $w×$h", padX, box.padX)
        assertEquals("padY for $w×$h", padY, box.padY)
        assertEquals("resizedW for $w×$h", rw, box.resizedW)
        assertEquals("resizedH for $w×$h", rh, box.resizedH)
    }

    @Test
    fun `a portrait page is width-limited`() {
        // 836×1200: scale = 640/1200 = 0.53333; 836*scale = 445.87 -> 446,
        // padX = (640 - 446) / 2 = 97.
        check(836, 1200, 0.533333f, 97, 0, 446, 640)
    }

    @Test
    fun `a square page has no padding`() {
        check(640, 640, 1f, 0, 0, 640, 640)
    }

    @Test
    fun `a landscape page is height-limited`() {
        // 1280×720: scale = 0.5, resized 640×360, padY = 140
        check(1280, 720, 0.5f, 0, 140, 640, 360)
    }

    @Test
    fun `rounding matches Python round-half-even`() {
        // 801×1200 -> scale 0.53333, 801*scale = 427.2 -> 427
        check(801, 1200, 0.533333f, 106, 0, 427, 640)
    }
}
