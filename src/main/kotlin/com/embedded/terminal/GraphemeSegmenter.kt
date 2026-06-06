package com.embedded.terminal

import com.ibm.icu.text.BreakIterator

enum class CellWidth {
    SINGLE,
    DOUBLE
}

interface GraphemeSegmenter {
    fun nextClusters(text: String): List<String>
    fun getCellWidth(grapheme: String): CellWidth
}

class ICU4JSegmenter : GraphemeSegmenter {
    override fun nextClusters(text: String): List<String> {
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text)
        val clusters = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            clusters.add(text.substring(start, end))
            start = end
            end = iterator.next()
        }
        return clusters
    }

    override fun getCellWidth(grapheme: String): CellWidth {
        if (grapheme.isEmpty()) return CellWidth.SINGLE
        val firstCodePoint = grapheme.codePointAt(0)
        // macOS compat: these Dingbats have EAW=W in ICU Unicode-15 data but
        // macOS wcwidth() returns 1 for them.  Shell cursor math uses wcwidth(),
        // so we must agree with the OS, not the Unicode standard.
        if (firstCodePoint in macosDingbatSingleOverride) return CellWidth.SINGLE
        return try {
            val eaw = com.ibm.icu.lang.UCharacter.getIntPropertyValue(firstCodePoint, com.ibm.icu.lang.UProperty.EAST_ASIAN_WIDTH)
            if (eaw == com.ibm.icu.lang.UCharacter.EastAsianWidth.WIDE ||
                eaw == com.ibm.icu.lang.UCharacter.EastAsianWidth.FULLWIDTH) {
                CellWidth.DOUBLE
            } else if (isEmojiWide(firstCodePoint)) {
                CellWidth.DOUBLE
            } else {
                CellWidth.SINGLE
            }
        } catch (e: Throwable) {
            // Fallback for CJK and Emoji range
            if ((firstCodePoint in 0x3000..0x9FFF) || (firstCodePoint in 0xFF00..0xFFEF) || isEmojiWide(firstCodePoint)) {
                CellWidth.DOUBLE
            } else {
                CellWidth.SINGLE
            }
        }
    }

    private fun isEmojiWide(cp: Int): Boolean {
        // Standard emoji ranges that are always rendered as double-width
        if (cp in 0x1F300..0x1F9FF) return true
        if (cp in 0x1F600..0x1F64F) return true
        if (cp in 0x1F680..0x1F6FF) return true
        // Additional codepoints confirmed as width=2 by macOS wcwidth() but NOT covered
        // by the ICU4J EastAsianWidth=W ranges — found via C-level wcwidth() sweep.
        if (cp == 0x25FD || cp == 0x25FE) return true  // WHITE/BLACK MEDIUM SMALL SQUARE
        if (cp == 0x2B1B || cp == 0x2B1C) return true  // BLACK/WHITE LARGE SQUARE (git status)
        if (cp == 0x2B50) return true                   // WHITE MEDIUM STAR
        if (cp == 0x2B55) return true                   // HEAVY LARGE CIRCLE
        return false
    }

    /**
     * Codepoints in the Dingbats block (U+2700–U+27BF) where ICU4J Unicode-15
     * data assigns EAW=W (→ DOUBLE), but macOS libc wcwidth() returns 1.
     * We MUST follow macOS because bash/readline use macOS wcwidth() to compute
     * cursor positions. Deviating causes a 1-column drift per occurrence.
     */
    private val macosDingbatSingleOverride = setOf(
        0x2702, // ✂ SCISSORS
        0x2708, // ✈ AIRPLANE
        0x2709, // ✉ ENVELOPE
        0x270C, // ✌ VICTORY HAND
        0x270D, // ✍ WRITING HAND
        0x270F, // ✏ PENCIL
        0x2712, // ✒ BLACK NIB
        0x2714, // ✔ HEAVY CHECK MARK
        0x2716, // ✖ HEAVY MULTIPLICATION X
        0x271D, // ✝ LATIN CROSS
        0x2721, // ✡ STAR OF DAVID
        0x2728, // ✨ SPARKLES
        0x2733, // ✳ EIGHT SPOKED ASTERISK
        0x2734, // ✴ EIGHT POINTED STAR
        0x2744, // ❄ SNOWFLAKE
        0x2747, // ❇ SPARKLE
        0x2763, // ❣ HEAVY HEART EXCLAMATION
        0x2764, // ❤ HEAVY BLACK HEART
        0x27A1  // ➡ BLACK RIGHTWARDS ARROW
    )
}

class JdkBreakIteratorSegmenter : GraphemeSegmenter {
    override fun nextClusters(text: String): List<String> {
        val iterator = java.text.BreakIterator.getCharacterInstance()
        iterator.setText(text)
        val clusters = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != java.text.BreakIterator.DONE) {
            clusters.add(text.substring(start, end))
            start = end
            end = iterator.next()
        }
        return clusters
    }

    override fun getCellWidth(grapheme: String): CellWidth {
        if (grapheme.isEmpty()) return CellWidth.SINGLE
        val firstCodePoint = grapheme.codePointAt(0)
        val isWide = (firstCodePoint in 0x1100..0x115F) || 
                (firstCodePoint in 0x2E80..0x303F) || 
                (firstCodePoint in 0x3040..0x309F) || 
                (firstCodePoint in 0x30A0..0x30FF) || 
                (firstCodePoint in 0x31F0..0x31FF) || 
                (firstCodePoint in 0x3200..0x9FFF) || 
                (firstCodePoint in 0xF900..0xFAFF) || 
                (firstCodePoint in 0xFF00..0xFFEF) || 
                (firstCodePoint in 0x1F300..0x1F9FF) ||
                (firstCodePoint in 0x1F600..0x1F6FF)
        return if (isWide) CellWidth.DOUBLE else CellWidth.SINGLE
    }
}

object GraphemeSegmenterFactory {
    val instance: GraphemeSegmenter by lazy {
        try {
            Class.forName("com.ibm.icu.text.BreakIterator")
            ICU4JSegmenter()
        } catch (e: Throwable) {
            JdkBreakIteratorSegmenter()
        }
    }
}
