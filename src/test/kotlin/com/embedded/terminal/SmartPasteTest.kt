package com.embedded.terminal

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.File
import java.util.UUID

class SmartPasteTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testContentClassificationShortText() {
        val shortText = "Hello World"
        val transferable = MockTransferable(shortText, DataFlavor.stringFlavor)
        val content = ContentClassifier.classify(transferable)
        
        assertTrue(content is SmartPasteContent.ShortText)
        assertEquals(shortText, (content as SmartPasteContent.ShortText).text)
    }

    @Test
    fun testContentClassificationLargeText() {
        val largeText = "A".repeat(2500)
        val transferable = MockTransferable(largeText, DataFlavor.stringFlavor)
        val content = ContentClassifier.classify(transferable)
        
        assertTrue(content is SmartPasteContent.LargeText)
        assertEquals(largeText, (content as SmartPasteContent.LargeText).text)
    }

    @Test
    fun testContentClassificationImage() {
        val image = BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB)
        val transferable = MockTransferable(image, DataFlavor.imageFlavor)
        val content = ContentClassifier.classify(transferable)
        
        assertTrue(content is SmartPasteContent.Image)
        assertEquals(image, (content as SmartPasteContent.Image).image)
    }

    @Test
    fun testContentClassificationFiles() {
        val filesList = listOf(File("test.txt"))
        val transferable = MockTransferable(filesList, DataFlavor.javaFileListFlavor)
        val content = ContentClassifier.classify(transferable)
        
        assertTrue(content is SmartPasteContent.Files)
        assertEquals(filesList, (content as SmartPasteContent.Files).files)
    }

    @Test
    fun testSanitizeFileName() {
        assertEquals("dataset__rm_-rf.csv", ContentClassifier.sanitizeFileName("dataset; rm -rf.csv"))
        assertEquals("test_file_123.log", ContentClassifier.sanitizeFileName("test file 123.log"))
        assertEquals("abc_def.txt", ContentClassifier.sanitizeFileName("abc/def.txt"))
    }

    @Test
    fun testDetectExtension() {
        assertEquals("json", ContentClassifier.detectExtension("  {\"key\": \"value\"}"))
        assertEquals("json", ContentClassifier.detectExtension("[1, 2, 3]"))
        assertEquals("xml", ContentClassifier.detectExtension("<?xml version=\"1.0\"?> <root></root>"))
        assertEquals("yaml", ContentClassifier.detectExtension("key: value\nnested:\n  key2: value2"))
        assertEquals("py", ContentClassifier.detectExtension("def foo():\n    return 42"))
        assertEquals("py", ContentClassifier.detectExtension("import os\nimport sys"))
        assertEquals("log", ContentClassifier.detectExtension("Exception in thread \"main\" java.lang.NullPointerException"))
        assertEquals("sql", ContentClassifier.detectExtension("SELECT * FROM users;"))
        assertEquals("txt", ContentClassifier.detectExtension("Plain simple text with no special patterns."))
    }

    @Test
    fun testShellEscaperDetection() {
        assertEquals(ShellType.UNIX, ShellEscaper.detectShell("/bin/zsh"))
        assertEquals(ShellType.UNIX, ShellEscaper.detectShell("/usr/local/bin/bash"))
        assertEquals(ShellType.POWERSHELL, ShellEscaper.detectShell("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"))
        assertEquals(ShellType.POWERSHELL, ShellEscaper.detectShell("pwsh"))
        assertEquals(ShellType.CMD, ShellEscaper.detectShell("cmd.exe"))
    }

    @Test
    fun testShellEscaperEscapeUnix() {
        assertEquals("'/path/to/my file'", ShellEscaper.escape("/path/to/my file", ShellType.UNIX))
        assertEquals("'/path/to/o'\\''brien'", ShellEscaper.escape("/path/to/o'brien", ShellType.UNIX))
    }

    @Test
    fun testShellEscaperEscapeCmd() {
        assertEquals("\"C:\\path\\to\\my file\"", ShellEscaper.escape("C:\\path\\to\\my file", ShellType.CMD))
        assertEquals("\"C:\\path\\to\\\"\"quotes\"\"\"", ShellEscaper.escape("C:\\path\\to\\\"quotes\"", ShellType.CMD))
    }

    @Test
    fun testShellEscaperEscapePowerShell() {
        assertEquals("'/path/to/my file'", ShellEscaper.escape("/path/to/my file", ShellType.POWERSHELL))
        assertEquals("'/path/to/o''brien'", ShellEscaper.escape("/path/to/o'brien", ShellType.POWERSHELL))
    }

    @Test
    fun testPersistenceManagerLargeText() {
        val largeText = "A".repeat(2500)
        val content = SmartPasteContent.LargeText(largeText)
        val sessionDir = tempFolder.newFolder("session")
        
        val persistedFiles = PersistenceManager.persist(content, sessionDir)
        
        assertEquals(1, persistedFiles.size)
        val file = persistedFiles.first()
        assertTrue(file.exists())
        assertEquals(largeText, file.readText())
        assertEquals("txt", file.extension)
    }

    @Test
    fun testPersistenceManagerImage() {
        val image = BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB)
        val content = SmartPasteContent.Image(image)
        val sessionDir = tempFolder.newFolder("session")
        
        val persistedFiles = PersistenceManager.persist(content, sessionDir)
        
        assertEquals(1, persistedFiles.size)
        val file = persistedFiles.first()
        assertTrue(file.exists())
        assertEquals("png", file.extension)
    }

    @Test
    fun testPersistenceManagerFilesCopy() {
        val srcFile = tempFolder.newFile("dataset.csv")
        srcFile.writeText("id,name\n1,Alice")
        
        val content = SmartPasteContent.Files(listOf(srcFile))
        val sessionDir = tempFolder.newFolder("session")
        
        val persistedFiles = PersistenceManager.persist(content, sessionDir)
        
        assertEquals(1, persistedFiles.size)
        val file = persistedFiles.first()
        assertTrue(file.exists())
        assertEquals("id,name\n1,Alice", file.readText())
        assertTrue(file.name.startsWith("dataset_"))
        assertEquals("csv", file.extension)
    }

    @Test
    fun testPersistenceManagerDynamicLinkThreshold() {
        val settings = EmbeddedTerminalSettings.getInstance().state
        val originalThreshold = settings.smartPasteMinLinkSizeMb
        
        try {
            val srcFile = tempFolder.newFile("dataset_large.csv")
            srcFile.writeText("id,name\n1,Bob")
            
            // Set threshold to 0 MB to force link path
            settings.smartPasteMinLinkSizeMb = 0
            
            val content = SmartPasteContent.Files(listOf(srcFile))
            val sessionDir = tempFolder.newFolder("session_link")
            
            val persistedFiles = PersistenceManager.persist(content, sessionDir)
            assertEquals(1, persistedFiles.size)
            val file = persistedFiles.first()
            assertTrue(file.exists())
            assertEquals("id,name\n1,Bob", file.readText())
        } finally {
            // Restore settings
            settings.smartPasteMinLinkSizeMb = originalThreshold
        }
    }

    @Test
    fun testSmartPasteControllerFlowHeadless() {
        // We set up a mock transferable and mock clipboard
        val largeText = "A".repeat(2500)
        val mockTransferable = MockTransferable(largeText, DataFlavor.stringFlavor)
        val mockClipboard = object : SmartPasteClipboard {
            override fun getContents(): Transferable? = mockTransferable
        }

        // We mock buffer, engine, and controller
        val buffer = TerminalBuffer(80, 24, 100)
        val stateEngine = TerminalStateEngine(buffer)
        var receivedInput: String? = null
        val panel = SwingTerminalPanel(null, buffer, stateEngine)
        panel.onInput = { receivedInput = it }

        val project = null
        val tempFiles = mutableListOf<File>()
        val controller = object : TerminalSession {
            override val sessionId = UUID.randomUUID().toString()
            override fun registerTempFile(file: File) {
                tempFiles.add(file)
            }
        }

        // Trigger smart paste
        SmartPasteController.triggerSmartPaste(
            project = project,
            panel = panel,
            controller = controller,
            clipboard = mockClipboard
        )

        // Verify that receivedInput is not null, contains escaped path and file exists in tempFiles
        assertNotNull(receivedInput)
        assertEquals(1, tempFiles.size)
        val generatedFile = tempFiles.first()
        assertTrue(generatedFile.exists())
        assertEquals(largeText, generatedFile.readText())
        
        // Escape check
        val expectedEscaped = ShellEscaper.escape(generatedFile.absolutePath, ShellType.UNIX)
        assertEquals(expectedEscaped, receivedInput)
    }

    private class MockTransferable(private val data: Any, private val flavor: DataFlavor) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(flavor)
        override fun isDataFlavorSupported(f: DataFlavor): Boolean = f == flavor
        override fun getTransferData(f: DataFlavor): Any {
            if (f != flavor) throw java.awt.datatransfer.UnsupportedFlavorException(f)
            return data
        }
    }
}
