package com.pittech

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pittech.billing.MemorySubscriptionCacheStore
import com.pittech.billing.ProBillingClient
import com.pittech.billing.ProPlacement
import com.pittech.billing.ProPurchaseOutcome
import com.pittech.billing.ProStoreProduct
import com.pittech.billing.ProStorePurchase
import com.pittech.billing.SubscriptionAPI
import com.pittech.billing.SubscriptionManager
import com.pittech.data.PreparedShareCard
import com.pittech.domain.InsightsCalculations
import com.pittech.domain.MemoryCustomizationBacking
import com.pittech.domain.ProCustomization
import com.pittech.domain.ProCustomizationStore
import com.pittech.domain.ProIconController
import com.pittech.domain.ProPersonalLibrary
import com.pittech.domain.ProRecap
import com.pittech.ui.PitTechTheme
import com.pittech.ui.ProGateDialog
import com.pittech.ui.ShareCardRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Slice 18 native lane: Pro personal analysis, advanced curves, recap
 * building, and customization on Android UI. Entitlement and the library are
 * scripted (the same fixture file as iOS); no store or server is contacted. */
@RunWith(AndroidJUnit4::class)
class ProPersonalTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class FakeBilling(
        override var productId: String = "stub.pro.monthly",
        var owned: List<ProStorePurchase> = emptyList(),
    ) : ProBillingClient {
        override var externalPurchases: ((ProStorePurchase) -> Unit)? = null

        override suspend fun queryProduct(): ProStoreProduct? {
            if (productId.isEmpty()) return null
            return ProStoreProduct(productId, "$3.00", isMonthly = true, offerToken = "stub-offer")
        }

        override suspend fun launchPurchase(activity: android.app.Activity?): ProPurchaseOutcome = ProPurchaseOutcome.Cancelled
        override suspend fun queryOwnedPurchases(): List<ProStorePurchase> = owned
        override suspend fun acknowledge(purchaseToken: String): Boolean = true
        override fun close() {}
    }

    private class FakeAPI(var entitlementState: String = "none") : SubscriptionAPI {
        private fun cache(state: String, verified: Boolean): JSONObject = JSONObject(
            """{"version":"entitlement-cache-v1","environment":"production","verified":$verified,"state":"$state","verifiedUntil":"2026-11-17T12:00:00Z","lastVerifiedAt":"2026-10-18T12:00:00Z","offlineValidUntil":"2026-10-25T12:00:00Z","contractVersion":"store-verification-v1"}""",
        )

        override suspend fun call(path: String, body: JSONObject?, access: String?): JSONObject {
            require(access != null)
            return when (path) {
                "/v1/me/entitlement" -> cache(entitlementState, entitlementState != "unknown")
                "/v1/me/entitlement/reconcile" -> cache(entitlementState, entitlementState != "unknown")
                "/v1/me/purchases/restore" -> JSONObject().put("outcomes", JSONArray().put(JSONObject().put("cache", cache("active", true)).put("applied", true).put("note", "stub-verified")))
                else -> throw IllegalStateException("Unexpected local test path")
            }
        }
    }

    private class FakeIcons(var fail: Boolean = false) : ProIconController {
        val applied = mutableListOf<String>()
        override fun apply(variantId: String): Boolean {
            applied.add(variantId)
            return !fail
        }
    }

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        scopes.clear()
    }

    private fun manager(api: FakeAPI = FakeAPI(), token: String? = "A".repeat(43)): SubscriptionManager {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scopes.add(scope)
        val manager = SubscriptionManager(FakeBilling(), scope, api, { token }, true, MemorySubscriptionCacheStore())
        runBlocking { manager.loadProduct(); manager.refresh() }
        return manager
    }

    private fun proLibrary(name: String): ProPersonalLibrary {
        val text = InstrumentationRegistry.getInstrumentation().targetContext.assets.open("pro-personal-v1.json").bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        assertEquals("pro-personal-v1", root.getString("contractVersion"))
        val entry = root.getJSONObject("libraries").getJSONObject(name).getJSONObject("archive")
        val archive = InsightsCalculations.archiveWithLifecycles(entry)
        val titlesJson = root.getJSONObject("titles")
        val titles = titlesJson.keys().asSequence().associateWith { titlesJson.getString(it) }
        return ProPersonalLibrary(archive, titles, root.getLong("asOfUtcMillis"))
    }

    private fun show(subscription: SubscriptionManager, library: ProPersonalLibrary, customization: ProCustomizationStore = ProCustomizationStore(MemoryCustomizationBacking())) {
        compose.setContent { PitTechTheme { ProGateDialog(subscription, true, onSignIn = {}, personal = library, customization = customization) } }
    }

    private fun assertNoProTag() {
        for (tag in listOf("pro-analysis", "pro-curves", "pro-customization", "pro-more-hub", "pro-recap-scope", "pro-recap-preview")) {
            compose.onAllNodesWithTag(tag).assertCountEquals(0)
        }
    }

    @Test fun entitledAnalysisShowsRealQuestions() {
        val subscription = manager(FakeAPI("active"))
        try {
            show(subscription, proLibrary("many"))
            subscription.openPro(ProPlacement.ANALYSIS)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-analysis").fetchSemanticsNodes().isNotEmpty() }
            for (line in listOf(
                "How your cooks of a cut compare",
                "Boston butt · 2 recorded cooks",
                "Saturday pork butt: heating 8h · 8 lb · Smoked · °F readings",
                "February pork butt: heating 7h · 3.5 kg · Pellet · °C readings",
                "Your best-rated recorded attempt",
                "You rated this 5 / 5",
                "Timing across recorded cuts and weights",
                "Boston butt: typical (median) 7h 30m across 2 recorded cooks · range 7h – 8h",
                "Wrap and rest differences",
                "Saturday pork butt: wrapped +3:20 after Food on · rested 50m · holding not recorded",
                "Whole packer brisket: wrap not recorded · rested 20m · holding not recorded",
                "Heating time recorded for 4 of 4 dishes · weights recorded for 3 of 4 dishes",
                "Wrap recorded for 2 of 4 dishes · rest recorded for 3 of 4 dishes · holding recorded for 1 of 4 dishes",
            )) {
                compose.onNodeWithText(line).performScrollTo().assertIsDisplayed()
            }
            compose.onAllNodesWithTag("pro-paywall").assertCountEquals(0)
            compose.onAllNodesWithTag("pro-subscribe").assertCountEquals(0)
            compose.onNodeWithTag("pro-close").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-analysis").fetchSemanticsNodes().isEmpty() }
        } finally {
            subscription.stop()
        }
    }

    @Test fun entitledCurvesShowAlignmentAndSourceSelection() {
        val subscription = manager(FakeAPI("active"))
        try {
            show(subscription, proLibrary("many"))
            subscription.openPro(ProPlacement.CURVES)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-curves").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("curve-channel-cook-a-probe-a-food--dish-a-food-controller-cloud").performScrollTo().performClick()
            compose.onNodeWithTag("curve-series-cook-a-probe-a-food--dish-a-food-controller-cloud").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("+1:00 · 150 °F").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("4 recorded points · anchor recorded · values in °F").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Wrap").performScrollTo().performClick()
            compose.onNodeWithText("-2:20 · 150 °F").performScrollTo().assertIsDisplayed()
            compose.onAllNodesWithTag("pro-paywall").assertCountEquals(0)
            compose.onNodeWithTag("pro-close").performClick()
        } finally {
            subscription.stop()
        }
    }

    @Test fun entitledThemesPreviewApplyPersists() {
        val subscription = manager(FakeAPI("active"))
        val customization = ProCustomizationStore(MemoryCustomizationBacking(), FakeIcons())
        try {
            show(subscription, proLibrary("many"), customization)
            subscription.openPro(ProPlacement.THEMES)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-customization").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("pro-theme-ember").performScrollTo().performClick()
            compose.onNodeWithTag("pro-theme-apply-ember").performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithTag("pro-theme-applied-ember", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            assertEquals("ember", customization.themeId)
            compose.onNodeWithTag("pro-palette-color-vision-safe").performScrollTo().performClick()
            compose.onNodeWithTag("pro-palette-apply-color-vision-safe").performScrollTo().performClick()
            compose.onNodeWithTag("pro-palette-applied-color-vision-safe", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            assertEquals("color-vision-safe", customization.paletteId)
            compose.onNodeWithTag("pro-icon-ember").performScrollTo().performClick()
            compose.onNodeWithTag("pro-icon-apply-ember").performScrollTo().performClick()
            compose.onNodeWithTag("pro-icon-applied-ember", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            assertEquals("ember", customization.iconId)
            compose.onNodeWithTag("pro-close").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-customization").fetchSemanticsNodes().isEmpty() }
            subscription.openPro(ProPlacement.THEMES)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-customization").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("pro-theme-applied-ember", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("pro-palette-applied-color-vision-safe", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("pro-close").performClick()
        } finally {
            subscription.stop()
        }
    }

    @Test fun recapBuilderPreviewsBeforeShare() {
        val subscription = manager(FakeAPI("active"))
        try {
            show(subscription, proLibrary("many"))
            subscription.openPro(ProPlacement.ANALYSIS)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-analysis").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("pro-recap-entry").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-recap-scope").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithTag("pro-recap-share").assertCountEquals(0)
            compose.onNodeWithText("2026").performScrollTo().performClick()
            compose.onAllNodesWithTag("pro-recap-cook").onFirst().performScrollTo().performClick()
            compose.onNodeWithTag("pro-recap-preview-button").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-recap-preview").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-recap-card").fetchSemanticsNodes().isNotEmpty() }
            // The share action appears only once rendering finishes; wait for it
            // before scrolling so the inserted card image cannot shift the
            // preview layout mid-scroll (large-text failure at font 1.5).
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-recap-share").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Year in Smoke · 2026").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("3 of 4 recorded cooks selected · 4 recorded milestones", substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("nothing here changes what is public", substring = true).performScrollTo().assertIsDisplayed()
            // The share action exists only on the preview, and is never tapped here.
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-recap-share").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("pro-recap-back").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-recap-scope").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("pro-close").performClick()
        } finally {
            subscription.stop()
        }
    }

    @Test fun emptyLibraryShowsHonestEmpty() {
        val subscription = manager(FakeAPI("active"))
        try {
            show(subscription, ProPersonalLibrary.EMPTY)
            subscription.openPro(ProPlacement.ANALYSIS)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-analysis").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("pro-analysis-empty").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("No recorded cooks yet").performScrollTo().assertIsDisplayed()
            compose.onAllNodesWithTag("pro-paywall").assertCountEquals(0)
            compose.onNodeWithTag("pro-close").performClick()
        } finally {
            subscription.stop()
        }
    }

    @Test fun nonProShowsPaywallWithoutProContent() {
        val subscription = manager()
        try {
            show(subscription, proLibrary("many"))
            subscription.openPro(ProPlacement.ANALYSIS)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-paywall").fetchSemanticsNodes().isNotEmpty() }
            assertNoProTag()
            compose.onNodeWithTag("pro-paywall-close").performClick()
        } finally {
            subscription.stop()
        }
    }

    @Test fun unknownEntitlementShowsNeutralLoadingWithNoPaywallFlash() {
        val subscription = manager(FakeAPI("unknown"))
        try {
            show(subscription, proLibrary("many"))
            subscription.openPro(ProPlacement.CURVES)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-loading").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithTag("pro-paywall").assertCountEquals(0)
            assertNoProTag()
            compose.onNodeWithTag("pro-close").performClick()
        } finally {
            subscription.stop()
        }
    }

    @Test fun expiredShowsFreeFallback() {
        val subscription = manager(FakeAPI("expired"))
        try {
            show(subscription, proLibrary("many"))
            subscription.openPro(ProPlacement.CURVES)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-expired-fallback").fetchSemanticsNodes().isNotEmpty() }
            assertNoProTag()
            compose.onNodeWithText("nothing was deleted", substring = true, ignoreCase = true).assertTextContains("nothing was deleted", substring = true, ignoreCase = true)
            compose.onNodeWithTag("pro-close").performClick()
        } finally {
            subscription.stop()
        }
    }

    @Test fun themedRecapRenderUsesProThemeAndPhotoFreeLongText() {
        val library = proLibrary("many")
        val report = InsightsCalculations.compute(library.archive, library.asOfUtcMillis)
        val longNote = "A".repeat(2000)
        val card = checkNotNull(ProRecap.build(report, library.titles, ProRecap.Scope("2026", listOf("cook-a", "cook-b", "cook-c", "cook-d"), true, true, true, longNote), 4))
        val content = ProRecap.shareCardContent(card, "ember", "color-vision-safe").toString()
        val bytes = ShareCardRenderer.render(PreparedShareCard(content, null))
        val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        try {
            assertEquals(1080, image.width)
            assertEquals(1350, image.height)
            assertEquals("the Ember background must paint", Color.rgb(251, 243, 232), image.getPixel(0, 0))
        } finally {
            image.recycle()
        }
        // Unknown theme and palette ids fail safe to the Classic treatment.
        val fallback = ShareCardRenderer.themeColors("nope", "nope")
        assertEquals(Color.rgb(245, 240, 231), fallback.background)
        assertEquals(6, fallback.palette.size)
        val classic = ShareCardRenderer.themeColors(null, null)
        assertEquals(Color.rgb(245, 240, 231), classic.background)
        val night = ShareCardRenderer.themeColors("sage-night", null)
        assertEquals(Color.rgb(35, 38, 31), night.background)
        assertEquals(6, night.palette.size)
    }

    @Test fun iconApplyPersistsSelectionAndUsesPlatformMechanism() {
        val icons = FakeIcons()
        val customization = ProCustomizationStore(MemoryCustomizationBacking(), icons)
        assertNull(customization.applyIcon("midnight"))
        assertEquals("midnight", customization.iconId)
        assertEquals(listOf("midnight"), icons.applied)
        assertEquals("ProIconMidnight", ProCustomization.icon("midnight").aliasSuffix)
        icons.fail = true
        val notice = customization.applyIcon("ember")
        assertTrue(checkNotNull(notice).contains("isn't available"))
        assertEquals("the stored selection is retained when the platform call fails", "ember", customization.iconId)
    }
}
