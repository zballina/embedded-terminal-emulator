package com.embedded.terminal

import java.awt.Color

class TerminalBuffer(
    override var cols: Int,
    override var rows: Int,
    var historyLimit: Int = 1000
) : TerminalStateProvider {
    private var primaryGrid: Array<Array<TerminalCell>> = Array(rows) { Array(cols) { TerminalCell() } }
    private var alternateGrid: Array<Array<TerminalCell>> = Array(rows) { Array(cols) { TerminalCell() } }
    
    var grid: Array<Array<TerminalCell>> = primaryGrid
    var isAlternateBufferActive: Boolean = false

    val history = PagedScrollbackBuffer(cols, historyLimit)
    val dirtyTracker = DirtyTracker()

    override var cursorX: Int = 0
    override var cursorY: Int = 0
    var isCursorVisible: Boolean = true
    var isBracketedPasteMode: Boolean = false
    var marginTop: Int = 0
    var marginBottom: Int = rows - 1

    // Mouse tracking attributes
    var mouseTrackingMode: Int = 0
    var isMouseSgrEnabled: Boolean = false

    // Saved cursor position and attributes
    private var savedCursorX: Int = 0
    private var savedCursorY: Int = 0
    private var savedActiveFgColor: Color? = null
    private var savedActiveBgColor: Color? = null
    private var savedActiveBold: Boolean = false
    private var savedActiveItalic: Boolean = false
    private var savedActiveUnderline: Boolean = false
    private var savedActiveHyperlinkUrl: String? = null

    // Active style attributes
    var activeFgColor: Color? = null
    var activeBgColor: Color? = null
    var activeBold: Boolean = false
    var activeItalic: Boolean = false
    var activeUnderline: Boolean = false
    var activeHyperlinkUrl: String? = null

    init {
        dirtyTracker.markAllDirty(rows, cols)
    }

    fun useAlternateBuffer(useAlt: Boolean) {
        synchronized(this) {
            if (isAlternateBufferActive == useAlt) return
            isAlternateBufferActive = useAlt
            
            if (useAlt) {
                // Clear alternate screen before entering
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        alternateGrid[r][c].reset()
                    }
                }
                grid = alternateGrid
            } else {
                grid = primaryGrid
            }
            dirtyTracker.markAllDirty(rows, cols)
        }
    }

    fun writeGrapheme(grapheme: String) {
        synchronized(this) {
            val segmenter = GraphemeSegmenterFactory.instance
            val cellWidth = segmenter.getCellWidth(grapheme)
            val neededCols = if (cellWidth == CellWidth.DOUBLE) 2 else 1

            // If cursorX is at cols (meaning the last column was filled), or a double-width
            // character does not fit on this line, we wrap to the next line.
            if (cursorX >= cols || (cellWidth == CellWidth.DOUBLE && cursorX + neededCols > cols)) {
                cursorX = 0
                cursorY++
                if (cursorY >= rows) {
                    scrollUp()
                    cursorY = rows - 1
                }
            }

            val y = cursorY.coerceIn(0, rows - 1)
            val x = cursorX.coerceIn(0, cols - 1)

            // Write the primary cell
            val cell = grid[y][x]
            cell.reset()
            cell.grapheme = grapheme
            cell.width = cellWidth
            cell.isContinuation = false
            cell.fgColor = activeFgColor
            cell.bgColor = activeBgColor
            cell.isBold = activeBold
            cell.isItalic = activeItalic
            cell.isUnderline = activeUnderline
            cell.hyperlinkUrl = activeHyperlinkUrl
            dirtyTracker.markDirty(y, x)

            // Advance cursor
            if (cellWidth == CellWidth.DOUBLE) {
                // Write the continuation (right half) cell
                val contX = x + 1
                if (contX < cols) {
                    val contCell = grid[y][contX]
                    contCell.reset()
                    contCell.grapheme = ""
                    contCell.width = CellWidth.SINGLE
                    contCell.isContinuation = true
                    contCell.fgColor = activeFgColor
                    contCell.bgColor = activeBgColor
                    contCell.isBold = activeBold
                    contCell.isItalic = activeItalic
                    contCell.isUnderline = activeUnderline
                    contCell.hyperlinkUrl = activeHyperlinkUrl
                    dirtyTracker.markDirty(y, contX)
                }
                cursorX = minOf(cols, x + 2)
            } else {
                cursorX = minOf(cols, x + 1)
            }
        }
    }

    fun writeChar(char: Char) {
        writeGrapheme(char.toString())
    }

    fun newLine() {
        synchronized(this) {
            val oldY = cursorY
            if (cursorY == marginBottom) {
                scrollUp()
            } else if (cursorY < rows - 1) {
                cursorY++
            }
            dirtyTracker.markRowDirty(oldY, cols)
            dirtyTracker.markRowDirty(cursorY, cols)
        }
    }

    fun carriageReturn() {
        synchronized(this) {
            cursorX = 0
            dirtyTracker.markRowDirty(cursorY, cols)
        }
    }

    fun backspace() {
        synchronized(this) {
            if (cursorX >= cols) {
                cursorX = cols - 1
            } else {
                cursorX = maxOf(0, cursorX - 1)
            }
            dirtyTracker.markRowDirty(cursorY, cols)
        }
    }

    fun tab() {
        synchronized(this) {
            val nextTab = ((cursorX / 8) + 1) * 8
            cursorX = minOf(cols - 1, nextTab)
            dirtyTracker.markRowDirty(cursorY, cols)
        }
    }

    fun backTab(count: Int) {
        synchronized(this) {
            var currentX = cursorX
            for (i in 0 until count) {
                if (currentX % 8 == 0) {
                    currentX -= 8
                } else {
                    currentX = (currentX / 8) * 8
                }
                if (currentX <= 0) {
                    currentX = 0
                    break
                }
            }
            cursorX = currentX
            dirtyTracker.markRowDirty(cursorY, cols)
        }
    }

    fun scrollUp() {
        synchronized(this) {
            if (cols <= 0 || rows <= 0) return
            scrollUpRegion(marginTop, marginBottom)
        }
    }

    fun scrollUpRegion(top: Int, bottom: Int) {
        synchronized(this) {
            if (top < 0 || bottom >= rows || top >= bottom) return
            
            // Only scroll operations on the full screen push to history (and only if primary screen is active)
            if (top == 0 && bottom == rows - 1 && !isAlternateBufferActive) {
                val poppedLine = Array(cols) { x -> TerminalCell().apply { copyFrom(grid[0][x]) } }
                history.appendLine(poppedLine)
            }
            
            for (y in top until bottom) {
                for (x in 0 until cols) {
                    grid[y][x].copyFrom(grid[y + 1][x])
                }
            }
            
            for (x in 0 until cols) {
                grid[bottom][x].reset()
            }

            for (y in top..bottom) {
                dirtyTracker.markRowDirty(y, cols)
            }
        }
    }

    fun scrollDownRegion(top: Int, bottom: Int) {
        synchronized(this) {
            if (top < 0 || bottom >= rows || top >= bottom) return
            
            for (y in bottom downTo top + 1) {
                for (x in 0 until cols) {
                    grid[y][x].copyFrom(grid[y - 1][x])
                }
            }
            
            for (x in 0 until cols) {
                grid[top][x].reset()
            }

            for (y in top..bottom) {
                dirtyTracker.markRowDirty(y, cols)
            }
        }
    }

    fun setScrollingMargins(top: Int, bottom: Int) {
        synchronized(this) {
            val t = top.coerceIn(0, rows - 1)
            val b = bottom.coerceIn(0, rows - 1)
            if (t < b) {
                marginTop = t
                marginBottom = b
            } else {
                marginTop = 0
                marginBottom = rows - 1
            }
        }
    }

    fun updateHistoryLimit(newLimit: Int) {
        synchronized(this) {
            historyLimit = newLimit
            history.historyLimit = newLimit
        }
    }

    fun clearScreen(mode: Int = 2) {
        synchronized(this) {
            when (mode) {
                0 -> {
                    // Clear from cursor to end of screen
                    for (x in cursorX until cols) {
                        grid[cursorY][x].reset()
                    }
                    for (y in cursorY + 1 until rows) {
                        for (x in 0 until cols) {
                            grid[y][x].reset()
                        }
                    }
                }
                1 -> {
                    // Clear from start of screen to cursor
                    for (y in 0 until cursorY) {
                        for (x in 0 until cols) {
                            grid[y][x].reset()
                        }
                    }
                    for (x in 0..cursorX) {
                        if (x < cols) {
                            grid[cursorY][x].reset()
                        }
                    }
                }
                2 -> {
                    // Clear entire screen
                    for (y in 0 until rows) {
                        for (x in 0 until cols) {
                            grid[y][x].reset()
                        }
                    }
                    cursorX = 0
                    cursorY = 0
                }
            }
            dirtyTracker.markAllDirty(rows, cols)
        }
    }

    fun clearLine(mode: Int) {
        synchronized(this) {
            if (rows <= 0) return
            val y = cursorY.coerceIn(0, rows - 1)
            when (mode) {
                0 -> {
                    for (x in cursorX until cols) {
                        grid[y][x].reset()
                    }
                }
                1 -> {
                    for (x in 0..minOf(cursorX, cols - 1)) {
                        grid[y][x].reset()
                    }
                }
                2 -> {
                    for (x in 0 until cols) {
                        grid[y][x].reset()
                    }
                }
            }
            dirtyTracker.markRowDirty(y, cols)
        }
    }

    fun moveCursor(x: Int, y: Int) {
        synchronized(this) {
            val oldY = cursorY
            cursorX = x.coerceIn(0, cols - 1)
            cursorY = y.coerceIn(0, rows - 1)
            dirtyTracker.markRowDirty(oldY, cols)
            dirtyTracker.markRowDirty(cursorY, cols)
        }
    }

    fun moveCursorRelative(dx: Int, dy: Int) {
        synchronized(this) {
            val oldY = cursorY
            cursorX = (cursorX + dx).coerceIn(0, cols - 1)
            cursorY = (cursorY + dy).coerceIn(0, rows - 1)
            dirtyTracker.markRowDirty(oldY, cols)
            dirtyTracker.markRowDirty(cursorY, cols)
        }
    }

    fun resize(newCols: Int, newRows: Int) {
        synchronized(this) {
            if (newCols <= 0 || newRows <= 0) return
            if (newCols == cols && newRows == rows) return

            val oldPrimaryGrid = primaryGrid
            val oldAltGrid = alternateGrid
            val oldRows = rows
            val oldCols = cols

            cols = newCols
            rows = newRows

            // 1. Resize primary screen buffer using history
            val allLines = ArrayList<Array<TerminalCell>>(history.size + oldRows)
            for (i in 0 until history.size) {
                allLines.add(history.getLine(i))
            }
            allLines.addAll(oldPrimaryGrid)

            val totalLines = allLines.size
            val cursorLine = history.size + cursorY
            val newGridStart = maxOf(0, minOf(totalLines - newRows, cursorLine))

            // Build new history for primary buffer
            history.clear()
            history.cols = newCols
            val historyEnd = newGridStart
            for (i in 0 until historyEnd) {
                val line = allLines[i]
                val resizedLine = Array(cols) { x ->
                    if (x < line.size) line[x] else TerminalCell()
                }
                history.appendLine(resizedLine)
            }

            // Build new primary grid
            val newPrimaryGrid = Array(rows) { Array(cols) { TerminalCell() } }
            for (y in 0 until rows) {
                val lineIdx = newGridStart + y
                if (lineIdx < totalLines) {
                    val line = allLines[lineIdx]
                    for (x in 0 until cols) {
                        newPrimaryGrid[y][x] = if (x < line.size) line[x] else TerminalCell()
                    }
                } else {
                    for (x in 0 until cols) {
                        newPrimaryGrid[y][x] = TerminalCell()
                    }
                }
            }
            primaryGrid = newPrimaryGrid

            // 2. Resize alternate screen buffer (simple copy, no scrollback)
            val newAltGrid = Array(rows) { Array(cols) { TerminalCell() } }
            for (y in 0 until minOf(oldRows, rows)) {
                for (x in 0 until minOf(oldCols, cols)) {
                    newAltGrid[y][x].copyFrom(oldAltGrid[y][x])
                }
            }
            alternateGrid = newAltGrid

            // Swap active grid pointer
            grid = if (isAlternateBufferActive) alternateGrid else primaryGrid

            // Update cursor position
            cursorY = (cursorLine - newGridStart).coerceIn(0, rows - 1)
            cursorX = cursorX.coerceIn(0, cols - 1)

            // Reset scrolling margins to full screen
            marginTop = 0
            marginBottom = rows - 1

            dirtyTracker.markAllDirty(rows, cols)
        }
    }

    fun deleteCharacters(count: Int) {
        synchronized(this) {
            if (cols <= 0 || rows <= 0) return
            val y = cursorY.coerceIn(0, rows - 1)
            val c = count.coerceIn(1, cols - cursorX)
            
            for (x in cursorX until cols - c) {
                grid[y][x].copyFrom(grid[y][x + c])
            }
            
            for (x in cols - c until cols) {
                grid[y][x].reset()
                grid[y][x].fgColor = activeFgColor
                grid[y][x].bgColor = activeBgColor
            }
            dirtyTracker.markRowDirty(y, cols)
        }
    }

    fun eraseCharacters(count: Int) {
        synchronized(this) {
            if (cols <= 0 || rows <= 0) return
            val y = cursorY.coerceIn(0, rows - 1)
            val limit = minOf(cursorX + count, cols)
            for (x in cursorX until limit) {
                grid[y][x].reset()
                grid[y][x].fgColor = activeFgColor
                grid[y][x].bgColor = activeBgColor
            }
            dirtyTracker.markRowDirty(y, cols)
        }
    }

    fun insertSpaces(count: Int) {
        synchronized(this) {
            if (cols <= 0 || rows <= 0) return
            val y = cursorY.coerceIn(0, rows - 1)
            val c = count.coerceIn(1, cols - cursorX)
            
            for (x in cols - 1 downTo cursorX + c) {
                grid[y][x].copyFrom(grid[y][x - c])
            }
            
            for (x in cursorX until cursorX + c) {
                grid[y][x].reset()
                grid[y][x].fgColor = activeFgColor
                grid[y][x].bgColor = activeBgColor
            }
            dirtyTracker.markRowDirty(y, cols)
        }
    }

    fun insertLines(count: Int) {
        synchronized(this) {
            if (cols <= 0 || rows <= 0) return
            if (cursorY < marginTop || cursorY > marginBottom) return
            
            val limit = marginBottom
            val c = count.coerceIn(1, limit - cursorY + 1)
            
            for (y in limit downTo cursorY + c) {
                for (x in 0 until cols) {
                    grid[y][x].copyFrom(grid[y - c][x])
                }
            }
            for (y in cursorY until cursorY + c) {
                for (x in 0 until cols) {
                    grid[y][x].reset()
                }
            }
            for (y in cursorY..limit) {
                dirtyTracker.markRowDirty(y, cols)
            }
        }
    }

    fun deleteLines(count: Int) {
        synchronized(this) {
            if (cols <= 0 || rows <= 0) return
            if (cursorY < marginTop || cursorY > marginBottom) return
            
            val limit = marginBottom
            val c = count.coerceIn(1, limit - cursorY + 1)
            
            for (y in cursorY until limit - c + 1) {
                for (x in 0 until cols) {
                    grid[y][x].copyFrom(grid[y + c][x])
                }
            }
            for (y in limit - c + 1 .. limit) {
                for (x in 0 until cols) {
                    grid[y][x].reset()
                }
            }
            for (y in cursorY..limit) {
                dirtyTracker.markRowDirty(y, cols)
            }
        }
    }

    fun scrollDown() {
        synchronized(this) {
            if (cols <= 0 || rows <= 0) return
            scrollDownRegion(marginTop, marginBottom)
        }
    }

    fun reverseIndex() {
        synchronized(this) {
            val oldY = cursorY
            if (cursorY == marginTop) {
                scrollDown()
            } else if (cursorY > 0) {
                cursorY--
            }
            dirtyTracker.markRowDirty(oldY, cols)
            dirtyTracker.markRowDirty(cursorY, cols)
        }
    }

    fun getLine(index: Int): Array<TerminalCell> {
        synchronized(this) {
            if (isAlternateBufferActive) {
                return if (index in 0 until rows) grid[index] else Array(cols) { TerminalCell() }
            }
            return if (index < history.size) {
                history.getLine(index)
            } else {
                val gridIndex = index - history.size
                if (gridIndex < rows) {
                    grid[gridIndex]
                } else {
                    Array(cols) { TerminalCell() }
                }
            }
        }
    }

    fun totalLines(): Int {
        synchronized(this) {
            return if (isAlternateBufferActive) rows else history.size + rows
        }
    }

    fun saveCursor() {
        synchronized(this) {
            savedCursorX = cursorX
            savedCursorY = cursorY
            savedActiveFgColor = activeFgColor
            savedActiveBgColor = activeBgColor
            savedActiveBold = activeBold
            savedActiveItalic = activeItalic
            savedActiveUnderline = activeUnderline
            savedActiveHyperlinkUrl = activeHyperlinkUrl
        }
    }

    fun restoreCursor() {
        synchronized(this) {
            val oldY = cursorY
            cursorX = savedCursorX.coerceIn(0, cols - 1)
            cursorY = savedCursorY.coerceIn(0, rows - 1)
            activeFgColor = savedActiveFgColor
            activeBgColor = savedActiveBgColor
            activeBold = savedActiveBold
            activeItalic = savedActiveItalic
            activeUnderline = savedActiveUnderline
            activeHyperlinkUrl = savedActiveHyperlinkUrl
            dirtyTracker.markRowDirty(oldY, cols)
            dirtyTracker.markRowDirty(cursorY, cols)
        }
    }

    fun resetStyle() {
        synchronized(this) {
            activeFgColor = null
            activeBgColor = null
            activeBold = false
            activeItalic = false
            activeUnderline = false
            activeHyperlinkUrl = null
        }
    }
}
