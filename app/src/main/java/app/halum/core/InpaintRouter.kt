package app.halum.core

/**
 * D4: the inpaint method is chosen by mask area, and the whole bubble mask is
 * filled (not just the text box) so no ghost strokes survive.
 *
 *   < 15,000 px      Telea   (OpenCV, no model)
 *   >= 15,000 px     MI-GAN  (when the model is installed)
 *   anything else    SolidFill sampled from the ring around the mask
 */
object InpaintRouter {
    const val TELEA_MAX_AREA = 15_000
    const val MIN_FILL_RATIO = 0.90f

    enum class Method { TELEA, MI_GAN, SOLID_FILL }

    data class Mask(
        /** Union of text boxes, in page pixels; the area is what decides. */
        val areaPx: Int,
        /** Number of mask pixels actually painted by the engine. */
        val filledPx: Int = areaPx,
    ) {
        val fillRatio: Float get() = if (areaPx <= 0) 0f else filledPx.toFloat() / areaPx
    }

    fun decide(
        mask: Mask,
        miGanAvailable: Boolean,
    ): Method =
        when {
            mask.areaPx < TELEA_MAX_AREA -> Method.TELEA
            miGanAvailable -> Method.MI_GAN
            else -> Method.SOLID_FILL
        }

    /** D4 post-fill assertion; a failure means ghost text may remain. */
    fun fillOk(mask: Mask): Boolean = mask.fillRatio >= MIN_FILL_RATIO

    /** Spec log line: `INPAINT: residual=… fillRatio=… method=…`. */
    fun logLine(
        residual: Float,
        mask: Mask,
        method: Method,
    ): String =
        "INPAINT: residual=${"%.2f".format(residual)} " +
            "fillRatio=${"%.3f".format(mask.fillRatio)} method=$method"
}
