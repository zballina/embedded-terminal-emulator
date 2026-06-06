package com.embedded.terminal

import java.awt.Color

class AnsiEscapeParser(
    private val stateProvider: TerminalStateProvider,
    private val onCommand: (TerminalCommand) -> Unit
) {
    enum class State {
        TEXT_NORMAL,
        ESCAPE_DETECTED,
        CSI_PARSE,
        G0_G1_SET,
        OSC_PARSE,
        OSC_ESCAPE,
        DCS_PARSE,
        DCS_ESCAPE
    }

    private var state = State.TEXT_NORMAL
    private val csiParams = StringBuilder()
    private val textBuffer = StringBuilder()

    fun flush() {
        if (textBuffer.isNotEmpty()) {
            onCommand(TerminalCommand.WriteText(textBuffer.toString()))
            textBuffer.clear()
        }
    }

    fun parseChar(c: Char) {
        when (state) {
            State.TEXT_NORMAL -> {
                when (c) {
                    '\u001b' -> {
                        flush()
                        state = State.ESCAPE_DETECTED
                    }
                    '\n' -> {
                        flush()
                        onCommand(TerminalCommand.NewLine)
                    }
                    '\r' -> {
                        flush()
                        onCommand(TerminalCommand.CarriageReturn)
                    }
                    '\b' -> {
                        flush()
                        onCommand(TerminalCommand.Backspace)
                    }
                    '\t' -> {
                        flush()
                        onCommand(TerminalCommand.Tab)
                    }
                    else -> {
                        if (c.code >= 32) {
                            textBuffer.append(c)
                        } else {
                            flush()
                        }
                    }
                }
            }
            State.ESCAPE_DETECTED -> {
                when (c) {
                    '[' -> {
                        csiParams.clear()
                        state = State.CSI_PARSE
                    }
                    ']' -> {
                        csiParams.clear()
                        state = State.OSC_PARSE
                    }
                    'P' -> {
                        csiParams.clear()
                        state = State.DCS_PARSE
                    }
                    '(' , ')' -> {
                        state = State.G0_G1_SET
                    }
                    'M' -> {
                        onCommand(TerminalCommand.ReverseIndex)
                        state = State.TEXT_NORMAL
                    }
                    '7' -> {
                        onCommand(TerminalCommand.SaveCursor)
                        state = State.TEXT_NORMAL
                    }
                    '8' -> {
                        onCommand(TerminalCommand.RestoreCursor)
                        state = State.TEXT_NORMAL
                    }
                    else -> {
                        // Some other escape sequence we don't fully parse, just go back to normal
                        state = State.TEXT_NORMAL
                    }
                }
            }
            State.CSI_PARSE -> {
                if (c.code in 0x30..0x3F || c.code in 0x20..0x2F) {
                    csiParams.append(c)
                } else if (c.code in 0x40..0x7E) {
                    // CSI command character
                    processCsiCommand(c, csiParams.toString())
                    state = State.TEXT_NORMAL
                } else {
                    // Out of bounds character, reset to normal
                    state = State.TEXT_NORMAL
                }
            }
            State.G0_G1_SET -> {
                // Ignore the character set character (like 'B' or '0') and return to normal
                state = State.TEXT_NORMAL
            }
            State.OSC_PARSE -> {
                if (c == '\u0007') {
                    processOscCommand(csiParams.toString())
                    state = State.TEXT_NORMAL
                } else if (c == '\u001b') {
                    state = State.OSC_ESCAPE
                } else {
                    csiParams.append(c)
                }
            }
            State.OSC_ESCAPE -> {
                if (c == '\\') {
                    processOscCommand(csiParams.toString())
                    state = State.TEXT_NORMAL
                } else {
                    state = State.TEXT_NORMAL
                }
            }
            State.DCS_PARSE -> {
                if (c == '\u0007') {
                    processDcsCommand(csiParams.toString())
                    state = State.TEXT_NORMAL
                } else if (c == '\u001b') {
                    state = State.DCS_ESCAPE
                } else {
                    csiParams.append(c)
                }
            }
            State.DCS_ESCAPE -> {
                if (c == '\\') {
                    processDcsCommand(csiParams.toString())
                    state = State.TEXT_NORMAL
                } else {
                    state = State.TEXT_NORMAL
                }
            }
        }
    }

    private fun processOscCommand(paramsStr: String) {
        if (paramsStr.startsWith("8;")) {
            // Hyperlink: 8;[params];[url]
            val parts = paramsStr.substring(2).split(';')
            if (parts.size >= 2) {
                val url = parts[1]
                val finalUrl = if (url.isEmpty()) null else url
                onCommand(TerminalCommand.SetHyperlinkUrl(finalUrl))
            }
        } else if (paramsStr.startsWith("52;")) {
            // Clipboard copy: 52;[pc];[base64]
            val parts = paramsStr.substring(3).split(';')
            if (parts.size >= 2) {
                val base64Data = parts[1]
                try {
                    val decoded = String(java.util.Base64.getDecoder().decode(base64Data), Charsets.UTF_8)
                    onCommand(TerminalCommand.CopyToClipboard(decoded))
                } catch (e: Exception) {}
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun processDcsCommand(paramsStr: String) {
        // DCS is parsed formally, but currently unhandled by specific terminal operations
        // (can be extended if future DCS commands are required).
    }

    private fun processCsiCommand(cmd: Char, paramsStr: String) {
        if (cmd == 'h' || cmd == 'l') {
            val show = cmd == 'h'
            if (paramsStr.startsWith("?")) {
                val modeStr = paramsStr.substring(1)
                val modes = modeStr.split(';').map { it.toIntOrNull() ?: 0 }
                for (mode in modes) {
                    when (mode) {
                        25 -> onCommand(TerminalCommand.SetCursorVisible(show))
                        2004 -> onCommand(TerminalCommand.SetBracketedPasteMode(show))
                        47, 1047, 1049 -> onCommand(TerminalCommand.UseAlternateBuffer(show))
                        1000, 1002, 1003 -> onCommand(TerminalCommand.SetMouseTrackingMode(mode, show))
                        1006 -> onCommand(TerminalCommand.SetMouseSgrEncoding(show))
                    }
                }
            }
            return
        }

        // Parse numeric parameters
        val cleanParamsStr = if (paramsStr.startsWith("?")) paramsStr.substring(1) else paramsStr
        val params = cleanParamsStr.split(';').map { it.toIntOrNull() ?: 0 }

        when (cmd) {
            'm' -> {
                // SGR
                if (paramsStr.isEmpty()) {
                    onCommand(TerminalCommand.ResetStyle)
                    return
                }
                var idx = 0
                while (idx < params.size) {
                    val p = params[idx]
                    when (p) {
                        0 -> onCommand(TerminalCommand.ResetStyle)
                        1 -> onCommand(TerminalCommand.SetBold(true))
                        3 -> onCommand(TerminalCommand.SetItalic(true))
                        4 -> onCommand(TerminalCommand.SetUnderline(true))
                        22 -> onCommand(TerminalCommand.SetBold(false))
                        23 -> onCommand(TerminalCommand.SetItalic(false))
                        24 -> onCommand(TerminalCommand.SetUnderline(false))
                        in 30..37 -> onCommand(TerminalCommand.SetFgColor(getAnsiColor(p - 30)))
                        38 -> {
                            if (idx + 1 < params.size) {
                                val mode = params[idx + 1]
                                if (mode == 5 && idx + 2 < params.size) {
                                    onCommand(TerminalCommand.SetFgColor(get256Color(params[idx + 2])))
                                    idx += 2
                                } else if (mode == 2 && idx + 4 < params.size) {
                                    val r = params[idx + 2].coerceIn(0, 255)
                                    val g = params[idx + 3].coerceIn(0, 255)
                                    val b = params[idx + 4].coerceIn(0, 255)
                                    onCommand(TerminalCommand.SetFgColor(Color(r, g, b)))
                                    idx += 4
                                }
                            }
                        }
                        39 -> onCommand(TerminalCommand.SetFgColor(null))
                        in 40..47 -> onCommand(TerminalCommand.SetBgColor(getAnsiColor(p - 40)))
                        48 -> {
                            if (idx + 1 < params.size) {
                                val mode = params[idx + 1]
                                if (mode == 5 && idx + 2 < params.size) {
                                    onCommand(TerminalCommand.SetBgColor(get256Color(params[idx + 2])))
                                    idx += 2
                                } else if (mode == 2 && idx + 4 < params.size) {
                                    val r = params[idx + 2].coerceIn(0, 255)
                                    val g = params[idx + 3].coerceIn(0, 255)
                                    val b = params[idx + 4].coerceIn(0, 255)
                                    onCommand(TerminalCommand.SetBgColor(Color(r, g, b)))
                                    idx += 4
                                }
                            }
                        }
                        49 -> onCommand(TerminalCommand.SetBgColor(null))
                        in 90..97 -> onCommand(TerminalCommand.SetFgColor(getAnsiColor(p - 90 + 8)))
                        in 100..107 -> onCommand(TerminalCommand.SetBgColor(getAnsiColor(p - 100 + 8)))
                    }
                    idx++
                }
            }
            'H', 'f' -> {
                // Cursor position
                val row = if (params.isNotEmpty()) params[0] else 1
                val col = if (params.size > 1) params[1] else 1
                onCommand(TerminalCommand.MoveCursor(col - 1, row - 1))
            }
            'A' -> {
                // Cursor Up
                val dist = if (params.isNotEmpty() && params[0] > 0) params[0] else 1
                onCommand(TerminalCommand.MoveCursorRelative(0, -dist))
            }
            'B' -> {
                // Cursor Down
                val dist = if (params.isNotEmpty() && params[0] > 0) params[0] else 1
                onCommand(TerminalCommand.MoveCursorRelative(0, dist))
            }
            'C' -> {
                // Cursor Forward
                val dist = if (params.isNotEmpty() && params[0] > 0) params[0] else 1
                onCommand(TerminalCommand.MoveCursorRelative(dist, 0))
            }
            'D' -> {
                // Cursor Backward
                val dist = if (params.isNotEmpty() && params[0] > 0) params[0] else 1
                onCommand(TerminalCommand.MoveCursorRelative(-dist, 0))
            }
            'J' -> {
                // Erase in Display
                val mode = if (params.isNotEmpty()) params[0] else 0
                onCommand(TerminalCommand.ClearScreen(mode))
            }
            'K' -> {
                // Erase in Line
                val mode = if (params.isNotEmpty()) params[0] else 0
                onCommand(TerminalCommand.ClearLine(mode))
            }
            'd' -> {
                // Line Position Absolute
                val row = if (params.isNotEmpty()) params[0] else 1
                onCommand(TerminalCommand.MoveCursor(stateProvider.cursorX, row - 1))
            }
            'G' -> {
                // Cursor Horizontal Absolute
                val col = if (params.isNotEmpty()) params[0] else 1
                onCommand(TerminalCommand.MoveCursor(col - 1, stateProvider.cursorY))
            }
            'P' -> {
                // Delete Character (DCH)
                val count = if (params.isNotEmpty() && params[0] > 0) params[0] else 1
                onCommand(TerminalCommand.DeleteCharacters(count))
            }
            'X' -> {
                // Erase Character (ECH)
                val count = if (params.isNotEmpty() && params[0] > 0) params[0] else 1
                onCommand(TerminalCommand.EraseCharacters(count))
            }
            '@' -> {
                // Insert Character (ICH)
                val count = if (params.isNotEmpty() && params[0] > 0) params[0] else 1
                onCommand(TerminalCommand.InsertSpaces(count))
            }
            'L' -> {
                // Insert Line (IL)
                val count = if (params.isNotEmpty() && params[0] > 0) params[0] else 1
                onCommand(TerminalCommand.InsertLines(count))
            }
            'M' -> {
                // Delete Line (DL)
                val count = if (params.isNotEmpty() && params[0] > 0) params[0] else 1
                onCommand(TerminalCommand.DeleteLines(count))
            }
            'E' -> {
                // Cursor Next Line (CNL)
                val dist = if (params.isNotEmpty() && params[0] > 0) params[0] else 1
                onCommand(TerminalCommand.MoveCursor(0, stateProvider.cursorY + dist))
            }
            'F' -> {
                // Cursor Previous Line (CPL)
                val dist = if (params.isNotEmpty() && params[0] > 0) params[0] else 1
                onCommand(TerminalCommand.MoveCursor(0, stateProvider.cursorY - dist))
            }
            'S' -> {
                // Scroll Up (SU)
                val count = if (params.isNotEmpty() && params[0] > 0) params[0] else 1
                for (i in 0 until count) onCommand(TerminalCommand.ScrollUp)
            }
            'T' -> {
                // Scroll Down (SD)
                val count = if (params.isNotEmpty() && params[0] > 0) params[0] else 1
                for (i in 0 until count) onCommand(TerminalCommand.ScrollDown)
            }
            'r' -> {
                // Set Scrolling Region (DECSTBM)
                val top = if (params.isNotEmpty() && params[0] > 0) params[0] else 1
                val bottom = if (params.size > 1 && params[1] > 0) params[1] else stateProvider.rows
                onCommand(TerminalCommand.SetScrollingMargins(top - 1, bottom - 1))
            }
            's' -> {
                // Save Cursor Position
                onCommand(TerminalCommand.SaveCursor)
            }
            'u' -> {
                // Restore Cursor Position
                onCommand(TerminalCommand.RestoreCursor)
            }
            'Z' -> {
                // Cursor Backward Tabulation (CBT)
                val count = if (params.isNotEmpty() && params[0] > 0) params[0] else 1
                onCommand(TerminalCommand.BackTab(count))
            }
        }
    }

    private fun getAnsiColor(index: Int): Color {
        return when (index) {
            0 -> Color(0, 0, 0) // Black
            1 -> Color(205, 0, 0) // Red
            2 -> Color(0, 205, 0) // Green
            3 -> Color(205, 205, 0) // Yellow
            4 -> Color(0, 0, 238) // Blue
            5 -> Color(205, 0, 205) // Magenta
            6 -> Color(0, 205, 205) // Cyan
            7 -> Color(229, 229, 229) // White
            8 -> Color(127, 127, 127) // Bright Black (Gray)
            9 -> Color(255, 0, 0) // Bright Red
            10 -> Color(0, 255, 0) // Bright Green
            11 -> Color(255, 255, 0) // Bright Yellow
            12 -> Color(92, 92, 255) // Bright Blue
            13 -> Color(255, 0, 255) // Bright Magenta
            14 -> Color(0, 255, 255) // Bright Cyan
            15 -> Color(255, 255, 255) // Bright White
            else -> Color.WHITE
        }
    }

    private fun get256Color(i: Int): Color {
        if (i in 0..15) {
            return getAnsiColor(i)
        }
        if (i in 16..231) {
            val r = (i - 16) / 36
            val g = ((i - 16) / 6) % 6
            val b = (i - 16) % 6
            val red = if (r == 0) 0 else 55 + r * 40
            val green = if (g == 0) 0 else 55 + g * 40
            val blue = if (b == 0) 0 else 55 + b * 40
            return Color(red, green, blue)
        }
        if (i in 232..255) {
            val gray = 8 + (i - 232) * 10
            return Color(gray, gray, gray)
        }
        return Color.WHITE
    }
}
