package com.embedded.terminal

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.util.ui.FormBuilder
import java.awt.GraphicsEnvironment
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField

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
                component.showScrollBarSelected != settings.showScrollBar
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

        // Publicar el cambio en el message bus para actualizar las terminales activas
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
    }

    override fun disposeUIResources() {
        mySettingsComponent = null
    }
}

class EmbeddedTerminalSettingsComponent {
    val panel: JPanel
    private val shellPathField = JTextField()
    private val useEditorThemeCheckbox = JCheckBox("Sincronizar con el tema y fuente del editor")
    private val customFontFamilyField = ComboBox<String>().apply {
        val fonts = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames
        fonts.forEach { addItem(it) }
        isEditable = true
    }
    private val customFontSizeField = JTextField()
    private val customLineHeightField = JTextField()
    private val enableLigaturesCheckbox = JCheckBox("Habilitar ligaduras tipográficas (si están disponibles)")
    private val autoCloseOnExitCheckbox = JCheckBox("Cerrar pestaña automáticamente al salir del proceso del shell")
    private val historyLimitField = JTextField()
    private val showScrollBarCheckbox = JCheckBox("Mostrar barra de desplazamiento (Scrollbar)")

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

    init {
        useEditorThemeCheckbox.addActionListener {
            val notSynced = !useEditorThemeCheckbox.isSelected
            customFontFamilyField.isEnabled = notSynced
            customFontSizeField.isEnabled = notSynced
            customLineHeightField.isEnabled = notSynced
            enableLigaturesCheckbox.isEnabled = notSynced
        }

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Ruta del Shell (dejar vacío para usar el predeterminado):", shellPathField, 1, false)
            .addComponent(useEditorThemeCheckbox, 1)
            .addLabeledComponent("Tipografía personalizada (ej. JetBrains Mono, Fira Code):", customFontFamilyField, 1, false)
            .addLabeledComponent("Tamaño de fuente personalizado:", customFontSizeField, 1, false)
            .addLabeledComponent("Altura de línea personalizada (ej. 1.0):", customLineHeightField, 1, false)
            .addComponent(enableLigaturesCheckbox, 1)
            .addComponent(autoCloseOnExitCheckbox, 1)
            .addLabeledComponent("Límite de historial de líneas (ej. 1000):", historyLimitField, 1, false)
            .addComponent(showScrollBarCheckbox, 1)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }
}

