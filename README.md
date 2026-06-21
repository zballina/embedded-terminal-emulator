# Embedded Terminal Emulator for PyCharm / IntelliJ

A professional, fast, and ultra-low resource terminal emulator developed 100% in native Kotlin and Swing for the IntelliJ platform.

This plugin is designed as a direct and efficient replacement for integrated web-based terminals (based on xterm.js / JCEF) and the official terminal, maximizing RAM usage efficiency and offering frictionless keyboard integration.

---

## 🚀 Key Features

1. **Pure Swing Native Architecture**: Completely removes dependencies on Chromium (JCEF) and heavy web-based JS rendering engines. Instead, it utilizes a custom AWT/Java2D renderer with a `GlyphAtlas` (texture cache for font characters) that copies glyph images directly to the screen (Blitting), consuming **less than 10 MB of RAM** (a 98% reduction compared to JCEF).
2. **Hot Editor Sync**: Instantly synchronizes the terminal's typography (font, size, code ligatures) and colors (background, foreground, ANSI colors) in response to IDE theme changes (Darcula, IntelliJ Light, etc.) without requiring terminal restarts.
3. **Smart Virtual Environment Activation**: Instantly detects Python virtual environment folders (`.venv`, `venv`, `env`) linked to the project when opening any tab. It prepares and prepends the executable path to `PATH` and declares the `VIRTUAL_ENV` variable transparently.
4. **Priority Keyboard Shortcut Interception**: Resolves the historic issue where the IDE captures critical shell key combinations. Shortcuts like `Ctrl + R` (reverse history search), `Ctrl + C` (interrupt), `Ctrl + Z` (suspend), and word navigation (`Opt/Ctrl + Arrows`) are delivered directly to the active shell.
5. **Smart Paste & Drag and Drop for AI Agents (PNG, PDF, Text Logs)**: Copy screenshots (images), documents (PDFs), or massive text files/logs and paste them directly into the terminal with `Cmd+Shift+V` (or drag and drop them). The plugin handles file generation, folder isolation, and dynamic symbolic/hard links for heavy resources. Clickable paths open directly in the PyCharm editor.
6. **Instant File & Directory Path Drag & Drop (with Auto-Escaping)**: Drag any file or folder from the PyCharm project tree, Finder, or system explorer and drop it (or copy and paste it) directly into the terminal prompt. The plugin automatically detects your shell (zsh, bash, fish, cmd, powershell) and inserts the absolute path with correct character escaping, saving time and preventing path syntax errors.
7. **ANSI Inverse Video Support (SGR 7 & 27)**: Native support for inverse/reverse video mode (SGR 7 to enable, SGR 27 to disable). This ensures that highlighted selections inside interactive CLI prompts (such as `agy` artifact option selectors) are rendered with proper background/foreground color swapping.
8. **Dumb Mode Responsive Loading**: Blocking shell process startup and PTY reading loops are shifted to asynchronous daemon background threads, keeping tab creation, close actions, and UI interaction fully responsive even during PyCharm project indexing.
9. **High-Precision Wobble-Free Scroll & Selection**: Uses precise mouse/trackpad wheel accumulation to eliminate scroll jitter and wobbles when scrolling slowly. It also intercepts double-clicks to dynamically expand and select word tokens, paths, variables, and links.
10. **Bracketed Paste Mode Support (ANSI 2004)**: Securely wraps pasted text in special sequences to prevent the shell prompt from executing commands mid-line or causing visual layout corruption.
11. **Stable Logical Scrolling**: Features a synchronized side scrollbar that can be shown or hidden through the plugin's configuration settings.
12. **Lazy Resource Allocation**: Starts PTY processes and allocates OS threads only when the corresponding terminal tab actually becomes visible to the user.
13. **Shift + Enter for Multiline Input**: Automatically translates `Shift + Enter` into the backslash + enter sequence (`\\\r`) to seamlessly write multiline prompts in CLI chat interfaces (like agy) and handle Zsh/Bash line continuations.

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
