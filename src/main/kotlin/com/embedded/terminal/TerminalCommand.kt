package com.embedded.terminal

import java.awt.Color

sealed interface TerminalCommand {
    data class WriteText(val text: String) : TerminalCommand
    object NewLine : TerminalCommand
    object CarriageReturn : TerminalCommand
    object Backspace : TerminalCommand
    object Tab : TerminalCommand
    data class BackTab(val count: Int) : TerminalCommand
    object ScrollUp : TerminalCommand
    object ScrollDown : TerminalCommand
    data class ScrollUpRegion(val top: Int, val bottom: Int) : TerminalCommand
    data class ScrollDownRegion(val top: Int, val bottom: Int) : TerminalCommand
    data class SetScrollingMargins(val top: Int, val bottom: Int) : TerminalCommand
    data class ClearScreen(val mode: Int) : TerminalCommand
    data class ClearLine(val mode: Int) : TerminalCommand
    data class MoveCursor(val x: Int, val y: Int) : TerminalCommand
    data class MoveCursorRelative(val dx: Int, val dy: Int) : TerminalCommand
    data class Resize(val cols: Int, val rows: Int) : TerminalCommand
    data class DeleteCharacters(val count: Int) : TerminalCommand
    data class EraseCharacters(val count: Int) : TerminalCommand
    data class InsertSpaces(val count: Int) : TerminalCommand
    data class InsertLines(val count: Int) : TerminalCommand
    data class DeleteLines(val count: Int) : TerminalCommand
    object ReverseIndex : TerminalCommand
    object SaveCursor : TerminalCommand
    object RestoreCursor : TerminalCommand
    object ResetStyle : TerminalCommand
    data class SetCursorVisible(val visible: Boolean) : TerminalCommand
    data class SetBracketedPasteMode(val enabled: Boolean) : TerminalCommand
    data class SetFgColor(val color: Color?) : TerminalCommand
    data class SetBgColor(val color: Color?) : TerminalCommand
    data class SetBold(val enabled: Boolean) : TerminalCommand
    data class SetItalic(val enabled: Boolean) : TerminalCommand
    data class SetUnderline(val enabled: Boolean) : TerminalCommand
    
    // Iteration 3 Advanced VT commands
    data class UseAlternateBuffer(val useAlt: Boolean) : TerminalCommand
    data class SetHyperlinkUrl(val url: String?) : TerminalCommand
    data class CopyToClipboard(val text: String) : TerminalCommand
    data class SetMouseTrackingMode(val mode: Int, val enabled: Boolean) : TerminalCommand
    data class SetMouseSgrEncoding(val enabled: Boolean) : TerminalCommand
}
