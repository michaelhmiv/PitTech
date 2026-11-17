package com.pittech.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #322: the taxonomy is the privacy boundary for GA4. These tests pin
 * the exact event/screen/method vocabulary and prove the sanitizer drops
 * anything outside it — PII-shaped params can never reach the SDK.
 */
class PitTechAnalyticsTest {

    @Test
    fun taxonomyContainsExactlyTheFiveCoreLoopEvents() {
        assertEquals(
            setOf("cook_started", "cook_completed", "cook_shared", "recipe_created", "sign_in"),
            AnalyticsTaxonomy.EVENT_NAMES,
        )
    }

    @Test
    fun screenNamesMatchTheBriefSurfaces() {
        assertEquals(
            listOf("Home", "Live board", "Timeline", "History", "Recipes", "Settings"),
            AnalyticsScreen.entries.map { it.analyticsName },
        )
    }

    @Test
    fun signInMethodsAreTheSmallEnumOnly() {
        assertEquals(
            listOf("email", "google", "apple"),
            AnalyticsSignInMethod.entries.map { it.analyticsValue },
        )
    }

    @Test
    fun sanitizerKeepsOnlyTheAllowedMethodParamForSignIn() {
        val clean = AnalyticsTaxonomy.sanitizeParams(
            AnalyticsTaxonomy.EVENT_SIGN_IN,
            mapOf(AnalyticsTaxonomy.PARAM_METHOD to "google"),
        )
        assertEquals(mapOf("method" to "google"), clean)
    }

    @Test
    fun sanitizerDropsPiiShapedParamsEvenForKnownEvents() {
        val clean = AnalyticsTaxonomy.sanitizeParams(
            AnalyticsTaxonomy.EVENT_SIGN_IN,
            mapOf(
                AnalyticsTaxonomy.PARAM_METHOD to "email",
                "email" to "someone@example.com",
                "cook_title" to "Saturday brisket",
                "notes" to "wrapped at 165",
                "user_id" to "account-123",
            ),
        )
        assertEquals(mapOf("method" to "email"), clean)
    }

    @Test
    fun sanitizerDropsOutOfEnumMethodValues() {
        val clean = AnalyticsTaxonomy.sanitizeParams(
            AnalyticsTaxonomy.EVENT_SIGN_IN,
            mapOf(AnalyticsTaxonomy.PARAM_METHOD to "carrier-pigeon"),
        )
        assertTrue(clean.isEmpty())
    }

    @Test
    fun sanitizerSendsNoParamsForParamlessEvents() {
        val clean = AnalyticsTaxonomy.sanitizeParams(
            AnalyticsTaxonomy.EVENT_COOK_STARTED,
            mapOf("method" to "email", "anything" to 42),
        )
        assertTrue(clean.isEmpty())
    }

    @Test
    fun sanitizerDropsNullAndUnsupportedValues() {
        val clean = AnalyticsTaxonomy.sanitizeParams(
            AnalyticsTaxonomy.EVENT_SIGN_IN,
            mapOf(AnalyticsTaxonomy.PARAM_METHOD to null, "method2" to listOf("x")),
        )
        assertTrue(clean.isEmpty())
    }

    @Test
    fun unknownEventsAreNotKnown() {
        assertFalse(AnalyticsTaxonomy.isKnownEvent("cook_deleted"))
        assertFalse(AnalyticsTaxonomy.isKnownEvent("screen_view"))
        assertTrue(AnalyticsTaxonomy.isKnownEvent(AnalyticsTaxonomy.EVENT_COOK_SHARED))
    }

    private class RecordingSink : AnalyticsSink {
        val events = mutableListOf<Pair<String, Map<String, Any>>>()
        val screens = mutableListOf<String>()
        override fun logEvent(name: String, params: Map<String, Any>) {
            events += name to params
        }
        override fun logScreenView(screenName: String) {
            screens += screenName
        }
    }

    @Test
    fun wrapperForwardsSanitizedEventsAndScreensToTheSink() {
        val recording = RecordingSink()
        val previous = PitTechAnalytics.installSinkForTests(recording)
        try {
            PitTechAnalytics.cookStarted()
            PitTechAnalytics.cookCompleted()
            PitTechAnalytics.cookShared()
            PitTechAnalytics.recipeCreated()
            PitTechAnalytics.signIn(AnalyticsSignInMethod.APPLE)
            PitTechAnalytics.screenView(AnalyticsScreen.LIVE_BOARD)
        } finally {
            PitTechAnalytics.installSinkForTests(previous)
        }
        assertEquals(
            listOf("cook_started", "cook_completed", "cook_shared", "recipe_created", "sign_in"),
            recording.events.map { it.first },
        )
        assertEquals(mapOf("method" to "apple"), recording.events.last().second)
        assertTrue(recording.events.dropLast(1).all { it.second.isEmpty() })
        assertEquals(listOf("Live board"), recording.screens)
    }

    @Test
    fun noOpSinkAcceptsEverythingSilently() {
        // The unconfigured-build path: calls land on the no-op sink and the
        // wrapper's total-function contract means nothing can throw.
        PitTechAnalytics.cookStarted()
        PitTechAnalytics.signIn(AnalyticsSignInMethod.EMAIL)
        PitTechAnalytics.screenView(AnalyticsScreen.HOME)
    }
}
