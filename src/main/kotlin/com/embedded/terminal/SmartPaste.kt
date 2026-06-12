package com.embedded.terminal

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBScrollPane
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.UUID
import javax.imageio.ImageIO
import javax.swing.*

/**
 * Global paths utility for Smart Paste.
 */
object SmartPastePaths {
    fun getSessionsDir(): String {
        return PathManager.getSystemPath() + "/embedded-terminal/agent-sessions"
    }

    init {
        // Startup cleanup: delete existing sessions from previous runs
        try {
            val sessionsDir = File(getSessionsDir())
            if (sessionsDir.exists() && sessionsDir.isDirectory) {
                sessionsDir.deleteRecursively()
            }
        } catch (e: Exception) {
            // Ignore startup cleanup errors
        }
    }
}

/**
 * Content type classifications for the clipboard.
 */
sealed class SmartPasteContent {
    data class ShortText(val text: String) : SmartPasteContent()
    data class LargeText(val text: String) : SmartPasteContent()
    data class Image(val image: BufferedImage) : SmartPasteContent()
    data class Files(val files: List<File>) : SmartPasteContent()
}

/**
 * Details representing persisted content to be pasted.
 */
data class SmartPasteDetails(
    val sourceName: String,
    val formattedSize: String,
    val escapedPath: String,
    val files: List<File>
)

/**
 * TerminalSession interface representing the active session/tab.
 */
interface TerminalSession {
    val sessionId: String
    fun registerTempFile(file: File)
}

/**
 * Interfaces to mock clipboard during tests.
 */
interface SmartPasteClipboard {
    fun getContents(): Transferable?
}

