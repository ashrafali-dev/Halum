package app.halum.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchPlannerTest {
    @Test
    fun `empty input plans nothing`() {
        assertEquals(emptyList<BatchPlanner.Batch>(), BatchPlanner.plan(emptyList()))
    }

    @Test
    fun `at most eight blocks per batch`() {
        val blocks = List(20) { "line $it" }
        val batches = BatchPlanner.plan(blocks)
        assertEquals(listOf(8, 8, 4), batches.map { it.indices.size })
    }

    @Test
    fun `character budget splits before the block count does`() {
        val blocks = List(8) { "x".repeat(150) } // 1200 chars total
        val batches = BatchPlanner.plan(blocks)
        assertTrue(batches.size > 1)
        assertTrue(batches.all { it.chars(blocks) <= BatchPlanner.MAX_CHARS })
        assertEquals(
            (0 until blocks.size).toList(),
            batches.flatMap { it.indices },
        )
    }

    @Test
    fun `order is preserved across sub-batches`() {
        val blocks = List(30) { "block-$it" }
        val flat = BatchPlanner.plan(blocks).flatMap { it.indices }
        assertEquals((0 until 30).toList(), flat)
    }

    @Test
    fun `a single oversized block gets its own batch instead of being truncated`() {
        val blocks = listOf("short", "y".repeat(900), "short2")
        val batches = BatchPlanner.plan(blocks)
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), batches.map { it.indices })
    }

    @Test
    fun `exactly the limits fit in one batch`() {
        val blocks = List(8) { "z".repeat(50) }
        assertEquals(1, BatchPlanner.plan(blocks).size)
    }
}
