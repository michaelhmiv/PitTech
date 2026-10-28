package com.pittech.domain

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import kotlin.math.pow

/** Curated customization inventory (mirrors `contracts/fixtures/pro-personal-v1.json`
 * `customization`): share-card themes, accessible chart palettes, restrained
 * public-profile presentation options, and app-icon variants. Selections
 * persist in the existing preference store and never modify measured data.
 * Unsupported or expired entitlement resolves to the readable free fallback
 * (stored Pro selections are kept, just not applied) and prior exports are
 * never destroyed. Anonymous content never carries Pro identity: the public
 * projection helpers below return the unstyled classic treatment for
 * anonymous items. iOS `ProCustomization` pins the same inventory, contrast
 * math, and resolution rules. */
object ProCustomization {
    data class CardTheme(val id: String, val name: String, val isPro: Boolean, val background: String, val ink: String, val supporting: String, val accent: String)

    val cardThemes: List<CardTheme> = listOf(
        CardTheme("classic", "Classic", false, "#FBF9F4", "#1D2733", "#5C6B7A", "#A84A0C"),
        CardTheme("ember", "Ember", true, "#FBF3E8", "#2B2118", "#6B5B4C", "#B04D2E"),
        CardTheme("sage-night", "Sage night", true, "#23261F", "#F2EFE6", "#B9B3A4", "#9DB29A"),
        CardTheme("parchment", "Parchment", true, "#FDFBF5", "#33302A", "#6E675C", "#416C8A"),
    )

    /** Share-card renderer theme ids: the free default plus the curated Pro set. */
    val shareCardThemeIds: Set<String> = cardThemes.map { it.id }.toSet()

    data class ChartPalette(val id: String, val name: String, val isPro: Boolean, val intendedBackground: String, val colors: List<String>)

    val chartPalettes: List<ChartPalette> = listOf(
        ChartPalette("classic", "Classic", false, "#FBF9F4", listOf("#B04D2E", "#58665A", "#416C8A", "#8B5D84", "#A17A2C", "#357D78")),
        ChartPalette("color-vision-safe", "Color-vision safe", true, "#FBF9F4", listOf("#0072B2", "#D55E00", "#00855F", "#A34D7E", "#2A7F9E", "#444444")),
        ChartPalette("high-contrast", "High contrast", true, "#FBF9F4", listOf("#000000", "#005A9C", "#8B0000", "#006400", "#4B0082", "#8B4513")),
        ChartPalette("sage-night-chart", "Sage night chart", true, "#23261F", listOf("#F0C987", "#A8D5BA", "#A8C8E8", "#D8B8E0", "#E8E18A", "#9AD5D0")),
    )

    data class ProfileAccent(val id: String, val name: String, val color: String)

    val profileAccents: List<ProfileAccent> = listOf(
        ProfileAccent("ember", "Ember accent", "#B04D2E"),
        ProfileAccent("sage", "Sage accent", "#58665A"),
        ProfileAccent("slate", "Slate accent", "#416C8A"),
    )

    val profileLayouts: List<String> = listOf("standard", "compact")

    data class IconVariant(val id: String, val name: String, val isPro: Boolean) {
        /** The launcher alias suffix, or null for the primary (Classic) alias. */
        val aliasSuffix: String?
            get() = when (id) {
                "ember" -> "ProIconEmber"
                "midnight" -> "ProIconMidnight"
                else -> null
            }
    }

    val iconVariants: List<IconVariant> = listOf(
        IconVariant("classic", "Classic", false),
        IconVariant("ember", "Ember", true),
        IconVariant("midnight", "Midnight", true),
    )

    /** Relative luminance per WCAG 2.1. Returns null for malformed hex. */
    fun luminance(hex: String): Double? {
        val clean = hex.removePrefix("#")
        if (clean.length != 6) return null
        val rgb = clean.toIntOrNull(16) ?: return null
        fun linear(channel: Double): Double = if (channel <= 0.04045) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
        return 0.2126 * linear(((rgb shr 16) and 0xFF) / 255.0) +
            0.7152 * linear(((rgb shr 8) and 0xFF) / 255.0) +
            0.0722 * linear((rgb and 0xFF) / 255.0)
    }

