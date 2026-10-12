package com.pittech.data

import com.pittech.domain.*

fun methodEvents(events: List<TimelineEventEntity>): List<MethodEvent> = events.flatMap { event ->
    val metadata = CookNoteMetadata.decode(event.noteMetadataJson)
    val method = metadata?.method ?: if (PlaybookCodec.action(event.eventType, event.noteMetadataJson) == "food_on") "smoker" else null
    if (method == null) emptyList() else (metadata?.dishIds.orEmpty().ifEmpty { listOfNotNull(event.dishId) }.map { it as String? }.ifEmpty { listOf(null) })
        .map { MethodEvent(event.id, it, event.occurredAtUtcMillis, method) }
}
