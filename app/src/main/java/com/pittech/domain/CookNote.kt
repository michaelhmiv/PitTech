package com.pittech.domain

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class CookNoteMetadata(val category: String = "general", val action: String? = null, val dishIds: List<String> = emptyList(),
    val method: String? = null, val durationMillis: Long? = null, val startedAtUtcMillis: Long? = null, val endedAtUtcMillis: Long? = null,
    val ingredients: List<IngredientDraft> = emptyList()) {
    val version get() = if (method != null || durationMillis != null || startedAtUtcMillis != null || endedAtUtcMillis != null || ingredients.isNotEmpty()) 2 else 1
    fun validate() {
        require(category in categories && (action == null || action in actions) && dishIds.distinct().size == dishIds.size)
        require(method == null || method in CookingMethods.labels) { "Choose a cooking method." }
        require(durationMillis == null || durationMillis in 0..2_592_000_000L) { "Use a duration up to 30 days." }
        require(startedAtUtcMillis == null || startedAtUtcMillis > 0)
        require(endedAtUtcMillis == null || endedAtUtcMillis > 0)
        require(startedAtUtcMillis == null || endedAtUtcMillis == null || endedAtUtcMillis >= startedAtUtcMillis && (durationMillis == null || durationMillis == endedAtUtcMillis - startedAtUtcMillis)) { "Duration must agree with the actual start and end." }
        require(ingredients.size <= 100 && ingredients.all { it.name.isNotBlank() && CookEntryValidation.isOptionalPositiveNumberValid(it.amountText) }) { "Add valid ingredient names and quantities." }
    }
    fun encode(): String = JSONObject().put("version", version).put("category", category)
        .put("action", action ?: JSONObject.NULL).put("dishIds", JSONArray(dishIds))
        .apply { if (version == 2) { put("method", method).put("durationMillis", durationMillis).put("startedAtUtcMillis", startedAtUtcMillis).put("endedAtUtcMillis", endedAtUtcMillis)
            put("ingredients", JSONArray(ingredients.map { i -> JSONObject().put("name", i.name).put("stage", i.stage).put("brand", i.brand).put("amount", i.amountText).put("unit", i.amountUnit) })) } }.toString()
    companion object {
        val categories = setOf("general", "temperature", "photo", "action")
        val actions get() = PlaybookCodec.actions.keys + setOf("preheat_start", "season", "trim", "marinate", "add_fuel")
        fun decode(raw: String?): CookNoteMetadata? {
            if (raw == null) return null
            val o = JSONObject(raw)
            require(o.getInt("version") in 1..2) { "This note format is not supported." }
            val category = o.getString("category")
            val action = if (o.isNull("action")) null else o.getString("action")
            val scopes = o.optJSONArray("dishIds")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            require(category in categories && (action == null || action in actions) && scopes.distinct().size == scopes.size)
            val advanced = o.getInt("version") == 2
            return CookNoteMetadata(category, action, scopes,
                if (advanced && !o.isNull("method")) o.getString("method") else null,
                if (advanced && !o.isNull("durationMillis")) o.getLong("durationMillis") else null,
                if (advanced && !o.isNull("startedAtUtcMillis")) o.getLong("startedAtUtcMillis") else null,
                if (advanced && !o.isNull("endedAtUtcMillis")) o.getLong("endedAtUtcMillis") else null,
                if (advanced) PlaybookCodec.objects(o, "ingredients").map { i -> IngredientDraft(i.getString("name"), i.optString("stage", "main_ingredient"), i.optString("brand"), i.optString("amount"), i.optString("unit")) } else emptyList()).also { it.validate() }
        }
    }
}

