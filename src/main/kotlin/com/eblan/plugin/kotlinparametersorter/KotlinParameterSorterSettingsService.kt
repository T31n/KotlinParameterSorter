package com.eblan.plugin.kotlinparametersorter

import com.intellij.openapi.components.*

@Service(Service.Level.APP)
@State(
    name = "KotlinParameterSorterSettings",
    storages = [Storage("kotlinParameterSorterSettings.xml")]
)
class KotlinParameterSorterSettingsService : PersistentStateComponent<KotlinParameterSorterSettings> {

    private var myState = KotlinParameterSorterSettings()

    // DO NOT CLONE HERE. The IDE needs the actual reference to save changes.
    override fun getState(): KotlinParameterSorterSettings = myState

    override fun loadState(state: KotlinParameterSorterSettings) {
        this.myState = state
    }

    companion object {
        @JvmStatic
        fun getInstance(): KotlinParameterSorterSettingsService =
            service<KotlinParameterSorterSettingsService>()
    }
}