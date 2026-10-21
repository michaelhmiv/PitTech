package com.pittech.online

import com.pittech.BuildConfig
import java.net.URI

/** Build-selected first-party endpoints. There is no editable server URL or entitlement override. */
internal data class OnlineConfiguration(val environment: String, val enabled: Boolean = false) {
    init { require(environment in setOf("production", "staging")) }
    val apiHost: String get() = if (environment == "staging") "api-staging.pittech.app" else "api.pittech.app"
    val publicHost: String get() = if (environment == "staging") "staging.pittech.app" else "pittech.app"
    val apiOrigin: String get() = "https://$apiHost"
    val publicOrigin: String get() = "https://$publicHost"
    val linkScheme: String get() = if (environment == "staging") "pittech-staging" else "pittech"
    val sandbox: Boolean get() = environment == "staging"
    // Retain existing production credentials and retry files exactly where they are.
    fun scopedName(productionName: String): String = if (sandbox) "$productionName-staging" else productionName
    fun acceptsApi(input: String): Boolean = runCatching {
        val uri = URI(input)
        uri.scheme == "https" && uri.rawAuthority == apiHost && uri.port == -1 &&
            uri.rawUserInfo == null && uri.rawFragment == null
    }.getOrDefault(false)

    companion object {
        val current = OnlineConfiguration(BuildConfig.PITTECH_ONLINE_ENVIRONMENT, BuildConfig.PITTECH_ONLINE_ENABLED)
    }
}
