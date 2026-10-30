package app.halum.core

/**
 * D11: `isSmall` when width < 60 px or area < 4000 px. Small bubbles are
 * placed with 8–10 sp text and must never be merged into a large neighbour —
 * merging is what makes a whisper bubble's text appear in a speech bubble.
 */
object BubbleClassifier {
    const val SMALL_WIDTH_PX = 60f
    const val SMALL_AREA_PX = 4000f

    data class Box(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
    ) {
        val width: Float get() = x2 - x1
        val height: Float get() = y2 - y1
        val area: Float get() = width * height
        val centreX: Float get() = (x1 + x2) / 2f
        val centreY: Float get() = (y1 + y2) / 2f

        fun contains(
            x: Float,
            y: Float,
        ): Boolean = x >= x1 && x <= x2 && y >= y1 && y <= y2

        fun intersects(other: Box): Boolean = x1 < other.x2 && other.x1 < x2 && y1 < other.y2 && other.y1 < y2

        fun expanded(margin: Float): Box = Box(x1 - margin, y1 - margin, x2 + margin, y2 + margin)
    }

    fun isSmall(box: Box): Boolean = box.width < SMALL_WIDTH_PX || box.area < SMALL_AREA_PX

    /**
     * Groups OCR lines into bubbles. A line joins a bubble when its centre is
     * inside the bubble box. A small bubble never absorbs a line that also
     * lies in a larger bubble, and never merges with a large neighbour.
     */
    data class Bubble(
        val box: Box,
        val lineIndices: List<Int>,
        val isSmall: Boolean,
    )

    fun assign(
        bubbles: List<Box>,
        lines: List<Box>,
    ): List<Bubble> {
        val ownership = arrayOfNulls<Int>(lines.size)
        // Largest first so a large bubble claims shared lines before a small one.
        val order = bubbles.indices.sortedByDescending { bubbles[it].area }
        for (ly in lines.indices) {
            val l = lines[ly]
            for (bi in order) {
                if (bubbles[bi].contains(l.centreX, l.centreY)) {
                    ownership[ly] = bi
                    break
                }
            }
        }
        val byBubble = bubbles.indices.associateWith { mutableListOf<Int>() }
        ownership.forEachIndexed { ly, bi -> if (bi != null) byBubble.getValue(bi) += ly }
        return bubbles.indices.map { bi ->
            Bubble(bubbles[bi], byBubble.getValue(bi).toList(), isSmall(bubbles[bi]))
        }
    }
}
