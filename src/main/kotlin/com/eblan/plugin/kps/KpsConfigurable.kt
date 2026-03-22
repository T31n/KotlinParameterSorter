package com.eblan.plugin.kps

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.listCellRenderer
import javax.swing.JComponent

class KpsConfigurable : Configurable {
    private var comboBox: ComboBox<ModifierPositionMode>? = null

    override fun getDisplayName(): String = "Kotlin Parameter Sorter"

    override fun createComponent(): JComponent {
        return panel {
            row("Modifier position in @Composable functions:") {
                comboBox = comboBox(
                    items = ModifierPositionMode.entries,
                    renderer = listCellRenderer {
                        text(
                            text = when (value) {
                                ModifierPositionMode.FIRST_PARAMETER -> "First parameter"
                                ModifierPositionMode.FIRST_OPTIONAL -> "First optional parameter (lint-recommended)"
                                else -> "None"
                            }
                        )
                    }
                ).component
            }
            row {
                comment("Choose how the sorter handles the 'modifier' parameter in composable functions.")
            }
        }
    }

    override fun isModified(): Boolean {
        val settings = KpsPersistentStateComponent.getInstance().state

        return comboBox?.item != settings.modifierPositionMode
    }

    override fun apply() {
        val settings = KpsPersistentStateComponent.getInstance().state

        settings.modifierPositionMode = comboBox?.item ?: ModifierPositionMode.FIRST_OPTIONAL
    }

    override fun reset() {
        val settings = KpsPersistentStateComponent.getInstance().state

        comboBox?.item = settings.modifierPositionMode
    }

    override fun disposeUIResources() {
        comboBox = null
    }
}