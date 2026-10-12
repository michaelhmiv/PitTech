package com.pittech.domain

import org.json.JSONArray
import org.json.JSONObject

object RecipeSnapshotCodec {
    fun validate(s: RecipeSnapshot) {
        require(s.schemaVersion == 1) { "Unsupported recipe snapshot version." }
        require(s.name.isNotBlank() && s.name.length <= 200 && s.dish.name.isNotBlank()) { "Give this recipe and dish a name." }
        require(s.tags.size <= 100 && s.instructions.size <= 500 && s.observations.size <= 5000) { "This recipe contains too many entries." }
        require(s.instructions.map { it.id }.distinct().size == s.instructions.size && s.observations.map { it.sourceEventId }.distinct().size == s.observations.size) { "Recipe entries need unique identities." }
        require(s.instructions.all { it.title.isNotBlank() && (it.durationMillis == null || it.durationMillis >= 0) }) { "Use a title and a positive duration for instructions." }
        require(s.observations.all { it.occurredAtUtcMillis > 0 && it.offsetMillis == s.smokingAnchorUtcMillis?.let { anchor -> it.occurredAtUtcMillis - anchor } }) { "Historical offsets must use the recorded smoking anchor." }
        require(s.preferredPitTemperatureF == null || s.preferredPitTemperatureF.isFinite())
        require(s.rating == null || s.rating.isFinite())
        // Existing draft validation provides bounded ingredients and valid numeric quantities.
        PlaybookCodec.validate(CookPlaybook(s.name, NewCookDraft(title = s.name, dishes = listOf(s.dish)), emptyList(), s.targets))
        require(s.targets.all { it.dishIndex == 0 }) { "Recipe targets must belong to this dish." }
        s.dish.preparationItems.forEach { ingredient ->
            require(ingredient.amountText.isBlank() || ingredient.amountText.toDoubleOrNull()?.let { it.isFinite() && it >= 0 } == true) { "Use a non-negative ingredient amount." }
        }
    }
    fun encode(s: RecipeSnapshot): String {
        validate(s)
        return JSONObject().put("version", s.schemaVersion).put("name", s.name).put("description", s.description)
            .put("tags", JSONArray(s.tags)).put("draft", PlaybookCodec.draftJson(NewCookDraft(title = s.name, dishes = listOf(s.dish))))
            .put("instructions", JSONArray(s.instructions.map { i -> JSONObject().put("id", i.id).put("title", i.title).put("details", i.details).put("method", i.method).put("offsetMillis", i.offsetMillis).put("durationMillis", i.durationMillis).put("sourceEventId", i.sourceEventId) }))
            .put("observations", JSONArray(s.observations.map { e -> JSONObject().put("sourceEventId", e.sourceEventId).put("occurredAtUtcMillis", e.occurredAtUtcMillis).put("timeZoneId", e.timeZoneId).put("title", e.title).put("details", e.details).put("action", e.action).put("offsetMillis", e.offsetMillis).put("temperatureContextJson", e.temperatureContextJson) }))
            .put("targets", JSONArray(s.targets.map { t -> JSONObject().put("dish", t.dishIndex).put("type", t.type).put("value", t.value).put("unit", t.unit).put("explanation", t.explanation) }))
            .put("resultNotes", s.resultNotes).put("rating", s.rating).put("smokingAnchorUtcMillis", s.smokingAnchorUtcMillis)
            .put("preferredPitTemperatureF", s.preferredPitTemperatureF).put("servings", s.servings).toString()
    }
    fun decode(raw: String): RecipeSnapshot {
        val o = JSONObject(raw)
        val draft = PlaybookCodec.draft(o.getJSONObject("draft"))
        require(draft.dishes.size == 1) { "A saved recipe contains exactly one dish." }
        return RecipeSnapshot(o.getString("name"), draft.dishes.single(), o.optString("description"),
            o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            PlaybookCodec.objects(o, "instructions").map { i -> RecipeInstruction(i.getString("id"), i.getString("title"), i.optString("details"), i.nullText("method"), i.nullLong("offsetMillis"), i.nullLong("durationMillis"), i.nullText("sourceEventId")) },
            PlaybookCodec.objects(o, "observations").map { e -> RecipeObservation(e.getString("sourceEventId"), e.getLong("occurredAtUtcMillis"), e.getString("timeZoneId"), e.getString("title"), e.optString("details"), e.nullText("action"), e.nullLong("offsetMillis"), e.nullText("temperatureContextJson")) },
            PlaybookCodec.objects(o, "targets").map { t -> PlaybookTarget(t.getInt("dish"), t.getString("type"), t.getDouble("value"), t.getString("unit"), t.nullText("explanation")) },
            o.optString("resultNotes"), o.nullDouble("rating"), o.nullLong("smokingAnchorUtcMillis"), o.nullDouble("preferredPitTemperatureF"), o.optString("servings"), o.getInt("version")).also(::validate)
    }
    private fun JSONObject.nullText(k: String) = if (isNull(k)) null else getString(k)
    private fun JSONObject.nullLong(k: String) = if (isNull(k)) null else getLong(k)
    private fun JSONObject.nullDouble(k: String) = if (isNull(k)) null else getDouble(k)
}
