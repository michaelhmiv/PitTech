package com.pittech.data

import androidx.room.withTransaction
import com.pittech.domain.*
import java.util.UUID

data class EquipmentSummary(val id: String, val profile: EquipmentProfile, val cookingHours: Double, val burnRangeKgPerHour: Pair<Double,Double>?, val recordedFuelCooks: Int) {
    val maintenanceDue: Boolean get() = profile.cleanEveryHours?.let { cookingHours - profile.maintainedAtCookingHours >= it } == true
}
class CookPreparationRepository(private val db: PitTechDatabase) {
    private suspend fun save(kind: String, title: String, payload: String, cookId: String? = null, id: String? = null): String {
        val key = id ?: UUID.randomUUID().toString(); val now = System.currentTimeMillis(); val old = db.companionDao().get(key)
        db.companionDao().put(CompanionRecord(key, kind, cookId, title, payload, old?.createdAtUtcMillis ?: now, now)); return key
    }
    suspend fun saveCombo(combo: PrepCombo): String = save("prep_combo", combo.name, CookPreparation.encodeCombo(combo))
    suspend fun saveEquipment(profile: EquipmentProfile, id: String? = null): String {
        val existing = db.companionDao().all().filter { it.kind == "equipment" && it.id != id }
        require(existing.none { CookPreparation.decodeEquipment(it.payload).name.equals(profile.name.trim(), true) }) { "An equipment profile already uses this name." }
        return save("equipment", profile.name.trim(), CookPreparation.encodeEquipment(profile.copy(name = profile.name.trim())), id = id)
    }
    suspend fun addFuel(entry: FuelEntry): String {
        entry.cookId?.let { id -> require(db.cookDao().getCook(id)?.smokerName?.equals(entry.equipmentName, true) == true) { "Choose a cook recorded on this equipment." } }
        return save("fuel", "${entry.type} fuel", CookPreparation.encodeFuel(entry), entry.cookId)
    }
    suspend fun createChecklist(cookId: String) = db.withTransaction {
        if (db.companionDao().get("checklist:$cookId") != null) return@withTransaction
        val cook = db.cookDao().getCook(cookId) ?: error("Cook not found.")
        val ingredients = db.cookDao().getIngredientsForCook(cookId).map { IngredientDraft(it.name, it.stage, it.brand.orEmpty(), it.amountValue?.toString().orEmpty(), it.amountUnit.orEmpty()) }
        val dishes = db.cookDao().getDishesForCook(cookId)
        val items = CookPreparation.ingredientChecklist(ingredients) + listOf(ChecklistItem(text = "Fuel${cook.fuelType?.let { " · $it" }.orEmpty()}${cook.woodOrPelletBlend?.let { " · $it" }.orEmpty()}"), ChecklistItem(text = "Thermometer"), ChecklistItem(text = "Heat-resistant gloves"), ChecklistItem(text = "Wrapping material (if using)")) +
            dishes.mapNotNull { d -> d.prepNotes?.takeIf { it.isNotBlank() }?.let { ChecklistItem(text = "${d.name}: $it", category = "prep") } }
        save("checklist", "Prep checklist", CookPreparation.encodeChecklist(items), cookId, "checklist:$cookId")
    }
    suspend fun saveChecklist(cookId: String, items: List<ChecklistItem>) = save("checklist", "Prep checklist", CookPreparation.encodeChecklist(items), cookId, "checklist:$cookId")
    suspend fun check(cookId: String, itemId: String, done: Boolean) = db.withTransaction {
        val r = db.companionDao().get("checklist:$cookId") ?: return@withTransaction
        saveChecklist(cookId, CookPreparation.decodeChecklist(r.payload).map { if (it.id == itemId) it.copy(done = done) else it })
    }
    suspend fun summaries(now: Long = System.currentTimeMillis()): List<EquipmentSummary> {
        val records = db.companionDao().all(); val cooks = db.cookDao().getAllCooks(); val dishes = db.cookDao().getAllDishes().groupBy { it.cookId }; val events = db.cookDao().getAllTimelineEvents().groupBy { it.cookId }
        val hours = cooks.associate { cook -> cook.id to CookPreparation.cookingHours(events[cook.id].orEmpty().map { PlanEvent(it.id, PlaybookCodec.action(it.eventType), it.dishId, it.occurredAtUtcMillis) }, dishes[cook.id].orEmpty().map { it.id }, cook.endedAtUtcMillis ?: now) }
        return records.filter { it.kind == "equipment" }.map { r ->
            val p = CookPreparation.decodeEquipment(r.payload)
            val matching = cooks.filter { it.smokerName?.equals(p.name, true) == true }
            val matchingIds = matching.map { it.id }.toSet()
            val used = records.filter { it.kind == "fuel" }.map { CookPreparation.decodeFuel(it.payload) }.filter { it.type == "used" && it.equipmentName.equals(p.name, true) && it.cookId in matchingIds }
            val rates = used.groupBy { it.cookId }.mapNotNull { (id, rows) -> val cook = matching.firstOrNull { it.id == id && it.status == CookStatus.COMPLETED }; val h = hours[id] ?: 0.0; if (cook != null && h > 0) rows.sumOf { it.kilograms } / h else null }
            EquipmentSummary(r.id, p, matching.sumOf { hours[it.id] ?: 0.0 }, if (rates.size >= 2) rates.min() to rates.max() else null, rates.size)
        }
    }
    suspend fun maintained(id: String) {
        val summary = summaries().firstOrNull { it.id == id } ?: return
        saveEquipment(summary.profile.copy(maintainedAtCookingHours = summary.cookingHours, maintainedAt = System.currentTimeMillis()), id)
    }
}
