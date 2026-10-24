package com.pittech.ui

import org.junit.Assert.assertEquals
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
    fun nightServiceIsDarkOnlyForEveryMode() {
        // #172 owner ruling 2026-10-24 (DD-7 rejected): saved values still parse,
        // but every mode resolves to the Night Service dark appearance.
        PitTechThemeMode.values().forEach { mode ->
            assertTrue(mode.usesDarkTheme(systemIsDark = true))
            assertTrue(mode.usesDarkTheme(systemIsDark = false))
        }
    }
}
