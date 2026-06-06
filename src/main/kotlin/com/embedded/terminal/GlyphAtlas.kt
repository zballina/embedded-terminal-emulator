package com.embedded.terminal

import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage

class TexturePage(val width: Int, val height: Int, val id: Int) {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val g2 = image.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    }
    
    private var currentX = 0
    private var currentY = 0
    private var shelfHeight = 0
    
    @Volatile
    var lastUsedTime = System.currentTimeMillis()

    fun pack(w: Int, h: Int, drawBlock: (Graphics2D, Int, Int) -> Unit): Rectangle? {
        synchronized(this) {
            lastUsedTime = System.currentTimeMillis()
            if (currentX + w > width) {
                currentX = 0
                currentY += shelfHeight
                shelfHeight = 0
            }
            if (currentY + h > height) {
                return null
            }
            
            val rect = Rectangle(currentX, currentY, w, h)
            drawBlock(g2, currentX, currentY)
            
            currentX += w
            shelfHeight = maxOf(shelfHeight, h)
            
            return rect
        }
    }
    
    fun clear() {
        synchronized(this) {
            val g = image.createGraphics()
            g.composite = AlphaComposite.Clear
            g.fillRect(0, 0, width, height)
            g.dispose()
            currentX = 0
            currentY = 0
            shelfHeight = 0
        }
    }
    
    fun dispose() {
        g2.dispose()
    }
}

data class GlyphKey(
    val grapheme: String,
    val style: Int,
    val fgColor: Color
)

class GlyphAtlas(
    private val pageSize: Int = 2048,
    private val maxPages: Int = 8
) {
    private val pages = mutableListOf<TexturePage>()
    private val glyphMap = mutableMapOf<GlyphKey, GlyphLocation>()
    private var pageCounter = 0

    data class GlyphLocation(
        val page: TexturePage,
        val rect: Rectangle
    )

    fun getGlyph(
        key: GlyphKey,
        font: Font,
        charWidth: Int,
        rowHeight: Int,
        charAscent: Int,
        fontMetrics: java.awt.FontMetrics
    ): GlyphLocation {
        synchronized(this) {
            val loc = glyphMap[key]
            if (loc != null) {
                loc.page.lastUsedTime = System.currentTimeMillis()
                return loc
            }
            
            val isDouble = GraphemeSegmenterFactory.instance.getCellWidth(key.grapheme) == CellWidth.DOUBLE
            val w = charWidth * (if (isDouble) 2 else 1)
            val h = rowHeight
            
            val drawBlock = { g2: Graphics2D, x: Int, y: Int ->
                g2.font = if (key.style != Font.PLAIN) font.deriveFont(key.style) else font
                g2.color = key.fgColor
                
                val oldComposite = g2.composite
                g2.composite = AlphaComposite.Clear
                g2.fillRect(x, y, w, h)
                g2.composite = oldComposite
                
                val textY = y + charAscent + (rowHeight - fontMetrics.height) / 2
                g2.drawString(key.grapheme, x, textY)
            }
            
            var rect: Rectangle? = null
            var page: TexturePage? = null
            for (p in pages) {
                rect = p.pack(w, h, drawBlock)
                if (rect != null) {
                    page = p
                    break
                }
            }
            
            if (rect == null) {
                if (pages.size >= maxPages) {
                    val lruPage = pages.minByOrNull { it.lastUsedTime }
                    if (lruPage != null) {
                        glyphMap.entries.removeIf { it.value.page == lruPage }
                        lruPage.clear()
                        page = lruPage
                        rect = lruPage.pack(w, h, drawBlock)
                    }
                }
                
                if (rect == null) {
                    val newPage = TexturePage(pageSize, pageSize, pageCounter++)
                    pages.add(newPage)
                    page = newPage
                    rect = newPage.pack(w, h, drawBlock)
                }
            }
            
            val location = GlyphLocation(page!!, rect!!)
            glyphMap[key] = location
            return location
        }
    }

    fun clear() {
        synchronized(this) {
            for (page in pages) {
                page.clear()
                page.dispose()
            }
            pages.clear()
            glyphMap.clear()
            pageCounter = 0
        }
    }
}
