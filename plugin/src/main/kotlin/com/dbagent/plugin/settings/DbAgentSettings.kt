package com.dbagent.plugin.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * 插件本地设置：引擎地址 / NotesID 存 IDE 配置（dbagent.xml）；
 * Token 存 PasswordSafe（系统级凭据库，不落明文配置文件）。
 */
@Service(Service.Level.APP)
@State(name = "DbAgentSettings", storages = [Storage("dbagent.xml")])
class DbAgentSettings : PersistentStateComponent<DbAgentSettings.State> {

    class State {
        var engineUrl: String = "http://127.0.0.1:9090"
        var notesId: String = ""
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var token: String
        get() = PasswordSafe.instance.get(CREDENTIALS)?.getPasswordAsString() ?: ""
        set(value) {
            PasswordSafe.instance.set(
                CREDENTIALS,
                if (value.isBlank()) null else Credentials("", value)
            )
        }

    companion object {
        private val CREDENTIALS = CredentialAttributes(
            generateServiceName("DbAgent", "engineToken")
        )

        fun getInstance(): DbAgentSettings =
            ApplicationManager.getApplication().getService(DbAgentSettings::class.java)
    }
}
