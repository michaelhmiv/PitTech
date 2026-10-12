package com.pittech.ui

import com.pittech.data.*
import com.pittech.domain.CookNoteMetadata
import com.pittech.domain.PlaybookCodec
import com.pittech.domain.CookPhases
import com.pittech.domain.DishPhase
import com.pittech.devices.GrillSamplingPolicy

internal enum class TimelineFilter(val label: String) {
    STORY("Cook story"), NOTES("Notes"), EVENTS("Actions"), PHOTOS("Photos"),
    TEMPERATURES("Temps"), GRILL("Grill & probes"), ALL("Everything")
}

internal fun foodChoices(dishes: List<DishEntity>): List<Pair<String, String>> = dishes.mapIndexed { index, dish ->
    dish.id to (dish.name + if (dishes.count { it.name == dish.name } > 1) " (" + (index + 1) + ")" else "")
}

/** Content filters describe the contents of a combined note, rather than splitting it. */
internal object CookFeedPresentation {
    fun isSetpoint(event: TimelineEventEntity) = event.eventType in setOf("setpoint_changed", "setpoint_recorded")
    fun isStory(event: TimelineEventEntity) = event.source == "manual" || isSetpoint(event) || event.eventType == "probe_assigned"
    fun matchesFood(event: TimelineEventEntity, foodId: String?): Boolean {
        if (foodId == null) return true
        val scopes = CookNoteMetadata.decode(event.noteMetadataJson)?.dishIds.orEmpty()
        return event.dishId == foodId || foodId in scopes ||
            (event.dishId == null && scopes.isEmpty() && !event.eventType.startsWith("removed_dish_"))
    }
    fun matches(event: TimelineEventEntity, filter: TimelineFilter, hasPhotos: Boolean, hasReadings: Boolean): Boolean =
        when (filter) {
            TimelineFilter.STORY -> isStory(event)
            TimelineFilter.NOTES -> event.source == "manual" && (event.eventType == "note" || !event.details.isNullOrBlank())
            TimelineFilter.EVENTS -> event.eventType in setOf("cook_started", "cook_finished", "reminder_completed", "probe_assigned") ||
                PlaybookCodec.action(event.eventType, event.noteMetadataJson) in CookNoteMetadata.actions
            TimelineFilter.PHOTOS -> hasPhotos || event.eventType == "photo"
            TimelineFilter.TEMPERATURES -> hasReadings
            TimelineFilter.GRILL -> event.source == "controller_cloud" || isSetpoint(event)
            TimelineFilter.ALL -> true
        }
    fun title(event: TimelineEventEntity): String {
        if (isSetpoint(event)) return event.title.replace("Grill setpoint changed to", "Grill set to")
        val action = PlaybookCodec.action(event.eventType, event.noteMetadataJson)
        val actionTitle = PlaybookCodec.actions[action]
        return when {
            actionTitle != null && (event.title.isBlank() || event.title in setOf("Cook note", "Note") || event.title == actionTitle) -> actionTitle
            event.title in setOf("Cook note", "Note", "Photo added") && !event.details.isNullOrBlank() ->
                event.details!!
            else -> event.title
        }
    }
}

internal data class FoodTemperature(val reading: SensorReadingEntity?, val probe: ProbeEntity? = null,
    val live: Boolean = false, val waiting: Boolean = false)

/** The latest sample's quality and current assignment take precedence over an old valid value. */
internal object FoodLivePresentation {
    fun phases(data: CookDetailData, end: Long) = CookPhases.all(
        notePlanEvents(data.events).let { events ->
            if (data.lifecycle?.timingMode == "explicit_phases") events else events.filter { it.dishId != null }
        }, data.dishes.map { it.id }, data.cook.startedAtUtcMillis, end)

    fun phaseElapsed(phase: DishPhase): Long = if (phase.phase in setOf("prep", "done")) 0 else
        phase.intervals.lastOrNull()?.takeIf { it.phase == phase.phase }
            ?.let { (it.end - it.start).coerceAtLeast(0) } ?: 0

    fun temperatures(foodId: String, probes: List<ProbeEntity>, assignments: List<ProbeAssignmentEntity>,
        readings: List<SensorReadingEntity>, recording: CookRecordingEntity?, now: Long): List<FoodTemperature> {
        val active = probes.filter { probe ->
            probe.measurementType == "food_probe" && (recording == null || probe.deviceId == recording.deviceId) &&
                probe.assignedDishId == foodId
        }
        if (active.isEmpty()) {
            val last = readings.filter { it.dishId == foodId && accepted(it) }.maxByOrNull { it.measuredAtUtcMillis }
            return listOf(FoodTemperature(last))
        }
        val values = active.map { probe ->
            val assignment = assignments.filter { it.probeId == probe.id && it.endedAtUtcMillis == null }
                .maxByOrNull { it.startedAtUtcMillis }
            val since = assignment?.startedAtUtcMillis ?: probe.createdAtUtcMillis
            val samples = readings.filter { it.probeId == probe.id && it.dishId == foodId && it.measuredAtUtcMillis >= since }
            val latest = samples.maxByOrNull { it.measuredAtUtcMillis }
            val last = samples.filter(::accepted).maxByOrNull { it.measuredAtUtcMillis }
            val live = latest != null && accepted(latest) && latest.source == "controller_cloud" &&
                recording?.status == CookRecordingEntity.RECORDING &&
                now >= latest.measuredAtUtcMillis &&
                now - latest.measuredAtUtcMillis <= GrillSamplingPolicy.receiptWindow(latest.samplingIntervalMillis)
            FoodTemperature(if (live) latest else last, probe, live, waiting = latest == null)
        }
        val manual = readings.filter { it.dishId == foodId && it.source == "manual" && accepted(it) }
            .maxByOrNull { it.measuredAtUtcMillis }
        return if (manual != null && values.none { it.live } &&
            manual.measuredAtUtcMillis > (values.mapNotNull { it.reading?.measuredAtUtcMillis }.maxOrNull() ?: Long.MIN_VALUE))
            listOf(FoodTemperature(manual)) + values.filter { it.waiting || it.reading == null } else values
    }
    fun accepted(reading: SensorReadingEntity) = reading.qualityStatus == "valid" && reading.value.isFinite() &&
        reading.unit in setOf("°F", "°C")
}
