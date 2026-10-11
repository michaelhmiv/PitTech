package com.pittech.domain

data class PhaseInterval(val phase: String, val start: Long, val end: Long)
data class DishPhase(val dishId: String?, val phase: String, val intervals: List<PhaseInterval>, val foodOnAt: Long?) {
    fun duration(vararg phases: String) = intervals.filter { it.phase in phases }.sumOf { (it.end - it.start).coerceAtLeast(0) }
    val cookingMillis get() = duration("cooking", "wrapped")
    val restMillis get() = duration("resting")
    val holdMillis get() = duration("holding")
}

/** Physical time is anchored only by actual dish actions, never by recorder or guidance pauses. */
object CookPhases {
    val phaseActions = setOf("food_on", "wrap", "remove", "rest_start", "hold_start", "dish_done")
    private fun next(action: String) = when (action) { "food_on" -> "cooking"; "wrap" -> "wrapped"; "remove" -> "removed"; "rest_start" -> "resting"; "hold_start" -> "holding"; else -> "done" }
    fun dish(events: List<PlanEvent>, dishId: String?, sessionStart: Long, end: Long): DishPhase {
        val scoped = events.filter { (it.dishId == null || it.dishId == dishId) && it.action in phaseActions && it.at <= end }.sortedWith(compareBy<PlanEvent> { it.at }.thenBy { it.id })
        var phase = "prep"; var start = minOf(sessionStart, scoped.firstOrNull()?.at ?: sessionStart)
        val intervals = mutableListOf<PhaseInterval>()
        scoped.forEach { e ->
            val target = next(e.action)
            if (target != phase) { if (e.at > start) intervals += PhaseInterval(phase, start, e.at); phase = target; start = e.at }
        }
        if (end > start) intervals += PhaseInterval(phase, start, end)
        return DishPhase(dishId, phase, intervals, scoped.firstOrNull { it.action == "food_on" }?.at)
    }
    fun all(events: List<PlanEvent>, dishes: List<String>, start: Long, end: Long) =
        (dishes.map { it as String? }.ifEmpty { listOf(null) }).map { dish(events, it, start, end) }
    fun summary(phases: List<DishPhase>): String = phases.map { it.phase }.distinct().let { if (it.size == 1) label(it.first()) else "Mixed phases" }
    fun label(phase: String) = when (phase) { "prep" -> "Preparing"; "wrapped" -> "Wrapped"; "cooking" -> "Cooking"; "removed" -> "Removed"; "resting" -> "Resting"; "holding" -> "Holding"; "done" -> "Ready to serve"; else -> phase }
    fun validate(events: List<PlanEvent>, dishIds: List<String>) {
        (dishIds.map { it as String? }.ifEmpty { listOf(null) }).forEach { dish ->
            var phase = "prep"
            events.filter { (it.dishId == null || it.dishId == dish) && it.action in phaseActions }.sortedWith(compareBy<PlanEvent> { it.at }.thenBy { it.id }).forEach { e ->
                val valid = when (e.action) {
                    "food_on" -> phase in setOf("prep", "removed", "resting", "holding")
                    "wrap" -> phase in setOf("cooking", "wrapped")
                    "remove" -> phase in setOf("cooking", "wrapped")
                    "rest_start" -> phase in setOf("cooking", "wrapped", "removed")
                    "hold_start" -> phase in setOf("removed", "resting")
                    "dish_done" -> phase in setOf("removed", "resting", "holding")
                    else -> true
                }
                require(valid) { "${PlaybookCodec.actions[e.action] ?: e.action} is out of order for this dish. Check its actual time and earlier actions." }
                phase = next(e.action)
            }
        }
    }
    /** One smoker hour counts once even if several dishes are heating simultaneously. */
    fun heatingMillis(phases: List<DishPhase>): Long {
        val spans = phases.flatMap { p -> p.intervals.filter { it.phase in setOf("cooking", "wrapped") } }.sortedBy { it.start }
        if (spans.isEmpty()) return 0
        var start = spans.first().start; var end = spans.first().end; var sum = 0L
        spans.drop(1).forEach { if (it.start <= end) end = maxOf(end, it.end) else { sum += end - start; start = it.start; end = it.end } }
        return sum + end - start
    }
}
