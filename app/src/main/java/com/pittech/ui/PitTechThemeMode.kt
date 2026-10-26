package com.pittech.ui

/**
 * Appearance preference. Since the #172 Night Service refresh (owner ruling
 * 2026-10-24, design delta DD-7 rejected), PitTech ships ONE appearance —
 * Night Service dark — on every screen. The enum and its preference parsing
 * remain so saved preferences, backups and device-action commands keep
 * decoding, but every value now resolves to dark; the value is no longer
 * user-changeable (Settings shows an informational row instead of a picker).
 */
enum class PitTechThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    /** Night Service is dark-only: every mode resolves to the dark scheme. */
    fun usesDarkTheme(systemIsDark: Boolean): Boolean = true

    companion object {
        const val PREFERENCE_KEY = "theme-mode"

        fun fromPreference(value: String?): PitTechThemeMode =
            values().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: SYSTEM
    }
}
