package com.pittech.domain

data class MethodEvent(val id: String, val dishId: String?, val at: Long, val method: String)

object CookingMethods {
    val labels = linkedMapOf("prep" to "Preparation", "stovetop" to "Stovetop", "smoker" to "Smoker",
        "oven" to "Oven", "grill" to "Grill", "resting" to "Resting", "holding" to "Holding", "other" to "Other")
    /** Food on is a real smoker anchor; method changes subdivide those physical heating intervals. */
    fun smokerMillis(phases: List<DishPhase>, methods: List<MethodEvent>): Long {
        val spans = phases.flatMap { phase ->
            val scoped = methods.filter { it.dishId == null || it.dishId == phase.dishId }.sortedWith(compareBy<MethodEvent> { it.at }.thenBy { it.id })
            phase.intervals.filter { it.phase in setOf("cooking", "wrapped") }.flatMap { interval ->
                val boundaries = (listOf(interval.start, interval.end) + scoped.filter { it.at > interval.start && it.at < interval.end }.map { it.at }).distinct().sorted()
                boundaries.zipWithNext().mapNotNull { (start, end) ->
                    val method = scoped.lastOrNull { it.at <= start }?.method ?: "smoker"
                    if (method == "smoker") PhaseInterval("cooking", start, end) else null
                }
            }
        }
        return CookPhases.heatingMillis(listOf(DishPhase(null, "cooking", spans, null)))
    }
}
