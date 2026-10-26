package com.pittech.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * BUILD-SPEC §3.1 contrast floors, asserted pairwise against the raw
 * [PitTechPalette] values: body text >= 4.5:1 in both modes, and text on
 * marigold/ember fills (always navy [PitTechPalette.DARK_ON_ACCENT] /
 * [PitTechPalette.LIGHT_ON_ACCENT], never white) >= 4.5:1. Light ember is
 * deliberately NOT asserted as a text-bearing fill: per §3.1 the light
 * ember role is a container/text color (see `emberText`), and light
 * primary fills are marigold.
 */
class PitTechTokensTest {

    private fun luminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private fun contrastRatio(foreground: Long, background: Long): Double {
        val a = luminance(foreground)
        val b = luminance(background)
        val lighter = maxOf(a, b)
        val darker = minOf(a, b)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun assertPair(mode: String, name: String, foreground: Long, background: Long, floor: Double = 4.5) {
        val ratio = contrastRatio(foreground, background)
        assertTrue(
            "$mode $name contrast %.2f is below the %.1f floor".format(ratio, floor),
            ratio >= floor,
        )
    }

    @Test
    fun darkTokensMeetContrastFloors() {
        val grounds = listOf(
            "ground" to PitTechPalette.DARK_GROUND,
            "surface" to PitTechPalette.DARK_SURFACE,
            "surfaceRaised" to PitTechPalette.DARK_SURFACE_RAISED,
        )
        val textTokens = listOf(
            "ink" to PitTechPalette.DARK_INK,
            "inkMuted" to PitTechPalette.DARK_INK_MUTED,
            "emberText" to PitTechPalette.DARK_EMBER_TEXT,
            "attentionText" to PitTechPalette.DARK_ATTENTION_TEXT,
        )
        for ((textName, text) in textTokens) {
            for ((groundName, ground) in grounds) {
                assertPair("dark", "$textName on $groundName", text, ground)
            }
        }
        for ((groundName, ground) in grounds.take(2)) {
            assertPair("dark", "cobalt on $groundName", PitTechPalette.DARK_COBALT, ground)
        }
        assertPair("dark", "flag on ground", PitTechPalette.DARK_FLAG, PitTechPalette.DARK_GROUND)
        assertPair("dark", "onAccent on marigold", PitTechPalette.DARK_ON_ACCENT, PitTechPalette.DARK_MARIGOLD)
        assertPair("dark", "onAccent on ember", PitTechPalette.DARK_ON_ACCENT, PitTechPalette.DARK_EMBER)
    }

    @Test
    fun lightTokensMeetContrastFloors() {
        val grounds = listOf(
            "ground" to PitTechPalette.LIGHT_GROUND,
            "surface" to PitTechPalette.LIGHT_SURFACE,
            "surfaceRaised" to PitTechPalette.LIGHT_SURFACE_RAISED,
        )
        val textTokens = listOf(
            "ink" to PitTechPalette.LIGHT_INK,
            "inkMuted" to PitTechPalette.LIGHT_INK_MUTED,
            "emberText" to PitTechPalette.LIGHT_EMBER_TEXT,
            "attentionText" to PitTechPalette.LIGHT_ATTENTION_TEXT,
        )
        for ((textName, text) in textTokens) {
            for ((groundName, ground) in grounds) {
                assertPair("light", "$textName on $groundName", text, ground)
            }
        }
        for ((groundName, ground) in grounds.take(2)) {
            assertPair("light", "cobalt on $groundName", PitTechPalette.LIGHT_COBALT, ground)
        }
        assertPair("light", "flag on ground", PitTechPalette.LIGHT_FLAG, PitTechPalette.LIGHT_GROUND)
        assertPair("light", "onAccent on marigold", PitTechPalette.LIGHT_ON_ACCENT, PitTechPalette.LIGHT_MARIGOLD)
    }

    @Test
    fun capsFloorIsEleven() {
        // BUILD-SPEC §3.2: the metaLabel token carries the tracked-caps floor.
        assertTrue(PitTechTextStyles.metaLabel.fontSize.value >= 11f)
    }
}
