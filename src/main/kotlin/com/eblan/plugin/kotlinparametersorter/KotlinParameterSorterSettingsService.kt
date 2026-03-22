package com.eblan.plugin.kotlinparametersorter

import com.intellij.openapi.components.*

@Service(Service.Level.APP)
@State(
    name = "KotlinParameterSorterSettings",
    storages = [Storage("kotlinParameterSorterSettings.xml")]
)
class KotlinParameterSorterSettingsService : PersistentStateComponent<KotlinParameterSorterSettings> {

    private var state = KotlinParameterSorterSettings()

    override fun getState(): KotlinParameterSorterSettings = state.clone()

    override fun loadState(state: KotlinParameterSorterSettings) {
        this.state = state
    }

    companion object {
        @JvmStatic
        fun getInstance(): KotlinParameterSorterSettingsService =
            service<KotlinParameterSorterSettingsService>()
    }
}