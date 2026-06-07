package com.embedded.terminal

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerListener
import com.intellij.ui.content.ContentManagerEvent
import com.pty4j.PtyProcess
import com.pty4j.WinSize
import java.awt.BorderLayout
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.swing.JPanel
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsListener
import kotlin.jvm.Volatile


class EmbeddedTerminalToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Clear existing contents for safety
        toolWindow.contentManager.removeAllContents(true)
        
        // Lazy loading: Only create the first tab if the tool window is visible
        if (toolWindow.isVisible) {
            addNewTerminalTab(project, toolWindow, "Terminal 1")
        }

        // Action to add new tabs
        val addTabAction = object : AnAction("Nueva pestaña", "Abre una nueva sesión de terminal", AllIcons.General.Add) {
            override fun actionPerformed(e: AnActionEvent) {
                val nextTabIndex = toolWindow.contentManager.contentCount + 1
                addNewTerminalTab(project, toolWindow, "Terminal $nextTabIndex")
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
        }

        // Action to close active tab
        val closeTabAction = object : AnAction("Cerrar pestaña", "Cierra la pestaña de terminal activa", AllIcons.Actions.Cancel) {
            override fun actionPerformed(e: AnActionEvent) {
                val selectedContent = toolWindow.contentManager.selectedContent
                if (selectedContent != null) {
                    toolWindow.contentManager.removeContent(selectedContent, true)
                }
            }

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = toolWindow.contentManager.contentCount > 0
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
        }
        toolWindow.setTitleActions(listOf(addTabAction, closeTabAction))

        // Hide tool window if all tabs are closed
        toolWindow.contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun contentRemoved(event: ContentManagerEvent) {
                if (toolWindow.contentManager.contentCount == 0) {
                    toolWindow.hide(null)
                }
            }
        })

        // Listen to visibility changes to lazy load the first tab when shown
        project.messageBus.connect(toolWindow.disposable).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun stateChanged(toolWindowManager: ToolWindowManager) {
                    val tw = toolWindowManager.getToolWindow(toolWindow.id)
                    if (tw != null && tw.isVisible && tw.contentManager.contentCount == 0) {
                        addNewTerminalTab(project, tw, "Terminal 1")
                    }
                }

                override fun toolWindowShown(tw: ToolWindow) {
                    if (tw.id == toolWindow.id && tw.contentManager.contentCount == 0) {
                        addNewTerminalTab(project, tw, "Terminal 1")
                    }
                }
            }
        )
    }

    private fun addNewTerminalTab(project: Project, toolWindow: ToolWindow, title: String) {
        val controller = EmbeddedTerminalController(project)
        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(controller.containerPanel, title, false)
        content.isCloseable = true
        content.setDisposer(controller) // Dispose the controller when tab is closed
        
        controller.onProcessExit = {
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                if (toolWindow.contentManager.contents.contains(content)) {
                    toolWindow.contentManager.removeContent(content, true)
                }
            }
        }
        
        toolWindow.contentManager.addContent(content)
        toolWindow.contentManager.setSelectedContent(content)
        
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            controller.terminalPanel.requestFocusInWindow()
        }
    }
}

class EmbeddedTerminalController(private val project: Project) : Disposable {
    private val settings = EmbeddedTerminalSettings.getInstance().state
    val buffer = TerminalBuffer(80, 24, settings.historyLimit)
    val stateEngine = TerminalStateEngine(buffer)
    val terminalPanel = SwingTerminalPanel(buffer, stateEngine)
    val scrollBar = javax.swing.JScrollBar(javax.swing.JScrollBar.VERTICAL)
    val scrollPane = com.intellij.ui.components.JBScrollPane().apply {
        val customViewport = object : javax.swing.JViewport() {
            override fun setViewPosition(p: java.awt.Point) {
                super.setViewPosition(java.awt.Point(0, 0))
            }
        }
        setViewport(customViewport)
        setViewportView(terminalPanel)
        border = javax.swing.BorderFactory.createEmptyBorder()
        verticalScrollBarPolicy = javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
        horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    }
    val containerPanel = JPanel(BorderLayout()).apply {
        add(scrollPane, BorderLayout.CENTER)
        add(scrollBar, BorderLayout.EAST)
    }
    private val backpressureManager = PtyBackpressureManager()
    
    private var ptyProcess: PtyProcess? = null
    private var writer: OutputStreamWriter? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val writeExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var isDisposed = false
    var onProcessExit: (() -> Unit)? = null

    @Volatile
    private var targetCols = 80
    @Volatile
    private var targetRows = 24
    @Volatile
    private var lastSetCols = -1
    @Volatile
    private var lastSetRows = -1

