package com.embedded.terminal

import org.junit.Assert.*
import org.junit.Test

class TerminalBufferTest {

    @Test
    fun testBasicWriting() {
        val buffer = TerminalBuffer(80, 24, 10)
        buffer.writeChar('A')
        buffer.writeChar('B')
        
        assertEquals(2, buffer.cursorX)
        assertEquals(0, buffer.cursorY)
        assertEquals('A', buffer.grid[0][0].char)
        assertEquals('B', buffer.grid[0][1].char)
    }

    @Test
    fun testLineWrapAndScroll() {
        val buffer = TerminalBuffer(5, 3, 2)
        // Write 5 characters -> cursorX = 5, cursorY = 0
        for (i in 0 until 5) {
            buffer.writeChar('A')
        }
        assertEquals(5, buffer.cursorX)
        assertEquals(0, buffer.cursorY)

        // Writing the 6th character should wrap to next line
        buffer.writeChar('B')
        assertEquals(1, buffer.cursorX)
        assertEquals(1, buffer.cursorY)
        assertEquals('B', buffer.grid[1][0].char)

        // Write 4 more 'B's to complete line 1
        for (i in 0 until 4) {
            buffer.writeChar('B')
        }
        assertEquals(5, buffer.cursorX)
        assertEquals(1, buffer.cursorY)

        // Write 5 'C's -> wraps to line 2 (bottom line)
        for (i in 0 until 5) {
            buffer.writeChar('C')
        }
        assertEquals(5, buffer.cursorX)
        assertEquals(2, buffer.cursorY)

        // Write one 'D' -> triggers scroll up since cursorY goes beyond rows
        buffer.writeChar('D')
        assertEquals(1, buffer.cursorX)
        assertEquals(2, buffer.cursorY) // stays at bottom row

        // Verify history has the scrolled line "AAAAA"
        assertEquals(1, buffer.history.size)
        assertEquals('A', buffer.history[0][0].char)
        // Verify current grid top row is now "BBBBB"
        assertEquals('B', buffer.grid[0][0].char)
    }

    @Test
    fun testHistoryLimit() {
        val buffer = TerminalBuffer(80, 5, 3)
        // Scroll 10 times
        for (i in 0 until 10) {
            buffer.scrollUp()
        }
        assertEquals(3, buffer.history.size)
    }

    @Test
    fun testResizeHeightIncreaseNoHistory() {
        val buffer = TerminalBuffer(80, 5, 10)
        buffer.writeChar('X') // row 0, col 0
        buffer.newLine()
        buffer.carriageReturn()
        buffer.writeChar('Y') // row 1, col 0
        
        assertEquals(1, buffer.cursorY)
        assertEquals('X', buffer.grid[0][0].char)
        assertEquals('Y', buffer.grid[1][0].char)
        
        // Resize to height 8 (increase by 3)
        buffer.resize(80, 8)
        
        assertEquals(8, buffer.rows)
        assertEquals(0, buffer.history.size)
        // CursorY should remain 1
        assertEquals(1, buffer.cursorY)
        // Characters should remain at row 0 and 1
        assertEquals('X', buffer.grid[0][0].char)
        assertEquals('Y', buffer.grid[1][0].char)
        // Bottom rows should be empty
        assertEquals(' ', buffer.grid[7][0].char)
    }

