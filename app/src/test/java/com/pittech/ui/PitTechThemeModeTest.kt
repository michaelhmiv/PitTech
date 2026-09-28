package com.pittech.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PitTechThemeModeTest {
    @Test
    fun missingOrInvalidPreferenceDefaultsToSystem() {
        assertEquals(PitTechThemeMode.SYSTEM, PitTechThemeMode.fromPreference(null))
        assertEquals(PitTechThemeMode.SYSTEM, PitTechThemeMode.fromPreference("unexpected"))
    }

    @Test
    fun savedPreferenceIsReadWithoutCaseSensitivity() {
        assertEquals(PitTechThemeMode.DARK, PitTechThemeMode.fromPreference("dark"))
        assertEquals(PitTechThemeMode.LIGHT, PitTechThemeMode.fromPreference("LIGHT"))
    }

    @Test
    fun systemFollowsDeviceWhileManualChoicesOverrideIt() {
        assertTrue(PitTechThemeMode.SYSTEM.usesDarkTheme(systemIsDark = true))
        assertFalse(PitTechThemeMode.SYSTEM.usesDarkTheme(systemIsDark = false))
        assertTrue(PitTechThemeMode.DARK.usesDarkTheme(systemIsDark = false))
        assertFalse(PitTechThemeMode.LIGHT.usesDarkTheme(systemIsDark = true))
    }
}
