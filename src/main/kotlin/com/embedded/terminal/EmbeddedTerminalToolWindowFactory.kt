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
import com.intellij.openapi.roots.ProjectRootManager
import kotlin.jvm.Volatile

private val CONTROLLER_KEY = com.intellij.openapi.util.Key.create<EmbeddedTerminalController>("EmbeddedTerminalController")

class EmbeddedTerminalToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Clear existing contents for safety
        toolWindow.contentManager.removeAllContents(true)
        
        // Lazy loading: Only create the first tab if the tool window is visible
        if (toolWindow.isVisible) {
            addNewTerminalTab(project, toolWindow, "Terminal 1")
        }

        // Action to add new tabs
        val addTabAction = object : AnAction(TerminalBundle.message("new.tab"), TerminalBundle.message("new.tab.desc"), AllIcons.General.Add), DumbAware {
            override fun actionPerformed(e: AnActionEvent) {
                val nextTabIndex = toolWindow.contentManager.contentCount + 1
                addNewTerminalTab(project, toolWindow, "Terminal $nextTabIndex")
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
        }

        // Action to close active tab
        val closeTabAction = object : AnAction(TerminalBundle.message("close.tab"), TerminalBundle.message("close.tab.desc"), AllIcons.Actions.Cancel), DumbAware {
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

            override fun contentRemoveQuery(event: ContentManagerEvent) {
                val content = event.content
                val controller = content.getUserData(CONTROLLER_KEY)
                if (controller != null && controller.isProcessRunning()) {
                    val result = com.intellij.openapi.ui.Messages.showYesNoDialog(
                        project,
                        TerminalBundle.message("close.confirm.msg"),
                        TerminalBundle.message("close.confirm.title"),
                        com.intellij.openapi.ui.Messages.getQuestionIcon()
                    )
                    if (result != com.intellij.openapi.ui.Messages.YES) {
                        event.consume()
                    }
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
        content.putUserData(CONTROLLER_KEY, controller)
        
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

open class EmbeddedTerminalController(private val project: Project, private val startPtyOnInit: Boolean = true) : Disposable, TerminalSession {
    override val sessionId = java.util.UUID.randomUUID().toString()
    private val settings = EmbeddedTerminalSettings.getInstance().state
    val buffer = TerminalBuffer(80, 24, settings.historyLimit)
    val stateEngine = TerminalStateEngine(buffer)
    val terminalPanel = SwingTerminalPanel(project, buffer, stateEngine)
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
    private val tempFilesToDelete = java.util.Collections.synchronizedList(mutableListOf<java.io.File>())

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

        // Smart paste trigger binding
        terminalPanel.triggerSmartPasteCallback = {
            SmartPasteController.triggerSmartPaste(project, terminalPanel, this)
        }

        // Drag and drop handler registration
        terminalPanel.transferHandler = TerminalTransferHandler(project, terminalPanel, this)

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

        if (startPtyOnInit) startPty()
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
        executor.submit {
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
                var detectedVenv: java.io.File? = null
                var detectedBinDir: java.io.File? = null
                val isWin = os.contains("win")

                // 1. Try local project venv candidates
                val baseDir = project.basePath
                if (baseDir != null) {
                    val venvCandidates = listOf(".venv", "venv", "env")
                    for (cand in venvCandidates) {
                        val f = java.io.File(baseDir, cand)
                        if (f.exists() && f.isDirectory) {
                            val binDir = if (isWin) java.io.File(f, "Scripts") else java.io.File(f, "bin")
                            if (binDir.exists() && binDir.isDirectory) {
                                detectedVenv = f
                                detectedBinDir = binDir
                                break
                            }
                        }
                    }
                }

                // 2. Try configured Project SDK if local candidates not found
                if (detectedVenv == null) {
                    try {
                        val sdk = ProjectRootManager.getInstance(project).projectSdk
                        val homePath = sdk?.homePath
                        if (homePath != null) {
                            val exeFile = java.io.File(homePath)
                            if (exeFile.exists()) {
                                val binDir = exeFile.parentFile
                                if (binDir != null && binDir.exists() && binDir.isDirectory) {
                                    val activateFile = if (isWin) java.io.File(binDir, "activate.bat") else java.io.File(binDir, "activate")
                                    val venvDir = binDir.parentFile
                                    val pyvenvCfg = if (venvDir != null) java.io.File(venvDir, "pyvenv.cfg") else null
                                    if (activateFile.exists() || (pyvenvCfg != null && pyvenvCfg.exists())) {
                                        detectedVenv = venvDir
                                        detectedBinDir = binDir
                                    }
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        // Ignore class loading or other SDK access errors
                    }
                }

                if (detectedVenv != null && detectedBinDir != null) {
                    val venvPath = detectedVenv.absolutePath
                    val binPath = detectedBinDir.absolutePath
                    val oldPath = env["PATH"] ?: System.getenv("PATH") ?: ""
                    val pathSeparator = if (isWin) ";" else ":"
                    env["PATH"] = if (oldPath.isNotEmpty()) "$binPath$pathSeparator$oldPath" else binPath
                    env["VIRTUAL_ENV"] = venvPath
                    env.remove("PYTHONHOME")
                }

                var activatedSilently = false
                var finalCmd = cmd
                val shellPath = cmd.firstOrNull() ?: ""
                val shellLower = shellPath.lowercase()
                
                if (isWin) {
                    if (detectedVenv != null && detectedBinDir != null) {
                        if (shellLower.contains("powershell") || shellLower.contains("pwsh")) {
                            val psScript = java.io.File(detectedBinDir, "Activate.ps1")
                            if (psScript.exists()) {
                                finalCmd = arrayOf(shellPath, "-NoExit", "-ExecutionPolicy", "Bypass", "-Command", "& \"${psScript.absolutePath}\"")
                                activatedSilently = true
                            }
                        } else if (shellLower.contains("cmd")) {
                            val batScript = java.io.File(detectedBinDir, "activate.bat")
                            if (batScript.exists()) {
                                finalCmd = arrayOf(shellPath, "/K", batScript.absolutePath)
                                activatedSilently = true
                            }
                        }
                    }
                } else {
                    // Unix shells
                    if (detectedVenv != null && detectedBinDir != null) {
                        if (shellLower.contains("zsh")) {
                            try {
                                val tempDir = java.nio.file.Files.createTempDirectory("terminal-zsh-").toFile()
                                tempFilesToDelete.add(tempDir)
                                val zshrcFile = java.io.File(tempDir, ".zshrc")
                                val originalZdotdir = System.getenv("ZDOTDIR") ?: System.getProperty("user.home")
                                val sb = java.lang.StringBuilder()
                                sb.append("""
                                if [ -f "$originalZdotdir/.zshrc" ]; then
                                    source "$originalZdotdir/.zshrc"
                                fi
                                
                                # Gentoo-style terminal coloring and alias configuration
                                export CLICOLOR=1
                                export LSCOLORS="Gxfxcxdxbxegedabagacad"
                                if [[ "${'$'}OSTYPE" == "darwin"* ]]; then
                                    alias ls='ls -G'
                                else
                                    alias ls='ls --color=auto'
                                fi
                                alias grep='grep --color=auto'
                                alias diff='diff --color=auto'
                                PROMPT="%F{green}%n%f@%F{green}%m%f %F{blue}%~%f %# "
                                """.trimIndent())
                                
                                val shScript = java.io.File(detectedBinDir, "activate")
                                if (shScript.exists()) {
                                    sb.append("\n\nsource \"${shScript.absolutePath}\"\n")
                                }
                                
                                zshrcFile.writeText(sb.toString())
                                tempFilesToDelete.add(zshrcFile)
                                env["ZDOTDIR"] = tempDir.absolutePath
                                activatedSilently = true
                            } catch (e: Exception) {
                                // Fallback
                            }
                        } else if (shellLower.contains("bash")) {
                            try {
                                val tempFile = java.io.File.createTempFile("terminal-bashrc-", ".sh")
                                tempFilesToDelete.add(tempFile)
                                val sb = java.lang.StringBuilder()
                                sb.append("""
                                if [ -f ~/.bashrc ]; then
                                    source ~/.bashrc
                                fi
    
                                # Gentoo-style terminal coloring and alias configuration
                                export CLICOLOR=1
                                export LSCOLORS="Gxfxcxdxbxegedabagacad"
                                if [[ "${'$'}OSTYPE" == "darwin"* ]]; then
                                    alias ls='ls -G'
                                else
                                    alias ls='ls --color=auto'
                                fi
                                alias grep='grep --color=auto'
                                alias diff='diff --color=auto'
                                PS1="\[\033[01;32m\]\u@\h\[\033[01;34m\] \w \$\[\033[00m\] "
                                """.trimIndent())
                                
                                val shScript = java.io.File(detectedBinDir, "activate")
                                if (shScript.exists()) {
                                    sb.append("\n\nsource \"${shScript.absolutePath}\"\n")
                                }
                                
                                tempFile.writeText(sb.toString())
                                finalCmd = arrayOf(shellPath, "--rcfile", tempFile.absolutePath, "-i")
                                activatedSilently = true
                            } catch (e: Exception) {
                                // Fallback
                            }
                        } else if (shellLower.contains("fish")) {
                            val fishScript = java.io.File(detectedBinDir, "activate.fish")
                            if (fishScript.exists()) {
                                finalCmd = arrayOf(shellPath, "-C", "source \"${fishScript.absolutePath}\"")
                                activatedSilently = true
                            }
                        }
                    }
                }

                val workingDir = project.basePath ?: System.getProperty("user.home")
                val pty = PtyProcess.exec(finalCmd, env, workingDir)
                ptyProcess = pty

                // Send activation command to the shell as fallback if silent activation was not supported
                if (detectedVenv != null && detectedBinDir != null && !activatedSilently) {
                    val activeShellPath = finalCmd.firstOrNull() ?: ""
                    executor.submit {
                        try {
                            Thread.sleep(600) // Wait a brief moment for the shell to print its prompt and be ready
                            val activationCmd = getActivationCommand(detectedBinDir, activeShellPath, os)
                            if (activationCmd != null) {
                                writeToPty(activationCmd)
                            }
                        } catch (e: Exception) {
                            // Ignore
                        }
                    }
                }
                
                // Set initial win size target using current buffer dimensions if not set by onResize yet
                if (targetCols == 80 && targetRows == 24 && (buffer.cols != 80 || buffer.rows != 24)) {
                    targetCols = buffer.cols
                    targetRows = buffer.rows
                }
                applyWindowSizeSilently()
                
                writer = OutputStreamWriter(pty.outputStream, StandardCharsets.UTF_8)

                // PTY reading thread
                Thread({
                    val reader = InputStreamReader(pty.inputStream, StandardCharsets.UTF_8)
                    val bufferChars = CharArray(1024)
                    while (!isDisposed) {
                        try {
                            if (lastSetCols != targetCols || lastSetRows != targetRows) {
                                applyWindowSizeSilently()
                            }
                            val read = reader.read(bufferChars)
                            if (read == -1) break
                            
                            if (java.lang.Boolean.getBoolean("terminal.debug.logging")) {
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
                            }
                            
                            val delay = backpressureManager.registerRead(read * 2)
                            if (delay > 0) {
                                Thread.sleep(delay)
                            }

                            stateEngine.feedChars(bufferChars, read)
                            terminalPanel.requestRepaint()
                        } catch (e: Exception) {
                            break
                        }
                    }
                    if (currentSettings.autoCloseOnExit) {
                        onProcessExit?.invoke()
                    }
                }, "TerminalPtyReader-${sessionId}").apply { isDaemon = true }.start()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun getActivationCommand(binDir: java.io.File, shellPath: String, os: String): String? {
        val shellLower = shellPath.lowercase()
        val isWin = os.contains("win")
        
        return if (isWin) {
            if (shellLower.contains("powershell") || shellLower.contains("pwsh")) {
                val psScript = java.io.File(binDir, "Activate.ps1")
                if (psScript.exists()) "& \"${psScript.absolutePath}\"\r\n" else null
            } else {
                val batScript = java.io.File(binDir, "activate.bat")
                if (batScript.exists()) "\"${batScript.absolutePath}\"\r\n" else null
            }
        } else {
            if (shellLower.contains("fish")) {
                val fishScript = java.io.File(binDir, "activate.fish")
                if (fishScript.exists()) "source \"${fishScript.absolutePath}\"\n" else null
            } else if (shellLower.contains("csh") || shellLower.contains("tcsh")) {
                val cshScript = java.io.File(binDir, "activate.csh")
                if (cshScript.exists()) "source \"${cshScript.absolutePath}\"\n" else null
            } else {
                val shScript = java.io.File(binDir, "activate")
                if (shScript.exists()) "source \"${shScript.absolutePath}\"\n" else null
            }
        }
    }

    fun isProcessRunning(): Boolean {
        val pty = ptyProcess ?: return false
        if (!pty.isAlive) return false
        return try {
            val handle = ProcessHandle.of(pty.pid()).orElse(null) ?: return false
            val descendants = handle.descendants().toList()

            fun isIgnoreProcess(cmd: String): Boolean {
                val name = cmd.substringAfterLast('/').substringAfterLast('\\').lowercase()
                return name == "zsh" || 
                       name == "bash" || 
                       name == "sh" || 
                       name == "fish" || 
                       name == "powershell" || 
                       name == "pwsh" || 
                       name == "cmd" || 
                       name == "cmd.exe" ||
                       name == "login" || 
                       name == "conhost" || 
                       name == "conhost.exe" || 
                       name == "winpty-agent" || 
                       name == "winpty-agent.exe" || 
                       name == "fsnotifier" || 
                       name == "reptyr" || 
                       name == "ptyhelper"
            }

            descendants.any { descendant ->
                val cmdPath = descendant.info().command().orElse("")
                if (cmdPath.isEmpty()) {
                    false
                } else {
                    !isIgnoreProcess(cmdPath)
                }
            }
        } catch (t: Throwable) {
            false
        }
    }

    override fun registerTempFile(file: java.io.File) {
        tempFilesToDelete.add(file)
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
        synchronized(tempFilesToDelete) {
            for (f in tempFilesToDelete) {
                try {
                    if (f.isDirectory) {
                        f.deleteRecursively()
                    } else {
                        f.delete()
                    }
                } catch (e: Exception) {}
            }
            tempFilesToDelete.clear()
        }
    }
}
