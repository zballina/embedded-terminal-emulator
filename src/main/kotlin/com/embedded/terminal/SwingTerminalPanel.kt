package com.embedded.terminal

import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import java.awt.*
import java.awt.event.*
import java.awt.font.TextAttribute
import javax.swing.JPanel
import javax.swing.Timer
import javax.swing.SwingUtilities
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.DataFlavor

class SwingTerminalPanel(
    val project: com.intellij.openapi.project.Project?,
    val buffer: TerminalBuffer,
    val stateEngine: TerminalStateEngine,
    var onInput: ((String) -> Unit)? = null
) : JPanel(), KeyListener, MouseListener, MouseMotionListener, FocusListener, ComponentListener, DataProvider, javax.swing.Scrollable {

    var triggerSmartPasteCallback: (() -> Unit)? = null

    var onResize: ((Int, Int) -> Unit)? = null

    private val padding = 8
    private var charWidth = 8
    private var rowHeight = 16
    private var charAscent = 12
    private var charDescent = 4

    private var cursorBlinkState = true
    private var blinkTimer: Timer? = null
    private val glyphAtlas = GlyphAtlas()
    var isTelemetryEnabled: Boolean = false
    private var fpsCount = 0
    private var lastFpsTime = System.currentTimeMillis()
    private var currentFps = 0

    // Scrollback offset (0 means scrolled all the way to the bottom)
    var scrollOffset = 0
    var scrollBar: javax.swing.JScrollBar? = null
    var inResize = false
    private var fractionalScroll = 0.0
    private var fractionalReportScroll = 0.0
    @Volatile
    private var cachedFont: Font? = null
    @Volatile
    private var cachedColors: ActiveColors? = null

    // Text selection coordinates in buffer space
    private var selStartX = -1
    private var selStartY = -1
    private var selEndX = -1
    private var selEndY = -1

    private val renderScheduler = RenderScheduler {
        // Read dirty spans atomically, then trigger repaint — lock held only for this tiny window
        val spans: List<DirtySpan>
        synchronized(buffer) {
            spans = buffer.dirtyTracker.getConsolidatedSpans()
            if (spans.isNotEmpty()) buffer.dirtyTracker.clear()
        }
        if (spans.isEmpty()) return@RenderScheduler
        updateScrollBar()
        repaint()
    }

    fun requestRepaint() {
        renderScheduler.requestRepaint()
    }

    fun updateScrollBar() {
        val bar = scrollBar ?: return
        val isAlt = buffer.isAlternateBufferActive
        val historySize = if (isAlt) 0 else buffer.history.size
        val rows = buffer.rows
        val currentOffset = if (isAlt) 0 else scrollOffset.coerceIn(0, historySize)
        bar.setValues(historySize - currentOffset, rows, 0, historySize + rows)
        bar.isEnabled = !isAlt
    }

    private fun resetScrollToBottom() {
        if (scrollOffset > 0) {
            scrollOffset = 0
            repaint()
            updateScrollBar()
        }
    }

    init {
        isFocusable = true
        setFocusTraversalKeysEnabled(false)
        addKeyListener(this)
        addMouseListener(this)
        addMouseMotionListener(this)
        addFocusListener(this)
        addComponentListener(this)

        // Mouse wheel listener for scrolling
        addMouseWheelListener { e ->
            synchronized(buffer) {
                if (buffer.mouseTrackingMode > 0) {
                    fractionalReportScroll -= e.preciseWheelRotation
                    val reportTicks = fractionalReportScroll.toInt()
                    if (reportTicks != 0) {
                        fractionalReportScroll -= reportTicks
                        val btn = if (reportTicks > 0) 64 else 65
                        val pt = getBufferCellCoords(e.x, e.y)
                        val x = pt.x + 1
                        val historySize = buffer.history.size
                        val viewStartLine = if (buffer.isAlternateBufferActive) 0 else historySize - scrollOffset
                        val y = (pt.y - viewStartLine) + 1
                        val absTicks = Math.abs(reportTicks)
                        for (i in 0 until absTicks) {
                            if (buffer.isMouseSgrEnabled) {
                                onInput?.invoke("\u001b[<$btn;$x;${y}M")
                            } else {
                                onInput?.invoke("\u001b[M" + (btn + 32).toChar() + (x + 32).toChar() + (y + 32).toChar())
                            }
                        }
                    }
                    e.consume()
                    return@addMouseWheelListener
                }

                if (buffer.isAlternateBufferActive) {
                    e.consume()
                    return@addMouseWheelListener
                }

                fractionalScroll -= e.preciseWheelRotation * 3.0 // 3 lines per full tick
                val intScroll = fractionalScroll.toInt()
                if (intScroll != 0) {
                    fractionalScroll -= intScroll
                    val oldOffset = scrollOffset
                    scrollOffset = (scrollOffset + intScroll).coerceIn(0, buffer.history.size)
                    if (scrollOffset != oldOffset) {
                        repaint()
                        updateScrollBar()
                    }
                }
                e.consume()
            }
        }

        // Timer for cursor blinking (500ms)
        blinkTimer = Timer(500) {
            cursorBlinkState = !cursorBlinkState
            // Only mark dirty — no need to hold lock longer than the markRowDirty call
            val cy: Int
            val cols: Int
            synchronized(buffer) {
                cy = buffer.cursorY
                cols = buffer.cols
                buffer.dirtyTracker.markRowDirty(cy, cols)
            }
            requestRepaint()
        }
        blinkTimer?.start()

        // Link scrollback offset increment
        buffer.onLineAppended = {
            synchronized(buffer) {
                if (scrollOffset > 0) {
                    scrollOffset = minOf(buffer.history.size, scrollOffset + 1)
                }
            }
        }
    }

    override fun addNotify() {
        super.addNotify()
        blinkTimer?.start()
        renderScheduler.start()
    }

    override fun removeNotify() {
        super.removeNotify()
        blinkTimer?.stop()
        renderScheduler.shutdown()
    }

    private fun getTerminalFont(): Font {
        val cached = cachedFont
        if (cached != null) return cached

        val settings = EmbeddedTerminalSettings.getInstance().state
        val scheme = EditorColorsManager.getInstance().globalScheme
        val fontFamily = if (settings.useEditorTheme) scheme.consoleFontName else settings.customFontFamily
        val fontSize = if (settings.useEditorTheme) scheme.consoleFontSize else settings.customFontSize

        val attributes = HashMap<TextAttribute, Any>()
        if (settings.enableLigatures) {
            attributes[TextAttribute.LIGATURES] = TextAttribute.LIGATURES_ON
        }
        val font = Font(fontFamily, Font.PLAIN, fontSize).deriveFont(attributes)
        cachedFont = font
        return font
    }

    private fun getLineHeightMultiplier(): Double {
        val settings = EmbeddedTerminalSettings.getInstance().state
        return if (settings.useEditorTheme) 1.0 else settings.customLineHeight
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        val settings = EmbeddedTerminalSettings.getInstance().state
        val activeColors = resolveActiveColors()
        val defaultBg = activeColors.bg
        val defaultFg = activeColors.fg

        // Setup font metrics (done before acquiring the lock — pure computation)
        val terminalFont = getTerminalFont()
        g2.font = terminalFont
        val fm = g2.fontMetrics
        charWidth = fm.charWidth('A').coerceAtLeast(1)
        rowHeight = (fm.height * getLineHeightMultiplier()).toInt().coerceAtLeast(1)
        charAscent = fm.ascent
        charDescent = fm.descent

        // ── Atomic snapshot ─────────────────────────────────────────────────────
        // Acquire the lock ONLY long enough to copy the visible cell references and
        // the cursor/meta state into local variables. The actual painting happens
        // completely outside the lock so the state engine can keep writing freely.
        val snapRows: Int
        val snapCols: Int
        val snapCursorX: Int
        val snapCursorY: Int
        val snapCursorVisible: Boolean
        val snapIsAlt: Boolean
        val snapHistorySize: Int
        val snapScrollOffset: Int
        val snapLines: Array<Array<TerminalCell>>

        synchronized(buffer) {
            snapRows = buffer.rows
            snapCols = buffer.cols
            snapCursorX = buffer.cursorX
            snapCursorY = buffer.cursorY
            snapCursorVisible = buffer.isCursorVisible
            snapIsAlt = buffer.isAlternateBufferActive
            snapHistorySize = buffer.history.size
            snapScrollOffset = if (snapIsAlt) 0 else scrollOffset.coerceIn(0, snapHistorySize)
            val viewStartLine = if (snapIsAlt) 0 else snapHistorySize - snapScrollOffset
            // Deep-copy visible rows so painting is race-free.
            // 80×24 = 1920 cells × ~8 field copies ≈ 15 µs — much cheaper than
            // holding the lock for the entire paint cycle.
            snapLines = Array(snapRows) { y ->
                val lineIdx = viewStartLine + y
                val src = if (lineIdx < buffer.totalLines()) buffer.getLine(lineIdx)
                          else Array(snapCols) { TerminalCell() }
                Array(snapCols) { x ->
                    if (x < src.size) src[x].copy() else TerminalCell()
                }
            }
        }
        // ── End of synchronized region ───────────────────────────────────────────

        // Fill background with opacity support
        val opacity = settings.backgroundOpacity / 100.0f
        if (opacity < 1.0f) {
            val oldComposite = g2.composite
            g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity)
            g2.color = defaultBg
            g2.fillRect(0, 0, width, height)
            g2.composite = oldComposite
        } else {
            g2.color = defaultBg
            g2.fillRect(0, 0, width, height)
        }

        // Draw cells from snapshot
        for (y in 0 until snapRows) {
            val line = snapLines[y]
            val links = findLinksInLine(line)
            for (x in 0 until snapCols) {
                if (x >= line.size) break
                val cell = line[x]

                if (cell.isContinuation) continue

                val cellX = padding + x * charWidth
                val cellY = padding + y * rowHeight
                val cellW = charWidth * (if (cell.width == CellWidth.DOUBLE) 2 else 1)

                // 1. Draw cell background if custom/inverted
                var finalBgColor = cell.bgColor
                var finalFgColor = cell.fgColor
                if (cell.isInverse) {
                    finalBgColor = cell.fgColor ?: defaultFg
                    finalFgColor = cell.bgColor ?: defaultBg
                }

                val bg = resolveCellColor(finalBgColor, defaultBg, activeColors.ansi)
                if (bg != defaultBg) {
                    g2.color = bg
                    g2.fillRect(cellX, cellY, cellW, rowHeight)
                }

                // 2. Selection overlay
                val snapViewStartLine = if (snapIsAlt) 0 else snapHistorySize - snapScrollOffset
                if (isCellSelected(x, snapViewStartLine + y)) {
                    g2.color = activeColors.sel
                    g2.fillRect(cellX, cellY, cellW, rowHeight)
                }

                // 3. Glyph
                if (cell.grapheme != " " && cell.grapheme.isNotEmpty()) {
                    var style = Font.PLAIN
                    if (cell.isBold) style = style or Font.BOLD
                    if (cell.isItalic) style = style or Font.ITALIC

                    val isLink = links.any { x in it }
                    val linkColor = if (defaultBg.red + defaultBg.green + defaultBg.blue < 380) {
                        Color(88, 166, 255) // Bright blue for dark mode
                    } else {
                        Color(0, 102, 204) // Darker blue for light mode
                    }
                    val fg = if (isLink) linkColor else resolveCellColor(finalFgColor, defaultFg, activeColors.ansi)
                    val key = GlyphKey(cell.grapheme, style, fg)
                    val loc = glyphAtlas.getGlyph(key, terminalFont, charWidth, rowHeight, charAscent, fm)

                    g2.drawImage(
                        loc.page.image,
                        cellX, cellY, cellX + cellW, cellY + rowHeight,
                        loc.rect.x, loc.rect.y, loc.rect.x + loc.rect.width, loc.rect.y + loc.rect.height,
                        null
                    )

                    if (cell.isUnderline || isLink) {
                        g2.stroke = BasicStroke(1f)
                        g2.color = fg
                        g2.drawLine(cellX, cellY + rowHeight - 2, cellX + cellW, cellY + rowHeight - 2)
                    }
                }
            }
        }

        // Draw cursor from snapshot
        if (snapCursorVisible) {
            val snapViewStartLine = if (snapIsAlt) 0 else snapHistorySize - snapScrollOffset
            val cursorAbsLine = if (snapIsAlt) snapCursorY else snapCursorY + snapHistorySize
            val isCursorInView = cursorAbsLine in snapViewStartLine until (snapViewStartLine + snapRows)
            if (isCursorInView) {
                val cursorViewY = cursorAbsLine - snapViewStartLine
                val cursorXPos = padding + snapCursorX * charWidth
                val cursorYPos = padding + cursorViewY * rowHeight

                g2.color = activeColors.cur

                if (hasFocus()) {
                    if (cursorBlinkState) {
                        val oldComposite = g2.composite
                        g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.6f)
                        g2.fillRect(cursorXPos, cursorYPos, charWidth, rowHeight)
                        g2.composite = oldComposite
                    }
                } else {
                    g2.stroke = BasicStroke(1f)
                    g2.drawRect(cursorXPos, cursorYPos, charWidth - 1, rowHeight - 1)
                }
            }
        }

        // Draw Telemetry HUD
        if (isTelemetryEnabled) {
            fpsCount++
            val now = System.currentTimeMillis()
            if (now - lastFpsTime >= 1000) {
                currentFps = fpsCount
                fpsCount = 0
                lastFpsTime = now
            }

            val hudWidth = 200
            val hudHeight = 90
            val xOffset = width - hudWidth - 15
            val yOffset = 15

            g2.color = Color(30, 30, 30, 210)
            g2.fillRect(xOffset, yOffset, hudWidth, hudHeight)
            g2.color = Color(80, 80, 80, 255)
            g2.stroke = BasicStroke(1f)
            g2.drawRect(xOffset, yOffset, hudWidth - 1, hudHeight - 1)

            g2.font = Font("Monospaced", Font.BOLD, 11)
            g2.color = Color(80, 220, 100, 255)
            g2.drawString("MATRIX TERMINAL HUD", xOffset + 12, yOffset + 18)

            g2.font = Font("Monospaced", Font.PLAIN, 10)
            g2.color = Color.WHITE
            g2.drawString("FPS:         $currentFps", xOffset + 12, yOffset + 34)
            g2.drawString("Buffer:      ${if (snapIsAlt) "Alternate" else "Primary"}", xOffset + 12, yOffset + 48)
            g2.drawString("Cursor:      ($snapCursorX, $snapCursorY)", xOffset + 12, yOffset + 62)
            g2.drawString("Dirty Spans: (cleared)", xOffset + 12, yOffset + 76)
        }
    }

    private fun isCellSelected(x: Int, y: Int): Boolean {
        if (selStartY == -1 || selEndY == -1) return false

        val startY = minOf(selStartY, selEndY)
        val endY = maxOf(selStartY, selEndY)

        if (y < startY || y > endY) return false

        return if (startY == endY) {
            val startX = minOf(selStartX, selEndX)
            val endX = maxOf(selStartX, selEndX)
            x in startX..endX
        } else {
            when (y) {
                selStartY -> {
                    if (selStartY < selEndY) x >= selStartX else x <= selStartX
                }
                selEndY -> {
                    if (selStartY < selEndY) x <= selEndX else x >= selEndX
                }
                else -> true
            }
        }
    }

    private fun getSelectedText(): String {
        if (selStartY == -1 || selEndY == -1) return ""
        val sb = StringBuilder()
        synchronized(buffer) {
            val startY = minOf(selStartY, selEndY)
            val endY = maxOf(selStartY, selEndY)

            for (y in startY..endY) {
                if (y >= buffer.totalLines()) break
                val line = buffer.getLine(y)
                val lineSb = StringBuilder()

                val startX = if (y == startY) {
                    if (selStartY < selEndY) selStartX else if (selStartY > selEndY) selEndX else minOf(selStartX, selEndX)
                } else {
                    0
                }

                val endX = if (y == endY) {
                    if (selStartY < selEndY) selEndX else if (selStartY > selEndY) selStartX else maxOf(selStartX, selEndX)
                } else {
                    buffer.cols - 1
                }

                for (x in startX..endX) {
                    if (x >= line.size) break
                    lineSb.append(line[x].char)
                }

                // Trim trailing spaces for this line segment
                var textLine = lineSb.toString()
                if (y != endY) {
                    textLine = textLine.replaceLastSpaces()
                    sb.append(textLine).append("\n")
                } else {
                    sb.append(textLine)
                }
            }
        }
        return sb.toString()
    }

    private fun String.replaceLastSpaces(): String {
        var len = length
        while (len > 0 && this[len - 1] == ' ') {
            len--
        }
        return substring(0, len)
    }

    // Keyboard handling
    override fun keyTyped(e: KeyEvent) {
        val c = e.keyChar
        if (c != KeyEvent.CHAR_UNDEFINED) {
            // Only process printable characters (control characters are handled in keyPressed)
            if (c.code in 32..126 || c.code > 127) {
                if (e.isControlDown && c.code in 1..26) return // Handled in keyPressed
                onInput?.invoke(c.toString())
                e.consume()
                // Reset scroll to bottom on user input
                resetScrollToBottom()
            }
        }
    }

    override fun keyPressed(e: KeyEvent) {
        val code = e.keyCode
        val isMac = System.getProperty("os.name").lowercase().contains("mac")

        // Page Up/Down viewport scrolling in normal mode
        if (!buffer.isAlternateBufferActive) {
            val hasScrollModifiers = !e.isControlDown && !e.isAltDown && !e.isMetaDown
            if (code == KeyEvent.VK_PAGE_UP && hasScrollModifiers) {
                synchronized(buffer) {
                    val oldOffset = scrollOffset
                    scrollOffset = (scrollOffset + (buffer.rows - 2)).coerceIn(0, buffer.history.size)
                    if (scrollOffset != oldOffset) {
                        repaint()
                        updateScrollBar()
                    }
                }
                e.consume()
                return
            }
            if (code == KeyEvent.VK_PAGE_DOWN && hasScrollModifiers) {
                synchronized(buffer) {
                    val oldOffset = scrollOffset
                    scrollOffset = (scrollOffset - (buffer.rows - 2)).coerceIn(0, buffer.history.size)
                    if (scrollOffset != oldOffset) {
                        repaint()
                        updateScrollBar()
                    }
                }
                e.consume()
                return
            }
        }

        // macOS modifier keys mappings
        if (isMac) {
            // Opt + Backspace -> delete previous word (send ESC + DEL)
            if (e.isAltDown && code == KeyEvent.VK_BACK_SPACE) {
                onInput?.invoke("\u001b\u007f")
                e.consume()
                return
            }
            // Cmd + Backspace -> delete entire line (send Ctrl + U)
            if (e.isMetaDown && code == KeyEvent.VK_BACK_SPACE) {
                onInput?.invoke("\u0015")
                e.consume()
                return
            }
            // Opt + Right Arrow -> move to next word (send ESC + f)
            if (e.isAltDown && code == KeyEvent.VK_RIGHT) {
                onInput?.invoke("\u001bf")
                e.consume()
                return
            }
            // Opt + Left Arrow -> move to previous word (send ESC + b)
            if (e.isAltDown && code == KeyEvent.VK_LEFT) {
                onInput?.invoke("\u001bb")
                e.consume()
                return
            }
            // Cmd + Left Arrow -> move to start of line (send Ctrl + A)
            if (e.isMetaDown && code == KeyEvent.VK_LEFT) {
                onInput?.invoke("\u0001")
                e.consume()
                return
            }
            // Cmd + Right Arrow -> move to end of line (send Ctrl + E)
            if (e.isMetaDown && code == KeyEvent.VK_RIGHT) {
                onInput?.invoke("\u0005")
                e.consume()
                return
            }
        } else {
            // Windows / Linux modifier keys mappings
            // Ctrl + Backspace -> delete previous word (send ESC + DEL)
            if (e.isControlDown && code == KeyEvent.VK_BACK_SPACE) {
                onInput?.invoke("\u001b\u007f")
                e.consume()
                return
            }
            // Alt + Backspace -> delete entire line (send Ctrl + U)
            if (e.isAltDown && code == KeyEvent.VK_BACK_SPACE) {
                onInput?.invoke("\u0015")
                e.consume()
                return
            }
            // Ctrl + Right Arrow -> move to next word (send ESC + f)
            if (e.isControlDown && code == KeyEvent.VK_RIGHT) {
                onInput?.invoke("\u001bf")
                e.consume()
                return
            }
            // Ctrl + Left Arrow -> move to previous word (send ESC + b)
            if (e.isControlDown && code == KeyEvent.VK_LEFT) {
                onInput?.invoke("\u001bb")
                e.consume()
                return
            }
            // Alt + Left Arrow -> move to start of line (send Ctrl + A)
            if (e.isAltDown && code == KeyEvent.VK_LEFT) {
                onInput?.invoke("\u0001")
                e.consume()
                return
            }
            // Alt + Right Arrow -> move to end of line (send Ctrl + E)
            if (e.isAltDown && code == KeyEvent.VK_RIGHT) {
                onInput?.invoke("\u0005")
                e.consume()
                return
            }
        }

        // Handle copy/paste shortcuts
        val modifierDown = if (isMac) e.isMetaDown else e.isControlDown

        if (modifierDown && code == KeyEvent.VK_C) {
            // Copy
            val text = getSelectedText()
            if (text.isNotEmpty()) {
                val stringSelection = StringSelection(text)
                Toolkit.getDefaultToolkit().systemClipboard.setContents(stringSelection, stringSelection)
                e.consume()
                return
            }
            if (isMac) {
                e.consume()
                return
            }
        }

        if (modifierDown && code == KeyEvent.VK_V) {
            System.err.println("[SMART_PASTE] VK_V pressed. modifierDown=$modifierDown, isShiftDown=${e.isShiftDown}")
            if (e.isShiftDown) {
                triggerSmartPasteCallback?.invoke()
            } else {
                handleNormalPaste()
            }
            e.consume()
            return
        }

        // Handle Ctrl + [Key]
        if (e.isControlDown && code in KeyEvent.VK_A..KeyEvent.VK_Z) {
            val ctrlChar = (code - KeyEvent.VK_A + 1).toChar().toString()
            onInput?.invoke(ctrlChar)
            e.consume()
            resetScrollToBottom()
            return
        }

        // Map key codes with modifiers (Shift=1, Alt/Meta=2, Control=4)
        val shift = if (e.isShiftDown) 1 else 0
        val alt = if (e.isAltDown) 2 else 0
        val ctrl = if (e.isControlDown) 4 else 0
        val modifierNum = 1 + shift + alt + ctrl

        val escapeSequence = if (modifierNum > 1) {
            when (code) {
                KeyEvent.VK_UP -> "\u001b[1;${modifierNum}A"
                KeyEvent.VK_DOWN -> "\u001b[1;${modifierNum}B"
                KeyEvent.VK_RIGHT -> "\u001b[1;${modifierNum}C"
                KeyEvent.VK_LEFT -> "\u001b[1;${modifierNum}D"
                KeyEvent.VK_HOME -> "\u001b[1;${modifierNum}H"
                KeyEvent.VK_END -> "\u001b[1;${modifierNum}F"
                KeyEvent.VK_PAGE_UP -> "\u001b[5;${modifierNum}~"
                KeyEvent.VK_PAGE_DOWN -> "\u001b[6;${modifierNum}~"
                KeyEvent.VK_DELETE -> "\u001b[3;${modifierNum}~"
                KeyEvent.VK_ENTER -> if (e.isShiftDown && !e.isControlDown && !e.isAltDown) "\\\r" else "\r"
                KeyEvent.VK_BACK_SPACE -> "\u007f"
                KeyEvent.VK_TAB -> "\u001b[Z"
                KeyEvent.VK_ESCAPE -> "\u001b"
                else -> null
            }
        } else {
            when (code) {
                KeyEvent.VK_UP -> "\u001b[A"
                KeyEvent.VK_DOWN -> "\u001b[B"
                KeyEvent.VK_RIGHT -> "\u001b[C"
                KeyEvent.VK_LEFT -> "\u001b[D"
                KeyEvent.VK_HOME -> "\u001b[H"
                KeyEvent.VK_END -> "\u001b[F"
                KeyEvent.VK_PAGE_UP -> "\u001b[5~"
                KeyEvent.VK_PAGE_DOWN -> "\u001b[6~"
                KeyEvent.VK_DELETE -> "\u001b[3~"
                KeyEvent.VK_ENTER -> "\r"
                KeyEvent.VK_BACK_SPACE -> "\u007f"
                KeyEvent.VK_TAB -> "\t"
                KeyEvent.VK_ESCAPE -> "\u001b"
                else -> null
            }
        }

        if (escapeSequence != null) {
            onInput?.invoke(escapeSequence)
            e.consume()
            resetScrollToBottom()
        }
    }

    override fun keyReleased(e: KeyEvent) {}

    private fun sendMouseEvent(e: MouseEvent, button: Int, isRelease: Boolean) {
        val pt = getBufferCellCoords(e.x, e.y)
        val x = pt.x + 1
        val historySize = buffer.history.size
        val viewStartLine = if (buffer.isAlternateBufferActive) 0 else historySize - scrollOffset
        val y = (pt.y - viewStartLine) + 1
        
        if (buffer.isMouseSgrEnabled) {
            val suffix = if (isRelease) "m" else "M"
            onInput?.invoke("\u001b[<$button;$x;$y$suffix")
        } else {
            val btnChar = (button + 32).toChar()
            val xChar = (x + 32).coerceIn(32, 255).toChar()
            val yChar = (y + 32).coerceIn(32, 255).toChar()
            onInput?.invoke("\u001b[M$btnChar$xChar$yChar")
        }
    }

    // Mouse Selection Handling
    override fun mousePressed(e: MouseEvent) {
        requestFocusInWindow()
        if (buffer.mouseTrackingMode > 0) {
            val btn = if (SwingUtilities.isLeftMouseButton(e)) 0
                      else if (SwingUtilities.isMiddleMouseButton(e)) 1
                      else if (SwingUtilities.isRightMouseButton(e)) 2
                      else 0
            sendMouseEvent(e, btn, false)
            e.consume()
            return
        }

        if (e.button == MouseEvent.BUTTON1) {
            val pt = getBufferCellCoords(e.x, e.y)
            selStartX = pt.x
            selStartY = pt.y
            selEndX = pt.x
            selEndY = pt.y
            repaint()
        }
    }

    override fun mouseDragged(e: MouseEvent) {
        if (buffer.mouseTrackingMode >= 1002) {
            val btn = if (SwingUtilities.isLeftMouseButton(e)) 32
                      else if (SwingUtilities.isMiddleMouseButton(e)) 33
                      else if (SwingUtilities.isRightMouseButton(e)) 34
                      else 32
            sendMouseEvent(e, btn, false)
            e.consume()
            return
        }
        if (buffer.mouseTrackingMode > 0) return

        val pt = getBufferCellCoords(e.x, e.y)
        selEndX = pt.x
        selEndY = pt.y
        repaint()
    }

    override fun mouseReleased(e: MouseEvent) {
        if (buffer.mouseTrackingMode > 0) {
            val btn = if (SwingUtilities.isLeftMouseButton(e)) 0
                      else if (SwingUtilities.isMiddleMouseButton(e)) 1
                      else if (SwingUtilities.isRightMouseButton(e)) 2
                      else 0
            if (buffer.isMouseSgrEnabled) {
                sendMouseEvent(e, btn, true)
            } else {
                sendMouseEvent(e, 3, false)
            }
            e.consume()
            return
        }

        val pt = getBufferCellCoords(e.x, e.y)
        selEndX = pt.x
        selEndY = pt.y
        if (selStartX == selEndX && selStartY == selEndY) {
            clearSelection()
        }
        repaint()
    }

    private fun getBufferCellCoords(mx: Int, my: Int): Point {
        synchronized(buffer) {
            val historySize = buffer.history.size
            val isAlt = buffer.isAlternateBufferActive
            val currentOffset = if (isAlt) 0 else scrollOffset.coerceIn(0, historySize)
            val viewStartLine = if (isAlt) 0 else historySize - currentOffset
            val x = ((mx - padding) / charWidth).coerceIn(0, buffer.cols - 1)
            val y = (((my - padding) / rowHeight).coerceIn(0, buffer.rows - 1)) + viewStartLine
            return Point(x, y)
        }
    }

    private fun clearSelection() {
        selStartX = -1
        selStartY = -1
        selEndX = -1
        selEndY = -1
    }

    override fun mouseClicked(e: MouseEvent) {
        if (SwingUtilities.isLeftMouseButton(e)) {
            if (e.clickCount == 2) {
                handleDoubleSelect(e)
            } else if (e.clickCount == 1) {
                handleLinkClick(e)
            }
        }
    }

    private fun handleDoubleSelect(e: MouseEvent) {
        val pt = getBufferCellCoords(e.x, e.y)
        val y = pt.y
        val x = pt.x
        val lineCells = synchronized(buffer) {
            if (y in 0 until buffer.totalLines()) {
                buffer.getLine(y)
            } else {
                null
            }
        } ?: return

        if (x >= lineCells.size) return

        val delimiters = setOf(' ', '"', '\'', '(', ')', '[', ']', '{', '}', '<', '>', ';', ',', '|', '&', '*', '^', '?', '!', '=', '+', '`', '\t', '\n', '\r')
        fun isWordChar(cell: TerminalCell): Boolean {
            val g = cell.grapheme
            if (g.isEmpty() || g.isBlank()) return false
            val c = g.first()
            return c !in delimiters
        }

        // Search left
        var startX = x
        while (startX > 0 && isWordChar(lineCells[startX - 1])) {
            startX--
        }

        // Search right
        var endX = x
        while (endX < lineCells.size - 1 && isWordChar(lineCells[endX + 1])) {
            endX++
        }

        if (isWordChar(lineCells[x])) {
            selStartX = startX
            selStartY = y
            selEndX = endX
            selEndY = y
            repaint()
        }
    }

    private fun handleLinkClick(e: MouseEvent) {
        val pt = getBufferCellCoords(e.x, e.y)
        val y = pt.y
        val lineCells = synchronized(buffer) {
            if (y in 0 until buffer.totalLines()) {
                buffer.getLine(y)
            } else {
                null
            }
        }
        if (lineCells != null) {
            val links = findLinksInLine(lineCells)
            val x = pt.x
            val matchedRange = links.find { x in it }
            if (matchedRange != null && project != null) {
                val lineText = lineCells.joinToString("") { it.grapheme }
                val pathStr = lineText.substring(matchedRange.first, matchedRange.last + 1)
                val file = java.io.File(pathStr)
                if (file.exists()) {
                    val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(file)
                    if (virtualFile != null) {
                        ApplicationManager.getApplication().invokeLater {
                            FileEditorManager.getInstance(project).openFile(virtualFile, true)
                        }
                    }
                }
            }
        }
    }
    override fun mouseEntered(e: MouseEvent) {}
    override fun mouseExited(e: MouseEvent) {}
    override fun mouseMoved(e: MouseEvent) {}

    // Focus Listener
    override fun focusGained(e: FocusEvent) {
        cursorBlinkState = true
        repaint()
    }

    override fun focusLost(e: FocusEvent) {
        repaint()
    }

    // Component Resizing Listener
    override fun componentResized(e: ComponentEvent) {
        inResize = true
        try {
            recalculateDimensions()
        } finally {
            javax.swing.SwingUtilities.invokeLater {
                inResize = false
            }
        }
    }

    override fun componentMoved(e: ComponentEvent) {}
    override fun componentShown(e: ComponentEvent) {
        inResize = true
        try {
            recalculateDimensions()
        } finally {
            javax.swing.SwingUtilities.invokeLater {
                inResize = false
            }
        }
    }
    override fun componentHidden(e: ComponentEvent) {}

    fun recalculateDimensions() {
        cachedFont = null
        cachedColors = null
        if (width < 50 || height < 30) return
        val font = getTerminalFont()
        val metrics = getFontMetrics(font)
        val newCharWidth = metrics.charWidth('A').coerceAtLeast(1)
        val newRowHeight = (metrics.height * getLineHeightMultiplier()).toInt().coerceAtLeast(1)

        if (newCharWidth != charWidth || newRowHeight != rowHeight) {
            charWidth = newCharWidth
            rowHeight = newRowHeight
            glyphAtlas.clear()
        }

        val newCols = ((width - padding * 2) / charWidth).coerceAtLeast(1)
        val newRows = ((height - padding * 2) / rowHeight).coerceAtLeast(1)

        if (newCols != buffer.cols || newRows != buffer.rows) {
            stateEngine.dispatchCommand(TerminalCommand.Resize(newCols, newRows))
            onResize?.invoke(newCols, newRows)
            updateScrollBar()
            repaint()
        }
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visibleRect: Rectangle?, orientation: Int, direction: Int): Int = rowHeight
    override fun getScrollableBlockIncrement(visibleRect: Rectangle?, orientation: Int, direction: Int): Int = rowHeight * 3
    override fun getScrollableTracksViewportWidth(): Boolean = true
    override fun getScrollableTracksViewportHeight(): Boolean = true

    override fun getData(dataId: String): Any? {
        return null
    }

    override fun getPreferredSize(): Dimension {
        return Dimension(600, 400)
    }

    private fun resolveActiveColors(): ActiveColors {
        val cached = cachedColors
        if (cached != null) return cached

        val settings = EmbeddedTerminalSettings.getInstance().state
        val schemeName = settings.colorSchemeName

        val defaultBg: Color
        val defaultFg: Color
        val selectionColor: Color
        val cursorColor: Color
        val ansiColors: List<Color>

        when (schemeName) {
            "Editor Theme" -> {
                val scheme = EditorColorsManager.getInstance().globalScheme
                defaultBg = scheme.defaultBackground ?: (if (com.intellij.util.ui.UIUtil.isUnderDarcula()) Color(23, 23, 23) else Color(255, 255, 255))
                defaultFg = scheme.defaultForeground ?: (if (com.intellij.util.ui.UIUtil.isUnderDarcula()) Color(248, 248, 242) else Color(0, 0, 0))
                selectionColor = Color(60, 120, 220, 100)
                cursorColor = if (defaultBg.red + defaultBg.green + defaultBg.blue < 380) Color.WHITE else Color.BLACK

                ansiColors = (0..15).map { idx ->
                    getEditorAnsiColor(idx, scheme)
                }
            }
            "Custom" -> {
                defaultBg = TerminalColorScheme.parseHexColor(settings.customBackground)
                defaultFg = TerminalColorScheme.parseHexColor(settings.customForeground)
                selectionColor = TerminalColorScheme.parseHexColor(settings.customSelection)
                cursorColor = TerminalColorScheme.parseHexColor(settings.customCursor)
                ansiColors = settings.customAnsiColorsHex.split(";").map { TerminalColorScheme.parseHexColor(it) }
            }
            else -> {
                val preset = TerminalColorScheme.findByName(schemeName) ?: TerminalColorScheme.Dracula
                defaultBg = preset.backgroundColor
                defaultFg = preset.foregroundColor
                selectionColor = preset.selectionColor
                cursorColor = preset.cursorColor
                ansiColors = preset.ansiColors
            }
        }

        val colors = ActiveColors(defaultBg, defaultFg, selectionColor, cursorColor, ansiColors)
        cachedColors = colors
        return colors
    }

    private fun resolveCellColor(cellColor: Color?, defaultColor: Color, ansiColors: List<Color>): Color {
        if (cellColor == null) return defaultColor
        if (cellColor is IndexedAnsiColor) {
            val idx = cellColor.index
            if (idx in 0 until ansiColors.size) {
                return ansiColors[idx]
            }
        }
        return cellColor
    }

    private fun getEditorAnsiColor(index: Int, scheme: com.intellij.openapi.editor.colors.EditorColorsScheme): Color {
        val key = when (index) {
            0 -> com.intellij.execution.process.ConsoleHighlighter.BLACK
            1 -> com.intellij.execution.process.ConsoleHighlighter.RED
            2 -> com.intellij.execution.process.ConsoleHighlighter.GREEN
            3 -> com.intellij.execution.process.ConsoleHighlighter.YELLOW
            4 -> com.intellij.execution.process.ConsoleHighlighter.BLUE
            5 -> com.intellij.execution.process.ConsoleHighlighter.MAGENTA
            6 -> com.intellij.execution.process.ConsoleHighlighter.CYAN
            7 -> com.intellij.execution.process.ConsoleHighlighter.GRAY
            8 -> com.intellij.execution.process.ConsoleHighlighter.DARKGRAY
            9 -> com.intellij.execution.process.ConsoleHighlighter.RED_BRIGHT
            10 -> com.intellij.execution.process.ConsoleHighlighter.GREEN_BRIGHT
            11 -> com.intellij.execution.process.ConsoleHighlighter.YELLOW_BRIGHT
            12 -> com.intellij.execution.process.ConsoleHighlighter.BLUE_BRIGHT
            13 -> com.intellij.execution.process.ConsoleHighlighter.MAGENTA_BRIGHT
            14 -> com.intellij.execution.process.ConsoleHighlighter.CYAN_BRIGHT
            15 -> com.intellij.execution.process.ConsoleHighlighter.WHITE
            else -> null
        }
        val attr = if (key != null) scheme.getAttributes(key) else null
        return attr?.foregroundColor ?: getDefaultAnsiColor(index)
    }

    private fun getDefaultAnsiColor(index: Int): Color {
        return when (index) {
            0 -> Color(0, 0, 0)
            1 -> Color(205, 0, 0)
            2 -> Color(0, 205, 0)
            3 -> Color(205, 205, 0)
            4 -> Color(0, 0, 238)
            5 -> Color(205, 0, 205)
            6 -> Color(0, 205, 205)
            7 -> Color(229, 229, 229)
            8 -> Color(127, 127, 127)
            9 -> Color(255, 0, 0)
            10 -> Color(0, 255, 0)
            11 -> Color(255, 255, 0)
            12 -> Color(92, 92, 255)
            13 -> Color(255, 0, 255)
            14 -> Color(0, 255, 255)
            15 -> Color(255, 255, 255)
            else -> Color.WHITE
        }
    }

    private data class ActiveColors(
        val bg: Color,
        val fg: Color,
        val sel: Color,
        val cur: Color,
        val ansi: List<Color>
    )

    private fun handleNormalPaste() {
        try {
            val clipboard = SystemClipboardWrapper()
            val contents = clipboard.getContents() ?: return
            if (contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                val list = contents.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
                val files = list?.filterIsInstance<java.io.File>() ?: emptyList()
                if (files.isNotEmpty()) {
                    val settings = EmbeddedTerminalSettings.getInstance().state
                    val shellType = ShellEscaper.detectShell(settings.shellPath)
                    val escapedPaths = files.joinToString(" ") { ShellEscaper.escape(it.absolutePath, shellType) }
                    onInput?.invoke(escapedPaths)
                    resetScrollToBottom()
                    return
                }
            }
            if (contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                val text = contents.getTransferData(DataFlavor.stringFlavor) as String
                val sanitized = TerminalPasteSanitizer.sanitize(text)
                val processedText = if (buffer.isBracketedPasteMode) {
                    "\u001b[200~$sanitized\u001b[201~"
                } else {
                    sanitized
                }
                onInput?.invoke(processedText)
                resetScrollToBottom()
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

    private fun findLinksInLine(line: Array<TerminalCell>): List<IntRange> {
        val lineText = line.joinToString("") { it.grapheme }
        val sessionsDir = SmartPastePaths.getSessionsDir()
        val sessionsDirEscaped = Regex.escape(sessionsDir)
        val pathRegex = Regex("$sessionsDirEscaped/[a-zA-Z0-9_-]+/[a-zA-Z0-9_.-]+")
        return pathRegex.findAll(lineText).map { it.range }.toList()
    }
}
