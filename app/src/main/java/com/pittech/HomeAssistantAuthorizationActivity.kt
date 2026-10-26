package com.pittech

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.pittech.devices.HomeAssistantEndpoint
import com.pittech.devices.HomeAssistantSession

/**
 * Documented IndieAuth flow, intercepted locally before the authorization
 * code reaches the client website.
 *
 * PL-06 (#172): this activity returns ONLY the authorization code — the one
 * token exchange happens in TelemetryRuntime.connectHomeAssistant. It has a
 * visible Cancel and progress, foreign navigations are blocked and surfaced
 * in place (never a silent finish), and SSL / state failures return an
 * error result the caller renders in its sheet with the server URL and the
 * form intact.
 */
class HomeAssistantAuthorizationActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var notice: TextView
    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val server = intent.getStringExtra("server")
        val id = intent.getStringExtra("profile")
        val endpoint = server?.let { runCatching { HomeAssistantEndpoint(it) }.getOrNull() }
        if (server == null || id == null || endpoint == null) {
            fail("Home Assistant sign-in could not start. Check the server URL and try again.")
            return
        }
        // Public builds require HTTPS, including authorization. No public HTTP exception.
        if (!endpoint.base.startsWith("https://")) {
            fail("Home Assistant sign-in needs an HTTPS server URL. Server: ${endpoint.base}")
            return
        }
        val nonce = savedInstanceState?.getString("state") ?: HomeAssistantSession.nonce()

        val title = TextView(this).apply {
            text = "Sign in to Home Assistant"
            textSize = 18f
            setPadding(32, 24, 16, 24)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val cancel = Button(this).apply {
            text = "Cancel"
            contentDescription = "Cancel Home Assistant sign-in"
            setOnClickListener { setResult(RESULT_CANCELED); finish() }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title); addView(cancel)
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        notice = TextView(this).apply {
            visibility = View.GONE
            setTextColor(Color.rgb(176, 0, 32))
            setPadding(32, 16, 32, 16)
        }
        web = WebView(this).apply {
            isSaveEnabled = false
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) { progressBar.visibility = View.VISIBLE }
                override fun onPageFinished(view: WebView, url: String?) { progressBar.visibility = View.GONE }
                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                    val url = request.url.toString()
                    val code = runCatching { HomeAssistantSession.authorizationCode(url, nonce) }.getOrElse {
                        fail("Home Assistant sign-in failed: the response did not match this sign-in request. Server: ${endpoint.base}")
                        return true
                    }
                    if (code != null) {
                        if (!completed) {
                            completed = true
                            // The code ONLY — the caller exchanges it exactly once.
                            setResult(RESULT_OK, Intent().putExtra(EXTRA_CODE, code))
                            finish()
                        }
                        return true
                    }
                    val uri = runCatching { java.net.URI.create(url) }.getOrNull() ?: return true
                    val expected = java.net.URI.create(endpoint.base)
                    val foreign = uri.scheme != expected.scheme || uri.host != expected.host || uri.port != expected.port
                    if (foreign) {
                        // Blocked, surfaced, and the flow stays alive on the
                        // server — never a silent finish or a lost form.
                        notice.text = "This sign-in only continues on your Home Assistant server. Navigation to ${uri.host ?: url} was blocked."
                        notice.visibility = View.VISIBLE
                    }
                    return foreign
                }
                override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
                    handler.cancel()
                    fail("Home Assistant sign-in failed: the server's certificate could not be verified. Server: ${endpoint.base}")
                }
            }
            tag = nonce
            loadUrl(HomeAssistantSession.authorizationUrl(server, nonce))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header); addView(progressBar); addView(notice)
            addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
    }

    private fun fail(message: String) {
        setResult(RESULT_ERROR, Intent().putExtra(EXTRA_ERROR, message))
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) { if (::web.isInitialized) outState.putString("state", web.tag as String); super.onSaveInstanceState(outState) }
    override fun onDestroy() { if (::web.isInitialized) { web.stopLoading(); web.destroy() }; super.onDestroy() }

    companion object {
        const val EXTRA_CODE = "com.pittech.extra.HA_CODE"
        const val EXTRA_ERROR = "com.pittech.extra.HA_ERROR"
        const val RESULT_ERROR = RESULT_FIRST_USER
    }
}