    /** Contrast ratio, always >= 1. Chart colors need >= 3.0 against their
     * intended background; text colors (ink, supporting) need >= 4.5. */
    fun contrastRatio(first: String, second: String): Double? {
        val a = luminance(first) ?: return null
        val b = luminance(second) ?: return null
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    fun theme(id: String?): CardTheme = cardThemes.firstOrNull { it.id == id } ?: cardThemes[0]

    fun effectiveTheme(storedId: String?, entitled: Boolean): CardTheme {
        val theme = theme(storedId)
        return if (theme.isPro && !entitled) cardThemes[0] else theme
    }

    fun palette(id: String?): ChartPalette = chartPalettes.firstOrNull { it.id == id } ?: chartPalettes[0]

    fun effectivePalette(storedId: String?, entitled: Boolean): ChartPalette {
        val palette = palette(storedId)
        return if (palette.isPro && !entitled) chartPalettes[0] else palette
    }

    fun icon(id: String?): IconVariant = iconVariants.firstOrNull { it.id == id } ?: iconVariants[0]

    fun effectiveIcon(storedId: String?, entitled: Boolean): IconVariant {
        val variant = icon(storedId)
        return if (variant.isPro && !entitled) iconVariants[0] else variant
    }

    enum class PublicIdentity { NAMED, ANONYMOUS }

    /** Restrained profile presentation. Anonymous items always render the
     * unstyled classic treatment: no accent, no Pro link, regardless of the
     * stored selection or entitlement. Named items apply the stored style for
     * entitled accounts and classic otherwise. */
    fun effectiveProfileAccent(identity: PublicIdentity, storedId: String?, entitled: Boolean): ProfileAccent? {
        if (identity != PublicIdentity.NAMED || !entitled) return null
        return profileAccents.firstOrNull { it.id == storedId }
    }

    fun effectiveProfileLayout(identity: PublicIdentity, stored: String?, entitled: Boolean): String {
        if (identity != PublicIdentity.NAMED || !entitled || stored != "compact") return "standard"
        return "compact"
    }
}

interface ProCustomizationBacking {
    fun value(key: String): String?
    fun set(key: String, value: String)
}

/** The existing `pittech-preferences` store — the same file as theme mode and units. */
class SharedPrefsCustomizationBacking(context: Context) : ProCustomizationBacking {
    private val prefs = context.getSharedPreferences("pittech-preferences", Context.MODE_PRIVATE)
    override fun value(key: String): String? = prefs.getString(key, null)
    override fun set(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}

class MemoryCustomizationBacking : ProCustomizationBacking {
    private val values = mutableMapOf<String, String>()
    override fun value(key: String): String? = values[key]
    override fun set(key: String, value: String) {
        values[key] = value
    }
}

/** Preview-then-apply customization state. Preview ids drive the preview
 * tiles; applying copies one preview id into its stored selection. Nothing
 * here touches measured data — only preference keys. */
class ProCustomizationStore(
    private val backing: ProCustomizationBacking,
    private val icons: ProIconController? = null,
) {
    /** Preview ids, one per inventory. Null previews the stored selection. */
    var previewThemeId: String? = null
    var previewPaletteId: String? = null
    var previewProfileAccentId: String? = null
    var previewProfileLayout: String? = null
    var previewIconId: String? = null

    var themeId: String
        get() = backing.value(THEME_KEY) ?: "classic"
        set(value) = backing.set(THEME_KEY, value)

    var paletteId: String
        get() = backing.value(PALETTE_KEY) ?: "classic"
        set(value) = backing.set(PALETTE_KEY, value)

    var profileAccentId: String?
        get() = backing.value(PROFILE_ACCENT_KEY)
        set(value) {
            if (value != null) backing.set(PROFILE_ACCENT_KEY, value)
        }

    var profileLayout: String
        get() = backing.value(PROFILE_LAYOUT_KEY) ?: "standard"
        set(value) = backing.set(PROFILE_LAYOUT_KEY, value)

    var iconId: String
        get() = backing.value(ICON_KEY) ?: "classic"
        set(value) = backing.set(ICON_KEY, value)

    fun applyTheme(id: String) {
        themeId = id
        previewThemeId = null
    }

    fun applyPalette(id: String) {
        paletteId = id
        previewPaletteId = null
    }

    fun applyProfile(accentId: String?, layout: String) {
        if (accentId != null) backing.set(PROFILE_ACCENT_KEY, accentId)
        profileLayout = layout
        previewProfileAccentId = null
        previewProfileLayout = null
    }

    fun clearProfileAccent() {
        // The classic treatment is the absence of a stored accent; clearing
        // restores it without touching any other selection.
        backing.set(PROFILE_ACCENT_KEY, "none")
        previewProfileAccentId = null
    }

    /** Applies an icon variant through the launcher-alias mechanism. Returns
     * an honest notice when the platform call is unavailable; the stored
     * selection is retained either way. */
    fun applyIcon(id: String): String? {
        iconId = id
        previewIconId = null
        val controller = icons ?: return "Icon switching isn't available in this build. The Classic icon stays."
        val applied = runCatching { controller.apply(id) }.getOrDefault(false)
        if (applied) return null
        val variant = ProCustomization.icon(id)
        return "The ${variant.name} icon isn't available in this build yet. The Classic icon stays."
    }

    companion object {
        const val THEME_KEY = "proCardTheme"
        const val PALETTE_KEY = "proChartPalette"
        const val PROFILE_ACCENT_KEY = "proProfileAccent"
        const val PROFILE_LAYOUT_KEY = "proProfileLayout"
        const val ICON_KEY = "proAppIcon"
    }
}

/** Launcher-alias switching (the platform-supported mechanism: one enabled
 * alias per variant, all targeting MainActivity). */
interface ProIconController {
    fun apply(variantId: String): Boolean
}

class PackageManagerProIconController(private val context: Context) : ProIconController {
    override fun apply(variantId: String): Boolean {
        val packageManager = context.packageManager
        val selected = ProCustomization.icon(variantId).aliasSuffix ?: CLASSIC_SUFFIX
        for (suffix in listOf(CLASSIC_SUFFIX, "ProIconEmber", "ProIconMidnight")) {
            val state = if (suffix == selected) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            // The runtime package name (not a hardcoded id): debug/dev builds
            // carry an applicationIdSuffix and must resolve their own aliases.
            packageManager.setComponentEnabledSetting(ComponentName(context.packageName, "${context.packageName}.$suffix"), state, PackageManager.DONT_KILL_APP)
        }
        return true
    }

    companion object {
        const val CLASSIC_SUFFIX = "ProIconClassic"
    }
}