    private fun applyWindowSizeSilently() {
        val pty = ptyProcess ?: return
        val c = targetCols
        val r = targetRows
        if (c <= 0 || r <= 0) return
        try {
            pty.setWinSize(WinSize(c, r))
            lastSetCols = c
            lastSetRows = r
            System.err.println("[TERMINAL_DEBUG] Successfully set win size to $c x $r (pty=${pty.hashCode()})")
        } catch (e: Exception) {
            System.err.println("[TERMINAL_DEBUG] Failed to set win size to $c x $r (pty=${pty.hashCode()}): ${e.message}")
        }
    }

    init {
        // Intercept global IDE shortcuts when terminal has focus, allowing terminal to process them
        com.intellij.ide.IdeEventQueue.getInstance().addDispatcher(com.intellij.ide.IdeEventQueue.EventDispatcher { event ->
            if (event is java.awt.event.KeyEvent && terminalPanel.hasFocus()) {
                val code = event.keyCode
                val isModifierOnly = code == java.awt.event.KeyEvent.VK_SHIFT || 
                                     code == java.awt.event.KeyEvent.VK_CONTROL || 
                                     code == java.awt.event.KeyEvent.VK_ALT || 
                                     code == java.awt.event.KeyEvent.VK_META || 
                                     code == java.awt.event.KeyEvent.VK_ALT_GRAPH
                                     
                if (isModifierOnly) {
                    return@EventDispatcher false
                }
                
                val isMac = System.getProperty("os.name").lowercase().contains("mac")
                val toolWindowModifier = if (isMac) event.isMetaDown else event.isAltDown
                
                // Allow ToolWindow shortcuts (Cmd/Alt + 1..9) to pass through to the IDE
                if (toolWindowModifier && code in java.awt.event.KeyEvent.VK_1..java.awt.event.KeyEvent.VK_9) {
                    return@EventDispatcher false
                }
                
                // Dispatch event directly to the terminal panel and consume
                when (event.id) {
                    java.awt.event.KeyEvent.KEY_PRESSED -> terminalPanel.keyPressed(event)
                    java.awt.event.KeyEvent.KEY_TYPED -> terminalPanel.keyTyped(event)
                    java.awt.event.KeyEvent.KEY_RELEASED -> terminalPanel.keyReleased(event)
                }
                event.consume()
                return@EventDispatcher true
            }
            false
        }, this)

        // Link scrollbar and terminal panel
        terminalPanel.scrollBar = scrollBar
        scrollBar.addAdjustmentListener { e ->
            val historySize = synchronized(buffer) { buffer.history.size }
            val newOffset = (historySize - e.value).coerceIn(0, historySize)
            if (terminalPanel.scrollOffset != newOffset) {
                synchronized(buffer) {
                    terminalPanel.scrollOffset = newOffset
                }
                terminalPanel.repaint()
            }
        }
        updateScrollBarVisibility()

        // Apply initial opacity/transparency settings to containers
        val isOpaque = settings.backgroundOpacity >= 100
        terminalPanel.isOpaque = isOpaque
        scrollPane.isOpaque = isOpaque
        scrollPane.viewport.isOpaque = isOpaque
        containerPanel.isOpaque = isOpaque

        // Direct key input piping to PTY
        terminalPanel.onInput = { input ->
            writeToPty(input)
        }

        // PTY resizing synchronization
        terminalPanel.onResize = { cols, rows ->
            targetCols = cols
            targetRows = rows
            applyWindowSizeSilently()
        }

        // Real-time editor colors scheme changes listening
        project.messageBus.connect(this).subscribe(EditorColorsManager.TOPIC, EditorColorsListener {
            updateTerminalSettings()
        })

        // Real-time custom settings changes listening
        com.intellij.openapi.application.ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            EMBEDDED_TERMINAL_SETTINGS_CHANGED,
            object : EmbeddedTerminalSettingsListener {
                override fun settingsChanged(state: EmbeddedTerminalSettings.State) {
                    updateTerminalSettings()
                }
            }
        )

