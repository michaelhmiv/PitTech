package com.pittech.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Slice 10 (PL-22 / DD-6): the dialog grammar is enforced, not just
 * documented. Every remaining `AlertDialog(` call site in the UI sources
 * must be one of the allowlisted guard-class dialogs — a destructive
 * guard, a single-value entry, or the show-once token. Editors, forms,
 * lists, and status surfaces live in sheets / full screens via
 * [SheetSurface] / [FullScreenSurface]; a NEW dialog site (or a title
 * change to a kept one) fails this test until the grammar review is
 * repeated and the allowlist below is updated deliberately.
 *
 * Sites are fingerprinted by file + the first 24 characters of the
 * dialog's title `Text(...)` call, whitespace-collapsed — stable across
 * reformatting of the surrounding call, sensitive to what the dialog is.
 */
class DialogGrammarTest {

    private val allowed: Map<String, List<String>> = mapOf(
        // Sign-out guard; show-once connection token; disconnect guards
        // (one / all); stop-taking-requests guard; the two destructive
        // account flows (Slice 8 kept them as dialogs deliberately).
        "AccountScreen.kt" to listOf(
            "Text(\"Sign out on this d",
            "Text(\"Your connection to",
            "Text(\"Disconnect this co",
            "Text(\"Disconnect all con",
            "Text(\"Stop taking reques",
            "Text(\"Delete account\")},",
            "Text(\"Remove local journ",
        ),
        // Delete-dish guard; Finish-meal guard; delete-cook guard.
        // (The call-site-less timeline-entry editor dialog was retired
        // by PL-28 in Slice 11 — see CorpseRetirementTest.)
        "CookDetailScreen.kt" to listOf(
            "Text(\"Delete \${dish.name",
            "Text(\"Finish meal\", modi",
            "Text(\"Delete this cook?\"",
        ),
        // Guidance confirm guards: confirm a physical removal / a skip.
        "CookGuidanceCard.kt" to listOf(
            "Text(\"Remove \${data.dish",
            "Text(\"Skip \${e.step.titl",
        ),
        // Adopt-actual-timing confirm guard.
        "CookPhaseCard.kt" to listOf(
            "Text(\"Use actual Food on",
        ),
        // Backup-contents review (a destructive-import guard); leave-setup
        // guard; leave-dish draft guard (PL-24 — was the discard-dish
        // guard before draft persistence became the house standard).
        "CookScreens.kt" to listOf(
            "Text(\"Review backup cont",
            "Text(\"Leave this cook se",
            "Text(\"Leave this dish?\")",
        ),
        // Unpublish guard.
        "MyCookbookScreen.kt" to listOf(
            "Text(\"Remove this public",
        ),
        // Release-purchase guard.
        "ProPaywall.kt" to listOf(
            "Text(\"Release this purch",
        ),
        // Revoke-code guard.
        "RelayScreens.kt" to listOf(
            "Text(\"Stop new downloads",
        ),
        // Save-meal-combination: single-value entry (one name field).
        "SavedRecipesLibrary.kt" to listOf(
            "Text(\"Save meal combinat",
        ),
    )

    private fun uiSources(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val direct = File(dir, "src/main/java/com/pittech/ui")
            if (direct.isDirectory) return direct
            val viaApp = File(dir, "app/src/main/java/com/pittech/ui")
            if (viaApp.isDirectory) return viaApp
            dir = dir.parentFile
        }
        error("Could not locate the UI sources from ${System.getProperty("user.dir")}")
    }

    private fun fingerprint(window: String): String {
        val titleAt = window.indexOf("title")
        val segment = if (titleAt >= 0) window.substring(titleAt) else window
        val textAt = segment.indexOf("Text(")
        if (textAt < 0) return "NO-TITLE"
        return segment.substring(textAt, minOf(textAt + 24, segment.length)).replace(Regex("\\s+"), " ")
    }

    @Test fun remainingAlertDialogsAreOnlyTheGuardClasses() {
        val found = mutableMapOf<String, MutableList<String>>()
        var total = 0
        uiSources().listFiles { f -> f.extension == "kt" }!!.forEach { file ->
            val text = file.readText()
            var index = text.indexOf("AlertDialog(")
            while (index >= 0) {
                val lineStart = text.lastIndexOf('\n', index) + 1
                val line = text.substring(lineStart, text.indexOf('\n', index).takeIf { it >= 0 } ?: text.length)
                if (!line.trimStart().startsWith("import") && !line.trimStart().startsWith("*") && !line.trimStart().startsWith("//")) {
                    val fp = fingerprint(text.substring(index, minOf(index + 700, text.length)))
                    found.getOrPut(file.name) { mutableListOf() }.add(fp)
                    total++
                }
                index = text.indexOf("AlertDialog(", index + 1)
            }
        }
        val expectedTotal = allowed.values.sumOf { it.size }
        assertEquals("AlertDialog site count drifted from the DD-6 allowlist", expectedTotal, total)
        assertEquals(
            "AlertDialog sites drifted from the DD-6 allowlist.\nFound: $found\nAllowed: $allowed",
            allowed.mapValues { it.value.sorted() },
            found.mapValues { it.value.sorted() },
        )
    }

    @Test fun errorCopyNeverSurfacesRawTechnicalText() {
        // PL-23 (DD-8): technical exception text falls back to the authored
        // DD-8 sentence; authored sentences pass through unchanged.
        assertEquals(
            ErrorCopy.SAVE_CHANGE_FAILED,
            ErrorCopy.failureText(IllegalStateException("Failed requirement."), ErrorCopy.SAVE_CHANGE_FAILED),
        )
        assertEquals(
            ErrorCopy.SAVE_CHANGE_FAILED,
            ErrorCopy.failureText(RuntimeException("column dish_id does not exist"), ErrorCopy.SAVE_CHANGE_FAILED),
        )
        assertEquals(
            ErrorCopy.SAVE_CHANGE_FAILED,
            ErrorCopy.failureText(null as Throwable?, ErrorCopy.SAVE_CHANGE_FAILED),
        )
        assertEquals(
            "This cook no longer exists.",
            ErrorCopy.failureText(RuntimeException("This cook no longer exists."), ErrorCopy.SAVE_CHANGE_FAILED),
        )
        assertTrue(ErrorCopy.SAVE_CHANGE_FAILED.contains("try again"))
        assertTrue(OfflineCopy.BANNER.startsWith("You're offline."))
    }
}
