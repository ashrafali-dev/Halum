package app.halum.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.res.ResourcesCompat
import app.halum.core.BubbleClassifier
import app.halum.core.TextFitter
import app.halum.util.Log

/**
 * Canvas overlay sitting on top of the MAIN WebView.
 *
 * Lifecycle (DECISIONS D9) is deliberately independent of pipeline state:
 * [show] keeps content until [clear] is called from a user gesture (scroll,
 * new panel, dismiss). Pipeline callbacks must never call [clear] directly.
 */
class OverlayView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : View(context, attrs, defStyleAttr) {
        data class Placement(
            /** Bubble box in view pixels. */
            val box: BubbleClassifier.Box,
            val text: String,
            val isSmall: Boolean = false,
            /** Optional redrawn artwork shown instead of a flat fill. */
            val bitmap: android.graphics.Bitmap? = null,
            val color: Int = Color.BLACK,
        )

        private val placements = mutableListOf<Placement>()
        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val textPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textAlign = Paint.Align.LEFT
                // D14.15 wants a bundled Bengali face; until one is added to
                // res/font the system font is used rather than crashing.
                typeface = bengaliTypeface(context) ?: Typeface.DEFAULT
            }
        private val fitter: TextFitter.Measure =
            TextFitter.Measure { text, sizePx ->
                textPaint.textSize = sizePx
                textPaint.measureText(text)
            }

        init {
            setLayerType(LAYER_TYPE_HARDWARE, null)
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = false
            isFocusable = false
        }

        fun show(
            placements: List<Placement>,
            panelTop: Int,
        ) {
            this.placements.clear()
            this.placements.addAll(placements)
            Log.i(TAG, "OVERLAY_SHOW: panelTop=$panelTop placed=${this.placements.size}")
            invalidate()
        }

        /** Called on every state-machine transition. Never removes content. */
        fun keep(state: String) {
            if (placements.isEmpty()) return
            Log.i(TAG, "OVERLAY_KEEP: state=$state visible=true")
        }

        fun clear(reason: ClearReason) {
            if (placements.isEmpty()) return
            placements.clear()
            Log.i(TAG, "OVERLAY_CLEAR: reason=${reason.wire}")
            invalidate()
        }

        val isEmpty: Boolean get() = placements.isEmpty()

        override fun onDraw(canvas: Canvas) {
            for (p in placements) {
                val x = p.box.x1
                val y = p.box.y1
                val w = p.box.width
                val h = p.box.height
                if (w <= 0f || h <= 0f) continue
                val art = p.bitmap
                if (art != null) {
                    canvas.drawBitmap(
                        art,
                        android.graphics.Rect(0, 0, art.width, art.height),
                        android.graphics.RectF(x, y, x + w, y + h),
                        bitmapPaint,
                    )
                    continue
                }
                textPaint.color = p.color
                // D11: small bubbles get the small preset and never spill.
                val sizePx = (if (p.isSmall) TextFitter.MIN_SP else TextFitter.MAX_SP) * density
                val pad = if (p.isSmall) 1f else 3f
                val fitted = TextFitter.fit(p.text, w - 2 * pad, h - 2 * pad, fitter)
                textPaint.textSize = fitted.sizeSp * density
                var ty = y + pad + textPaint.textSize
                for (line in fitted.lines) {
                    canvas.drawText(line, x + pad, ty, textPaint)
                    ty += fitted.sizeSp * density * TextFitter.LINE_GAP
                }
            }
        }

        enum class ClearReason(
            val wire: String,
        ) {
            SCROLL("scroll"),
            REPLACE("replace"),
            DISMISS("dismiss"),
        }

        private val density: Float get() = resources.displayMetrics.density

        private companion object {
            const val TAG = "OVERLAY"

            /**
             * D14.15 wants NotoSansBengali bundled in res/font. No font file is
             * committed yet, so the system face is used; add the resource and
             * swap this lookup when the .ttf lands (one-line change).
             */
            fun bengaliTypeface(context: Context): Typeface? =
                runCatching {
                    ResourcesCompat.getFont(context, android.R.font.sans_serif)
                }.getOrNull()
        }
    }
