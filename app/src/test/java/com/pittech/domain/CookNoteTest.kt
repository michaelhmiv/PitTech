package com.pittech.domain

import org.junit.Assert.*
import org.junit.Test

class CookNoteTest {
    @Test fun draftRoundTripKeepsIncompleteInputAndStableAttachmentIdentities() {
        val draft = CookNoteDraft(cookId = "cook", category = "temperature", action = "wrap", dishIds = listOf("flat", "point"),
            description = "Bark is set", occurredAt = 42L,
            temperatures = listOf(NoteTemperature(valueText = "94.5", unit = "°C", measuredAt = 42L)),
            photos = listOf(NotePhoto(uri = "file:///staged.jpg", caption = "Bark", capturedAt = 41L)))
        draft.validate()
        assertEquals(draft, CookNoteDraft.decode(draft.encode()))
        val incomplete = draft.copy(temperatures = listOf(draft.temperatures.single().copy(valueText = "-")))
        assertEquals(incomplete, CookNoteDraft.decode(incomplete.encode()))
        assertThrows(IllegalArgumentException::class.java) { incomplete.validate() }
        assertEquals("wrap", PlaybookCodec.action("note", draft.metadata.encode()))
        assertEquals("food_on", PlaybookCodec.action("meat_on", null))
    }
    @Test fun unsupportedVersionsActionsAndDuplicateScopesAreRejected() {
        val metadata = CookNoteMetadata("action", "food_on", listOf("a"))
        assertThrows(IllegalArgumentException::class.java) { CookNoteMetadata.decode(metadata.encode().replace("\"version\":1", "\"version\":2")) }
        assertThrows(IllegalArgumentException::class.java) { CookNoteDraft(cookId = "c", action = "unknown").validate() }
        assertThrows(IllegalArgumentException::class.java) { CookNoteDraft(cookId = "c", action = "food_on", dishIds = listOf("a", "a")).validate() }
        assertThrows(IllegalArgumentException::class.java) { CookNoteDraft(cookId = "c", temperatures = listOf(NoteTemperature(valueText = "NaN"))).validate() }
    }
}
