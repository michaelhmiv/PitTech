package com.pittech.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PitTechThemeModeTest {
    @Test
    fun missingOrInvalidPreferenceDefaultsToDark() {
        // BUILD-SPEC §3.4 (TURN-IN): Dark is the fresh-install default. Only
        // the unset/invalid fallback changed; stored values decode as before.
        assertEquals(PitTechThemeMode.DARK, PitTechThemeMode.fromPreference(null))
        assertEquals(PitTechThemeMode.DARK, PitTechThemeMode.fromPreference("unexpected"))
    }

    @Test
    fun savedPreferenceIsReadWithoutCaseSensitivity() {
        assertEquals(PitTechThemeMode.DARK, PitTechThemeMode.fromPreference("dark"))
        assertEquals(PitTechThemeMode.LIGHT, PitTechThemeMode.fromPreference("LIGHT"))
        assertEquals(PitTechThemeMode.SYSTEM, PitTechThemeMode.fromPreference("System"))
    }

    @Test
    fun systemFollowsDeviceWhileManualChoicesOverrideIt() {
        assertTrue(PitTechThemeMode.SYSTEM.usesDarkTheme(systemIsDark = true))
        assertFalse(PitTechThemeMode.SYSTEM.usesDarkTheme(systemIsDark = false))
        assertTrue(PitTechThemeMode.DARK.usesDarkTheme(systemIsDark = false))
        assertFalse(PitTechThemeMode.LIGHT.usesDarkTheme(systemIsDark = true))
    }
}
