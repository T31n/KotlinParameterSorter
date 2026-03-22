package com.eblan.plugin.kps

import com.intellij.openapi.components.*

@Service(Service.Level.APP)
@State(
    name = "KpsSettings",
    storages = [Storage("kpsSettings.xml")]
)
class KpsPersistentStateComponent : PersistentStateComponent<KpsSettings> {

    private var myState = KpsSettings()

    override fun getState(): KpsSettings = myState

    override fun loadState(state: KpsSettings) {
        this.myState = state
    }

    companion object {
        @JvmStatic
        fun getInstance(): KpsPersistentStateComponent =
            service<KpsPersistentStateComponent>()
    }
}