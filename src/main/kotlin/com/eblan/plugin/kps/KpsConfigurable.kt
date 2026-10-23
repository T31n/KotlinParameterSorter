package com.eblan.plugin.kps

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JButton
import javax.swing.JComponent

class KpsConfigurable : Configurable {
    private var endpointField: JBTextField? = null
    private var modelField: JBTextField? = null
    private var systemPromptField: JBTextField? = null
    private var apiKeyField: JBPasswordField? = null

    override fun getDisplayName(): String = "Kotlin Parameter Sorter"

    override fun createComponent(): JComponent {
        val apiKeyField = JBPasswordField()
        this.apiKeyField = apiKeyField
        val clearMemoryButton = JButton("Clear all remembered arrangements").apply {
            addActionListener { KpsArrangementMemory.getInstance().clearAll() }
        }

        return panel {
            row("AI-compatible API endpoint:") {
                endpointField = textField().align(AlignX.FILL).component
            }
            row("AI model:") {
                modelField = textField().align(AlignX.FILL).component
            }
            row("AI system prompt:") {
                systemPromptField = textField().align(AlignX.FILL).component
            }
            row("API key:") {
                cell(apiKeyField).align(AlignX.FILL)
            }
            row {
                comment(
                    "AI requests include the function name and parameter metadata. Partial reuse also sends the remembered group names and parameter names as context; " +
                        "the function body and call sites are not sent. The API key is stored in the IDE Password Safe."
                )
            }
            row {
                cell(clearMemoryButton)
            }
            row {
                comment(
                    "Validated arrangements are stored locally per project and reused for matching parameters. " +
                        "Clearing removes remembered arrangements for all projects."
                )
            }
        }
    }

    override fun isModified(): Boolean {
        val settings = KpsPersistentStateComponent.getInstance().state

        return endpointField?.text != settings.aiEndpoint ||
            modelField?.text != settings.aiModel ||
            systemPromptField?.text != settings.aiSystemPrompt ||
            readApiKey() != (AiApiKeyStore.getApiKey() ?: "")
    }

    override fun apply() {
        val settings = KpsPersistentStateComponent.getInstance().state

        settings.aiEndpoint = endpointField?.text?.trim() ?: settings.aiEndpoint
        settings.aiModel = modelField?.text?.trim() ?: settings.aiModel
        settings.aiSystemPrompt = systemPromptField?.text?.trim() ?: settings.aiSystemPrompt
        endpointField?.text = settings.aiEndpoint
        modelField?.text = settings.aiModel
        systemPromptField?.text = settings.aiSystemPrompt
        val apiKey = readApiKey().trim()
        AiApiKeyStore.setApiKey(apiKey.ifEmpty { null })
        apiKeyField?.text = apiKey
    }

    override fun reset() {
        val settings = KpsPersistentStateComponent.getInstance().state

        endpointField?.text = settings.aiEndpoint
        modelField?.text = settings.aiModel
        systemPromptField?.text = settings.aiSystemPrompt
        apiKeyField?.text = AiApiKeyStore.getApiKey() ?: ""
    }

    override fun disposeUIResources() {
        endpointField = null
        modelField = null
        systemPromptField = null
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