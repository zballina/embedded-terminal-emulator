package com.embedded.terminal

interface TerminalStateProvider {
    val cursorX: Int
    val cursorY: Int
    val rows: Int
    val cols: Int
}
