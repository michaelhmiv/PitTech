package com.pittech.online

import com.pittech.BuildConfig
import java.net.URI

/** Build-selected first-party endpoints. There is no editable server URL or entitlement override. */
internal data class OnlineConfiguration(val environment: String, val enabled: Boolean = false, val googleWebClientId: String = BuildConfig.PITTECH_GOOGLE_WEB_CLIENT_ID) {
    init { require(environment in setOf("production", "staging")) }
    val apiHost: String get() = if (environment == "staging") "api-staging.pittech.app" else "api.pittech.app"
    val publicHost: String get() = if (environment == "staging") "staging.pittech.app" else "pittech.app"
    val apiOrigin: String get() = "https://$apiHost"
    val publicOrigin: String get() = "https://$publicHost"
    val linkScheme: String get() = if (environment == "staging") "pittech-staging" else "pittech"
    val sandbox: Boolean get() = environment == "staging"
    /** Packages with a Google Cloud Android OAuth client for this deployment, per the shared
     * environments fixture. Staging covers Dev plus the registered identity the approved
     * store-sandbox build keeps; the debug package is a test-only vehicle on injected fakes. */
    val googlePackages: List<String> get() = if (sandbox) listOf("com.pittech.dev", "com.pittech") else listOf("com.pittech")
    /** No provider button ships in a build whose deployment cannot verify that provider's
     * tokens: the running package must hold an Android OAuth client for this environment. */
    fun advertisesGoogleSignIn(packageName: String = BuildConfig.APPLICATION_ID): Boolean = packageName in googlePackages
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
