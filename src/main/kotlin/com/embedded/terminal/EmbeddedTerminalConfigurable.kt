package com.embedded.terminal

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.ColorPanel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.ActionListener
import javax.swing.*

class EmbeddedTerminalConfigurable : Configurable {
    private var mySettingsComponent: EmbeddedTerminalSettingsComponent? = null

    override fun getDisplayName(): String = "Embedded Terminal"

    override fun createComponent(): JComponent? {
        mySettingsComponent = EmbeddedTerminalSettingsComponent()
        return mySettingsComponent!!.panel
    }

    override fun isModified(): Boolean {
        val settings = EmbeddedTerminalSettings.getInstance().state
        val component = mySettingsComponent ?: return false
        return component.shellPathText != settings.shellPath ||
                component.useEditorThemeSelected != settings.useEditorTheme ||
                component.customFontFamilyVal != settings.customFontFamily ||
                component.customFontSizeVal != settings.customFontSize ||
                component.customLineHeightVal != settings.customLineHeight ||
                component.enableLigaturesSelected != settings.enableLigatures ||
                component.autoCloseOnExitSelected != settings.autoCloseOnExit ||
                component.historyLimitVal != settings.historyLimit ||
                component.showScrollBarSelected != settings.showScrollBar ||
                component.colorSchemeNameVal != settings.colorSchemeName ||
                component.customForegroundVal != settings.customForeground ||
                component.customBackgroundVal != settings.customBackground ||
                component.customSelectionVal != settings.customSelection ||
                component.customCursorVal != settings.customCursor ||
                component.backgroundOpacityVal != settings.backgroundOpacity ||
                component.customAnsiColorsHexVal != settings.customAnsiColorsHex ||
                component.smartPasteMinLinkSizeMbVal != settings.smartPasteMinLinkSizeMb
    }

    override fun apply() {
        val settings = EmbeddedTerminalSettings.getInstance().state
        val component = mySettingsComponent ?: return
        settings.shellPath = component.shellPathText
        settings.useEditorTheme = component.useEditorThemeSelected
        settings.customFontFamily = component.customFontFamilyVal
        settings.customFontSize = component.customFontSizeVal
        settings.customLineHeight = component.customLineHeightVal
        settings.enableLigatures = component.enableLigaturesSelected
        settings.autoCloseOnExit = component.autoCloseOnExitSelected
        settings.historyLimit = component.historyLimitVal
        settings.showScrollBar = component.showScrollBarSelected
        
        settings.colorSchemeName = component.colorSchemeNameVal
        settings.customForeground = component.customForegroundVal
        settings.customBackground = component.customBackgroundVal
        settings.customSelection = component.customSelectionVal
        settings.customCursor = component.customCursorVal
        settings.backgroundOpacity = component.backgroundOpacityVal
        settings.customAnsiColorsHex = component.customAnsiColorsHexVal
        settings.smartPasteMinLinkSizeMb = component.smartPasteMinLinkSizeMbVal

        // Publish configuration changes to all terminal instances
        com.intellij.openapi.application.ApplicationManager.getApplication().messageBus
            .syncPublisher(EMBEDDED_TERMINAL_SETTINGS_CHANGED)
            .settingsChanged(settings)
    }

    override fun reset() {
        val settings = EmbeddedTerminalSettings.getInstance().state
        val component = mySettingsComponent ?: return
        component.shellPathText = settings.shellPath
        component.useEditorThemeSelected = settings.useEditorTheme
        component.customFontFamilyVal = settings.customFontFamily
        component.customFontSizeVal = settings.customFontSize
        component.customLineHeightVal = settings.customLineHeight
        component.enableLigaturesSelected = settings.enableLigatures
        component.autoCloseOnExitSelected = settings.autoCloseOnExit
        component.historyLimitVal = settings.historyLimit
        component.showScrollBarSelected = settings.showScrollBar

        component.colorSchemeNameVal = settings.colorSchemeName
        component.customForegroundVal = settings.customForeground
        component.customBackgroundVal = settings.customBackground
        component.customSelectionVal = settings.customSelection
        component.customCursorVal = settings.customCursor
        component.backgroundOpacityVal = settings.backgroundOpacity
        component.customAnsiColorsHexVal = settings.customAnsiColorsHex
        component.smartPasteMinLinkSizeMbVal = settings.smartPasteMinLinkSizeMb
        
        component.triggerColorUiUpdate()
    }

    override fun disposeUIResources() {
        mySettingsComponent = null
    }
}

