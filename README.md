# Embedded Terminal Emulator for PyCharm / IntelliJ

A professional, fast, and ultra-low resource terminal emulator developed 100% in native Kotlin and Swing for the IntelliJ platform.

This plugin is designed as a direct and efficient replacement for integrated web-based terminals (based on xterm.js / JCEF) and the official terminal, maximizing RAM usage efficiency and offering frictionless keyboard integration.

---

## 🚀 Key Features

1. **Pure Swing Native Architecture**: Completely removes dependencies on Chromium (JCEF) and heavy web-based JS rendering engines. Instead, it utilizes a custom AWT/Java2D renderer with a `GlyphAtlas` (texture cache for font characters) that copies glyph images directly to the screen (Blitting), consuming **less than 10 MB of RAM** (a 98% reduction compared to JCEF).
2. **Hot Editor Sync**: Instantly synchronizes the terminal's typography (font, size, code ligatures) and colors (background, foreground, ANSI colors) in response to IDE theme changes (Darcula, IntelliJ Light, etc.) without requiring terminal restarts.
3. **Smart Virtual Environment Activation**: Instantly detects Python virtual environment folders (`.venv`, `venv`, `env`) linked to the project when opening any tab. It prepares and prepends the executable path to `PATH` and declares the `VIRTUAL_ENV` variable transparently.
4. **Priority Keyboard Shortcut Interception**: Resolves the historic issue where the IDE captures critical shell key combinations. Shortcuts like `Ctrl + R` (reverse history search), `Ctrl + C` (interrupt), `Ctrl + Z` (suspend), and word navigation (`Opt/Ctrl + Arrows`) are delivered directly to the active shell.
5. **Bracketed Paste Mode Support (ANSI 2004)**: Securely wraps pasted text in special sequences to prevent the shell prompt from executing commands mid-line or causing visual layout corruption.
6. **Stable Logical Scrolling**: Features a synchronized side scrollbar that can be shown or hidden through the plugin's configuration settings.
7. **Lazy Resource Allocation**: Starts PTY processes and allocates OS threads only when the corresponding terminal tab actually becomes visible to the user.

---

## 📊 Comparison: Embedded Terminal vs. Official JetBrains Terminal

| Feature | **Embedded Terminal Emulator (This Plugin)** | **Official JetBrains Terminal** |
| :--- | :--- | :--- |
| **RAM Consumption** | **~5-10 MB** (Native AWT/Swing and glyph cache). | **~150-300 MB** (Uses heavy engines and Chromium in recent versions). |
| **Python Virtual Environments** | **Automatic & Instant**. Detects and injects `.venv`/`venv`/`env` into `PATH` transparently on tab creation. | **Variable**. Relies on external shell scripts and often fails in complex project structures. |
| **Shell Shortcuts (Ctrl+R / Ctrl+C / Ctrl+Z)** | **Full Compatibility**. Intercepts and consumes events with priority when focused, keeping ToolWindow shortcuts intact. | **Limited**. PyCharm intercepts key combinations for its internal menus, hindering the terminal workflow. |
| **Multiline Text Pasting** | **Secure**. Full support for *Bracketed Paste Mode* (ANSI 2004), preventing unintended execution or desynced prompts. | **Variable**. On many platforms, text is pasted character-by-character quickly, corrupting Zsh/Oh-My-Zsh prompts. |
| **Visual Aesthetics** | **Customizable**. Allows enabling/disabling the vertical scrollbar for a clean, integrated IDE layout. | **Rigid**. The scrollbar is permanently visible and unchangeable. |
| **Resource Loading** | **Lazy**. PTY read threads and initialization are deferred until the tab is visible. | **On Startup**. Heavily initializes resources during the tool window opening. |

---

## ⚙️ Configuration and Preferences

To adjust the plugin settings, navigate to:
**Settings/Preferences | Tools | Embedded Terminal**

Here you can configure:
*   **Shell Path**: Define your default shell (`/bin/zsh`, `/bin/bash`, `powershell.exe`, etc.).
*   **Show scroll bar**: Toggle the vertical scrollbar display.
*   **History Limit (Scrollback)**: Maximum number of lines kept in memory for scrollback (prevents memory leaks).
*   **Custom Typography Settings**: If you choose not to use the editor theme, you can customize the font family, text size, line height, and ligatures individually.

---

## 🔗 Repository

You can find the official source code, report issues, and contribute at:
[https://github.com/zballina/embedded-terminal-emulator](https://github.com/zballina/embedded-terminal-emulator)
