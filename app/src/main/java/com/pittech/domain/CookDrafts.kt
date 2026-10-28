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
    /** The rub for this dish (Flowing §4.2): a named entry, or blank to
     * fall back to the rub-staged preparation item at cook creation.
     * Declared last: DishDraft has positional call sites. */
    val rub: String = "",
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
    val scheduledPlanId: String? = null,
    val playbookId: String? = null,
    val followPlaybook: Boolean = true,
    val recordGrill: Boolean = false,
    val alreadyCooking: Boolean = false,
    val foodOnAtUtcMillis: Long? = null,
    val probe1DishIndex: Int? = null,
    val probe2DishIndex: Int? = null,
    val probe3DishIndex: Int? = null,
    val probe4DishIndex: Int? = null,
    /** Saved-recipe revisions to attach when the cook starts, parallel to
     * [dishes] (null = that dish has no saved-recipe source). Empty means
     * no attachments. PL-19: a reviewed recipe meal keeps its sources. */
    val recipeRevisionIds: List<String?> = emptyList(),
    /** Working-copy recipe snapshots (encoded [com.pittech.domain.RecipeSnapshot]
     * JSON) to apply over the attached revision when the cook starts, parallel
     * to [dishes] (null = use the revision exactly as saved). Meal presets
     * store working copies edited during the original cook; PL-10 routes the
     * preset start through the Start Cook review without losing them. */
    val recipeAttemptSnapshots: List<String?> = emptyList(),
    /** Where this draft came from, shown on the Start Cook review when set
     * (for example a shared Cook Pack), so the source is never a surprise. */
    val sourceLabel: String? = null,
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
