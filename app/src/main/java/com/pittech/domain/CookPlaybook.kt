package com.pittech.domain

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A trigger asks the cook to check; reaching a threshold never confirms a physical action. */
data class PlaybookStep(
    val id: String = UUID.randomUUID().toString(),
    val dishIndex: Int? = null,
    val action: String = "check_in",
    val title: String = "Check the cook",
    val instructions: String = "",
    val trigger: String = "manual",
    val minutes: Long = 0,
    val temperatureF: Double? = null,
    val anchor: String = "food_on",
    val clockAtUtcMillis: Long? = null,
    val repeatMinutes: Long? = null,
    val stage: String = "cooking",
)

data class PlaybookTarget(val dishIndex: Int?, val type: String, val value: Double, val unit: String, val explanation: String?)
data class CookPlaybook(
    val name: String,
    val draft: NewCookDraft,
    val steps: List<PlaybookStep>,
    val targets: List<PlaybookTarget> = emptyList(),
    val sourceCookId: String? = null,
    val revision: Int = 1,
    val previousRevisionId: String? = null,
    val keepDoing: String = "",
    val changeNextTime: String = "",
)

object PlaybookCodec {
    val actions = linkedMapOf("food_on" to "Food on", "spritz" to "Spritz", "wrap" to "Wrap", "check_in" to "Check", "remove" to "Remove", "rest_start" to "Start rest", "hold_start" to "Start hold", "dish_done" to "Ready to serve")
    fun action(eventType: String) = when (eventType) { "meat_on" -> "food_on"; "rest" -> "rest_start"; else -> eventType }
    fun eventType(action: String) = when (action) { "food_on" -> "meat_on"; "rest_start" -> "rest"; else -> action }
    val triggers = linkedMapOf("manual" to "When ready", "elapsed" to "After food goes on", "after_action" to "After an action", "temperature" to "At a temperature", "clock" to "At a time")
    fun validate(book: CookPlaybook) {
        require(book.name.isNotBlank() && book.name.length <= 200) { "Give this playbook a name." }
        require(book.draft.dishes.size <= 30 && book.steps.size <= 100) { "This playbook has too many dishes or steps." }
        require(book.steps.map { it.id }.distinct().size == book.steps.size) { "Each step needs its own ID." }
        book.steps.forEach { s ->
            require(s.title.isNotBlank() && s.action in actions && s.trigger in triggers && s.stage in setOf("cooking", "resting", "holding")) { "Choose a valid action and trigger for each step." }
            require(s.dishIndex == null || s.dishIndex in book.draft.dishes.indices) { "Choose a dish for this step." }
            require(s.minutes in 0..100_800 && (s.repeatMinutes == null || s.repeatMinutes in 1..10_080)) { "Use a positive interval, up to a week." }
            require(s.trigger != "temperature" || s.temperatureF?.let { it.isFinite() && it in 32.0..600.0 } == true) { "Enter a temperature between 32 and 600 °F." }
            require(s.trigger != "clock" || s.clockAtUtcMillis != null) { "Choose a time for this step." }
            require(s.anchor in actions) { "Choose an anchor action." }
        }
        require(book.targets.all { it.value.isFinite() && it.unit in setOf("°F", "°C") && (it.dishIndex == null || it.dishIndex in book.draft.dishes.indices) }) { "A target is invalid." }
    }
    fun encode(book: CookPlaybook): String {
        validate(book)
        return JSONObject().put("version", 1).put("name", book.name).put("draft", draftJson(book.draft))
            .put("steps", JSONArray(book.steps.map(::stepJson))).put("targets", JSONArray(book.targets.map { t -> JSONObject().put("dish", t.dishIndex).put("type", t.type).put("value", t.value).put("unit", t.unit).put("explanation", t.explanation) }))
            .put("sourceCookId", book.sourceCookId).put("revision", book.revision).put("previousRevisionId", book.previousRevisionId)
            .put("keepDoing", book.keepDoing).put("changeNextTime", book.changeNextTime).toString()
    }
    fun decode(raw: String): CookPlaybook {
        val o = JSONObject(raw)
        require(o.getInt("version") == 1) { "Unsupported playbook version." }
        return CookPlaybook(o.getString("name"), draft(o.getJSONObject("draft")), objects(o, "steps").map(::step),
            objects(o, "targets").map { PlaybookTarget(it.nullInt("dish"), it.getString("type"), it.getDouble("value"), it.getString("unit"), it.nullString("explanation")) },
            o.nullString("sourceCookId"), o.optInt("revision", 1), o.nullString("previousRevisionId"), o.optString("keepDoing"), o.optString("changeNextTime")).also(::validate)
    }
    fun stepJson(s: PlaybookStep) = JSONObject().put("id", s.id).put("dish", s.dishIndex).put("action", s.action).put("title", s.title).put("instructions", s.instructions).put("trigger", s.trigger).put("minutes", s.minutes).put("temperatureF", s.temperatureF).put("anchor", s.anchor).put("clock", s.clockAtUtcMillis).put("repeat", s.repeatMinutes).put("stage", s.stage)
    fun step(o: JSONObject) = PlaybookStep(o.getString("id"), o.nullInt("dish"), o.getString("action"), o.getString("title"), o.optString("instructions"), o.optString("trigger", "manual"), o.optLong("minutes"), if (o.isNull("temperatureF")) null else o.getDouble("temperatureF"), o.optString("anchor", "food_on"), if (o.isNull("clock")) null else o.getLong("clock"), if (o.isNull("repeat")) null else o.getLong("repeat"), o.optString("stage", "cooking"))
    fun draftJson(d: NewCookDraft): JSONObject = JSONObject().put("title", d.title).put("smoker", d.smokerName).put("setpoint", d.setpointText).put("unit", d.setpointUnit).put("notes", d.notes).put("fuel", d.fuelType).put("wood", d.woodOrPelletBlend).put("dishes", JSONArray(d.dishes.map { x -> JSONObject().put("name", x.name).put("foodType", x.foodType).put("cut", x.cut).put("weight", x.weightText).put("weightUnit", x.weightUnit).put("startingCondition", x.startingCondition).put("boneIn", x.boneIn).put("placement", x.placement).put("grade", x.gradeOrSource).put("thickness", x.thicknessNotes).put("prep", x.prepNotes).put("ingredients", JSONArray(x.preparationItems.map { i -> JSONObject().put("name", i.name).put("stage", i.stage).put("brand", i.brand).put("amount", i.amountText).put("unit", i.amountUnit) })) }))
    fun draft(o: JSONObject) = NewCookDraft(title = o.getString("title"), smokerName = o.optString("smoker"), setpointText = o.optString("setpoint"), setpointUnit = o.optString("unit", "°F"), notes = o.optString("notes"), fuelType = o.optString("fuel"), woodOrPelletBlend = o.optString("wood"), dishes = objects(o, "dishes").map { x -> DishDraft(x.getString("name"), x.getString("foodType"), x.optString("cut"), x.optString("weight"), x.optString("weightUnit", "lb"), x.nullString("startingCondition"), if (x.isNull("boneIn")) null else x.getBoolean("boneIn"), x.optString("placement"), x.optString("grade"), x.optString("thickness"), x.optString("prep"), objects(x, "ingredients").map { i -> IngredientDraft(i.getString("name"), i.optString("stage", "seasoning"), i.optString("brand"), i.optString("amount"), i.optString("unit", "tbsp")) }) })
    fun objects(o: JSONObject, key: String): List<JSONObject> = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
    fun JSONObject.nullInt(key: String): Int? = if (isNull(key)) null else getInt(key)
    fun JSONObject.nullString(key: String): String? = if (isNull(key)) null else getString(key)
}
