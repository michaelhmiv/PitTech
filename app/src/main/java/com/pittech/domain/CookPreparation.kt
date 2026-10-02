package com.pittech.domain

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PrepCombo(val name: String, val items: List<IngredientDraft>, val yieldAmount: Double? = null, val yieldUnit: String? = null)
data class ChecklistItem(val id: String = UUID.randomUUID().toString(), val text: String, val category: String = "supplies", val done: Boolean = false)
data class EquipmentProfile(val name: String, val fuelType: String = "", val hopperBlend: String = "", val cleanEveryHours: Double? = null,
    val maintainedAtCookingHours: Double = 0.0, val maintainedAt: Long? = null)
data class FuelEntry(val equipmentName: String, val kilograms: Double, val cookId: String?, val at: Long, val type: String = "added")

object CookPreparation {
    private val compatibleUnits = setOf("g", "kg", "oz", "lb", "ml", "L", "tsp", "tbsp", "cup")
    fun encodeCombo(combo: PrepCombo): String {
        require(combo.name.isNotBlank() && combo.items.isNotEmpty() && combo.items.all { it.name.isNotBlank() && CookEntryValidation.isOptionalPositiveNumberValid(it.amountText) }) { "Name the combo and add valid ingredients." }
        require(combo.yieldAmount == null || combo.yieldAmount.isFinite() && combo.yieldAmount > 0 && !combo.yieldUnit.isNullOrBlank()) { "Enter a positive recipe yield and its unit." }
        return JSONObject().put("version", 1).put("name", combo.name).put("yield", combo.yieldAmount).put("yieldUnit", combo.yieldUnit).put("items", JSONArray(combo.items.map { i -> JSONObject().put("name", i.name).put("stage", i.stage).put("brand", i.brand).put("amount", i.amountText).put("unit", i.amountUnit) })).toString()
    }
    fun decodeCombo(raw: String): PrepCombo { val o = JSONObject(raw); require(o.getInt("version") == 1); return PrepCombo(o.getString("name"), PlaybookCodec.objects(o, "items").map { IngredientDraft(it.getString("name"), it.optString("stage", "seasoning"), it.optString("brand"), it.optString("amount"), it.optString("unit", "tbsp")) }, if (o.isNull("yield")) null else o.getDouble("yield"), if (o.isNull("yieldUnit")) null else o.getString("yieldUnit")).also { encodeCombo(it) } }
    fun scale(combo: PrepCombo, requestedYield: Double, yieldUnit: String): List<IngredientDraft> {
        require(combo.yieldAmount != null && combo.yieldUnit == yieldUnit && requestedYield.isFinite() && requestedYield > 0) { "Scaling requires an explicit recipe yield in the same unit." }
        require(combo.items.all { it.amountText.toDoubleOrNull()?.let { v -> v.isFinite() && v > 0 } == true && it.amountUnit in compatibleUnits }) { "Add numeric quantities and compatible units before scaling." }
        val factor = requestedYield / combo.yieldAmount
        return combo.items.map { it.copy(amountText = (it.amountText.toDouble() * factor).toString()) }
    }
    /** Aggregate only like quantities. Unmeasured or incompatible items remain independent. */
    fun ingredientChecklist(items: List<IngredientDraft>): List<ChecklistItem> = items.filter { it.name.isNotBlank() }
        .groupBy { i -> if (i.amountText.toDoubleOrNull() != null && i.amountUnit in compatibleUnits) "${i.name.trim().lowercase()}:${i.brand.trim().lowercase()}:${i.amountUnit}" else UUID.randomUUID().toString() }
        .values.map { group ->
            val first = group.first(); val amounts = group.mapNotNull { it.amountText.toDoubleOrNull() }
            val amount = if (amounts.size == group.size) " · ${amounts.sum()} ${first.amountUnit}" else first.amountText.takeIf { it.isNotBlank() }?.let { " · $it ${first.amountUnit}" }.orEmpty()
            ChecklistItem(text = first.name + first.brand.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty() + amount, category = "ingredients")
        }
    fun encodeChecklist(items: List<ChecklistItem>): String { require(items.size <= 200 && items.all { it.text.isNotBlank() } && items.map { it.id }.distinct().size == items.size); return JSONObject().put("version", 1).put("items", JSONArray(items.map { JSONObject().put("id", it.id).put("text", it.text).put("category", it.category).put("done", it.done) })).toString() }
    fun decodeChecklist(raw: String): List<ChecklistItem> { val o = JSONObject(raw); require(o.getInt("version") == 1); return PlaybookCodec.objects(o, "items").map { ChecklistItem(it.getString("id"), it.getString("text"), it.optString("category", "supplies"), it.optBoolean("done")) }.also { encodeChecklist(it) } }
    fun encodeEquipment(profile: EquipmentProfile): String { require(profile.name.isNotBlank() && profile.maintainedAtCookingHours.isFinite() && profile.maintainedAtCookingHours >= 0 && (profile.cleanEveryHours == null || profile.cleanEveryHours.isFinite() && profile.cleanEveryHours > 0)); return JSONObject().put("version", 1).put("name", profile.name).put("fuel", profile.fuelType).put("blend", profile.hopperBlend).put("intervalHours", profile.cleanEveryHours).put("maintainedHours", profile.maintainedAtCookingHours).put("maintainedAt", profile.maintainedAt).toString() }
    fun decodeEquipment(raw: String): EquipmentProfile { val o = JSONObject(raw); require(o.getInt("version") == 1); return EquipmentProfile(o.getString("name"), o.optString("fuel"), o.optString("blend"), if (o.isNull("intervalHours")) null else o.getDouble("intervalHours"), o.optDouble("maintainedHours"), if (o.isNull("maintainedAt")) null else o.getLong("maintainedAt")).also { encodeEquipment(it) } }
    fun encodeFuel(entry: FuelEntry): String { require((entry.type != "used" || !entry.cookId.isNullOrBlank()) && entry.at >= 0 && entry.type in setOf("added", "used") && entry.equipmentName.isNotBlank() && entry.kilograms.isFinite() && entry.kilograms > 0); return JSONObject().put("version", 1).put("equipment", entry.equipmentName).put("kg", entry.kilograms).put("cookId", entry.cookId).put("at", entry.at).put("type", entry.type).toString() }
    fun decodeFuel(raw: String): FuelEntry { val o = JSONObject(raw); require(o.getInt("version") == 1); return FuelEntry(o.getString("equipment"), o.getDouble("kg"), if (o.isNull("cookId")) null else o.getString("cookId"), o.getLong("at"), o.optString("type", "added")).also { encodeFuel(it) } }
    /** Union simultaneous dish intervals so one smoker hour is counted once. No setup/rest time. */
    fun cookingHours(events: List<PlanEvent>, dishes: List<String>, endedAt: Long?): Double {
        val intervals = dishes.mapNotNull { dish ->
            val scope = events.filter { it.dishId == dish || it.dishId == null }
            val on = scope.filter { it.action == "food_on" }.minOfOrNull { it.at } ?: return@mapNotNull null
            val off = scope.filter { it.action in setOf("remove", "rest_start", "hold_start", "dish_done") && it.at >= on }.minOfOrNull { it.at } ?: endedAt ?: return@mapNotNull null
            if (off <= on) null else on to off
        }.sortedBy { it.first }
        if (intervals.isEmpty()) return 0.0
        var start = intervals.first().first; var end = intervals.first().second; var sum = 0L
        intervals.drop(1).forEach { (a,b) -> if (a <= end) end = maxOf(end,b) else { sum += end - start; start = a; end = b } }
        sum += end - start
        return sum / 3_600_000.0
    }
}