data class NoteTemperature(
    val id: String = UUID.randomUUID().toString(), val probeName: String = "Food",
    val measurementType: String = "food", val valueText: String = "", val unit: String = "°F",
    val measuredAt: Long = System.currentTimeMillis(), val dishId: String? = null, val probeId: String? = null,
)
data class NotePhoto(val id: String = UUID.randomUUID().toString(), val uri: String, val caption: String = "", val capturedAt: Long? = null)
data class CookNoteDraft(
    val id: String = UUID.randomUUID().toString(), val cookId: String, val dishId: String? = null,
    val category: String = "general", val action: String? = null, val dishIds: List<String> = emptyList(),
    val title: String = "", val description: String = "", val occurredAt: Long = System.currentTimeMillis(),
    val temperatures: List<NoteTemperature> = emptyList(), val photos: List<NotePhoto> = emptyList(),
    val method: String? = null, val durationMillis: Long? = null, val startedAtUtcMillis: Long? = null, val endedAtUtcMillis: Long? = null,
    val ingredients: List<IngredientDraft> = emptyList(),
) {
    val metadata get() = CookNoteMetadata(category, action, dishIds, method, durationMillis, startedAtUtcMillis, endedAtUtcMillis, ingredients)
    fun validate() {
        metadata.validate()
        require(category in CookNoteMetadata.categories && (action == null || action in CookNoteMetadata.actions))
        require(dishIds.distinct().size == dishIds.size && dishIds.all { it.isNotBlank() })
        require(title.isNotBlank() || description.isNotBlank() || action != null || temperatures.isNotEmpty() || photos.isNotEmpty() || method != null || durationMillis != null || ingredients.isNotEmpty()) { "Add a description, photo, temperature, or action." }
        require(temperatures.map { it.id }.distinct().size == temperatures.size && photos.map { it.id }.distinct().size == photos.size)
        temperatures.forEach {
            require(it.valueText.toDoubleOrNull()?.isFinite() == true && it.probeName.isNotBlank()) { "Enter a valid temperature and measurement name." }
            require(it.unit in setOf("°F", "°C") && it.measurementType in setOf("food", "food_probe", "pit", "pit_ambient"))
        }
    }
    fun encode(): String = JSONObject().put("version", 1).put("id", id).put("cookId", cookId)
        .put("dishId", dishId ?: JSONObject.NULL).put("metadata", JSONObject(metadata.encode()))
        .put("title", title).put("description", description).put("occurredAt", occurredAt)
        .put("temperatures", JSONArray(temperatures.map { t -> JSONObject().put("id", t.id).put("probeName", t.probeName)
            .put("measurementType", t.measurementType).put("valueText", t.valueText).put("unit", t.unit)
            .put("measuredAt", t.measuredAt).put("dishId", t.dishId ?: JSONObject.NULL).put("probeId", t.probeId ?: JSONObject.NULL) }))
        .put("photos", JSONArray(photos.map { p -> JSONObject().put("id", p.id).put("uri", p.uri)
            .put("caption", p.caption).put("capturedAt", p.capturedAt ?: JSONObject.NULL) })).toString()
    companion object {
        fun decode(raw: String): CookNoteDraft {
            val o = JSONObject(raw); require(o.getInt("version") == 1)
            val m = CookNoteMetadata.decode(o.getJSONObject("metadata").toString())!!
            val t = o.getJSONArray("temperatures"); val p = o.getJSONArray("photos")
            return CookNoteDraft(o.getString("id"), o.getString("cookId"), o.optional("dishId"), m.category, m.action, m.dishIds,
                o.getString("title"), o.getString("description"), o.getLong("occurredAt"),
                (0 until t.length()).map { i -> val v = t.getJSONObject(i); NoteTemperature(v.getString("id"), v.getString("probeName"), v.getString("measurementType"),
                    v.getString("valueText"), v.getString("unit"), v.getLong("measuredAt"), v.optional("dishId"), v.optional("probeId")) },
                (0 until p.length()).map { i -> val v = p.getJSONObject(i); NotePhoto(v.getString("id"), v.getString("uri"), v.optString("caption"),
                    if (v.isNull("capturedAt")) null else v.getLong("capturedAt")) },
                m.method, m.durationMillis, m.startedAtUtcMillis, m.endedAtUtcMillis, m.ingredients)
        }
        private fun JSONObject.optional(key: String): String? = if (isNull(key)) null else getString(key)
    }
}
