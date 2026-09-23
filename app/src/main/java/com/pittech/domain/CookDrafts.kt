package com.pittech.domain

data class IngredientDraft(
    val name: String,
    val stage: String = "seasoning",
    val brand: String = "",
    val amountText: String = "",
    val amountUnit: String = "tbsp",
)

data class DishDraft(
    val name: String,
    val foodType: String,
    val cut: String = "",
    val weightText: String = "",
    val weightUnit: String = "lb",
    val startingCondition: String? = null,
    val boneIn: Boolean? = null,
    val placement: String = "",
    val gradeOrSource: String = "",
    val thicknessNotes: String = "",
    val prepNotes: String = "",
    val preparationItems: List<IngredientDraft> = emptyList(),
    val photoUris: List<String> = emptyList(),
)

data class NewCookDraft(
    val title: String,
    val smokerName: String = "",
    val setpointText: String = "",
    val setpointUnit: String = "°F",
    val notes: String = "",
    val fuelType: String = "",
    val woodOrPelletBlend: String = "",
    val outdoorTemperatureText: String = "",
    val outdoorTemperatureUnit: String = "°F",
    val weatherNotes: String = "",
    val windNotes: String = "",
    val dishes: List<DishDraft> = emptyList(),
)

object CookEntryValidation {
    /** Blank values are intentionally allowed because cook details are optional. */
    fun optionalPositiveNumber(raw: String): Double? {
        if (raw.isBlank()) return null
        return raw.trim().toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() }
    }

    fun isOptionalPositiveNumberValid(raw: String): Boolean =
        raw.isBlank() || optionalPositiveNumber(raw) != null

    fun optionalFiniteNumber(raw: String): Double? {
        if (raw.isBlank()) return null
        return raw.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    fun isOptionalFiniteNumberValid(raw: String): Boolean = raw.isBlank() || optionalFiniteNumber(raw) != null
}
