package com.embedded.terminal

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.messages.Topic

interface EmbeddedTerminalSettingsListener {
    fun settingsChanged(state: EmbeddedTerminalSettings.State)
}

val EMBEDDED_TERMINAL_SETTINGS_CHANGED = Topic.create(
    "Embedded Terminal Settings Changed",
    EmbeddedTerminalSettingsListener::class.java
)

@State(
    name = "EmbeddedTerminalSettings",
    storages = [Storage("embeddedTerminalSettings.xml")]
)
class EmbeddedTerminalSettings : PersistentStateComponent<EmbeddedTerminalSettings.State> {
    class State {
        var shellPath: String = ""
        var useEditorTheme: Boolean = true
        var customFontFamily: String = "JetBrains Mono"
        var customFontSize: Int = 14
        var customLineHeight: Double = 1.0
        var enableLigatures: Boolean = true
        var autoCloseOnExit: Boolean = true
        var historyLimit: Int = 1000
        var showScrollBar: Boolean = true
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(): EmbeddedTerminalSettings {
            return ApplicationManager.getApplication().getService(EmbeddedTerminalSettings::class.java)
        }
    }
}

