package com.unshackledgames.copyforllmplus

import com.intellij.openapi.options.SearchableConfigurable
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout

class CopyForLLMConfigurable : SearchableConfigurable {
    private var panel: JPanel? = null
    private var textField: JBTextField? = null

    override fun getId(): String = "preferences.copyForLLM"
    override fun getDisplayName(): String = "Copy for LLM"

    override fun createComponent(): JComponent {
        panel = JPanel(BorderLayout(0, 5))
        panel!!.add(JLabel("Exclude file extensions (comma-separated):"), BorderLayout.NORTH)
        textField = JBTextField().apply {
            text = CopyForLLMSettings.instance.state.excludedExtensions
        }
        panel!!.add(textField, BorderLayout.CENTER)
        return panel!!
    }

    override fun isModified(): Boolean {
        val current = CopyForLLMSettings.instance.state.excludedExtensions
        val edited  = textField!!.text
        return current != edited
    }

    override fun apply() {
        CopyForLLMSettings.instance.loadState(
            CopyForLLMSettings.State(textField!!.text)
        )
    }

    override fun reset() {
        textField!!.text = CopyForLLMSettings.instance.state.excludedExtensions
    }

    override fun disposeUIResources() {
        panel = null
        textField = null
    }
}