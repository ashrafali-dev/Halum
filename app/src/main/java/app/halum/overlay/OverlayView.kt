package app.halum.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
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

        /** A placement hit by a long-press, with its index into [placements]. */
        data class Hit(
            val index: Int,
            val text: String,
            val box: BubbleClassifier.Box,
        )

        private val placements = mutableListOf<Placement>()
        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val textPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textAlign = Paint.Align.LEFT
                // D14.15 wants a bundled Bengali face; until one is added to
                // res/font the system font is used rather than crashing.
                typeface = bengaliTypeface()
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

        /** The topmost placement containing (x, y) in view pixels, or null. */
        fun hitAt(
            x: Float,
            y: Float,
        ): Hit? {
            for (i in placements.indices.reversed()) {
                val p = placements[i]
                if (p.box.contains(x, y)) return Hit(i, p.text, p.box)
            }
            return null
        }

        /** Q2 level 3: replace one placement's text and repaint immediately. */
        fun editAt(
            index: Int,
            text: String,
        ) {
            val p = placements.getOrNull(index) ?: return
            placements[index] = p.copy(text = text)
            Log.i(TAG, "OVERLAY_EDIT: index=$index len=${text.length}")
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            for (p in placements) {
                if (p.box.width > 0f && p.box.height > 0f) drawPlacement(canvas, p)
            }
        }

        private fun drawPlacement(
            canvas: Canvas,
            p: Placement,
        ) {
            val x = p.box.x1
            val y = p.box.y1
            val w = p.box.width
            val h = p.box.height
            val art = p.bitmap
            if (art != null) {
                canvas.drawBitmap(
                    art,
                    android.graphics.Rect(0, 0, art.width, art.height),
                    android.graphics.RectF(x, y, x + w, y + h),
                    bitmapPaint,
                )
                return
            }
            textPaint.color = p.color
            // D11: small bubbles get the small preset and never spill.
            val pad = if (p.isSmall) 1f else 3f
            val fitted = TextFitter.fit(p.text, w - 2 * pad, h - 2 * pad, fitter)
            textPaint.textSize = fitted.sizeSp * density
            var ty = y + pad + textPaint.textSize
            for (line in fitted.lines) {
                canvas.drawText(line, x + pad, ty, textPaint)
                ty += fitted.sizeSp * density * TextFitter.LINE_GAP
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
             * committed yet, so the system sans face (which carries Bengali on
             * API 26+) is used. Add the .ttf and swap this lookup to
             * `ResourcesCompat.getFont(context, R.font.noto_sans_bengali)`.
             */
            fun bengaliTypeface(): Typeface? = Typeface.SANS_SERIF
        }
    }
