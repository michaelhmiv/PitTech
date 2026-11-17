package com.pittech.analytics

import android.content.Context
import android.os.Bundle
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * Product analytics — GA4 via Firebase Analytics (issue #322). This object is
 * the ONE owner of product analytics for the Android app, mirroring the
 * CyberCache wrapper (CyberCache PR #322): every screen and service reports
 * through the small typed functions below and never touches the Firebase SDK
 * directly.
 *
 * What this feeds: the GA4 property linked to the PitTech Firebase project
 * (`pittech-529a1`) — its standard reports (active users DAU/WAU/MAU,
 * retention cohorts, engagement) come from the SDK's automatic collection;
 * this wrapper adds the app's own vocabulary on top: screen views for the
 * main surfaces and one custom event per core-loop verb.
 *
 * PRIVACY LAW (do not break):
 *   - No PII, ever: no emails, no cook titles, no notes text, no photos, no
 *     raw user/account ids in event params. Firebase's own pseudonymous
 *     app-instance id is the only identity GA4 sees.
 *   - Params are content ids and small enums only, enforced by
 *     [AnalyticsTaxonomy]'s allowlist sanitizer — a param that is not in the
 *     taxonomy cannot reach the SDK even if a future caller tries.
 *   - Analytics is never a gate: a failed or absent analytics config must
 *     never break, block, or delay cooking. Every public function here is
 *     fire-and-forget and total: it cannot throw into a caller.
 *
 * CONFIG: until the real Firebase client config is installed
 * (app/google-services.json, Phase B console work) the build carries no
 * generated `google_app_id` resource, [initialize] leaves the no-op sink in
 * place, and analytics is a silent no-op: nothing is faked, no placeholder
 * id is shipped. The manifest removes FirebaseInitProvider so the SDK never
 * auto-initializes (and never crashes an unconfigured build); [initialize]
 * performs the manual initialization only when the resource exists.
 */

/** The main surfaces that report a screen view. */
enum class AnalyticsScreen(val analyticsName: String) {
    HOME("Home"),
    LIVE_BOARD("Live board"),
    TIMELINE("Timeline"),
    HISTORY("History"),
    RECIPES("Recipes"),
    SETTINGS("Settings"),
}

/** Sign-in methods for the `sign_in` event — a small enum, never free text. */
enum class AnalyticsSignInMethod(val analyticsValue: String) {
    EMAIL("email"),
    GOOGLE("google"),
    APPLE("apple"),
}

/**
 * The event taxonomy: the ONLY custom events and params analytics may send.
 * Adding an event means adding it here, adding a typed function on
 * [PitTechAnalytics], and calling it from the ONE place the action settles —
 * never from a render path, never speculatively before the action succeeds.
 */
object AnalyticsTaxonomy {
    const val EVENT_COOK_STARTED = "cook_started"
    const val EVENT_COOK_COMPLETED = "cook_completed"
    const val EVENT_COOK_SHARED = "cook_shared"
    const val EVENT_RECIPE_CREATED = "recipe_created"
    const val EVENT_SIGN_IN = "sign_in"

    const val PARAM_METHOD = "method"

    val EVENT_NAMES: Set<String> = setOf(
        EVENT_COOK_STARTED,
        EVENT_COOK_COMPLETED,
        EVENT_COOK_SHARED,
        EVENT_RECIPE_CREATED,
        EVENT_SIGN_IN,
    )

    /** Per-event param allowlist. An event not listed sends no params. */
    private val ALLOWED_PARAMS: Map<String, Set<String>> = mapOf(
        EVENT_SIGN_IN to setOf(PARAM_METHOD),
    )

    private val SIGN_IN_METHODS: Set<String> = AnalyticsSignInMethod.entries.map { it.analyticsValue }.toSet()

    /** GA4 caps string param values at 100 characters. */
    private const val MAX_PARAM_VALUE_LENGTH = 100

    fun isKnownEvent(name: String): Boolean = name in EVENT_NAMES

    /**
     * Keep only the params the taxonomy allows for [event], with GA4-safe
     * scalar values. Unknown keys, nulls, unsupported types and out-of-enum
     * values are dropped — this is the enforcement point for the no-PII law.
     */
    fun sanitizeParams(event: String, params: Map<String, Any?>): Map<String, Any> {
        val allowed = ALLOWED_PARAMS[event] ?: return emptyMap()
        if (params.isEmpty()) return emptyMap()
        val clean = LinkedHashMap<String, Any>()
        for ((key, value) in params) {
            if (key !in allowed || value == null) continue
            when (value) {
                is String -> {
                    if (event == EVENT_SIGN_IN && key == PARAM_METHOD && value !in SIGN_IN_METHODS) continue
                    clean[key] = value.take(MAX_PARAM_VALUE_LENGTH)
                }
                is Int -> clean[key] = value
                is Long -> clean[key] = value
                is Double -> clean[key] = value
                is Boolean -> clean[key] = value.toString()
                else -> Unit // Unsupported types never reach the SDK.
            }
        }
        return clean
    }
}

/** Where sanitized events go. The Firebase sink exists only when configured. */
internal interface AnalyticsSink {
    fun logEvent(name: String, params: Map<String, Any>)
    fun logScreenView(screenName: String)
}

internal object NoOpAnalyticsSink : AnalyticsSink {
    override fun logEvent(name: String, params: Map<String, Any>) = Unit
    override fun logScreenView(screenName: String) = Unit
}

internal class FirebaseAnalyticsSink(private val analytics: FirebaseAnalytics) : AnalyticsSink {
    override fun logEvent(name: String, params: Map<String, Any>) {
        analytics.logEvent(name, params.toBundle())
    }

    override fun logScreenView(screenName: String) {
        analytics.logEvent(
            FirebaseAnalytics.Event.SCREEN_VIEW,
            Bundle().apply {
                putString(FirebaseAnalytics.Param.SCREEN_NAME, screenName)
                putString(FirebaseAnalytics.Param.SCREEN_CLASS, "PitTechApp")
            },
        )
    }

    private fun Map<String, Any>.toBundle(): Bundle = Bundle().apply {
        for ((key, value) in this@toBundle) {
            when (value) {
                is String -> putString(key, value)
                is Int -> putLong(key, value.toLong())
                is Long -> putLong(key, value)
                is Double -> putDouble(key, value)
                is Boolean -> putString(key, value.toString())
            }
        }
    }
}

object PitTechAnalytics {
    @Volatile
    private var sink: AnalyticsSink = NoOpAnalyticsSink

    @Volatile
    private var initializeAttempted = false

    /**
     * One-time setup from [com.pittech.PitTechApplication]. Initializes
     * Firebase manually ONLY when the google-services resources exist in the
     * build; otherwise analytics stays a silent no-op. Never throws.
     */
    fun initialize(context: Context) {
        if (initializeAttempted) return
        initializeAttempted = true
        sink = runCatching {
            val appContext = context.applicationContext
            val appIdResource = appContext.resources.getIdentifier("google_app_id", "string", appContext.packageName)
            if (appIdResource == 0 || appContext.getString(appIdResource).isBlank()) {
                return@runCatching NoOpAnalyticsSink
            }
            FirebaseApp.initializeApp(appContext) ?: return@runCatching NoOpAnalyticsSink
            FirebaseAnalyticsSink(FirebaseAnalytics.getInstance(appContext))
        }.getOrDefault(NoOpAnalyticsSink)
    }

    /** Test seam: swap the sink, returning the previous one for restore. */
    internal fun installSinkForTests(replacement: AnalyticsSink): AnalyticsSink {
        val previous = sink
        sink = replacement
        return previous
    }

    fun screenView(screen: AnalyticsScreen) {
        runCatching { sink.logScreenView(screen.analyticsName) }
    }

    fun cookStarted() = logEvent(AnalyticsTaxonomy.EVENT_COOK_STARTED, emptyMap())

    fun cookCompleted() = logEvent(AnalyticsTaxonomy.EVENT_COOK_COMPLETED, emptyMap())

    /** A relay share code was created — the cook can now be followed by link. */
    fun cookShared() = logEvent(AnalyticsTaxonomy.EVENT_COOK_SHARED, emptyMap())

    fun recipeCreated() = logEvent(AnalyticsTaxonomy.EVENT_RECIPE_CREATED, emptyMap())

    fun signIn(method: AnalyticsSignInMethod) =
        logEvent(AnalyticsTaxonomy.EVENT_SIGN_IN, mapOf(AnalyticsTaxonomy.PARAM_METHOD to method.analyticsValue))

    private fun logEvent(name: String, params: Map<String, Any>) {
        runCatching {
            if (!AnalyticsTaxonomy.isKnownEvent(name)) return@runCatching
            sink.logEvent(name, AnalyticsTaxonomy.sanitizeParams(name, params))
        }
    }
}