        startPty()
    }

    private fun writeToPty(input: String) {
        writeExecutor.submit {
            try {
                if (lastSetCols != targetCols || lastSetRows != targetRows) {
                    applyWindowSizeSilently()
                }
                writer?.write(input)
                writer?.flush()
            } catch (e: Exception) {
                // Process output stream might be closed
            }
        }
    }

    private fun updateScrollBarVisibility() {
        val show = settings.showScrollBar
        scrollBar.isVisible = show
        containerPanel.revalidate()
        containerPanel.repaint()
    }

    private fun updateTerminalSettings() {
        val currentSettings = EmbeddedTerminalSettings.getInstance().state
        buffer.updateHistoryLimit(currentSettings.historyLimit)
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            updateScrollBarVisibility()

            // Apply opacity/transparency settings to containers
            val isOpaque = currentSettings.backgroundOpacity >= 100
            terminalPanel.isOpaque = isOpaque
            scrollPane.isOpaque = isOpaque
            scrollPane.viewport.isOpaque = isOpaque
            containerPanel.isOpaque = isOpaque

            terminalPanel.recalculateDimensions()
            terminalPanel.repaint()
        }
    }

    private fun startPty() {
        try {
            val currentSettings = EmbeddedTerminalSettings.getInstance().state
            val customShell = currentSettings.shellPath
            val os = System.getProperty("os.name").lowercase()
            
            // Unix: force -l (login) and -i (interactive)
            val cmd = if (customShell.isNotEmpty()) {
                arrayOf(customShell)
            } else if (os.contains("win")) {
                arrayOf("powershell.exe")
            } else {
                val shell = if (java.io.File("/bin/zsh").exists()) "/bin/zsh" else "/bin/bash"
                arrayOf(shell, "-l", "-i")
            }

            val env = HashMap(System.getenv())
            env["TERM"] = "xterm-256color"

            // Auto-activate python virtual environment if present
            val baseDir = project.basePath
            if (baseDir != null) {
                val venvCandidates = listOf(".venv", "venv", "env")
                var detectedVenv: java.io.File? = null
                for (cand in venvCandidates) {
                    val f = java.io.File(baseDir, cand)
                    if (f.exists() && f.isDirectory) {
                        detectedVenv = f
                        break
                    }
                }
                if (detectedVenv != null) {
                    val venvPath = detectedVenv.absolutePath
                    val isWin = os.contains("win")
                    val binDir = if (isWin) java.io.File(detectedVenv, "Scripts") else java.io.File(detectedVenv, "bin")
                    if (binDir.exists() && binDir.isDirectory) {
                        val binPath = binDir.absolutePath
                        val oldPath = env["PATH"] ?: System.getenv("PATH") ?: ""
                        val pathSeparator = if (isWin) ";" else ":"
                        env["PATH"] = if (oldPath.isNotEmpty()) "$binPath$pathSeparator$oldPath" else binPath
                        env["VIRTUAL_ENV"] = venvPath
                        env.remove("PYTHONHOME")
                    }
                }
            }

            val workingDir = project.basePath ?: System.getProperty("user.home")
            ptyProcess = PtyProcess.exec(cmd, env, workingDir)
            
            // Set initial win size target using current buffer dimensions if not set by onResize yet
            if (targetCols == 80 && targetRows == 24 && (buffer.cols != 80 || buffer.rows != 24)) {
                targetCols = buffer.cols
                targetRows = buffer.rows
            }
            applyWindowSizeSilently()
            
            writer = OutputStreamWriter(ptyProcess!!.outputStream, StandardCharsets.UTF_8)

            // PTY reading thread
            executor.submit {
                val reader = InputStreamReader(ptyProcess!!.inputStream, StandardCharsets.UTF_8)
                val bufferChars = CharArray(1024)
                while (!isDisposed) {
                    try {
                        if (lastSetCols != targetCols || lastSetRows != targetRows) {
                            applyWindowSizeSilently()
                        }
                        val read = reader.read(bufferChars)
                        if (read == -1) break
                        
                        val rawOutput = String(bufferChars, 0, read)
                        val escaped = rawOutput.map { c ->
                            when (c) {
                                '\u001b' -> "\\e"
                                '\n' -> "\\n"
                                '\r' -> "\\r"
                                '\t' -> "\\t"
                                '\b' -> "\\b"
                                else -> if (c.code < 32 || c.code > 126) "\\u${c.code}" else c.toString()
                            }
                        }.joinToString("")
                        System.err.println("[PTY_OUTPUT] Raw: $escaped")
                        
                        val delay = backpressureManager.registerRead(read * 2)
                        if (delay > 0) {
                            Thread.sleep(delay)
                        }

                        stateEngine.feedChars(bufferChars, read)
                        
                        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                            terminalPanel.requestRepaint()
                        }
                    } catch (e: Exception) {
                        break
                    }
                }
                if (currentSettings.autoCloseOnExit) {
                    onProcessExit?.invoke()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun dispose() {
        isDisposed = true
        stateEngine.shutdown()
        executor.shutdownNow()
        writeExecutor.shutdownNow()
        try {
            writer?.close()
        } catch (e: Exception) {}
        try {
            ptyProcess?.destroy()
        } catch (e: Exception) {}
    }
}
