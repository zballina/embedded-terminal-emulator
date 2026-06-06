package com.embedded.terminal

data class DirtySpan(
    val row: Int,
    val start: Int,
    val end: Int
)

class DirtyTracker {
    private val dirtyMap = mutableMapOf<Int, MutableList<Pair<Int, Int>>>()

    fun markDirty(row: Int, col: Int) {
        markDirtyRange(row, col, col)
    }

    fun markDirtyRange(row: Int, startCol: Int, endCol: Int) {
        synchronized(this) {
            val list = dirtyMap.getOrPut(row) { mutableListOf() }
            list.add(Pair(startCol, endCol))
        }
    }

    fun markRowDirty(row: Int, maxCols: Int) {
        markDirtyRange(row, 0, maxCols - 1)
    }

    fun markAllDirty(rows: Int, cols: Int) {
        synchronized(this) {
            dirtyMap.clear()
            for (r in 0 until rows) {
                dirtyMap[r] = mutableListOf(Pair(0, cols - 1))
            }
        }
    }

    fun clear() {
        synchronized(this) {
            dirtyMap.clear()
        }
    }

    // Get consolidated dirty spans sorted by row and start column
    fun getConsolidatedSpans(): List<DirtySpan> {
        synchronized(this) {
            val spans = mutableListOf<DirtySpan>()
            for ((row, ranges) in dirtyMap) {
                if (ranges.isEmpty()) continue
                ranges.sortBy { it.first }
                
                val consolidated = mutableListOf<Pair<Int, Int>>()
                var current = ranges[0]
                for (i in 1 until ranges.size) {
                    val next = ranges[i]
                    if (next.first <= current.second + 1) {
                        current = Pair(current.first, maxOf(current.second, next.second))
                    } else {
                        consolidated.add(current)
                        current = next
                    }
                }
                consolidated.add(current)
                
                for (r in consolidated) {
                    spans.add(DirtySpan(row, r.first, r.second))
                }
            }
            return spans
        }
    }
}
