package com.embedded.terminal

import java.util.concurrent.Executors
import java.util.concurrent.Future

class TerminalStateEngine(val buffer: TerminalBuffer) {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TerminalStateEngineThread").apply { isDaemon = true }
    }

    private val parser = AnsiEscapeParser(buffer) { command ->
        executeCommand(command)
    }

    // Thread-safe dispatch of a raw character stream
    fun feedChars(chars: CharArray, length: Int): Future<*> {
        val copy = chars.copyOf(length)
        return executor.submit {
            for (c in copy) {
                parser.parseChar(c)
            }
            parser.flush()
        }
    }

    // Thread-safe dispatch of individual commands
    fun dispatchCommand(command: TerminalCommand): Future<*> {
        return executor.submit {
            executeCommand(command)
        }
    }

    // Must only be called on the dedicated executor thread.
    // NOTE: No outer synchronized(buffer) here — the executor is single-threaded so there
    // is no concurrent access from this side. Each TerminalBuffer method already holds
    // synchronized(this) internally for the minimum time needed. The old outer lock was
    // causing the renderer (which also holds synchronized(buffer) during paint) to starve
    // the state engine during large paste operations, making the terminal unresponsive.
    private fun executeCommand(command: TerminalCommand) {
        when (command) {
            is TerminalCommand.WriteText -> {
                val clusters = GraphemeSegmenterFactory.instance.nextClusters(command.text)
                buffer.writeGraphemes(clusters)
            }
            TerminalCommand.NewLine -> buffer.newLine()
            TerminalCommand.CarriageReturn -> buffer.carriageReturn()
            TerminalCommand.Backspace -> buffer.backspace()
            TerminalCommand.Tab -> buffer.tab()
            is TerminalCommand.BackTab -> buffer.backTab(command.count)
            TerminalCommand.ScrollUp -> buffer.scrollUp()
            TerminalCommand.ScrollDown -> buffer.scrollDown()
            is TerminalCommand.ScrollUpRegion -> buffer.scrollUpRegion(command.top, command.bottom)
            is TerminalCommand.ScrollDownRegion -> buffer.scrollDownRegion(command.top, command.bottom)
            is TerminalCommand.SetScrollingMargins -> buffer.setScrollingMargins(command.top, command.bottom)
            is TerminalCommand.ClearScreen -> buffer.clearScreen(command.mode)
            is TerminalCommand.ClearLine -> buffer.clearLine(command.mode)
            is TerminalCommand.MoveCursor -> buffer.moveCursor(command.x, command.y)
            is TerminalCommand.MoveCursorRelative -> buffer.moveCursorRelative(command.dx, command.dy)
            is TerminalCommand.Resize -> buffer.resize(command.cols, command.rows)
            is TerminalCommand.DeleteCharacters -> buffer.deleteCharacters(command.count)
            is TerminalCommand.EraseCharacters -> buffer.eraseCharacters(command.count)
            is TerminalCommand.InsertSpaces -> buffer.insertSpaces(command.count)
            is TerminalCommand.InsertLines -> buffer.insertLines(command.count)
            is TerminalCommand.DeleteLines -> buffer.deleteLines(command.count)
            TerminalCommand.ReverseIndex -> buffer.reverseIndex()
            TerminalCommand.SaveCursor -> buffer.saveCursor()
            TerminalCommand.RestoreCursor -> buffer.restoreCursor()
            TerminalCommand.ResetStyle -> buffer.resetStyle()
            is TerminalCommand.SetCursorVisible -> buffer.isCursorVisible = command.visible
            is TerminalCommand.SetBracketedPasteMode -> buffer.isBracketedPasteMode = command.enabled
            is TerminalCommand.SetFgColor -> buffer.activeFgColor = command.color
            is TerminalCommand.SetBgColor -> buffer.activeBgColor = command.color
            is TerminalCommand.SetBold -> buffer.activeBold = command.enabled
            is TerminalCommand.SetItalic -> buffer.activeItalic = command.enabled
            is TerminalCommand.SetUnderline -> buffer.activeUnderline = command.enabled
            is TerminalCommand.SetInverse -> buffer.activeInverse = command.enabled

            // Iteration 3
            is TerminalCommand.UseAlternateBuffer -> buffer.useAlternateBuffer(command.useAlt)
            is TerminalCommand.SetHyperlinkUrl -> buffer.activeHyperlinkUrl = command.url
            is TerminalCommand.CopyToClipboard -> {
                try {
                    val selection = java.awt.datatransfer.StringSelection(command.text)
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            is TerminalCommand.SetMouseTrackingMode -> buffer.mouseTrackingMode = if (command.enabled) command.mode else 0
            is TerminalCommand.SetMouseSgrEncoding -> buffer.isMouseSgrEnabled = command.enabled
        }
    }

    fun shutdown() {
        executor.shutdown()
    }
}
