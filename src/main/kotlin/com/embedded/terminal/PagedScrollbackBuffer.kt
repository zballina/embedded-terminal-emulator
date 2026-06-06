package com.embedded.terminal

import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class PagedScrollbackBuffer(
    var cols: Int,
    var historyLimit: Int = 1000
) {
    private val pageSize = 10000
    val pages = mutableListOf<ScrollbackPage>()
    private var totalLinesCount = 0

    // Temporary directory for paging to disk
    private val tempDir: File by lazy {
        File(System.getProperty("java.io.tmpdir"), "terminal-scrollback-" + System.nanoTime()).apply {
            mkdirs()
            deleteOnExit()
        }
    }

    inner class ScrollbackPage(val id: Int) {
        var inMemoryLines: ArrayList<Array<TerminalCell>>? = ArrayList(pageSize)
        private var diskFile: File? = null

        val size: Int
            get() = inMemoryLines?.size ?: pageSize

        fun append(line: Array<TerminalCell>) {
            val mem = inMemoryLines ?: throw IllegalStateException("Page already serialized to disk")
            // Make defensive copy of cells
            mem.add(Array(cols) { x -> TerminalCell().apply { copyFrom(line[x]) } })
            totalLinesCount++
            
            if (mem.size >= pageSize) {
                serializeToDisk()
            }
        }

        fun getLine(index: Int): Array<TerminalCell> {
            val mem = inMemoryLines
            if (mem != null) {
                return mem[index]
            }
            // Load from disk
            val file = diskFile ?: throw IllegalStateException("Page neither in memory nor disk")
            try {
                file.inputStream().buffered().use { fis ->
                    ObjectInputStream(fis).use { ois ->
                        val size = ois.readInt()
                        val lineSize = ois.readInt()
                        for (i in 0 until size) {
                            val currentLineSize = if (i == 0) lineSize else ois.readInt()
                            val line = Array(currentLineSize) {
                                val grapheme = ois.readUTF()
                                val widthOrdinal = ois.readInt()
                                val isContinuation = ois.readBoolean()
                                val fgRgb = ois.readInt()
                                val bgRgb = ois.readInt()
                                val bold = ois.readBoolean()
                                val italic = ois.readBoolean()
                                val underline = ois.readBoolean()
                                val linkStr = ois.readUTF()
                                TerminalCell(
                                    grapheme = grapheme,
                                    width = CellWidth.values()[widthOrdinal],
                                    isContinuation = isContinuation,
                                    fgColor = if (fgRgb == -1) null else java.awt.Color(fgRgb),
                                    bgColor = if (bgRgb == -1) null else java.awt.Color(bgRgb),
                                    isBold = bold,
                                    isItalic = italic,
                                    isUnderline = underline,
                                    hyperlinkUrl = if (linkStr.isEmpty()) null else linkStr
                                )
                            }
                            if (i == index) {
                                return line
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return Array(cols) { TerminalCell() }
        }

        fun serializeToDisk() {
            val mem = inMemoryLines ?: return
            try {
                val file = File(tempDir, "page_$id.bin").apply { deleteOnExit() }
                file.outputStream().buffered().use { fos ->
                    ObjectOutputStream(fos).use { oos ->
                        oos.writeInt(mem.size)
                        for (line in mem) {
                            oos.writeInt(line.size)
                            for (cell in line) {
                                oos.writeUTF(cell.grapheme)
                                oos.writeInt(cell.width.ordinal)
                                oos.writeBoolean(cell.isContinuation)
                                oos.writeInt(cell.fgColor?.rgb ?: -1)
                                oos.writeInt(cell.bgColor?.rgb ?: -1)
                                oos.writeBoolean(cell.isBold)
                                oos.writeBoolean(cell.isItalic)
                                oos.writeBoolean(cell.isUnderline)
                                oos.writeUTF(cell.hyperlinkUrl ?: "")
                            }
                        }
                    }
                }
                diskFile = file
                inMemoryLines = null // Free memory!
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun deserializeFromDiskToMemory(): ArrayList<Array<TerminalCell>> {
            val mem = inMemoryLines
            if (mem != null) return mem
            val file = diskFile ?: return ArrayList()
            val list = ArrayList<Array<TerminalCell>>(pageSize)
            try {
                file.inputStream().buffered().use { fis ->
                    ObjectInputStream(fis).use { ois ->
                        val size = ois.readInt()
                        for (i in 0 until size) {
                            val lineSize = ois.readInt()
                            val line = Array(lineSize) {
                                val grapheme = ois.readUTF()
                                val widthOrdinal = ois.readInt()
                                val isContinuation = ois.readBoolean()
                                val fgRgb = ois.readInt()
                                val bgRgb = ois.readInt()
                                val bold = ois.readBoolean()
                                val italic = ois.readBoolean()
                                val underline = ois.readBoolean()
                                val linkStr = ois.readUTF()
                                TerminalCell(
                                    grapheme = grapheme,
                                    width = CellWidth.values()[widthOrdinal],
                                    isContinuation = isContinuation,
                                    fgColor = if (fgRgb == -1) null else java.awt.Color(fgRgb),
                                    bgColor = if (bgRgb == -1) null else java.awt.Color(bgRgb),
                                    isBold = bold,
                                    isItalic = italic,
                                    isUnderline = underline,
                                    hyperlinkUrl = if (linkStr.isEmpty()) null else linkStr
                                )
                            }
                            list.add(line)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return list
        }

        fun cleanup() {
            diskFile?.delete()
            inMemoryLines = null
        }
    }

    val size: Int
        get() = totalLinesCount

    fun appendLine(line: Array<TerminalCell>) {
        if (pages.isEmpty() || pages.last().size >= pageSize) {
            val newPage = ScrollbackPage(pages.size)
            pages.add(newPage)
        }
        pages.last().append(line)
        enforceLimit()
    }

    fun getLine(index: Int): Array<TerminalCell> {
        if (index < 0 || index >= totalLinesCount) {
            return Array(cols) { TerminalCell() }
        }
        var currentOffset = 0
        for (page in pages) {
            val pageSize = page.size
            if (index < currentOffset + pageSize) {
                return page.getLine(index - currentOffset)
            }
            currentOffset += pageSize
        }
        return Array(cols) { TerminalCell() }
    }
    operator fun get(index: Int): Array<TerminalCell> {
        return getLine(index)
    }

    private fun enforceLimit() {
        while (totalLinesCount > historyLimit) {
            val firstPage = pages.firstOrNull() ?: break
            val mem = firstPage.inMemoryLines
            if (mem != null) {
                if (mem.isNotEmpty()) {
                    mem.removeAt(0)
                    totalLinesCount--
                }
                if (mem.isEmpty()) {
                    pages.removeAt(0)
                    firstPage.cleanup()
                }
            } else {
                if (pages.size > 1 && (totalLinesCount - firstPage.size) >= historyLimit) {
                    val removed = pages.removeAt(0)
                    totalLinesCount -= removed.size
                    removed.cleanup()
                } else {
                    break
                }
            }
        }
    }

    fun clear() {
        for (page in pages) {
            page.cleanup()
        }
        pages.clear()
        totalLinesCount = 0
        try {
            tempDir.deleteRecursively()
        } catch (e: Exception) {}
    }

    fun resize(newCols: Int) {
        if (newCols == cols) return
        val allLines = ArrayList<Array<TerminalCell>>(totalLinesCount)
        for (page in pages) {
            allLines.addAll(page.deserializeFromDiskToMemory())
            page.cleanup()
        }
        pages.clear()
        totalLinesCount = 0
        cols = newCols
        for (line in allLines) {
            val resized = Array(newCols) { x ->
                if (x < line.size) line[x] else TerminalCell()
            }
            appendLine(resized)
        }
    }
}
