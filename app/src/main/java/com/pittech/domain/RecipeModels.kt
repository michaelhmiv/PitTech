package com.pittech.domain

data class PublicRecipeSource(val itemId: String, val revisionId: String, val displayName: String, val removed: Boolean = false, val relativeTiming: Boolean = true) {
    fun validate() {
        val uuid = Regex("[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")
        require(itemId.matches(uuid) && revisionId.matches(uuid) && displayName.length <= 160)
    }
    val url get() = "https://pittech.app/c/$itemId"
}

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
    val noteMetadataJson: String? = null,
    val sourceCookId: String? = null,
    val sourceDishId: String? = null,
    val recordedTemperatures: List<RecipeMeasurement> = emptyList(),
)

data class RecipeInstruction(val id: String, val title: String, val details: String = "",
    val method: String? = null, val offsetMillis: Long? = null, val durationMillis: Long? = null,
    val sourceEventId: String? = null, val action: String? = null)

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
    val cookAgain: Boolean? = null,
    val publicSource: PublicRecipeSource? = null,
)

data class RecipeMeasurement(val sourceReadingId: String, val probeId: String?, val probeName: String, val measurementType: String,
    val value: Double, val unit: String, val measuredAtUtcMillis: Long, val timeZoneId: String, val sourceDishId: String?)
