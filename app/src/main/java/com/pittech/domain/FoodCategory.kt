package com.pittech.domain

import java.util.Locale

/**
 * One entry in PitTech's shared food taxonomy. The canonical list lives in
 * `contracts/fixtures/food-categories-v1.json` (owner decision, 2026-10-27):
 * publishing categories are a controlled dropdown, never free text, so a
 * publication carries exactly one of these ids and Discover filters by it.
 * Ids, labels, examples and display order here must stay identical to that
 * file — a JVM contract test pins them together — and the backend validates
 * publication payloads against the same list as an enum.
 */
data class FoodCategory(val id: String, val label: String, val examples: List<String>)

object FoodCategories {
    const val DEFAULT_ID = "other"

    /** The full taxonomy in display order: alphabetical, with Other pinned last. */
    val all: List<FoodCategory> = listOf(
        FoodCategory("apps-snacks", "Apps & Snacks", listOf("Smoked cheese", "Queso", "Smoked nuts", "Deviled eggs", "Pig shots")),
        FoodCategory("beef", "Beef", listOf("Brisket", "Beef ribs", "Steaks")),
        FoodCategory("chicken", "Chicken", listOf("Whole chicken", "Wings", "Thighs")),
        FoodCategory("desserts", "Desserts", listOf("Cobbler", "Pies")),
        FoodCategory("lamb-game", "Lamb & Game", listOf("Lamb", "Venison", "Duck")),
        FoodCategory("pork", "Pork", listOf("Pork ribs", "Boston butt", "Pork belly", "Ham")),
        FoodCategory("sausage-links", "Sausage & Links", listOf("Brats", "Kielbasa", "Hot links")),
        FoodCategory("seafood", "Seafood", listOf("Salmon", "Shrimp", "Fish")),
        FoodCategory("sides", "Sides", listOf("Beans", "Mac & cheese", "Slaw", "Potatoes", "Cornbread")),
        FoodCategory("turkey", "Turkey", listOf("Whole turkey", "Turkey breast")),
        FoodCategory("veggies", "Veggies", listOf("Corn", "Peppers", "Cabbage")),
        FoodCategory("other", "Other", listOf("Anything that does not fit the rest")),
    )

    val ids: Set<String> = all.map { it.id }.toSet()

    fun byId(id: String?): FoodCategory? = all.firstOrNull { it.id == id }

    fun isValid(id: String?): Boolean = id != null && id in ids

    /** The id a value counts as: itself when it is in the taxonomy, otherwise the default (Other). */
    fun normalizedId(id: String?): String = if (isValid(id)) id!! else DEFAULT_ID

    fun labelFor(id: String?): String = byId(id)?.label ?: byId(DEFAULT_ID)!!.label

    /**
     * Pre-selection heuristic only: maps a dish's existing free-text/native
     * food type to the category a person would most likely pick for it, so a
     * legacy draft or a fresh publish dialog opens with a sensible category
     * already chosen. It never locks anything in — the user can always change
     * the selection before publishing. Case-insensitive substring rules, in
     * order; anything unrecognized falls back to Other.
     */
    fun suggestedIdFor(foodType: String?): String {
        val text = foodType?.lowercase(Locale.ROOT).orEmpty()
        if (text.isBlank()) return DEFAULT_ID
        return when {
            text.contains("beef") -> "beef"
            text.contains("pork") -> "pork"
            text.contains("chicken") -> "chicken"
            text.contains("turkey") -> "turkey"
            text.contains("poultry") -> "chicken"
            text.contains("seafood") || text.contains("fish") || text.contains("salmon") || text.contains("shrimp") -> "seafood"
            text.contains("wild game") || text.contains("game") || text.contains("lamb") || text.contains("venison") || text.contains("duck") -> "lamb-game"
            text.contains("sausage") -> "sausage-links"
            text.contains("vegetable") || text.contains("veggie") -> "veggies"
            text.contains("dessert") -> "desserts"
            text.contains("side") -> "sides"
            text.contains("snack") || text.contains("appetizer") -> "apps-snacks"
            else -> DEFAULT_ID
        }
    }
}
