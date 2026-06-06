package com.embedded.terminal

import java.awt.Color

data class TerminalCell(
    var grapheme: String = " ",
    var width: CellWidth = CellWidth.SINGLE,
    var isContinuation: Boolean = false,
    var fgColor: Color? = null,
    var bgColor: Color? = null,
    var isBold: Boolean = false,
    var isItalic: Boolean = false,
    var isUnderline: Boolean = false,
    var hyperlinkUrl: String? = null
) {
    // Backwards compatibility for single-char properties
    var char: Char
        get() = if (grapheme.isNotEmpty()) grapheme[0] else ' '
        set(value) {
            grapheme = value.toString()
        }

    fun reset() {
        grapheme = " "
        width = CellWidth.SINGLE
        isContinuation = false
        fgColor = null
        bgColor = null
        isBold = false
        isItalic = false
        isUnderline = false
        hyperlinkUrl = null
    }

    fun copyFrom(other: TerminalCell) {
        this.grapheme = other.grapheme
        this.width = other.width
        this.isContinuation = other.isContinuation
        this.fgColor = other.fgColor
        this.bgColor = other.bgColor
        this.isBold = other.isBold
        this.isItalic = other.isItalic
        this.isUnderline = other.isUnderline
        this.hyperlinkUrl = other.hyperlinkUrl
    }
}
