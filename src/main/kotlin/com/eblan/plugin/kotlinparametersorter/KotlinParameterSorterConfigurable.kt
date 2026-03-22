package com.eblan.plugin.kotlinparametersorter

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.listCellRenderer
import javax.swing.JComponent

class KotlinParameterSorterConfigurable : Configurable {

    private var modified = false
    private lateinit var modeCombo: ComboBox<ModifierPositionMode>

    override fun getDisplayName(): String = "Kotlin Parameter Sorter"

    override fun createComponent(): JComponent {
        return panel {
            row("Modifier position in @Composable functions:") {
                modeCombo = comboBox(
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
                ).applyToComponent {
                    addActionListener { modified = true }
                }.component
            }
            row {
                comment("Choose how the sorter handles the 'modifier' parameter in composable functions.")
            }
        }
    }

    override fun isModified(): Boolean = modified

    override fun apply() {
        val settings = KotlinParameterSorterSettingsService.getInstance().state
        settings.modifierPositionMode = modeCombo.item ?: ModifierPositionMode.FIRST_OPTIONAL
        modified = false
    }

    override fun reset() {
        val settings = KotlinParameterSorterSettingsService.getInstance().state
        modeCombo.item = settings.modifierPositionMode
        modified = false
    }

    override fun disposeUIResources() {
        // No heavy resources
    }
}