class EmbeddedTerminalSettingsComponent {
    val panel: JPanel
    private val shellPathField = JTextField()
    private val useEditorThemeCheckbox = JCheckBox(TerminalBundle.message("sync.font"))
    private val customFontFamilyField = ComboBox<String>().apply {
        val fonts = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames
        fonts.forEach { addItem(it) }
        isEditable = true
    }
    private val customFontSizeField = JTextField()
    private val customLineHeightField = JTextField()
    private val enableLigaturesCheckbox = JCheckBox(TerminalBundle.message("enable.ligatures"))
    private val autoCloseOnExitCheckbox = JCheckBox(TerminalBundle.message("auto.close"))
    private val historyLimitField = JTextField()
    private val showScrollBarCheckbox = JCheckBox(TerminalBundle.message("show.scrollbar"))
    private val smartPasteMinLinkSizeMbField = JTextField()

    // Color UI Elements
    private val colorSchemeComboBox = ComboBox<String>().apply {
        addItem("Editor Theme")
        TerminalColorScheme.PRESETS.forEach { addItem(it.name) }
        addItem("Custom")
    }
    private val foregroundColorPanel = ColorPanel()
    private val backgroundColorPanel = ColorPanel()
    private val selectionColorPanel = ColorPanel()
    private val cursorColorPanel = ColorPanel()
    private val opacitySlider = JSlider(0, 100).apply {
        majorTickSpacing = 20
        paintTicks = true
        paintLabels = true
    }
    private val opacityLabel = JLabel("100%")
    private val ansiColorPanels = Array(16) { ColorPanel() }

    var shellPathText: String
        get() = shellPathField.text.trim()
        set(value) { shellPathField.text = value }

    var useEditorThemeSelected: Boolean
        get() = useEditorThemeCheckbox.isSelected
        set(value) {
            useEditorThemeCheckbox.isSelected = value
            val notSynced = !value
            customFontFamilyField.isEnabled = notSynced
            customFontSizeField.isEnabled = notSynced
            customLineHeightField.isEnabled = notSynced
            enableLigaturesCheckbox.isEnabled = notSynced
        }

    var customFontFamilyVal: String
        get() = (customFontFamilyField.selectedItem as? String)?.trim() ?: ""
        set(value) { customFontFamilyField.selectedItem = value }

    var customFontSizeVal: Int
        get() = customFontSizeField.text.toIntOrNull() ?: 14
        set(value) { customFontSizeField.text = value.toString() }

    var customLineHeightVal: Double
        get() = customLineHeightField.text.toDoubleOrNull() ?: 1.0
        set(value) { customLineHeightField.text = value.toString() }

    var enableLigaturesSelected: Boolean
        get() = enableLigaturesCheckbox.isSelected
        set(value) { enableLigaturesCheckbox.isSelected = value }

    var autoCloseOnExitSelected: Boolean
        get() = autoCloseOnExitCheckbox.isSelected
        set(value) { autoCloseOnExitCheckbox.isSelected = value }

    var historyLimitVal: Int
        get() = historyLimitField.text.toIntOrNull()?.coerceAtLeast(0) ?: 1000
        set(value) { historyLimitField.text = value.toString() }

    var showScrollBarSelected: Boolean
        get() = showScrollBarCheckbox.isSelected
        set(value) { showScrollBarCheckbox.isSelected = value }

    var smartPasteMinLinkSizeMbVal: Int
        get() = smartPasteMinLinkSizeMbField.text.toIntOrNull()?.coerceAtLeast(1) ?: 50
        set(value) { smartPasteMinLinkSizeMbField.text = value.toString() }

    // Color Getters / Setters
    var colorSchemeNameVal: String
        get() = (colorSchemeComboBox.selectedItem as? String) ?: "Editor Theme"
        set(value) { colorSchemeComboBox.selectedItem = value }

    var customForegroundVal: String
        get() = TerminalColorScheme.toHex(foregroundColorPanel.selectedColor ?: Color.WHITE)
        set(value) { foregroundColorPanel.selectedColor = TerminalColorScheme.parseHexColor(value) }

    var customBackgroundVal: String
        get() = TerminalColorScheme.toHex(backgroundColorPanel.selectedColor ?: Color.BLACK)
        set(value) { backgroundColorPanel.selectedColor = TerminalColorScheme.parseHexColor(value) }

    var customSelectionVal: String
        get() = TerminalColorScheme.toHex(selectionColorPanel.selectedColor ?: Color.GRAY)
        set(value) { selectionColorPanel.selectedColor = TerminalColorScheme.parseHexColor(value) }

    var customCursorVal: String
        get() = TerminalColorScheme.toHex(cursorColorPanel.selectedColor ?: Color.WHITE)
        set(value) { cursorColorPanel.selectedColor = TerminalColorScheme.parseHexColor(value) }

    var backgroundOpacityVal: Int
        get() = opacitySlider.value
        set(value) {
            opacitySlider.value = value
            opacityLabel.text = "$value%"
        }

