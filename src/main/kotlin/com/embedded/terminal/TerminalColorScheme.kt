package com.embedded.terminal

import java.awt.Color

data class TerminalColorScheme(
    val name: String,
    val foregroundHex: String,
    val backgroundHex: String,
    val selectionHex: String,
    val cursorHex: String,
    val ansiHexColors: List<String>
) {
    val foregroundColor: Color get() = parseHexColor(foregroundHex)
    val backgroundColor: Color get() = parseHexColor(backgroundHex)
    val selectionColor: Color get() = parseHexColor(selectionHex)
    val cursorColor: Color get() = parseHexColor(cursorHex)
    val ansiColors: List<Color> get() = ansiHexColors.map { parseHexColor(it) }

    companion object {
        fun parseHexColor(hex: String): Color {
            return try {
                val cleanHex = if (hex.startsWith("#")) hex.substring(1) else hex
                if (cleanHex.length == 8) {
                    val r = cleanHex.substring(0, 2).toInt(16)
                    val g = cleanHex.substring(2, 4).toInt(16)
                    val b = cleanHex.substring(4, 6).toInt(16)
                    val a = cleanHex.substring(6, 8).toInt(16)
                    Color(r, g, b, a)
                } else {
                    val r = cleanHex.substring(0, 2).toInt(16)
                    val g = cleanHex.substring(2, 4).toInt(16)
                    val b = cleanHex.substring(4, 6).toInt(16)
                    Color(r, g, b)
                }
            } catch (e: Exception) {
                Color.WHITE
            }
        }

        fun toHex(color: Color): String {
            val r = String.format("%02X", color.red)
            val g = String.format("%02X", color.green)
            val b = String.format("%02X", color.blue)
            if (color.alpha < 255) {
                val a = String.format("%02X", color.alpha)
                return "#$r$g$b$a"
            }
            return "#$r$g$b"
        }

        val Dracula = TerminalColorScheme(
            name = "Dracula",
            foregroundHex = "#F8F8F2",
            backgroundHex = "#282A36",
            selectionHex = "#44475A",
            cursorHex = "#F8F8F0",
            ansiHexColors = listOf(
                "#21222C", "#FF5555", "#50FA7B", "#F1FA8C", "#BD93F9", "#FF79C6", "#8BE9FD", "#F8F8F2",
                "#6272A4", "#FF6E6E", "#69FF94", "#FFFFA5", "#D6ACFF", "#FF92DF", "#A4FFFF", "#FFFFFF"
            )
        )

        val SolarizedDark = TerminalColorScheme(
            name = "Solarized Dark",
            foregroundHex = "#839496",
            backgroundHex = "#002B36",
            selectionHex = "#073642",
            cursorHex = "#586E75",
            ansiHexColors = listOf(
                "#073642", "#DC322F", "#859900", "#B58900", "#268BD2", "#D33682", "#2AA198", "#EEE8D5",
                "#002B36", "#CB4B16", "#586E75", "#657B83", "#839496", "#6C71C4", "#93A1A1", "#FDF6E3"
            )
        )

        val SolarizedLight = TerminalColorScheme(
            name = "Solarized Light",
            foregroundHex = "#657B83",
            backgroundHex = "#FDF6E3",
            selectionHex = "#EEE8D5",
            cursorHex = "#93A1A1",
            ansiHexColors = listOf(
                "#EEE8D5", "#DC322F", "#859900", "#B58900", "#268BD2", "#D33682", "#2AA198", "#073642",
                "#FDF6E3", "#CB4B16", "#93A1A1", "#839496", "#657B83", "#6C71C4", "#586E75", "#002B36"
            )
        )

        val Monokai = TerminalColorScheme(
            name = "Monokai",
            foregroundHex = "#F8F8F2",
            backgroundHex = "#272822",
            selectionHex = "#49483E",
            cursorHex = "#F8F8F0",
            ansiHexColors = listOf(
                "#272822", "#F92672", "#A6E22E", "#F4BF75", "#66D9EF", "#AE81FF", "#A1EFE4", "#F8F8F2",
                "#75715E", "#F92672", "#A6E22E", "#E6DB74", "#66D9EF", "#AE81FF", "#A1EFE4", "#F9F8F5"
            )
        )

        val OneDark = TerminalColorScheme(
            name = "One Dark",
            foregroundHex = "#ABB2BF",
            backgroundHex = "#282C34",
            selectionHex = "#3E4452",
            cursorHex = "#528BFF",
            ansiHexColors = listOf(
                "#1E2127", "#E06C75", "#98C379", "#D19A66", "#61AFEF", "#C678DD", "#56B6C2", "#ABB2BF",
                "#5C6370", "#E06C75", "#98C379", "#D19A66", "#61AFEF", "#C678DD", "#56B6C2", "#FFFFFF"
            )
        )

        val Konsole = TerminalColorScheme(
            name = "Konsole Default",
            foregroundHex = "#FCFCFC",
            backgroundHex = "#1B1E20",
            selectionHex = "#31363B",
            cursorHex = "#FCFCFC",
            ansiHexColors = listOf(
                "#232629", "#ED1515", "#11D116", "#F67400", "#1D99F3", "#9B59B6", "#1ABC9C", "#EFF0F1",
                "#7F8C8D", "#C0392B", "#1CDC9A", "#FDBC4B", "#3DAEE9", "#8E44AD", "#16A085", "#FFFFFF"
            )
        )

        val PRESETS = listOf(Dracula, SolarizedDark, SolarizedLight, Monokai, OneDark, Konsole)

        fun findByName(name: String): TerminalColorScheme? {
            return PRESETS.firstOrNull { it.name.equals(name, ignoreCase = true) }
        }
    }
}

class IndexedAnsiColor(val index: Int, r: Int, g: Int, b: Int) : Color(r, g, b)

