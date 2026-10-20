package com.eblan.plugin.kps

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.listCellRenderer
import javax.swing.JComponent

class KpsConfigurable : Configurable {
    private var comboBox: ComboBox<ModifierPositionMode>? = null
    private var sortingModeComboBox: ComboBox<ParameterSortingMode>? = null
    private var endpointField: JBTextField? = null
    private var modelField: JBTextField? = null
    private var apiKeyField: JBPasswordField? = null

    override fun getDisplayName(): String = "Kotlin Parameter Sorter"

    override fun createComponent(): JComponent {
        val apiKeyField = JBPasswordField()
        this.apiKeyField = apiKeyField

        return panel {
            row("Parameter sorting mode:") {
                sortingModeComboBox = comboBox(
                    items = ParameterSortingMode.entries,
                    renderer = listCellRenderer {
                        text(
                            text = when (value) {
                                ParameterSortingMode.ALPHABETICAL -> "Alphabetical"
                                ParameterSortingMode.AI -> "AI (group related parameters)"
                                null -> "Alphabetical"
                            }
                        )
                    }
                ).component
            }
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
            row("AI-compatible API endpoint:") {
                endpointField = textField().align(AlignX.FILL).component
            }
            row("AI model:") {
                modelField = textField().align(AlignX.FILL).component
            }
            row("API key:") {
                cell(apiKeyField).align(AlignX.FILL)
            }
            row {
                comment(
                    "Uses an OpenAI-compatible chat completions API. The API key is stored in the IDE Password Safe; " +
                        "the function name, parameter names/types, and ordering flags are sent to the configured endpoint."
                )
            }
        }
    }

    override fun isModified(): Boolean {
        val settings = KpsPersistentStateComponent.getInstance().state

        return comboBox?.item != settings.modifierPositionMode ||
            sortingModeComboBox?.item != settings.sortingMode ||
            endpointField?.text != settings.aiEndpoint ||
            modelField?.text != settings.aiModel ||
            readApiKey() != (AiApiKeyStore.getApiKey() ?: "")
    }

    override fun apply() {
        val settings = KpsPersistentStateComponent.getInstance().state

        settings.modifierPositionMode = comboBox?.item ?: ModifierPositionMode.FIRST_OPTIONAL
        settings.sortingMode = sortingModeComboBox?.item ?: ParameterSortingMode.ALPHABETICAL
        settings.aiEndpoint = endpointField?.text?.trim() ?: settings.aiEndpoint
        settings.aiModel = modelField?.text?.trim() ?: settings.aiModel
        endpointField?.text = settings.aiEndpoint
        modelField?.text = settings.aiModel
        val apiKey = readApiKey().trim()
        AiApiKeyStore.setApiKey(apiKey.ifEmpty { null })
        apiKeyField?.text = apiKey
    }

    override fun reset() {
        val settings = KpsPersistentStateComponent.getInstance().state

        comboBox?.item = settings.modifierPositionMode
        sortingModeComboBox?.item = settings.sortingMode
        endpointField?.text = settings.aiEndpoint
        modelField?.text = settings.aiModel
        apiKeyField?.text = AiApiKeyStore.getApiKey() ?: ""
    }

    override fun disposeUIResources() {
        comboBox = null
        sortingModeComboBox = null
        endpointField = null
        modelField = null
        apiKeyField = null
    }

    private fun readApiKey(): String {
        val characters = apiKeyField?.password ?: return ""

        return try {
            String(characters)
        } finally {
            characters.fill('\u0000')
        }
    }
}