    var customAnsiColorsHexVal: String
        get() = ansiColorPanels.map { TerminalColorScheme.toHex(it.selectedColor ?: Color.WHITE) }.joinToString(";")
        set(value) {
            val colors = value.split(";")
            for (i in 0 until 16) {
                if (i < colors.size) {
                    ansiColorPanels[i].selectedColor = TerminalColorScheme.parseHexColor(colors[i])
                }
            }
        }

    init {
        useEditorThemeCheckbox.addActionListener {
            val notSynced = !useEditorThemeCheckbox.isSelected
            customFontFamilyField.isEnabled = notSynced
            customFontSizeField.isEnabled = notSynced
            customLineHeightField.isEnabled = notSynced
            enableLigaturesCheckbox.isEnabled = notSynced
        }

        colorSchemeComboBox.addActionListener {
            updateColorUi(colorSchemeNameVal)
        }

        val pickerListener = ActionListener {
            if (colorSchemeNameVal != "Custom") {
                colorSchemeNameVal = "Custom"
                updateColorUi("Custom")
            }
        }
        foregroundColorPanel.addActionListener(pickerListener)
        backgroundColorPanel.addActionListener(pickerListener)
        selectionColorPanel.addActionListener(pickerListener)
        cursorColorPanel.addActionListener(pickerListener)
        ansiColorPanels.forEach { it.addActionListener(pickerListener) }

        opacitySlider.addChangeListener {
            opacityLabel.text = "${opacitySlider.value}%"
        }

        // Layout the Color Pickers in a GridBagLayout Panel
        val customColorsPanel = JPanel(GridBagLayout()).apply {
            val c = GridBagConstraints()
            c.fill = GridBagConstraints.HORIZONTAL
            c.weightx = 0.5
            c.insets = JBUI.insets(4)

            c.gridx = 0; c.gridy = 0
            add(JLabel(TerminalBundle.message("foreground")), c)
            c.gridx = 1
            add(foregroundColorPanel, c)

            c.gridx = 0; c.gridy = 1
            add(JLabel(TerminalBundle.message("background")), c)
            c.gridx = 1
            add(backgroundColorPanel, c)

            c.gridx = 2; c.gridy = 0
            add(JLabel(TerminalBundle.message("selection")), c)
            c.gridx = 3
            add(selectionColorPanel, c)

            c.gridx = 2; c.gridy = 1
            add(JLabel(TerminalBundle.message("cursor")), c)
            c.gridx = 3
            add(cursorColorPanel, c)
        }

        // Layout the 16 ANSI colors in a 2x8 grid
        val ansiGrid = JPanel(GridLayout(2, 8, 4, 4)).apply {
            ansiColorPanels.forEach { add(it) }
        }

        val opacityPanel = JPanel(BorderLayout(8, 0)).apply {
            add(opacitySlider, BorderLayout.CENTER)
            add(opacityLabel, BorderLayout.EAST)
        }

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(TerminalBundle.message("shell.path"), shellPathField, 1, false)
            .addComponent(useEditorThemeCheckbox, 1)
            .addLabeledComponent(TerminalBundle.message("custom.font"), customFontFamilyField, 1, false)
            .addLabeledComponent(TerminalBundle.message("font.size"), customFontSizeField, 1, false)
            .addLabeledComponent(TerminalBundle.message("line.height"), customLineHeightField, 1, false)
            .addComponent(enableLigaturesCheckbox, 1)
            .addComponent(autoCloseOnExitCheckbox, 1)
            .addLabeledComponent(TerminalBundle.message("history.limit"), historyLimitField, 1, false)
            .addComponent(showScrollBarCheckbox, 1)
            .addLabeledComponent(TerminalBundle.message("smart.paste.min.size"), smartPasteMinLinkSizeMbField, 1, false)
            .addSeparator(8)
            .addLabeledComponent(TerminalBundle.message("color.scheme"), colorSchemeComboBox, 1, false)
            .addComponent(customColorsPanel, 4)
            .addLabeledComponent(TerminalBundle.message("bg.opacity"), opacityPanel, 4, false)
            .addLabeledComponent(TerminalBundle.message( "ansi.palette"), ansiGrid, 6, false)
            .addComponentFillVertically(JPanel(), 0)
            .panel
            
        // Initial setup update
        triggerColorUiUpdate()
    }

    fun triggerColorUiUpdate() {
        updateColorUi(colorSchemeNameVal)
    }

