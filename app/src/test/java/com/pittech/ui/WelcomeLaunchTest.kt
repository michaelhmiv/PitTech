package com.pittech.ui

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #187: welcome seen-flag persistence + launch routing (crash precedence). */
class WelcomeLaunchTest {

    private class FakeSharedPreferences : SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clear = false
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { pending[key] = null }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clear) values.clear()
                pending.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
            }
        }
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    @Test
    fun seenFlagDefaultsUnseenAndPersistsOnceMarked() {
        val preferences = FakeSharedPreferences()
        assertFalse(WelcomePreferences.hasSeen(preferences))
        WelcomePreferences.markSeen(preferences)
        assertTrue(WelcomePreferences.hasSeen(preferences))
        assertEquals(true, preferences.values[WelcomePreferences.PREFERENCE_KEY])
    }

    @Test
    fun freshInstallWithoutCrashShowsWelcome() {
        assertEquals(
            LaunchSurface.WELCOME,
            resolveLaunchSurface(crashPendingNow = false, crashPendingAtLaunch = false, welcomeSeen = false),
        )
    }

    @Test
    fun seenWelcomeNeverShowsAgain() {
        assertEquals(
            LaunchSurface.APP,
            resolveLaunchSurface(crashPendingNow = false, crashPendingAtLaunch = false, welcomeSeen = true),
        )
    }

    @Test
    fun pendingCrashShowsRecoveryNotWelcome() {
        assertEquals(
            LaunchSurface.CRASH_RECOVERY,
            resolveLaunchSurface(crashPendingNow = true, crashPendingAtLaunch = true, welcomeSeen = false),
        )
    }

    @Test
    fun crashLaunchSuppressesWelcomeForTheWholeSession() {
        // Recovery was shown and continued: this session lands in the app and
        // the welcome remains unseen — it shows on the next clean launch.
        assertEquals(
            LaunchSurface.APP,
            resolveLaunchSurface(crashPendingNow = false, crashPendingAtLaunch = true, welcomeSeen = false),
        )
    }

    @Test
    fun cleanLaunchAfterCrashSessionShowsWelcome() {
        assertEquals(
            LaunchSurface.WELCOME,
            resolveLaunchSurface(crashPendingNow = false, crashPendingAtLaunch = false, welcomeSeen = false),
        )
    }
}
