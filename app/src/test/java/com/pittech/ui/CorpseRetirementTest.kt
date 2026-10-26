package com.pittech.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PL-28 (#172): the retired corpses stay retired. The call-site-less
 * TimelineEventDialog, its dead state apparatus (the never-read
 * showNewEvent / logPhotoUri / logPhotoCaption / photoEntryMode /
 * photoSourceDestination leftovers of the legacy Cook Detail
 * photo-attach path), and the never-shown showTemperature dialog state
 * were deleted in Slice 11 after their unreachability was verified in
 * code (zero call sites / zero reads repo-wide). If any of these
 * identifiers comes back in the main sources, this test fails — wire
 * the feature up properly through the note composer instead.
 */
class CorpseRetirementTest {

    private val retiredIdentifiers = listOf(
        "TimelineEventDialog",
        "photoSourceDestination",
        "photoEntryMode",
        "showNewEvent",
        "logPhotoUri",
        "logPhotoCaption",
        "showTemperature",
    )

    private fun mainSources(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val direct = File(dir, "src/main/java/com/pittech")
            if (direct.isDirectory) return direct
            val viaApp = File(dir, "app/src/main/java/com/pittech")
            if (viaApp.isDirectory) return viaApp
            dir = dir.parentFile
        }
        error("Could not locate the main sources from ${System.getProperty("user.dir")}")
    }

    @Test
    fun retiredIdentifiersStayOutOfMainSources() {
        val offenders = mutableListOf<String>()
        mainSources().walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            file.readLines().forEach { line ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEach
                retiredIdentifiers.forEach { identifier ->
                    if (line.contains(identifier)) offenders += "${file.name}: $identifier"
                }
            }
        }
        assertTrue("Retired PL-28 identifiers reappeared: $offenders", offenders.isEmpty())
    }
}
