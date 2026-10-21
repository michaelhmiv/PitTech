package com.pittech.online

import com.pittech.BuildConfig
import com.pittech.data.AccountProtocol
import com.pittech.data.PublicLink
import com.pittech.data.RelayProtocol
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OnlineConfigurationTest {
    private fun fixture(): JSONObject = JSONObject(checkNotNull(javaClass.classLoader!!.getResource("online-environments-v1.json")).readText())

    @Test fun sharedProfilesKeepOriginsLinksAndStoredRetriesInTheSameEnvironment() {
        val rows = fixture().getJSONArray("profiles")
        val id = "12345678-1234-4234-8234-123456789abc"
        val token = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        val code = "0123-4567-89AB-CDEF"
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val config = OnlineConfiguration(row.getString("environment"))
            assertFalse(config.enabled)
            assertEquals(row.getString("apiOrigin"), config.apiOrigin)
            assertEquals(row.getString("publicOrigin"), config.publicOrigin)
            assertEquals(row.getString("publicHost"), config.publicHost)
            assertEquals(row.getString("linkScheme"), config.linkScheme)
            assertEquals(row.getString("scopedName"), config.scopedName("saved-retry-v1"))
            assertEquals(row.getBoolean("sandbox"), config.sandbox)
            assertTrue(config.acceptsApi(config.apiOrigin + "/v1/me"))
            assertEquals(id, AccountProtocol.link(config.publicOrigin + "/auth#challenge=$id&token=$token", config).first)
            assertEquals(id, PublicLink.destination(config.publicOrigin + "/c/$id", config))
            assertEquals("profile:chef", PublicLink.destination(config.linkScheme + "://profile/chef", config))
            assertEquals(code, RelayProtocol.code(config.publicOrigin + "/s/$code", config))
            assertEquals(code, RelayProtocol.code(config.linkScheme + "://share/$code", config))
            val other = OnlineConfiguration(if (config.sandbox) "production" else "staging")
            assertNotEquals(config.scopedName("saved-retry-v1"), other.scopedName("saved-retry-v1"))
            assertFalse(config.acceptsApi(other.apiOrigin + "/v1/me"))
            assertThrows(IllegalArgumentException::class.java) { AccountProtocol.link(other.publicOrigin + "/auth#challenge=$id&token=$token", config) }
            assertThrows(IllegalStateException::class.java) { PublicLink.destination(other.publicOrigin + "/c/$id", config) }
            assertThrows(IllegalStateException::class.java) { RelayProtocol.code(other.linkScheme + "://share/$code", config) }
            val invalid = fixture().getJSONArray("invalidApiTemplates")
            for (j in 0 until invalid.length()) assertFalse(config.acceptsApi(invalid.getString(j).replace("HOST", config.apiHost)))
        }
    }

    @Test fun googlePackagesAndAdvertisementFollowTheSharedEnvironments() {
        val rows = fixture().getJSONArray("profiles")
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val config = OnlineConfiguration(row.getString("environment"))
            val packages = row.getJSONArray("googlePackages")
            assertEquals((0 until packages.length()).map { packages.getString(it) }, config.googlePackages)
            for (packageName in config.googlePackages) assertTrue(config.advertisesGoogleSignIn(packageName))
            assertFalse(config.advertisesGoogleSignIn("com.pittech.unconfigured"))
        }
        assertTrue(OnlineConfiguration("staging").advertisesGoogleSignIn("com.pittech"))
        assertFalse(OnlineConfiguration("staging").advertisesGoogleSignIn("com.pittech.debug"))
        assertFalse(OnlineConfiguration("production").advertisesGoogleSignIn("com.pittech.dev"))
        assertFalse(OnlineConfiguration("production").advertisesGoogleSignIn("com.pittech.debug"))
    }

    @Test fun unknownProfilesAreRejectedAndCurrentBuildUsesItsExplicitGate() {
        val invalid = fixture().getJSONArray("invalidEnvironments")
        for (i in 0 until invalid.length()) assertThrows(IllegalArgumentException::class.java) { OnlineConfiguration(invalid.getString(i), enabled = true) }
        assertEquals(BuildConfig.PITTECH_ONLINE_ENVIRONMENT, OnlineConfiguration.current.environment)
        assertEquals(BuildConfig.PITTECH_ONLINE_ENABLED, OnlineConfiguration.current.enabled)
        assertEquals(BuildConfig.PITTECH_ONLINE_ENABLED, AccountProtocol.ENABLED)
        assertEquals(BuildConfig.PITTECH_GOOGLE_WEB_CLIENT_ID, OnlineConfiguration.current.googleWebClientId)
    }
}
