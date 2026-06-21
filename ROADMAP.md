# Embedded Terminal Emulator - Development Roadmap & Session Context

This file serves as a persistent context tracker for developers and AI agents working on this project across sessions. It tracks implemented features and the planned backlog.

> [!IMPORTANT]
> For versioning guidelines, release strategies, and compilation rules, see [RELEASE_POLICY.md](file:///Users/francisco.ballina/PycharmProjects/plugin-terminal/RELEASE_POLICY.md). All AI agents must read it before modifying code.

---

## 📦 Current Release Status

*   **Latest Published Version**: `1.30.0` (compiled and ready for marketplace upload).
*   **Next Active Version (Under Development)**: `1.31.0`

---

## 🛠️ Active Release Cycle (Implemented, Unreleased)

### 🔴 Version 1.31.0
*None at this time.*

---

## 🟢 Released Versions & Changelogs

### 🟢 Version 1.30.0
*   [x] **Snappy Reactive Repaint & Low-Latency Rendering**:
    *   *Details*: Redesigned [RenderScheduler.kt](file:///Users/francisco.ballina/PycharmProjects/plugin-terminal/src/main/kotlin/com/embedded/terminal/RenderScheduler.kt) to be event-driven. It triggers repaints on the EDT with 0ms delay for typing/idle periods, rate-limiting to 60 FPS only under heavy output. This eliminated the background polling thread and reduced rendering latency.
    *   *Details*: Bypassed unnecessary thread hops by calling `terminalPanel.requestRepaint()` directly from the PTY reader thread.
*   [x] **ANSI Inverse Video Support (SGR 7 & 27)**:
    *   *Details*: Added support for ANSI SGR code `7` (enable inverse/reverse video) and SGR code `27` (disable inverse/reverse video) in [AnsiEscapeParser.kt](file:///Users/francisco.ballina/PycharmProjects/plugin-terminal/src/main/kotlin/com/embedded/terminal/AnsiEscapeParser.kt).
    *   *Details*: Implemented the `isInverse` flag inside [TerminalCell.kt](file:///Users/francisco.ballina/PycharmProjects/plugin-terminal/src/main/kotlin/com/embedded/terminal/TerminalCell.kt) and resolved color swapping logic in [SwingTerminalPanel.kt](file:///Users/francisco.ballina/PycharmProjects/plugin-terminal/src/main/kotlin/com/embedded/terminal/SwingTerminalPanel.kt) to swap foreground/background colors. This fixes selection highlighting inside interactive CLI menus (such as `agy` artifact option selection screens).
*   [x] **Lazy Wrapper Startup Optimization**:
    *   *Details*: Restricted temporary startup wrapper script creation in [EmbeddedTerminalToolWindowFactory.kt](file:///Users/francisco.ballina/PycharmProjects/plugin-terminal/src/main/kotlin/com/embedded/terminal/EmbeddedTerminalToolWindowFactory.kt) to sessions where a Python virtual environment is actually detected. Standard shell sessions now launch instantly with zero disk I/O and preserve the user's native environment.
*   [x] **PTY Write Performance Optimization**:
    *   *Details*: Added batched character writing (`writeGraphemes`) in [TerminalBuffer.kt](file:///Users/francisco.ballina/PycharmProjects/plugin-terminal/src/main/kotlin/com/embedded/terminal/TerminalBuffer.kt) and [TerminalStateEngine.kt](file:///Users/francisco.ballina/PycharmProjects/plugin-terminal/src/main/kotlin/com/embedded/terminal/TerminalStateEngine.kt) to reduce synchronization locking overhead.
    *   *Details*: Disabled verbose Raw PTY input diagnostics (`System.err.println`) in [EmbeddedTerminalToolWindowFactory.kt](file:///Users/francisco.ballina/PycharmProjects/plugin-terminal/src/main/kotlin/com/embedded/terminal/EmbeddedTerminalToolWindowFactory.kt) to optimize console output throughput.
*   [x] **Navigation & Viewport Scrolling Stability**:
    *   *Details*: Added history shift tracking (`onLineAppended` callback) in [TerminalBuffer.kt](file:///Users/francisco.ballina/PycharmProjects/plugin-terminal/src/main/kotlin/com/embedded/terminal/TerminalBuffer.kt) to adjust viewport `scrollOffset` as history grows. This prevents screen jumping and locks text in place when scrolled up.
    *   *Details*: Enabled viewport clamping (`coerceIn(0, historySize)`) in `paintComponent` and mouse/keyboard event handler `getBufferCellCoords` in [SwingTerminalPanel.kt](file:///Users/francisco.ballina/PycharmProjects/plugin-terminal/src/main/kotlin/com/embedded/terminal/SwingTerminalPanel.kt) to prevent negative and out-of-bound scroll offsets.
    *   *Details*: Fixed scrollbar and mouse coordinate rendering in alternate buffer mode (full-screen apps like `less`, `vi`, `nano`), disabling scrolling and mapping correct 0-based y coordinates.
    *   *Details*: Added keyboard page navigation (`Page Up` / `Page Down` keys) to scroll the terminal viewport when alternate screen buffer is inactive.
    *   *Details*: Integrated high-precision trackpad scrolling support (`preciseWheelRotation`) and value accumulators in [SwingTerminalPanel.kt](file:///Users/francisco.ballina/PycharmProjects/plugin-terminal/src/main/kotlin/com/embedded/terminal/SwingTerminalPanel.kt) to eliminate scrolling wobbles and jitter.
*   [x] **Dumb Mode Responsive Loading & Asynchronous PTY Startup**:
    *   *Details*: Moved the entire blocking PTY process startup sequence (virtual environment path scan, SDK config check, OS execution) from the Event Dispatch Thread (EDT) to a background thread to prevent UI freezing during heavy indexing.
    *   *Details*: Shifted the infinite PTY reader loop to a dedicated daemon thread to prevent queue blocking inside the single-thread background executor.
    *   *Details*: Implemented `DumbAware` on tab actions (`addTabAction`, `closeTabAction`) in [EmbeddedTerminalToolWindowFactory.kt](file:///Users/francisco.ballina/PycharmProjects/plugin-terminal/src/main/kotlin/com/embedded/terminal/EmbeddedTerminalToolWindowFactory.kt) to keep them active and clickable during PyCharm indexing.
*   [x] **Interactive Mouse Double-Click Word Selection**:
    *   *Details*: Intercepted mouse double-clicks in [SwingTerminalPanel.kt](file:///Users/francisco.ballina/PycharmProjects/plugin-terminal/src/main/kotlin/com/embedded/terminal/SwingTerminalPanel.kt) to dynamically expand and select path strings, variables, and words matching custom delimiters.

### 🟢 Version 1.26.0
*   [x] **Smart Paste & Drag and Drop for Agents**: Copy screenshots (images), documents (PDFs), or massive text logs and paste them directly into the terminal with `Cmd+Shift+V` (or Drag & Drop). Handles file generation, folder isolation, and dynamic physical/symbolic links for heavy resources.
*   [x] **Clickable Hyperlinks**: Injected temporary files show as active terminal hyperlinks that open directly in the editor on mouse click.
*   [x] **Minimum Linking Configuration**: Added a setting under tools configuration (default 50MB) to choose when to switch from copying to hardlinks/symlinks for pasted resources.
*   [x] **Default Right-Top Alignment**: Positioned the tool window by default to the upper-right sidebar for fresh installations.

### 🟢 Version 1.24.0
*   [x] **Silent Virtual Environment Auto-Activation**: Auto-detects and activates `.venv`, `venv`, or `env` folders without printing startup commands.
*   [x] **Gentoo-Style Terminal Colors**: Enabled custom colored prompt, LSColors, and command aliases for all Unix zsh/bash sessions.
*   [x] **Process Close protection**: Added active process checking via OS-level process handles to prompt a confirmation dialog before closing the tab.
*   [x] **Dynamic Localization**: Automatically translates settings options and action labels to matching locale.

### 🟢 Version 1.22.0
*   [x] **Color & Theme Customization** ([TerminalColorScheme.kt](file:///Users/francisco.ballina/PycharmProjects/plugin-terminal/src/main/kotlin/com/embedded/terminal/TerminalColorScheme.kt))
    *   *Details*: Added predefined schemes (Dracula, Solarized, Monokai, One Dark, Konsole Default), fully customized scheme creation, opacity/transparency controls (Konsole style), custom ANSI color palette picker grid, and live preview settings updates.
*   [x] **Shift + Enter for Multiline Input** ([SwingTerminalPanel.kt](file:///Users/francisco.ballina/PycharmProjects/plugin-terminal/src/main/kotlin/com/embedded/terminal/SwingTerminalPanel.kt#L588))
    *   *Details*: Translates `Shift + Enter` into `\\\r` (backslash + enter) so command line chat tools (like `agy`) and shell prompts treat it as a newline / line continuation instead of immediate execution.


---

## 📋 Pending Backlog (Under Discussion / Planned)

*None at this time.*

