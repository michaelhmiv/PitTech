package com.pittech.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PL-30 (#172): "Pro entries never appear inside Live or Finish" holds
 * today and is kept as a regression rule in the slice gates. The
 * behavioural half already exists (ProPersonalFlowTest's
 * liveCookAndFinishShowNoProSurfaces on Android); this source guard is
 * the cheap always-on half for BOTH platforms: no LIVE/FINISH paywall
 * placement may exist, and the Cook Detail sources (which host Live and
 * Finish on each platform) may not reference the Pro entry machinery.
 */
class ProPlacementGuardTest {

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "app/src/main/java/com/pittech").isDirectory) return dir
            if (File(dir, "src/main/java/com/pittech").isDirectory) return dir.parentFile
            dir = dir.parentFile
        }
        error("Could not locate the repo root from ${System.getProperty("user.dir")}")
    }

    private fun codeLines(file: File): List<String> =
        file.readLines().filter {
            val trimmed = it.trimStart()
            !trimmed.startsWith("//") && !trimmed.startsWith("*") && !trimmed.startsWith("/*")
        }

    @Test
    fun noLiveOrFinishPaywallPlacementExistsOnEitherPlatform() {
        val root = repoRoot()
        val androidPlacements = File(root, "app/src/main/java/com/pittech/billing/ProSubscription.kt").readText()
        val enumBody = androidPlacements.substringAfter("enum class ProPlacement").substringBefore("}")
        assertFalse("Android ProPlacement gained a LIVE entry", enumBody.contains("LIVE"))
        assertFalse("Android ProPlacement gained a FINISH entry", enumBody.contains("FINISH"))

        val iosPlacements = File(root, "ios/PitTech/ProSubscription.swift").readText()
        val iosEnumBody = iosPlacements.substringAfter("enum ProPlacement").substringBefore("}")
        assertFalse("iOS ProPlacement gained a live entry", iosEnumBody.contains("case live"))
        assertFalse("iOS ProPlacement gained a finish entry", iosEnumBody.contains("case finish"))
    }

    @Test
    fun cookDetailSourcesNeverReferenceProEntries() {
        val root = repoRoot()
        val banned = listOf("ProPlacement", "openPro", "ProGateDialog", "ProPaywall")
        val androidDetail = File(root, "app/src/main/java/com/pittech/ui/CookDetailScreen.kt")
        codeLines(androidDetail).forEach { line ->
            banned.forEach { token ->
                assertFalse("CookDetailScreen.kt references $token: $line", line.contains(token))
            }
        }
        val iosDetail = File(root, "ios/PitTech/CookDetailView.swift")
        codeLines(iosDetail).forEach { line ->
            banned.forEach { token ->
                assertFalse("CookDetailView.swift references $token: $line", line.contains(token))
            }
        }
        assertTrue(androidDetail.exists() && iosDetail.exists())
    }
}
