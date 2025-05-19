package com.unshackledgames.copyforllmplus


import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.openapi.components.Service

@State(
    name = "CopyForLLMPlusSettings",
    storages = [Storage("copyforllmplus.xml")]
)
@Service(Service.Level.APP)
class CopyForLLMSettings : PersistentStateComponent<CopyForLLMSettings.State> {
    data class State(var excludedExtensions: String = "")

    private var myState = State()

    companion object {
        @JvmStatic
        val instance: CopyForLLMSettings
            get() = ServiceManager.getService(CopyForLLMSettings::class.java)
    }

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.myState)
    }

    /** Returns `["java", "log"]` for “java, .log , txt” */
    fun getExcludedExtensionsList(): List<String> =
        myState.excludedExtensions
            .split(',')
            .map { it.trim().removePrefix(".").lowercase() }
            .filter { it.isNotBlank() }
}