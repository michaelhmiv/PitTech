package com.pittech.domain

/**
 * Structured cook-input capture (Flowing spec §4.2): the experiment's inputs
 * — wrap material, rub, spritz — recorded as data so compare (Spread 05) has
 * something to diff. Stored as rows in the existing results archive table
 * (resultType vocabulary below); absence of a row means NOT RECORDED, a
 * first-class state that is never backfilled by inference from notes.
 * The iOS CookInputs mirrors this file rule for rule.
 */
enum class WrapMaterial(val archiveValue: String, val label: String) {
    NONE("none", "No wrap"),
    FOIL("foil", "Foil"),
    BUTCHER_PAPER("butcher_paper", "Butcher paper");

    companion object {
        fun fromArchive(value: String?): WrapMaterial? = entries.firstOrNull { it.archiveValue == value }
    }
}

enum class SpritzState { NOT_RECORDED, NO, YES }

data class CookInputs(
    val wrapMaterial: WrapMaterial? = null,
    val rub: String? = null,
    val spritz: SpritzState = SpritzState.NOT_RECORDED,
    val spritzMedium: String? = null,
) {
    companion object { val NOT_RECORDED = CookInputs() }
}

/** One results-table row worth of capture data (the repository assigns ids). */
data class CookInputRow(val resultType: String, val textValue: String?)

object CookInputVocabulary {
    const val TYPE_WRAP_MATERIAL = "wrap_material"
    const val TYPE_RUB = "rub"
    const val TYPE_SPRITZ = "spritz"
    const val TYPE_SPRITZ_MEDIUM = "spritz_medium"
    const val NOT_RECORDED_LABEL = "Not recorded"

    /** The rows that persist [inputs] for one scope; a blank/absent field deletes its row. */
    fun rows(inputs: CookInputs): List<CookInputRow> = listOf(
        CookInputRow(TYPE_WRAP_MATERIAL, inputs.wrapMaterial?.archiveValue),
        CookInputRow(TYPE_RUB, inputs.rub?.trim()?.takeIf { it.isNotBlank() }),
        CookInputRow(TYPE_SPRITZ, when (inputs.spritz) {
            SpritzState.NO -> "no"
            SpritzState.YES -> "yes"
            SpritzState.NOT_RECORDED -> null
        }),
        CookInputRow(TYPE_SPRITZ_MEDIUM, inputs.spritzMedium?.trim()?.takeIf { it.isNotBlank() }),
    )

    /**
     * Read capture back from result rows. Dish-scoped rows win over
     * cook-scoped rows field by field. [hasSpritzEvent] is the timeline's
     * word: a logged spritz action means YES even when nobody answered the
     * medium prompt — the event is data, not inference.
     */
    fun fromResults(rows: List<InsightResultInput>, cookId: String, dishId: String?, hasSpritzEvent: Boolean = false): CookInputs {
        val scoped = rows.filter { it.cookId == cookId }
        fun pick(type: String): InsightResultInput? {
            val matches = scoped.filter { it.resultType == type }
            return matches.firstOrNull { it.dishId == dishId } ?: matches.firstOrNull { it.dishId == null }
        }
        val spritzRow = pick(TYPE_SPRITZ)?.textValue
        val spritz = when {
            spritzRow == "no" -> SpritzState.NO
            spritzRow == "yes" || hasSpritzEvent -> SpritzState.YES
            else -> SpritzState.NOT_RECORDED
        }
        return CookInputs(
            wrapMaterial = WrapMaterial.fromArchive(pick(TYPE_WRAP_MATERIAL)?.textValue),
            rub = pick(TYPE_RUB)?.textValue?.takeIf { it.isNotBlank() },
            spritz = spritz,
            spritzMedium = pick(TYPE_SPRITZ_MEDIUM)?.textValue?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * The prompt-time pre-fill for a wrap: a material named by the recipe's
     * or plan's wrap instruction, as whole words. This only preselects a
     * chip the cook confirms with the save — it never writes data itself.
     */
    fun wrapMaterialHint(texts: List<String>): WrapMaterial? {
        val joined = texts.joinToString(" ").lowercase()
        return when {
            Regex("""\bno wrap|\bunwrapped\b|\bnever wrap""").containsMatchIn(joined) -> WrapMaterial.NONE
            Regex("""\bbutcher paper\b|\bpeach paper\b|\bpink paper\b|\bpaper\b""").containsMatchIn(joined) -> WrapMaterial.BUTCHER_PAPER
            Regex("""\bfoil\b""").containsMatchIn(joined) -> WrapMaterial.FOIL
            else -> null
        }
    }

    /** The rub a recipe names for a dish, from its staged ingredients. */
    fun rubFromIngredients(ingredients: List<IngredientDraft>): String? =
        ingredients.firstOrNull { it.stage.equals("rub", ignoreCase = true) }?.name?.trim()?.takeIf { it.isNotBlank() }
}
