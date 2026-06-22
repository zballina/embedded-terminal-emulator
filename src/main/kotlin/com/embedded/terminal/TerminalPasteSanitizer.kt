package com.embedded.terminal

object TerminalPasteSanitizer {
    // Regex matching spaces/tabs, a backslash, spaces/tabs, a newline (Unix or Windows), and any leading spaces/tabs on the next line.
    private val regex = Regex("""[ \t]*\\[ \t]*\r?\n[ \t]*""")

    fun sanitize(text: String, forceMac: Boolean? = null): String {
        val isMac = forceMac ?: System.getProperty("os.name").lowercase().contains("mac")
        if (!isMac) {
            return text
        }
        return text.replace(regex, " ")
    }
}
