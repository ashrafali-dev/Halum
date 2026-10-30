package app.halum.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
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
            /** Bubble outline in view pixels. */
            val path: android.graphics.Path,
            val bitmap: android.graphics.Bitmap?,
            val text: String,
            val textSizeSp: Float,
            val color: Int = Color.BLACK,
            val isSmall: Boolean = false,
        )

        private val placements = mutableListOf<Placement>()

        init {
            setLayerType(LAYER_TYPE_HARDWARE, null)
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
            // Bitmap placement and text painting arrive with TextFitter in Phase 2.
        }

        enum class ClearReason(
            val wire: String,
        ) {
            SCROLL("scroll"),
            REPLACE("replace"),
            DISMISS("dismiss"),
        }

        private companion object {
            const val TAG = "OVERLAY"
        }
    }
