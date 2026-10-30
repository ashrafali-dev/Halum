package app.halum.core

import app.halum.core.BubbleClassifier.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleClassifierTest {
    @Test
    fun `narrow bubble is small even when tall`() {
        assertTrue(BubbleClassifier.isSmall(Box(0f, 0f, 59f, 500f)))
    }

    @Test
    fun `low-area bubble is small even when wide`() {
        // 100 x 35 = 3500 px
        assertTrue(BubbleClassifier.isSmall(Box(0f, 0f, 100f, 35f)))
    }

    @Test
    fun `large bubble is not small`() {
        assertFalse(BubbleClassifier.isSmall(Box(0f, 0f, 200f, 120f)))
    }

    @Test
    fun `thresholds are exclusive at the boundary`() {
        assertFalse(BubbleClassifier.isSmall(Box(0f, 0f, 60f, 100f))) // width == 60, area 6000
        assertFalse(BubbleClassifier.isSmall(Box(0f, 0f, 80f, 50f))) // area == 4000
    }

    @Test
    fun `lines are assigned to the bubble containing their centre`() {
        val big = Box(400f, 0f, 800f, 300f)
        val small = Box(0f, 0f, 50f, 400f)
        val lines = listOf(Box(410f, 10f, 600f, 50f), Box(5f, 10f, 45f, 60f), Box(300f, 500f, 340f, 540f))
        val bubbles = BubbleClassifier.assign(listOf(big, small), lines)
        assertEquals(listOf(0), bubbles[0].lineIndices)
        assertEquals(listOf(1), bubbles[1].lineIndices)
        assertTrue(bubbles[0].isSmall.not())
        assertTrue(bubbles[1].isSmall)
    }

    @Test
    fun `small bubble never claims a line that sits in a large bubble`() {
        // The small box is nested inside the big one; the line centre is in both.
        val big = Box(100f, 100f, 400f, 400f)
        val small = Box(150f, 150f, 190f, 300f) // 40 x 150 -> small by width
        val line = Box(160f, 200f, 180f, 240f)
        val bubbles = BubbleClassifier.assign(listOf(big, small), listOf(line))
        assertEquals(listOf(0), bubbles[0].lineIndices)
        assertEquals(emptyList<Int>(), bubbles[1].lineIndices)
    }

    @Test
    fun `unassigned lines belong to no bubble`() {
        val bubbles = BubbleClassifier.assign(listOf(Box(0f, 0f, 10f, 10f)), listOf(Box(90f, 90f, 99f, 99f)))
        assertEquals(emptyList<Int>(), bubbles.single().lineIndices)
    }
}
