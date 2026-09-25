package com.pittech.ui

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

        fun fromPreference(value: String?): PitTechThemeMode =
            values().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: SYSTEM
    }
}
