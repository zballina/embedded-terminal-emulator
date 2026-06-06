package com.embedded.terminal

import org.junit.Assert.*
import org.junit.Test
import java.awt.Color

class AnsiEscapeParserTest {

    private fun feedSync(stateEngine: TerminalStateEngine, text: String) {
        stateEngine.feedChars(text.toCharArray(), text.length).get()
    }

    @Test
    fun testSgrColors() {
        val buffer = TerminalBuffer(80, 24, 10)
        val stateEngine = TerminalStateEngine(buffer)

        // Reset
        feedSync(stateEngine, "\u001b[0m")
        assertNull(buffer.activeFgColor)

        // Set Red Foreground (SGR 31)
        feedSync(stateEngine, "\u001b[31m")
        assertEquals(Color(205, 0, 0), buffer.activeFgColor)

        // Set Bright Green Background (SGR 102)
        feedSync(stateEngine, "\u001b[102m")
        assertEquals(Color(0, 255, 0), buffer.activeBgColor)
    }

    @Test
    fun testClearScreenAndCursor() {
        val buffer = TerminalBuffer(80, 24, 10)
        val stateEngine = TerminalStateEngine(buffer)

        // Write some chars
        stateEngine.dispatchCommand(TerminalCommand.WriteText("X")).get()
        stateEngine.dispatchCommand(TerminalCommand.WriteText("Y")).get()
        assertEquals(2, buffer.cursorX)

        // Clear screen and home cursor: "\u001b[2J"
        feedSync(stateEngine, "\u001b[2J")
        assertEquals(0, buffer.cursorX)
        assertEquals(0, buffer.cursorY)
        assertEquals(' ', buffer.grid[0][0].char)
    }

    @Test
    fun testCursorMovement() {
        val buffer = TerminalBuffer(80, 24, 10)
        val stateEngine = TerminalStateEngine(buffer)

        // Move cursor to row 5, col 10 (1-indexed, so row index 4, col index 9)
        feedSync(stateEngine, "\u001b[5;10H")
        assertEquals(9, buffer.cursorX)
        assertEquals(4, buffer.cursorY)
    }

    @Test
    fun testSaveRestoreCursor() {
        val buffer = TerminalBuffer(80, 24, 10)
        val stateEngine = TerminalStateEngine(buffer)

        // Move cursor and set color
        stateEngine.dispatchCommand(TerminalCommand.MoveCursor(15, 8)).get()
        feedSync(stateEngine, "\u001b[31m")
        assertEquals(15, buffer.cursorX)
        assertEquals(8, buffer.cursorY)
        assertEquals(Color(205, 0, 0), buffer.activeFgColor)

        // Save cursor using ESC 7
        feedSync(stateEngine, "\u001b7")

        // Move cursor and reset color
        stateEngine.dispatchCommand(TerminalCommand.MoveCursor(5, 2)).get()
        feedSync(stateEngine, "\u001b[0m")
        assertEquals(5, buffer.cursorX)
        assertEquals(2, buffer.cursorY)
        assertNull(buffer.activeFgColor)

        // Restore cursor using ESC 8
        feedSync(stateEngine, "\u001b8")

        // Verify restored
        assertEquals(15, buffer.cursorX)
        assertEquals(8, buffer.cursorY)
        assertEquals(Color(205, 0, 0), buffer.activeFgColor)

        // Move cursor and save using CSI s
        stateEngine.dispatchCommand(TerminalCommand.MoveCursor(20, 10)).get()
        feedSync(stateEngine, "\u001b[s")

        // Move cursor elsewhere
        stateEngine.dispatchCommand(TerminalCommand.MoveCursor(0, 0)).get()

        // Restore using CSI u
        feedSync(stateEngine, "\u001b[u")

        assertEquals(20, buffer.cursorX)
        assertEquals(10, buffer.cursorY)
    }

    @Test
    fun testCsiIntermediateCharacters() {
        val buffer = TerminalBuffer(80, 24, 10)
        val stateEngine = TerminalStateEngine(buffer)

        // Enviar la secuencia DECSCUSR: ESC [ 0 space q (CSI 0 SP q)
        feedSync(stateEngine, "\u001b[0 q")

        // Verificar que no se haya impreso el carácter 'q' o el espacio en el buffer.
        assertEquals(' ', buffer.grid[0][0].char)
    }

    @Test
    fun testDcsParsing() {
        val buffer = TerminalBuffer(80, 24, 10)
        val stateEngine = TerminalStateEngine(buffer)

        // Enviar secuencia DCS: ESC P 1 . 2 . 3 ESC \
        feedSync(stateEngine, "\u001bP1.2.3\u001b\\")

        // Verificar que los caracteres de la secuencia DCS no se hayan impreso en el buffer
        assertEquals(' ', buffer.grid[0][0].char)

        // Y que después de DCS, volvemos a modo normal e imprimimos normalmente
        feedSync(stateEngine, "NORMAL")
        assertEquals("NORMAL", buffer.grid[0].take(6).joinToString("") { it.grapheme }.trim())
    }

    @Test
    fun testEmojiAndSurrogatePairs() {
        val buffer = TerminalBuffer(80, 24, 10)
        val stateEngine = TerminalStateEngine(buffer)

        // feed a string containing emojis with surrogate pairs
        // U+1F60D is 😍 (surrogate pair: \uD83D\uDE0D) -> width 2
        // U+2328 + U+FE0F is ⌨️ -> width 1
        feedSync(stateEngine, "d\uD83D\uDE0D\u2328\uFE0F")

        // First cell is 'd'
        assertEquals("d", buffer.grid[0][0].grapheme)
        assertEquals(CellWidth.SINGLE, buffer.grid[0][0].width)

        // Next is '😍' (double width: occupies 2 cells, second cell is continuation)
        assertEquals("\uD83D\uDE0D", buffer.grid[0][1].grapheme)
        assertEquals(CellWidth.DOUBLE, buffer.grid[0][1].width)
        assertTrue(buffer.grid[0][2].isContinuation)

        // Next is '⌨️' (single width, grapheme contains both ⌨ and VS-16)
        assertEquals("\u2328\uFE0F", buffer.grid[0][3].grapheme)
        assertEquals(CellWidth.SINGLE, buffer.grid[0][3].width)

        // CursorX should be at 4
        assertEquals(4, buffer.cursorX)
    }
}