class SystemClipboardWrapper : SmartPasteClipboard {
    override fun getContents(): Transferable? {
        return try {
            Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * ContentClassifier classifies clipboard data and provides heuristics.
 */
object ContentClassifier {
    fun classify(transferable: Transferable): SmartPasteContent? {
        try {
            if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                val list = transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
                val files = list?.filterIsInstance<File>() ?: emptyList()
                if (files.isNotEmpty()) {
                    return SmartPasteContent.Files(files)
                }
            }
            if (transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                val rawImg = transferable.getTransferData(DataFlavor.imageFlavor) as? Image
                if (rawImg != null) {
                    val img = toBufferedImage(rawImg)
                    return SmartPasteContent.Image(img)
                }
            }
            if (transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                val text = transferable.getTransferData(DataFlavor.stringFlavor) as? String
                if (text != null) {
                    return if (text.length > 2000) {
                        SmartPasteContent.LargeText(text)
                    } else {
                        SmartPasteContent.ShortText(text)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun toBufferedImage(img: Image): BufferedImage {
        if (img is BufferedImage) {
            return img
        }
        val icon = ImageIcon(img)
        val width = icon.iconWidth
        val height = icon.iconHeight
        val bimage = BufferedImage(
            width.coerceAtLeast(1),
            height.coerceAtLeast(1),
            BufferedImage.TYPE_INT_ARGB
        )
        val g = bimage.createGraphics()
        g.drawImage(img, 0, 0, null)
        g.dispose()
        return bimage
    }

    fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
    }

    fun detectExtension(text: String): String {
        val trimmed = text.trim()
        return when {
            trimmed.startsWith("{") || trimmed.startsWith("[") -> "json"
            trimmed.startsWith("<?xml") || trimmed.startsWith("<") -> "xml"
            trimmed.startsWith("---") || trimmed.contains(Regex("\n[\\s]*[a-zA-Z0-9_-]+:[\\s]")) -> "yaml"
            trimmed.startsWith("#!") -> {
                if (trimmed.contains("python")) "py" else "sh"
            }
            trimmed.contains(Regex("(def |class |import |from [a-zA-Z0-9_]+ import)")) -> "py"
            trimmed.contains(Regex("(Exception in thread|Caused by:|at [a-zA-Z0-9_.]+\\.[a-zA-Z0-9_]+\\()")) -> "log"
            trimmed.contains(Regex("^(select|insert|update|delete|create|drop) ", RegexOption.IGNORE_CASE)) -> "sql"
            else -> "txt"
        }
    }
}

/**
 * PersistenceManager saves resources to plugin cache and handles linking fallbacks.
 */
object PersistenceManager {
    fun persist(content: SmartPasteContent, sessionDir: File): List<File> {
        if (!sessionDir.exists()) {
            sessionDir.mkdirs()
        }

        return when (content) {
            is SmartPasteContent.ShortText -> emptyList()
            is SmartPasteContent.LargeText -> {
                val ext = ContentClassifier.detectExtension(content.text)
                val file = File(sessionDir, "text_${UUID.randomUUID()}.$ext")
                file.writeText(content.text, Charsets.UTF_8)
                listOf(file)
            }
            is SmartPasteContent.Image -> {
                val file = File(sessionDir, "screenshot_${UUID.randomUUID()}.png")
                ImageIO.write(content.image, "png", file)
                listOf(file)
            }
            is SmartPasteContent.Files -> {
                content.files.map { srcFile ->
                    val safeName = ContentClassifier.sanitizeFileName(srcFile.nameWithoutExtension)
                    val ext = srcFile.extension
                    val targetFile = File(sessionDir, "${safeName}_${UUID.randomUUID()}.$ext")

                    val fileSize = srcFile.length()
                    val minSizeMb = EmbeddedTerminalSettings.getInstance().state.smartPasteMinLinkSizeMb
                    if (fileSize < minSizeMb.toLong() * 1024 * 1024) {
                        Files.copy(srcFile.toPath(), targetFile.toPath())
                    } else {
                        try {
                            Files.createLink(targetFile.toPath(), srcFile.toPath())
                        } catch (e: Exception) {
                            try {
                                Files.createSymbolicLink(targetFile.toPath(), srcFile.toPath())
                            } catch (e2: Exception) {
                                Files.copy(srcFile.toPath(), targetFile.toPath())
                            }
                        }
                    }
                    targetFile
                }
            }
        }
    }
}

/**
 * ShellEscaper formats paths for the active shell.
 */
enum class ShellType {
    UNIX, CMD, POWERSHELL
}

object ShellEscaper {
    fun detectShell(shellPath: String): ShellType {
        val pathLower = shellPath.lowercase()
        return when {
            pathLower.contains("powershell") || pathLower.contains("pwsh") -> ShellType.POWERSHELL
            pathLower.contains("cmd") || pathLower.contains("cmd.exe") -> ShellType.CMD
            else -> ShellType.UNIX
        }
    }

    fun escape(path: String, shellType: ShellType): String {
        return when (shellType) {
            ShellType.UNIX -> {
                "'" + path.replace("'", "'\\''") + "'"
            }
            ShellType.CMD -> {
                "\"" + path.replace("\"", "\"\"") + "\""
            }
            ShellType.POWERSHELL -> {
                "'" + path.replace("'", "''") + "'"
            }
        }
    }
}

/**
 * SmartPasteDialog shows native preview popup.
 */
class SmartPasteDialog(
    project: Project?,
    private val content: SmartPasteContent,
    private val details: SmartPasteDetails
) : DialogWrapper(project, true) {

    init {
        title = "Smart Paste - Confirmar envío a la Terminal"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(10, 10))
        panel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)

        // Details Panel
        val detailsPanel = JPanel()
        detailsPanel.layout = BoxLayout(detailsPanel, BoxLayout.Y_AXIS)

        val titleLabel = JLabel("Se va a inyectar la ruta temporal en la terminal:").apply {
            font = font.deriveFont(Font.BOLD)
        }
        detailsPanel.add(titleLabel)
        detailsPanel.add(Box.createVerticalStrut(5))

        val pathField = JTextField(details.escapedPath).apply {
            isEditable = false
            background = UIManager.getColor("TextField.background")
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.GRAY),
                BorderFactory.createEmptyBorder(5, 5, 5, 5)
            )
        }
        detailsPanel.add(pathField)
        detailsPanel.add(Box.createVerticalStrut(5))

        val infoLabel = JLabel("Origen: ${details.sourceName} | Tamaño: ${details.formattedSize}")
        detailsPanel.add(infoLabel)
        detailsPanel.add(Box.createVerticalStrut(10))

        panel.add(detailsPanel, BorderLayout.NORTH)

        // Preview Panel
        val previewContentPanel = JPanel(BorderLayout())
        previewContentPanel.border = BorderFactory.createTitledBorder("Vista Previa")

        when (content) {
            is SmartPasteContent.Image -> {
                val img = content.image
                val scaledWidth = 300
                val scaledHeight = (img.height * (300.0 / img.width)).toInt().coerceIn(50, 200)
                val scaledImg = img.getScaledInstance(scaledWidth, scaledHeight, Image.SCALE_SMOOTH)
                val imageLabel = JLabel(ImageIcon(scaledImg))
                previewContentPanel.add(imageLabel, BorderLayout.CENTER)
            }
            is SmartPasteContent.LargeText -> {
                val lines = content.text.lines().take(15).joinToString("\n")
                val textArea = JTextArea(lines).apply {
                    isEditable = false
                    font = Font("Monospaced", Font.PLAIN, 12)
                }
                val scroll = JBScrollPane(textArea).apply {
                    preferredSize = Dimension(400, 150)
                }
                previewContentPanel.add(scroll, BorderLayout.CENTER)
            }
            is SmartPasteContent.Files -> {
                val filesList = content.files.joinToString("\n") { it.name }
                val textArea = JTextArea(filesList).apply {
                    isEditable = false
                }
                val scroll = JBScrollPane(textArea).apply {
                    preferredSize = Dimension(400, 100)
                }
                previewContentPanel.add(scroll, BorderLayout.CENTER)
            }
            else -> {
                val label = JLabel(details.sourceName)
                previewContentPanel.add(label, BorderLayout.CENTER)
            }
        }

        panel.add(previewContentPanel, BorderLayout.CENTER)
        return panel
    }
}

/**
 * Controller coordinating Smart Paste flows.
 */
object SmartPasteController {
    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1]
        val value = bytes / Math.pow(1024.0, exp.toDouble())
        return String.format("%.2f %sB", value, pre)
    }

