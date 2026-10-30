package app.halum.core

/**
 * D6: max 8 blocks and max 400 characters per sub-batch, order preserved
 * across sub-batches. A single block longer than the character budget still
 * gets its own batch: truncating it would silently drop translation input.
 */
object BatchPlanner {
    const val MAX_BLOCKS = 8
    const val MAX_CHARS = 400

    data class Batch(
        /** 0-based ids into the original block list, ascending. */
        val indices: List<Int>,
    ) {
        fun chars(blocks: List<String>): Int = indices.sumOf { blocks[it].length }
    }

    fun plan(blocks: List<String>): List<Batch> {
        val batches = mutableListOf<Batch>()
        var current = mutableListOf<Int>()
        var chars = 0
        for ((i, block) in blocks.withIndex()) {
            val fits =
                current.size < MAX_BLOCKS &&
                    (chars + block.length <= MAX_CHARS || current.isEmpty())
            if (!fits && current.isNotEmpty()) {
                batches += Batch(current)
                current = mutableListOf()
                chars = 0
            }
            current += i
            chars += block.length
        }
        if (current.isNotEmpty()) batches += Batch(current)
        return batches
    }
}
