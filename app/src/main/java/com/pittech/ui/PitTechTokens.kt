package com.pittech.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.pittech.R

/**
 * TURN-IN semantic tokens (design pass 2026-10-26, BUILD-SPEC §3.1).
 *
 * Tokens are named by role, not by color, and resolve per appearance mode.
 * The raw values live in [PitTechPalette] as hex constants so JVM unit tests
 * can assert the spec's contrast floors without a Compose runtime. The two
 * build-time fixes from the spec are baked into the light values:
 * `emberText` is #A84A0C (not the #C75B12 fill) and `attentionText` is
 * #8A6400, because marigold is a fill — it never carries text directly, and
 * text on marigold/ember fills is always [PitTechTokens.onAccent] navy ink,
 * never white.
 */
object PitTechPalette {
    // Dark (default)
    const val DARK_GROUND = 0xFF10151C
    const val DARK_SURFACE = 0xFF1D2733
    const val DARK_SURFACE_RAISED = 0xFF27313F
    const val DARK_INK = 0xFFF5EFE3
    const val DARK_INK_MUTED = 0xFF9AA7B4
    const val DARK_RULING = 0xFF33404F
    const val DARK_EMBER = 0xFFF07D2E
    const val DARK_EMBER_TEXT = 0xFFF07D2E
    const val DARK_MARIGOLD = 0xFFE8B41A
    const val DARK_ATTENTION_TEXT = 0xFFE8B41A
    const val DARK_COBALT = 0xFF6E8FE8
    const val DARK_FLAG = 0xFFE5533D
    const val DARK_ON_ACCENT = 0xFF1D2733

    // Light
    const val LIGHT_GROUND = 0xFFFBF9F4
    const val LIGHT_SURFACE = 0xFFFFFFFF
    const val LIGHT_SURFACE_RAISED = 0xFFF3EFE4
    const val LIGHT_INK = 0xFF1D2733
    const val LIGHT_INK_MUTED = 0xFF5C6B7A
    const val LIGHT_RULING = 0xFFD8D2C4
    const val LIGHT_EMBER = 0xFFC75B12
    const val LIGHT_EMBER_TEXT = 0xFFA84A0C
    const val LIGHT_MARIGOLD = 0xFFDFA408
    const val LIGHT_ATTENTION_TEXT = 0xFF8A6400
    const val LIGHT_COBALT = 0xFF2B50C8
    const val LIGHT_FLAG = 0xFFC23327
    const val LIGHT_ON_ACCENT = 0xFF1D2733
}

/** The semantic token set for one appearance mode (BUILD-SPEC §3.1). */
data class PitTechTokens(
    val ground: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val ink: Color,
    val inkMuted: Color,
    val ruling: Color,
    val ember: Color,
    val emberText: Color,
    val marigold: Color,
    val attentionText: Color,
    val cobalt: Color,
    val flag: Color,
    val onAccent: Color,
)

val PitTechDarkTokens = PitTechTokens(
    ground = Color(PitTechPalette.DARK_GROUND),
    surface = Color(PitTechPalette.DARK_SURFACE),
    surfaceRaised = Color(PitTechPalette.DARK_SURFACE_RAISED),
    ink = Color(PitTechPalette.DARK_INK),
    inkMuted = Color(PitTechPalette.DARK_INK_MUTED),
    ruling = Color(PitTechPalette.DARK_RULING),
    ember = Color(PitTechPalette.DARK_EMBER),
    emberText = Color(PitTechPalette.DARK_EMBER_TEXT),
    marigold = Color(PitTechPalette.DARK_MARIGOLD),
    attentionText = Color(PitTechPalette.DARK_ATTENTION_TEXT),
    cobalt = Color(PitTechPalette.DARK_COBALT),
    flag = Color(PitTechPalette.DARK_FLAG),
    onAccent = Color(PitTechPalette.DARK_ON_ACCENT),
)

val PitTechLightTokens = PitTechTokens(
    ground = Color(PitTechPalette.LIGHT_GROUND),
    surface = Color(PitTechPalette.LIGHT_SURFACE),
    surfaceRaised = Color(PitTechPalette.LIGHT_SURFACE_RAISED),
    ink = Color(PitTechPalette.LIGHT_INK),
    inkMuted = Color(PitTechPalette.LIGHT_INK_MUTED),
    ruling = Color(PitTechPalette.LIGHT_RULING),
    ember = Color(PitTechPalette.LIGHT_EMBER),
    emberText = Color(PitTechPalette.LIGHT_EMBER_TEXT),
    marigold = Color(PitTechPalette.LIGHT_MARIGOLD),
    attentionText = Color(PitTechPalette.LIGHT_ATTENTION_TEXT),
    cobalt = Color(PitTechPalette.LIGHT_COBALT),
    flag = Color(PitTechPalette.LIGHT_FLAG),
    onAccent = Color(PitTechPalette.LIGHT_ON_ACCENT),
)

val LocalPitTechTokens = staticCompositionLocalOf { PitTechDarkTokens }

/** The active TURN-IN tokens for the current appearance. */
@Composable
fun pitTechTokens(): PitTechTokens = LocalPitTechTokens.current

/**
 * Bundled type families (BUILD-SPEC §3.3 working assumption, pending
 * Michael's pick from the Slice 0 comparison sheet): banner = Noto Sans
 * Display Condensed, mono = IBM Plex Mono, text = Inter. All three are
 * OFL-licensed and Latin-subset for the app; see THIRD_PARTY_NOTICES.md.
 * If the pick changes, only these files and families change — the type
 * tokens below do not.
 */
val PitTechBannerFamily = FontFamily(
    Font(R.font.noto_sans_display_condensed_bold, FontWeight.Bold),
    Font(R.font.noto_sans_display_condensed_extrabold, FontWeight.ExtraBold),
    Font(R.font.noto_sans_display_condensed_black, FontWeight.Black),
)

val PitTechMonoFamily = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_mono_semibold, FontWeight.SemiBold),
    Font(R.font.ibm_plex_mono_bold, FontWeight.Bold),
)

val PitTechTextFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/**
 * TURN-IN type tokens (BUILD-SPEC §3.3). [metaLabel] carries the §3.2 floor:
 * no tracked-caps meta label renders below 11 sp at 1.0x font scale; where a
 * floored label no longer fits, the layout wraps or grows instead.
 */
object PitTechTextStyles {
    val scoreboard = TextStyle(
        fontFamily = PitTechBannerFamily,
        fontWeight = FontWeight.Black,
        fontSize = 52.sp,
        lineHeight = 56.sp,
    )
    val bannerTitle = TextStyle(
        fontFamily = PitTechBannerFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 21.sp,
        lineHeight = 24.sp,
    )
    val bannerCard = TextStyle(
        fontFamily = PitTechBannerFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 22.sp,
    )
    val monoReadout = TextStyle(
        fontFamily = PitTechMonoFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 20.sp,
    )
    val monoMeta = TextStyle(
        fontFamily = PitTechMonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )
    val body = TextStyle(
        fontFamily = PitTechTextFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    )
    val bodyEmphasis = TextStyle(
        fontFamily = PitTechTextFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    )
    val metaLabel = TextStyle(
        fontFamily = PitTechTextFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.4.sp,
    )
}
