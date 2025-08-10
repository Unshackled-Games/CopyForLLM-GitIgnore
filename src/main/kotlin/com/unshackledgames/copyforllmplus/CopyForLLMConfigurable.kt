package com.unshackledgames.copyforllmplus

import com.intellij.openapi.components.service
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import java.awt.GridLayout
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel

class CopyForLLMConfigurable : SearchableConfigurable {

    private val settings get() = service<CopyForLLMSettings>()

    private lateinit var root: JPanel

    private lateinit var rbInclude: JBRadioButton
    private lateinit var rbExclude: JBRadioButton

    private lateinit var includePanel: JPanel
    private lateinit var excludePanel: JPanel

    private lateinit var includeModel: CollectionListModel<String>
    private lateinit var excludeModel: CollectionListModel<String>

    override fun getId(): String = "preferences.copyForLLM"
    override fun getDisplayName(): String = "Copy for LLM"

    override fun createComponent(): JComponent {
        includeModel = CollectionListModel(settings.state.includePatterns.toMutableList())
        excludeModel = CollectionListModel(settings.state.excludePatterns.toMutableList())

        val includeList = JBList(includeModel).apply { selectionMode = ListSelectionModel.SINGLE_SELECTION }
        val excludeList = JBList(excludeModel).apply { selectionMode = ListSelectionModel.SINGLE_SELECTION }

        val includeDecorator = ToolbarDecorator.createDecorator(includeList)
            .setAddAction {
                val value = Messages.showInputDialog(
                    root, "Add include pattern (e.g., cs, .kt, *.md):", "Add Include", null
                ) ?: return@setAddAction
                includeModel.add(value.trim())
            }
            .setEditAction {
                val idx = includeList.selectedIndex
                if (idx >= 0) {
                    val old = includeModel.getElementAt(idx)
                    val value = Messages.showInputDialog(
                        root, "Edit include pattern:", "Edit Include", null, old, null
                    ) ?: return@setEditAction
                    includeModel.setElementAt(value.trim(), idx)
                }
            }
            .setRemoveAction {
                val idx = includeList.selectedIndex
                if (idx >= 0) includeModel.remove(idx)
            }
            .disableUpDownActions()

        val excludeDecorator = ToolbarDecorator.createDecorator(excludeList)
            .setAddAction {
                val value = Messages.showInputDialog(
                    root, "Add exclude pattern (e.g., png, *.meta):", "Add Exclude", null
                ) ?: return@setAddAction
                excludeModel.add(value.trim())
            }
            .setEditAction {
                val idx = excludeList.selectedIndex
                if (idx >= 0) {
                    val old = excludeModel.getElementAt(idx)
                    val value = Messages.showInputDialog(
                        root, "Edit exclude pattern:", "Edit Exclude", null, old, null
                    ) ?: return@setEditAction
                    excludeModel.setElementAt(value.trim(), idx)
                }
            }
            .setRemoveAction {
                val idx = excludeList.selectedIndex
                if (idx >= 0) excludeModel.remove(idx)
            }
            .disableUpDownActions()

        includePanel = JPanel(GridLayout(2, 1, 0, 4)).apply {
            add(JBLabel("Include list (allow only these)"))
            add(includeDecorator.createPanel())
        }
        excludePanel = JPanel(GridLayout(2, 1, 0, 4)).apply {
            add(JBLabel("Exclude list (filter these out)"))
            add(excludeDecorator.createPanel())
        }

        rbInclude = JBRadioButton("Use INCLUDE list (allowlist)")
        rbExclude = JBRadioButton("Use EXCLUDE list (denylist)")

        val group = ButtonGroup().apply {
            add(rbInclude); add(rbExclude)
        }

        fun applyEnabledState() {
            val includeMode = rbInclude.isSelected
            UIUtil.setEnabled(includePanel, includeMode, true)
            UIUtil.setEnabled(excludePanel, !includeMode, true)
        }
        rbInclude.addActionListener { applyEnabledState() }
        rbExclude.addActionListener { applyEnabledState() }

        val modeRow = JPanel(GridLayout(1, 2, 12, 0)).apply {
            add(rbInclude); add(rbExclude)
        }

        root = FormBuilder.createFormBuilder()
            .addComponent(modeRow, 0)
            .addComponent(JPanel(GridLayout(1, 2, 12, 0)).apply {
                add(includePanel)
                add(excludePanel)
            })
            .panel

        reset()
        return root!!
    }

    override fun isModified(): Boolean {
        val s = settings.state
        val editedIncludeMode = rbInclude.isSelected
        val editedInclude = includeModel.items.toList()
        val editedExclude = excludeModel.items.toList()
        return editedIncludeMode != s.includeMode ||
                editedInclude != s.includePatterns ||
                editedExclude != s.excludePatterns
    }

    override fun apply() {
        val s = settings.state
        s.includeMode = rbInclude.isSelected
        s.includePatterns.clear()
        s.includePatterns.addAll(includeModel.items.mapNotNull { it.trim().takeIf(String::isNotEmpty) })
        s.excludePatterns.clear()
        s.excludePatterns.addAll(excludeModel.items.mapNotNull { it.trim().takeIf(String::isNotEmpty) })
    }

    override fun reset() {
        val s = settings.state
        rbInclude.isSelected = s.includeMode
        rbExclude.isSelected = !s.includeMode

        includeModel.removeAll(); includeModel.add(s.includePatterns)
        excludeModel.removeAll(); excludeModel.add(s.excludePatterns)

        UIUtil.setEnabled(includePanel, s.includeMode, true)
        UIUtil.setEnabled(excludePanel, !s.includeMode, true)
    }

    override fun disposeUIResources() {
    }
}
