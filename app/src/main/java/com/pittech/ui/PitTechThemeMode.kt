package com.pittech.ui

/**
 * Appearance preference (BUILD-SPEC §3.4, TURN-IN pass 2026-10-26).
 *
 * The owner reversed the #172 dark-only ruling (DD-7) for this pass: PitTech
 * ships Dark and Light, with **Dark the fresh-install default** — when no
 * preference is stored (or the stored value does not decode), the app opens
 * dark. A stored choice always wins, and SYSTEM follows the device.
 */
enum class PitTechThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    fun usesDarkTheme(systemIsDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemIsDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        const val PREFERENCE_KEY = "theme-mode"

        /**
         * Decodes a stored preference. Existing stored values decode exactly
         * as before; only the *unset/invalid* fallback changed (SYSTEM under
         * #172, DARK now — see the class doc). Because [usesDarkTheme] was
         * forced dark for every value during #172, no stored value was ever
         * user-visible, so this changes the first-launch default only.
         */
        fun fromPreference(value: String?): PitTechThemeMode =
            values().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: DARK
    }
}
