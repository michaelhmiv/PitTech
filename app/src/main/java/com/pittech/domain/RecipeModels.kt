package com.pittech.domain

/** Recorded observations retain identity/time; editable instructions never replace those facts. */
data class RecipeObservation(
    val sourceEventId: String,
    val occurredAtUtcMillis: Long,
    val timeZoneId: String,
    val title: String,
    val details: String,
    val action: String?,
    val offsetMillis: Long?,
    val temperatureContextJson: String?,
)

data class RecipeInstruction(val id: String, val title: String, val details: String = "",
    val method: String? = null, val offsetMillis: Long? = null, val durationMillis: Long? = null,
    val sourceEventId: String? = null)

data class RecipeSnapshot(
    val name: String,
    val dish: DishDraft,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val instructions: List<RecipeInstruction> = emptyList(),
    val observations: List<RecipeObservation> = emptyList(),
    val targets: List<PlaybookTarget> = emptyList(),
    val resultNotes: String = "",
    val rating: Double? = null,
    val smokingAnchorUtcMillis: Long? = null,
    val preferredPitTemperatureF: Double? = null,
    val servings: String = "",
    val schemaVersion: Int = 1,
)
