package com.embedded.terminal

object TerminalBundle {
    private val locale = java.util.Locale.getDefault().language
    private val isEs = locale == "es"

    fun message(key: String): String {
        return if (isEs) es[key] ?: en[key] ?: key else en[key] ?: key
    }

    private val en = mapOf(
        "new.tab" to "New Tab",
        "new.tab.desc" to "Opens a new terminal session",
        "close.tab" to "Close Tab",
        "close.tab.desc" to "Closes the active terminal tab",
        "sync.font" to "Sync font with the editor",
        "enable.ligatures" to "Enable typographic ligatures (if available)",
        "auto.close" to "Close tab automatically when shell process exits",
        "show.scrollbar" to "Show scrollbar",
        "foreground" to "Foreground:",
        "background" to "Background:",
        "selection" to "Selection:",
        "cursor" to "Cursor:",
        "shell.path" to "Shell path (leave empty to use default):",
        "custom.font" to "Custom font family (e.g. JetBrains Mono, Fira Code):",
        "font.size" to "Custom font size:",
        "line.height" to "Custom line height (e.g. 1.0):",
        "history.limit" to "History line limit (e.g. 1000):",
        "color.scheme" to "Color Scheme:",
        "bg.opacity" to "Background opacity (Konsole style):",
        "ansi.palette" to "16-color ANSI palette (Custom):",
        "close.confirm.title" to "Close Terminal Tab?",
        "close.confirm.msg" to "An active process is running in this tab. Closing it will terminate the process. Do you want to proceed?"
    )

    private val es = mapOf(
        "new.tab" to "Nueva pestaña",
        "new.tab.desc" to "Abre una nueva sesión de terminal",
        "close.tab" to "Cerrar pestaña",
        "close.tab.desc" to "Cierra la pestaña de terminal activa",
        "sync.font" to "Sincronizar fuente con el editor",
        "enable.ligatures" to "Habilitar ligaduras tipográficas (si están disponibles)",
        "auto.close" to "Cerrar pestaña automáticamente al salir del proceso del shell",
        "show.scrollbar" to "Mostrar barra de desplazamiento (Scrollbar)",
        "foreground" to "Texto (Foreground):",
        "background" to "Fondo (Background):",
        "selection" to "Selección (Selection):",
        "cursor" to "Cursor:",
        "shell.path" to "Ruta del Shell (dejar vacío para usar el predeterminado):",
        "custom.font" to "Tipografía personalizada (ej. JetBrains Mono, Fira Code):",
        "font.size" to "Tamaño de fuente personalizado:",
        "line.height" to "Altura de línea personalizada (ej. 1.0):",
        "history.limit" to "Límite de historial de líneas (ej. 1000):",
        "color.scheme" to "Esquema de Colores:",
        "bg.opacity" to "Opacidad del fondo (Konsole style):",
        "ansi.palette" to "Paleta ANSI de 16 colores (Custom):",
        "close.confirm.title" to "¿Cerrar pestaña de terminal?",
        "close.confirm.msg" to "Hay un proceso activo ejecutándose en esta pestaña. Cerrarla terminará el proceso de forma abrupta. ¿Deseas continuar?"
    )
}
