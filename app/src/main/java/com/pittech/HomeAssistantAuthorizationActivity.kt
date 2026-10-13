package com.pittech

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.pittech.devices.HomeAssistantEndpoint
import com.pittech.devices.HomeAssistantSession
import com.pittech.devices.TelemetryProfile
import kotlinx.coroutines.launch

/** Documented IndieAuth flow, intercepted locally before the authorization code reaches the client website. */
class HomeAssistantAuthorizationActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var completed = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val server = intent.getStringExtra("server") ?: return finish()
        val id = intent.getStringExtra("profile") ?: return finish()
        val endpoint = runCatching { HomeAssistantEndpoint(server) }.getOrNull() ?: return finish()
        // Public builds require HTTPS, including authorization. No public HTTP exception.
        if (!endpoint.base.startsWith("https://")) return finish()
        val nonce = savedInstanceState?.getString("state") ?: HomeAssistantSession.nonce()
        web = WebView(this).apply {
            isSaveEnabled = false
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                    val url = request.url.toString()
                    val code = runCatching { HomeAssistantSession.authorizationCode(url, nonce) }.getOrElse { finish(); return true }
                    if (code != null) {
                        if (!completed) {
                            completed = true
                            lifecycleScope.launch {
                                runCatching { HomeAssistantSession(this@HomeAssistantAuthorizationActivity, TelemetryProfile(id, "home_assistant", "Home Assistant", server)).authorize(code) }
                                    .onSuccess { setResult(RESULT_OK) }
                                finish()
                            }
                        }
                        return true
                    }
                    val uri = java.net.URI.create(url)
                    val expected = java.net.URI.create(endpoint.base)
                    return uri.scheme != expected.scheme || uri.host != expected.host || uri.port != expected.port
                }
                override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) { handler.cancel(); finish() }
            }
            tag = nonce
            loadUrl(HomeAssistantSession.authorizationUrl(server, nonce))
        }
        setContentView(web)
    }
    override fun onSaveInstanceState(outState: Bundle) { if (::web.isInitialized) outState.putString("state", web.tag as String); super.onSaveInstanceState(outState) }
    override fun onDestroy() { if (::web.isInitialized) { web.stopLoading(); web.destroy() }; super.onDestroy() }
}
