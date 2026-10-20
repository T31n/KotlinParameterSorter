package com.eblan.plugin.kps

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe

internal object AiApiKeyStore {
    private val credentials = CredentialAttributes("com.eblan.plugin.KotlinParameterSorter.aiApiKey")

    fun getApiKey(): String? = PasswordSafe.instance.getPassword(credentials)

    fun setApiKey(apiKey: String?) {
        PasswordSafe.instance.setPassword(credentials, apiKey)
    }
}
