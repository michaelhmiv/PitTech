package com.pittech.domain

import org.json.JSONArray
import org.json.JSONObject

object MealPresetCodec {
    fun items(raw: String): JSONArray = if (raw.trimStart().startsWith("[")) JSONArray(raw) else JSONObject(raw).let { require(it.getInt("version") == 1); it.getJSONArray("items") }
    fun setup(raw: String): NewCookDraft = if (raw.trimStart().startsWith("[")) NewCookDraft("") else PlaybookCodec.draft(JSONObject(raw).getJSONObject("setup"))
    fun encode(items: JSONArray, setup: NewCookDraft): String = JSONObject().put("version", 1).put("items", items)
        .put("setup", PlaybookCodec.draftJson(setup.copy(dishes = emptyList(), alreadyCooking = false, playbookId = null, followPlaybook = false))).toString()
}
