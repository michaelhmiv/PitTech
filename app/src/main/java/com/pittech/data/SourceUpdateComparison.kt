package com.pittech.data

import com.pittech.domain.RecipeSnapshot
import com.pittech.domain.RecipeSnapshotCodec
import org.json.JSONObject

internal data class SourceComparisonSection(val title: String,val saved: List<String>,val shared: List<String>) { val changed get()=saved!=shared }
internal object SourceUpdateComparison {
    fun sections(snapshot: RecipeSnapshot,recipe: JSONObject): List<SourceComparisonSection> {
        val ingredients=recipe.rows("ingredients");val instructions=recipe.rows("instructions");require(ingredients.size<=100 && instructions.size<=100)
        val setup=JSONObject(RecipeSnapshotCodec.encode(snapshot)).getJSONObject("draft").getJSONArray("dishes").getJSONObject(0)
        fun text(row: JSONObject,key: String,limit: Int): String { if(row.isNull(key))return "";val value=row.get(key);require(value is String && value.length<=limit);return value }
        val savedIngredients=setup.rows("ingredients").map { row->listOf(text(row,"name",160),text(row,"brand",160),listOf(text(row,"amount",2000),text(row,"unit",2000)).filter { it.isNotEmpty() }.joinToString(" ")).filter { it.isNotEmpty() }.joinToString(" · ") }
        val sharedIngredients=ingredients.map { row->listOf(text(row,"name",160),text(row,"amount",2000)).filter { it.isNotEmpty() }.joinToString(" · ") }
        fun step(row: JSONObject,shared: Boolean): String {
            val fields=listOf(text(row,"title",160),text(row,"details",2000),text(row,"method",160),text(row,"action",80)).filter { it.isNotEmpty() }.toMutableList()
            val key=if(shared)"suggestedOffsetMillis" else "offsetMillis"
            if(!row.isNull(key)) { val value=row.getDouble(key);require(value.isFinite() && value==row.getLong(key).toDouble() && row.getLong(key) in -604800000L..2592000000L);fields+="Suggested offset · "+com.pittech.domain.RecipeReferenceTimeline.relativeLabel(row.getLong(key)) }
            return fields.joinToString(" · ")
        }
        val encoded=JSONObject(RecipeSnapshotCodec.encode(snapshot))
        return listOf(SourceComparisonSection("Ingredients",savedIngredients,sharedIngredients),SourceComparisonSection("Instructions in order",encoded.rows("instructions").map { step(it,false) },instructions.map { step(it,true) }))
    }
}
private fun JSONObject.rows(key: String)=getJSONArray(key).let { a->(0 until a.length()).map(a::getJSONObject) }