    private fun updateColorUi(schemeName: String) {
        val isCustom = schemeName == "Custom"

        foregroundColorPanel.isEnabled = isCustom
        backgroundColorPanel.isEnabled = isCustom
        selectionColorPanel.isEnabled = isCustom
        cursorColorPanel.isEnabled = isCustom
        ansiColorPanels.forEach { it.isEnabled = isCustom }

        val schemeColors = when (schemeName) {
            "Editor Theme" -> {
                val scheme = EditorColorsManager.getInstance().globalScheme
                val bg = scheme.defaultBackground ?: (if (com.intellij.util.ui.UIUtil.isUnderDarcula()) Color(23, 23, 23) else Color(255, 255, 255))
                val fg = scheme.defaultForeground ?: (if (com.intellij.util.ui.UIUtil.isUnderDarcula()) Color(248, 248, 242) else Color(0, 0, 0))
                val sel = Color(60, 120, 220, 100)
                val cur = if (bg.red + bg.green + bg.blue < 380) Color.WHITE else Color.BLACK
                val ansi = (0..15).map { getEditorAnsiColor(it, scheme) }
                PreviewColors(fg, bg, sel, cur, ansi)
            }
            "Custom" -> {
                val settings = EmbeddedTerminalSettings.getInstance().state
                val fg = TerminalColorScheme.parseHexColor(settings.customForeground)
                val bg = TerminalColorScheme.parseHexColor(settings.customBackground)
                val sel = TerminalColorScheme.parseHexColor(settings.customSelection)
                val cur = TerminalColorScheme.parseHexColor(settings.customCursor)
                val ansi = settings.customAnsiColorsHex.split(";").map { TerminalColorScheme.parseHexColor(it) }
                PreviewColors(fg, bg, sel, cur, ansi)
            }
            else -> {
                val preset = TerminalColorScheme.findByName(schemeName) ?: TerminalColorScheme.Dracula
                PreviewColors(
                    preset.foregroundColor,
                    preset.backgroundColor,
                    preset.selectionColor,
                    preset.cursorColor,
                    preset.ansiColors
                )
            }
        }

        foregroundColorPanel.selectedColor = schemeColors.fg
        backgroundColorPanel.selectedColor = schemeColors.bg
        selectionColorPanel.selectedColor = schemeColors.sel
        cursorColorPanel.selectedColor = schemeColors.cur

        for (i in 0 until 16) {
            if (i < schemeColors.ansi.size) {
                ansiColorPanels[i].selectedColor = schemeColors.ansi[i]
            }
        }
    }

    private fun getEditorAnsiColor(index: Int, scheme: com.intellij.openapi.editor.colors.EditorColorsScheme): Color {
        val key = when (index) {
            0 -> com.intellij.execution.process.ConsoleHighlighter.BLACK
            1 -> com.intellij.execution.process.ConsoleHighlighter.RED
            2 -> com.intellij.execution.process.ConsoleHighlighter.GREEN
            3 -> com.intellij.execution.process.ConsoleHighlighter.YELLOW
            4 -> com.intellij.execution.process.ConsoleHighlighter.BLUE
            5 -> com.intellij.execution.process.ConsoleHighlighter.MAGENTA
            6 -> com.intellij.execution.process.ConsoleHighlighter.CYAN
            7 -> com.intellij.execution.process.ConsoleHighlighter.GRAY
            8 -> com.intellij.execution.process.ConsoleHighlighter.DARKGRAY
            9 -> com.intellij.execution.process.ConsoleHighlighter.RED_BRIGHT
            10 -> com.intellij.execution.process.ConsoleHighlighter.GREEN_BRIGHT
            11 -> com.intellij.execution.process.ConsoleHighlighter.YELLOW_BRIGHT
            12 -> com.intellij.execution.process.ConsoleHighlighter.BLUE_BRIGHT
            13 -> com.intellij.execution.process.ConsoleHighlighter.MAGENTA_BRIGHT
            14 -> com.intellij.execution.process.ConsoleHighlighter.CYAN_BRIGHT
            15 -> com.intellij.execution.process.ConsoleHighlighter.WHITE
            else -> null
        }
        val attr = if (key != null) scheme.getAttributes(key) else null
        return attr?.foregroundColor ?: getDefaultAnsiColor(index)
    }

    private fun getDefaultAnsiColor(index: Int): Color {
        return when (index) {
            0 -> Color(0, 0, 0)
            1 -> Color(205, 0, 0)
            2 -> Color(0, 205, 0)
            3 -> Color(205, 205, 0)
            4 -> Color(0, 0, 238)
            5 -> Color(205, 0, 205)
            6 -> Color(0, 205, 205)
            7 -> Color(229, 229, 229)
            8 -> Color(127, 127, 127)
            9 -> Color(255, 0, 0)
            10 -> Color(0, 255, 0)
            11 -> Color(255, 255, 0)
            12 -> Color(92, 92, 255)
            13 -> Color(255, 0, 255)
            14 -> Color(0, 255, 255)
            15 -> Color(255, 255, 255)
            else -> Color.WHITE
        }
    }

    private data class PreviewColors(
        val fg: Color,
        val bg: Color,
        val sel: Color,
        val cur: Color,
        val ansi: List<Color>
    )
}