    @Test
    fun testResizeHeightIncreaseWithHistory() {
        val buffer = TerminalBuffer(5, 3, 10)
        // Fill buffer so we get history.
        // Write 3 lines: "AAAAA", "BBBBB", "CCCCC"
        for (i in 0 until 5) buffer.writeChar('A')
        buffer.writeChar('B') // Wraps to line 1
        for (i in 0 until 4) buffer.writeChar('B')
        buffer.writeChar('C') // Wraps to line 2
        for (i in 0 until 4) buffer.writeChar('C')
        
        // Now:
        // grid[0] = "AAAAA"
        // grid[1] = "BBBBB"
        // grid[2] = "CCCCC"
        // history is empty
        assertEquals(0, buffer.history.size)
        
        // Write one 'D' to trigger scroll
        buffer.writeChar('D')
        
        // Now:
        // grid[0] = "BBBBB"
        // grid[1] = "CCCCC"
        // grid[2] = "D    "
        // history[0] = "AAAAA"
        assertEquals(1, buffer.history.size)
        assertEquals('A', buffer.history[0][0].char)
        assertEquals('B', buffer.grid[0][0].char)
        assertEquals('C', buffer.grid[1][0].char)
        assertEquals('D', buffer.grid[2][0].char)
        assertEquals(2, buffer.cursorY)
        
        // Resize height from 3 to 6
        // totalLines = 1 (history) + 3 (grid) = 4
        // newRows (6) > totalLines (4)
        // All history should be pulled, and empty lines padded at the bottom
        buffer.resize(5, 6)
        
        assertEquals(6, buffer.rows)
        assertEquals(0, buffer.history.size)
        
        // The contents should be:
        // row 0: "AAAAA" (pulled from history)
        // row 1: "BBBBB"
        // row 2: "CCCCC"
        // row 3: "D    "
        // row 4: empty
        // row 5: empty
        assertEquals('A', buffer.grid[0][0].char)
        assertEquals('B', buffer.grid[1][0].char)
        assertEquals('C', buffer.grid[2][0].char)
        assertEquals('D', buffer.grid[3][0].char)
        assertEquals(' ', buffer.grid[4][0].char)
        
        // Cursor was at grid row 2. In new grid, old grid starts at row 1.
        // So cursorY should be 2 + 1 = 3.
        assertEquals(3, buffer.cursorY)
    }

    @Test
    fun testResizeHeightDecreaseKeepCursor() {
        val buffer = TerminalBuffer(80, 5, 10)
        buffer.writeChar('A') // row 0
        buffer.newLine()
        buffer.carriageReturn()
        buffer.writeChar('B') // row 1
        buffer.newLine()
        buffer.carriageReturn()
        buffer.writeChar('C') // row 2
        
        assertEquals(2, buffer.cursorY)
        
        // Resize to height 3
        // totalLines = 5
        // newRows = 3
        // cursorLine = 2
        // newGridStart = maxOf(0, minOf(5 - 3, 2)) = 2
        // History should get lines 0 and 1 ("A..." and "B...")
        // Grid should get lines 2, 3, 4 ("C...", empty, empty)
        buffer.resize(80, 3)
        
        assertEquals(3, buffer.rows)
        assertEquals(2, buffer.history.size)
        assertEquals('A', buffer.history[0][0].char)
        assertEquals('B', buffer.history[1][0].char)
        
        assertEquals('C', buffer.grid[0][0].char)
        // CursorY should be cursorLine - newGridStart = 2 - 2 = 0
        assertEquals(0, buffer.cursorY)
    }

    @Test
    fun testScrollingMargins() {
        val buffer = TerminalBuffer(80, 5, 10)
        // Set scrolling margins to row 1 and 3 (inclusive)
        buffer.setScrollingMargins(1, 3)
        
        // Write content to all 5 rows
        buffer.moveCursor(0, 0)
        buffer.writeChar('0')
        buffer.moveCursor(0, 1)
        buffer.writeChar('1')
        buffer.moveCursor(0, 2)
        buffer.writeChar('2')
        buffer.moveCursor(0, 3)
        buffer.writeChar('3')
        buffer.moveCursor(0, 4)
        buffer.writeChar('4')
        
        // Verify original placement
        assertEquals('0', buffer.grid[0][0].char)
        assertEquals('1', buffer.grid[1][0].char)
        assertEquals('2', buffer.grid[2][0].char)
        assertEquals('3', buffer.grid[3][0].char)
        assertEquals('4', buffer.grid[4][0].char)
        
        // Move cursor to marginBottom (row 3) and call newLine()
        buffer.moveCursor(0, 3)
        buffer.newLine()
        
        // Assert:
        // - Row 0 remains unchanged ('0')
        // - Row 1 gets previous Row 2 ('2')
        // - Row 2 gets previous Row 3 ('3')
        // - Row 3 is cleared (' ')
        // - Row 4 remains unchanged ('4')
        // - History remains empty (sub-region scrolls are not pushed to history)
        // - CursorY remains at 3
        assertEquals('0', buffer.grid[0][0].char)
        assertEquals('2', buffer.grid[1][0].char)
        assertEquals('3', buffer.grid[2][0].char)
        assertEquals(' ', buffer.grid[3][0].char)
        assertEquals('4', buffer.grid[4][0].char)
        assertEquals(0, buffer.history.size)
        assertEquals(3, buffer.cursorY)
    }
}