    fun triggerSmartPaste(
        project: Project?,
        panel: SwingTerminalPanel,
        controller: TerminalSession,
        clipboard: SmartPasteClipboard = SystemClipboardWrapper()
    ) {
        System.err.println("[SMART_PASTE] triggerSmartPaste invoked")
        val transferable = clipboard.getContents()
        if (transferable == null) {
            System.err.println("[SMART_PASTE] Clipboard contents are null")
            return
        }
        val flavors = transferable.transferDataFlavors.map { it.humanPresentableName }.joinToString(", ")
        System.err.println("[SMART_PASTE] Clipboard flavors: $flavors")
        
        val content = ContentClassifier.classify(transferable)
        if (content == null) {
            System.err.println("[SMART_PASTE] Content classification failed")
            return
        }
        System.err.println("[SMART_PASTE] Classified content type: ${content.javaClass.simpleName}")
        triggerSmartPasteFlow(project, panel, controller, content)
    }

    fun triggerSmartPasteFlow(
        project: Project?,
        panel: SwingTerminalPanel,
        controller: TerminalSession,
        content: SmartPasteContent
    ) {
        System.err.println("[SMART_PASTE] triggerSmartPasteFlow started. Content type: ${content.javaClass.simpleName}")
        if (content is SmartPasteContent.ShortText) {
            val text = content.text
            val processedText = if (panel.buffer.isBracketedPasteMode) {
                "\u001b[200~$text\u001b[201~"
            } else {
                text
            }
            panel.onInput?.invoke(processedText)
            panel.requestRepaint()
            return
        }

        val settings = try {
            val s = EmbeddedTerminalSettings.getInstance().state
            System.err.println("[SMART_PASTE] Settings loaded successfully. shellPath: ${s.shellPath}")
            s
        } catch (t: Throwable) {
            System.err.println("[SMART_PASTE] ERROR loading settings:")
            t.printStackTrace()
            return
        }

        val shellType = ShellEscaper.detectShell(settings.shellPath)
        val sessionDir = File(SmartPastePaths.getSessionsDir(), controller.sessionId)
        System.err.println("[SMART_PASTE] Target sessionDir: ${sessionDir.absolutePath}")

        val resultFiles = try {
            System.err.println("[SMART_PASTE] Persisting content to disk...")
            val files = PersistenceManager.persist(content, sessionDir)
            System.err.println("[SMART_PASTE] Content persisted successfully. Files count: ${files.size}")
            for (f in files) {
                System.err.println("[SMART_PASTE] - Persisted file: ${f.absolutePath} (exists=${f.exists()}, size=${f.length()})")
            }
            files
        } catch (t: Throwable) {
            System.err.println("[SMART_PASTE] ERROR during content persistence:")
            t.printStackTrace()
            emptyList<File>()
        }

        if (resultFiles.isEmpty()) {
            System.err.println("[SMART_PASTE] No files persisted. Aborting flow.")
            return
        }

        // Register files for cleanup
        for (f in resultFiles) {
            controller.registerTempFile(f)
        }

        val escapedPaths = resultFiles.joinToString(" ") { ShellEscaper.escape(it.absolutePath, shellType) }
        System.err.println("[SMART_PASTE] Escaped paths: $escapedPaths")

        val sourceName = when (content) {
            is SmartPasteContent.Image -> "Captura de pantalla"
            is SmartPasteContent.LargeText -> "Texto largo (log/código)"
            is SmartPasteContent.Files -> {
                if (content.files.size == 1) content.files.first().name else "${content.files.size} archivos"
            }
            else -> ""
        }

        val totalSize = resultFiles.sumOf { it.length() }
        val formattedSize = formatBytes(totalSize)
        System.err.println("[SMART_PASTE] Details: sourceName=$sourceName, formattedSize=$formattedSize")

        val details = SmartPasteDetails(
            sourceName = sourceName,
            formattedSize = formattedSize,
            escapedPath = escapedPaths,
            files = resultFiles
        )

        val app = ApplicationManager.getApplication()
        val isHeadlessOrTest = GraphicsEnvironment.isHeadless() || app == null || app.isUnitTestMode
        System.err.println("[SMART_PASTE] Environment check: isHeadlessOrTest=$isHeadlessOrTest (headless=${GraphicsEnvironment.isHeadless()}, appNull=${app == null}, testMode=${app?.isUnitTestMode})")

        if (isHeadlessOrTest) {
            System.err.println("[SMART_PASTE] Headless/Test mode detected. Direct injection to terminal.")
            panel.onInput?.invoke(escapedPaths)
            panel.requestRepaint()
        } else {
            try {
                System.err.println("[SMART_PASTE] Creating SmartPasteDialog...")
                val dialog = SmartPasteDialog(project, content, details)
                System.err.println("[SMART_PASTE] Showing SmartPasteDialog...")
                val dialogResult = dialog.showAndGet()
                System.err.println("[SMART_PASTE] SmartPasteDialog closed. dialogResult=$dialogResult")
                if (dialogResult) {
                    panel.onInput?.invoke(escapedPaths)
                    panel.requestRepaint()
                    System.err.println("[SMART_PASTE] Paths successfully pasted into terminal panel.")
                } else {
                    System.err.println("[SMART_PASTE] Paste canceled by user.")
                }
            } catch (t: Throwable) {
                System.err.println("[SMART_PASTE] ERROR creating/showing SmartPasteDialog:")
                t.printStackTrace()
            }
        }
    }
}

/**
 * TransferHandler registering Drag & Drop capabilities on SwingTerminalPanel.
 */
class TerminalTransferHandler(
    private val project: Project,
    private val panel: SwingTerminalPanel,
    private val controller: TerminalSession
) : TransferHandler() {

    override fun canImport(support: TransferSupport): Boolean {
        return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ||
                support.isDataFlavorSupported(DataFlavor.stringFlavor)
    }

    override fun importData(support: TransferSupport): Boolean {
        if (!canImport(support)) return false
        try {
            val content = ContentClassifier.classify(support.transferable) ?: return false
            SmartPasteController.triggerSmartPasteFlow(project, panel, controller, content)
            return true
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }
}
