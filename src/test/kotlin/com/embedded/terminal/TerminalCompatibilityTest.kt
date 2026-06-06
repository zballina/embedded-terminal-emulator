package com.embedded.terminal

import org.junit.Assert.*
import org.junit.Test
import java.awt.Color

class TerminalCompatibilityTest {

    private fun feedSync(stateEngine: TerminalStateEngine, text: String) {
        stateEngine.feedChars(text.toCharArray(), text.length).get()
    }

    @Test
    fun testStressAnsiStream() {
        val buffer = TerminalBuffer(80, 24, 20000)
        val stateEngine = TerminalStateEngine(buffer)

        val beforeMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val startTime = System.currentTimeMillis()

        // Stress: write 35,000 lines of data (which triggers scrollback and paging!)
        val linesCount = 35000
        for (i in 0 until linesCount) {
            val line = "Line number $i - this is stress testing the scrollback paging to disk\r\n"
            feedSync(stateEngine, line)
        }

        val elapsed = System.currentTimeMillis() - startTime
        val afterMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

        println("Stress Test: processed $linesCount lines in ${elapsed}ms")
        println("Memory: Before Stress: ${beforeMemory / 1024 / 1024}MB, After: ${afterMemory / 1024 / 1024}MB")

        // Assert scrollback contains the lines correctly
        assertTrue(buffer.totalLines() in (20000 + 24)..(20000 + 10000 + 24))
        
        // Assert we can read line from scrollback that was serialized to disk
        val checkLineIndex = 5000
        val lineCells = buffer.getLine(checkLineIndex)
        val lineStr = lineCells.joinToString("") { it.grapheme }.trim()
        assertTrue(lineStr.startsWith("Line number"))
    }

    @Test
    fun testGoldenLayoutRepresentation() {
        val buffer = TerminalBuffer(80, 24, 100)
        val stateEngine = TerminalStateEngine(buffer)

        // Write golden layout: Red text on Green background, bold, underlined
        feedSync(stateEngine, "\u001b[1;4;31;42mGOLDEN_TEST\u001b[0m")

        assertEquals("GOLDEN_TEST", buffer.grid[0].take(11).joinToString("") { it.grapheme })
        val cell = buffer.grid[0][0]
        assertEquals(Color(205, 0, 0), cell.fgColor)
        assertEquals(Color(0, 205, 0), cell.bgColor)
        assertTrue(cell.isBold)
        assertTrue(cell.isUnderline)
    }

    @Test
    fun testVimCompatibility() {
        val buffer = TerminalBuffer(80, 24, 100)
        val stateEngine = TerminalStateEngine(buffer)

        // Vim uses alternate buffer (CSI ? 1049 h) and bracketed paste (CSI ? 2004 h)
        feedSync(stateEngine, "\u001b[?1049h\u001b[?2004h")

        assertTrue(buffer.isAlternateBufferActive)
        assertTrue(buffer.isBracketedPasteMode)

        // Exit Vim: restore primary buffer (CSI ? 1049 l) and disable paste (CSI ? 2004 l)
        feedSync(stateEngine, "\u001b[?1049l\u001b[?2004l")

        assertFalse(buffer.isAlternateBufferActive)
        assertFalse(buffer.isBracketedPasteMode)
    }

    @Test
    fun testTmuxCompatibility() {
        val buffer = TerminalBuffer(80, 24, 100)
        val stateEngine = TerminalStateEngine(buffer)

        // Tmux queries terminal features and sets alternate screens and cursor style
        feedSync(stateEngine, "\u001b[?1047h\u001b[?1000h") // Alternate screen and mouse click reporting

        assertTrue(buffer.isAlternateBufferActive)
        assertEquals(1000, buffer.mouseTrackingMode)

        // Disable mouse and switch back
        feedSync(stateEngine, "\u001b[?1047l\u001b[?1000l")

        assertFalse(buffer.isAlternateBufferActive)
        assertEquals(0, buffer.mouseTrackingMode)
    }

    @Test
    fun testHtopCompatibility() {
        val buffer = TerminalBuffer(80, 24, 100)
        val stateEngine = TerminalStateEngine(buffer)

        // Htop uses mouse tracking SGR (CSI ? 1006 h) and alternate buffer
        feedSync(stateEngine, "\u001b[?1049h\u001b[?1002h\u001b[?1006h")

        assertTrue(buffer.isAlternateBufferActive)
        assertEquals(1002, buffer.mouseTrackingMode)
        assertTrue(buffer.isMouseSgrEnabled)

        // Reset
        feedSync(stateEngine, "\u001b[?1049l\u001b[?1002l\u001b[?1006l")

        assertFalse(buffer.isAlternateBufferActive)
        assertEquals(0, buffer.mouseTrackingMode)
        assertFalse(buffer.isMouseSgrEnabled)
    }

    @Test
    fun testNanoCompatibility() {
        val buffer = TerminalBuffer(80, 24, 100)
        val stateEngine = TerminalStateEngine(buffer)

        // Nano starts by clearing screen, moving cursor to (1,1) and entering text
        feedSync(stateEngine, "\u001b[H\u001b[2JFile: test.txt")
        assertEquals(0, buffer.cursorY)
        assertEquals("File: test.txt", buffer.grid[0].take(14).joinToString("") { it.grapheme })

        // Moves cursor to bottom line to write help keys (nano shortcut UI)
        feedSync(stateEngine, "\u001b[24;1H^G Get Help")
        assertEquals(23, buffer.cursorY)
        assertEquals("^G Get Help", buffer.grid[23].take(11).joinToString("") { it.grapheme })
    }

    @Test
    fun testLessCompatibility() {
        val buffer = TerminalBuffer(80, 24, 100)
        val stateEngine = TerminalStateEngine(buffer)

        // Less activates alternate screen, clears screen, sets cursor and displays text
        feedSync(stateEngine, "\u001b[?1049h\u001b[H\u001b[2J")
        assertTrue(buffer.isAlternateBufferActive)

        feedSync(stateEngine, "LINE 1\r\nLINE 2")
        assertEquals("LINE 1", buffer.grid[0].take(6).joinToString("") { it.grapheme }.trim())
        assertEquals("LINE 2", buffer.grid[1].take(6).joinToString("") { it.grapheme }.trim())

        // Exit less: returns to primary screen
        feedSync(stateEngine, "\u001b[?1049l")
        assertFalse(buffer.isAlternateBufferActive)
    }

    @Test
    fun testLazygitCompatibility() {
        val buffer = TerminalBuffer(80, 24, 100)
        val stateEngine = TerminalStateEngine(buffer)

        // Lazygit uses alternate buffer, mouse tracking (mode 1002, 1006 SGR), bracketed paste
        feedSync(stateEngine, "\u001b[?1049h\u001b[?1002h\u001b[?1006h\u001b[?2004h")

        assertTrue(buffer.isAlternateBufferActive)
        assertEquals(1002, buffer.mouseTrackingMode)
        assertTrue(buffer.isMouseSgrEnabled)
        assertTrue(buffer.isBracketedPasteMode)

        // Restores terminal state upon exiting
        feedSync(stateEngine, "\u001b[?1049l\u001b[?1002l\u001b[?1006l\u001b[?2004l")
        assertFalse(buffer.isAlternateBufferActive)
        assertEquals(0, buffer.mouseTrackingMode)
        assertFalse(buffer.isMouseSgrEnabled)
        assertFalse(buffer.isBracketedPasteMode)
    }
}
