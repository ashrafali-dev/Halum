package app.halum.core

import app.halum.core.InpaintRouter.Method
import app.halum.core.InpaintRouter.Mask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InpaintRouterTest {
    @Test
    fun `small masks use Telea regardless of the model`() {
        assertEquals(Method.TELEA, InpaintRouter.decide(Mask(14_999), miGanAvailable = true))
        assertEquals(Method.TELEA, InpaintRouter.decide(Mask(14_999), miGanAvailable = false))
    }

    @Test
    fun `large masks use MI-GAN when installed`() {
        assertEquals(Method.MI_GAN, InpaintRouter.decide(Mask(15_000), miGanAvailable = true))
    }

    @Test
    fun `large masks fall back to solid fill without the model`() {
        assertEquals(Method.SOLID_FILL, InpaintRouter.decide(Mask(15_000), miGanAvailable = false))
    }

    @Test
    fun `fill ratio must reach ninety percent`() {
        assertTrue(InpaintRouter.fillOk(Mask(10_000, filledPx = 9_000)))
        assertFalse(InpaintRouter.fillOk(Mask(10_000, filledPx = 8_999)))
    }

    @Test
    fun `empty mask reports zero ratio instead of dividing by zero`() {
        assertEquals(0f, Mask(0).fillRatio, 0f)
    }

    @Test
    fun `log line carries both metrics and the method`() {
        val line = InpaintRouter.logLine(12.5f, Mask(20_000, 19_500), Method.MI_GAN)
        assertEquals("INPAINT: residual=12.50 fillRatio=0.975 method=MI_GAN", line)
    }
}
