package com.pittech.domain

import org.json.JSONArray
import org.json.JSONObject

/**
 * PL-13 (#172): the Start Cook setup draft kept on this device when a setup
 * is left mid-edit, so leaving resumes instead of discarding. The draft is
 * device-local state only — it is stored in app-private preferences, never
 * exported, synced, or written into the library archive.
 *
 * The codec covers every user-entered Start Cook field, including the
 * source attachments (recipe revisions, playbook / serve-plan ids and the
 * source label) so a resumed sourced setup is still the same setup.
 */
object SetupDraftCodec {
    private const val VERSION = 1

    fun encode(draft: NewCookDraft): String {
        val root = JSONObject()
            .put("version", VERSION)
            .put("draft", PlaybookCodec.draftJson(draft))
            .put("outdoorTemperature", draft.outdoorTemperatureText)
            .put("outdoorUnit", draft.outdoorTemperatureUnit)
            .put("weather", draft.weatherNotes)
            .put("wind", draft.windNotes)
            .put("followPlaybook", draft.followPlaybook)
            .put("alreadyCooking", draft.alreadyCooking)
            .put("sourceLabel", draft.sourceLabel ?: JSONObject.NULL)
            .put("playbookId", draft.playbookId ?: JSONObject.NULL)
            .put("scheduledPlanId", draft.scheduledPlanId ?: JSONObject.NULL)
            .put("recipeRevisionIds", JSONArray(draft.recipeRevisionIds.map { it ?: JSONObject.NULL }))
            .put("recipeAttemptSnapshots", JSONArray(draft.recipeAttemptSnapshots.map { it ?: JSONObject.NULL }))
        draft.foodOnAtUtcMillis?.let { root.put("foodOnAtUtcMillis", it) }
        draft.dishes.forEachIndexed { index, dish ->
            if (dish.photoUris.isNotEmpty()) root.put("photos$index", JSONArray(dish.photoUris))
        }
        return root.toString()
    }

    fun decode(raw: String): NewCookDraft {
        val root = JSONObject(raw)
        require(root.optInt("version", VERSION) == VERSION) { "Unsupported setup draft version." }
        val base = PlaybookCodec.draft(root.getJSONObject("draft"))
        val dishes = base.dishes.mapIndexed { index, dish ->
            val photos = root.optJSONArray("photos$index")?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty()
            if (photos.isEmpty()) dish else dish.copy(photoUris = photos)
        }
        return base.copy(
            dishes = dishes,
            outdoorTemperatureText = root.optString("outdoorTemperature"),
            outdoorTemperatureUnit = root.optString("outdoorUnit", "°F"),
            weatherNotes = root.optString("weather"),
            windNotes = root.optString("wind"),
            followPlaybook = root.optBoolean("followPlaybook", true),
            alreadyCooking = root.optBoolean("alreadyCooking", false),
            foodOnAtUtcMillis = if (root.has("foodOnAtUtcMillis")) root.getLong("foodOnAtUtcMillis") else null,
            sourceLabel = root.nullString("sourceLabel"),
            playbookId = root.nullString("playbookId"),
            scheduledPlanId = root.nullString("scheduledPlanId"),
            recipeRevisionIds = root.optJSONArray("recipeRevisionIds")?.let { array -> (0 until array.length()).map { if (array.isNull(it)) null else array.getString(it) } }.orEmpty(),
            recipeAttemptSnapshots = root.optJSONArray("recipeAttemptSnapshots")?.let { array -> (0 until array.length()).map { if (array.isNull(it)) null else array.getString(it) } }.orEmpty(),
        )
    }

    private fun JSONObject.nullString(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
}
