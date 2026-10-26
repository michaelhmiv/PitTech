package com.pittech.data

import androidx.room.withTransaction
import com.pittech.domain.CookNoteDraft
import com.pittech.domain.NotePhoto
import java.time.ZoneId
import java.util.UUID

/** One human entry owns its manual samples. Photos are copied before the atomic DB commit. */
class CookNoteRepository(private val database: PitTechDatabase, private val storage: PhotoStorage,
    private val cooks: CookRepository) {
    private val dao = database.cookDao()
    private val experience = database.experienceDao()

    suspend fun persistDraft(draft: CookNoteDraft) {
        require(dao.getCook(draft.cookId) != null) { "This cook no longer exists." }
        experience.putDraft(CookLogDraft(draft.id, draft.cookId, draft.encode(), System.currentTimeMillis()))
    }
    suspend fun discardDraft(id: String) {
        experience.deleteDraft(id)
        storage.deleteDraftFiles(id)
    }
    suspend fun stagePhoto(draftId: String, uri: String): NotePhoto = storage.stageNotePhoto(draftId, uri)

    suspend fun save(draft: CookNoteDraft): CookLogSaveResult {
        draft.validate()
        val cook = dao.getCook(draft.cookId) ?: error("This cook no longer exists.")
        require(draft.dishId == null || dao.getDish(draft.dishId)?.cookId == draft.cookId)
        require(draft.dishIds.all { dao.getDish(it)?.cookId == draft.cookId })
        require(dao.getTimelineEvent(draft.id)?.cookId?.let { it == draft.cookId } != false)
        require(cook.endedAtUtcMillis == null || draft.action == null || draft.occurredAt <= cook.endedAtUtcMillis) { "An action on a finished cook must use its actual time before completion." }
        persistDraft(draft)
        val copied = mutableListOf<PhotoEntity>()
        var photoAttached = true
        draft.photos.forEach { photo ->
            val previous = experience.photo(photo.id)
            require(previous == null || previous.cookId == draft.cookId && (previous.eventId == null || previous.eventId == draft.id))
            if (previous != null) copied += previous.copy(eventId = draft.id, caption = photo.caption.ifBlank { null })
            else try {
                copied += storage.copyIntoLibrary(photo.uri, draft.cookId, draft.dishId, System.currentTimeMillis(),
                    draft.id, photo.caption, stableId = photo.id).let { it.copy(capturedAtUtcMillis = photo.capturedAt ?: it.capturedAtUtcMillis) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { photoAttached = false }
        }
        val event = database.withTransaction {
            val previous = dao.getTimelineEvent(draft.id)
            require(previous == null || previous.cookId == draft.cookId)
            val now = System.currentTimeMillis()
            val title = draft.title.trim().ifBlank { draft.description.lineSequence().firstOrNull()?.trim()?.take(80).orEmpty() }
                .ifBlank { draft.action?.let { com.pittech.domain.PlaybookCodec.actions[it] } ?: when (draft.category) { "temperature" -> "Temperature note"; "photo" -> "Photo note"; else -> "Cook note" } }
            val note = TimelineEventEntity(draft.id, draft.cookId, draft.dishId, "note", title, draft.description.trim().ifBlank { null },
                draft.occurredAt, previous?.recordedAtUtcMillis ?: now, previous?.timeZoneId ?: ZoneId.systemDefault().id,
                "manual", createdAtUtcMillis = previous?.createdAtUtcMillis ?: now, updatedAtUtcMillis = now,
                temperatureContextJson = if (previous != null && previous.occurredAtUtcMillis == draft.occurredAt && previous.dishId == draft.dishId) previous.temperatureContextJson else cooks.temperatureContext(draft.cookId, draft.occurredAt, draft.dishId),
                noteMetadataJson = draft.metadata.encode())
            if ((draft.action in com.pittech.domain.CookPhases.phaseActions || previous?.let { com.pittech.domain.PlaybookCodec.action(it.eventType, it.noteMetadataJson) in com.pittech.domain.CookPhases.phaseActions } == true) && experience.lifecycle(draft.cookId)?.timingMode == "explicit_phases") {
                require(draft.occurredAt > 0 && draft.occurredAt <= now + 60_000) { "Choose the actual action time, up to now." }
                val proposed = dao.getTimelineEventsForCook(draft.cookId).filterNot { it.id == draft.id } + note
                com.pittech.domain.CookPhases.validate(notePlanEvents(proposed), dao.getDishesForCook(draft.cookId).map { it.id })
            }
            if (previous == null) dao.insertTimelineEvent(note) else dao.updateTimelineEvent(note)
            database.activityTimerDao().markLogged(note.id, note.cookId)
            val owned = experience.readingsForLog(note.id)
            owned.filter { it.id !in draft.temperatures.map { t -> t.id } }.forEach { dao.deleteSensorReading(it) }
            experience.unlink(note.id)
            draft.temperatures.forEach { t ->
                val old = experience.reading(t.id)
                val link = experience.readingLink(t.id)
                // A temperature may reference an existing reading only when
                // that reading is manual, belongs to this cook, and is either
                // already owned by this note or owned by no note at all — the
                // unlinked case is the composer adopting a standalone manual
                // reading (the retired Temperature Entry dialog's old job).
                // A reading linked to another note can never be stolen.
                require(old == null || old.cookId == draft.cookId && old.source == "manual" && (link == null || link.eventId == note.id || owned.any { it.id == old.id }))
                require(t.dishId == null || dao.getDish(t.dishId)?.cookId == draft.cookId)
                val probeId = old?.probeId ?: t.probeId ?: UUID.nameUUIDFromBytes("${draft.cookId}:manual:${t.dishId}:${t.measurementType}:${t.probeName}".toByteArray()).toString()
                dao.insertProbesIgnoringDuplicates(listOf(ProbeEntity(probeId, draft.cookId, name = t.probeName, measurementType = t.measurementType, source = "manual", createdAtUtcMillis = now)))
                require(dao.getProbesForCook(draft.cookId).any { it.id == probeId }) { "The probe belongs to another cook." }
                val sample = SensorReadingEntity(t.id, draft.cookId, t.dishId, probeId, t.probeName.trim(), t.measurementType,
                    t.valueText.toDouble(), t.unit, t.measuredAt, ZoneId.systemDefault().id, "manual", recordedAtUtcMillis = old?.recordedAtUtcMillis ?: now, samplingIntervalMillis = 0)
                if (old == null) dao.insertSensorReading(sample) else dao.updateSensorReading(sample)
                experience.linkReadings(listOf(LogReadingLink(t.id, note.id)))
            }
            dao.getPhotosForCook(draft.cookId).filter { it.eventId == note.id && it.id !in draft.photos.map { p -> p.id } }
                .forEach { experience.updatePhoto(it.copy(eventId = null)) }
            copied.forEach { p -> if (experience.photo(p.id) == null) dao.insertPhotos(listOf(p)) else experience.updatePhoto(p) }
            val companion = CookCompanionRepository(database, storage)
            if (previous == null) companion.onEvent(note) else companion.reconcileEventChanges(draft.cookId)
            note
        }
        if (photoAttached) discardDraft(draft.id)
        return CookLogSaveResult(event, photoAttached)
    }

    suspend fun load(eventId: String): CookNoteDraft {
        val event = dao.getTimelineEvent(eventId) ?: error("This note no longer exists.")
        val meta = com.pittech.domain.CookNoteMetadata.decode(event.noteMetadataJson)
            ?: com.pittech.domain.CookNoteMetadata(action = com.pittech.domain.PlaybookCodec.action(event.eventType).takeIf { it in com.pittech.domain.CookNoteMetadata.actions })
        return CookNoteDraft(event.id, event.cookId, event.dishId, meta.category, meta.action, meta.dishIds,
            event.title, event.details.orEmpty(), event.occurredAtUtcMillis,
            experience.readingsForLog(event.id).map { r -> com.pittech.domain.NoteTemperature(r.id, r.probeName, r.measurementType, r.value.toString(), r.unit, r.measuredAtUtcMillis, r.dishId, r.probeId) },
            dao.getPhotosForCook(event.cookId).filter { it.eventId == event.id }.map { p -> NotePhoto(p.id, storage.uri(p.relativePath).toString(), p.caption.orEmpty(), p.capturedAtUtcMillis) },
            meta.method, meta.durationMillis, meta.startedAtUtcMillis, meta.endedAtUtcMillis, meta.ingredients)
    }

    suspend fun delete(eventId: String): DeletedCookNote = database.withTransaction {
        val event = dao.getTimelineEvent(eventId) ?: error("This note no longer exists.")
        if (experience.lifecycle(event.cookId)?.timingMode == "explicit_phases" && com.pittech.domain.PlaybookCodec.action(event.eventType, event.noteMetadataJson) in com.pittech.domain.CookPhases.phaseActions) {
            com.pittech.domain.CookPhases.validate(notePlanEvents(dao.getTimelineEventsForCook(event.cookId).filterNot { it.id == eventId }), dao.getDishesForCook(event.cookId).map { it.id })
        }
        val readings = experience.readingsForLog(eventId)
        val photos = dao.getPhotosForCook(event.cookId).filter { it.eventId == eventId }
        readings.forEach { dao.deleteSensorReading(it) }
        // Files stay in the cook's gallery; undo restores their explicit association.
        photos.forEach { experience.updatePhoto(it.copy(eventId = null)) }
        dao.deleteTimelineEvent(event)
        DeletedCookNote(event, readings, photos)
    }

    suspend fun restore(bundle: DeletedCookNote) = database.withTransaction {
        require(dao.getTimelineEvent(bundle.event.id) == null)
        if (experience.lifecycle(bundle.event.cookId)?.timingMode == "explicit_phases") com.pittech.domain.CookPhases.validate(notePlanEvents(dao.getTimelineEventsForCook(bundle.event.cookId) + bundle.event), dao.getDishesForCook(bundle.event.cookId).map { it.id })
        bundle.photos.forEach { photo -> require(experience.photo(photo.id)?.let { it.eventId == null || it.eventId == bundle.event.id } == true) { "A photo has been removed or attached to another note." } }
        dao.insertTimelineEvent(bundle.event)
        bundle.readings.forEach { dao.insertSensorReading(it) }
        experience.linkReadings(bundle.readings.map { LogReadingLink(it.id, bundle.event.id) })
        bundle.photos.forEach { experience.updatePhoto(it) }
    }
}

data class DeletedCookNote(val event: TimelineEventEntity, val readings: List<SensorReadingEntity>, val photos: List<PhotoEntity>)
