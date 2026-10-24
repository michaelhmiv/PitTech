package com.pittech.data

import android.content.Context
import androidx.room.withTransaction
import com.pittech.domain.*
import kotlinx.coroutines.flow.first
import java.util.UUID

fun SensorReadingEntity.channelIdentity() = listOf(cookId, probeId ?: probeName, sourceDeviceId.orEmpty(), dishId.orEmpty(), measurementType, source).joinToString(":")

class ReminderRepository(private val db: PitTechDatabase) {
    private val dao = db.reminderDao()
    fun observeRules() = dao.observeRules()
    suspend fun rule(id: String): ReminderRule? = dao.rule(id)
    fun observeOccurrences() = dao.observeOccurrences()
    suspend fun cancelDisplayed(context: Context, id: String) { dao.rule(id)?.let { com.pittech.UnifiedReminderNotifications.cancel(context, it) } }
    suspend fun save(cookId: String, dishId: String?, title: String, trigger: ReminderTrigger,
        id: String = UUID.randomUUID().toString(), sourceRevision: String? = null, sourceMilestone: String? = null): String = db.withTransaction {
        require(db.cookDao().getCook(cookId)?.status in setOf(CookStatus.ACTIVE, CookStatus.PAUSED)) { "Choose an active meal." }
        require(dishId == null || db.cookDao().getDish(dishId)?.cookId == cookId) { "Choose a dish in this meal." }
        require(title.isNotBlank()) { "Give the reminder a name." }
        val config = ReminderTriggerCodec.encode(trigger)
        val previous = dao.rule(id)
        require(previous == null || previous.cookId == cookId) { "Reminder belongs to another meal." }
        val now = System.currentTimeMillis()
        dao.putRule(ReminderRule(id, cookId, dishId, title.trim(), ReminderTriggerCodec.kind(trigger), config, sourceRevision, sourceMilestone, true, "pending", previous?.createdAtUtcMillis ?: now, now))
        if (previous != null) dao.history(id).filter { it.sequence > 0 && it.status in setOf("queued", "scheduled", "delivered") }.forEach { dao.putOccurrence(it.copy(status = "cancelled")) }
        // Editing the configuration creates a new decision without erasing earlier delivery receipts.
        dao.putOccurrence(ReminderOccurrence("state:$id", id, 0, "pending"))
        id
    }
    suspend fun change(context: Context, id: String, action: String, expectedSequence: Int? = null) = db.withTransaction {
        val rule = dao.rule(id) ?: return@withTransaction
        val rows = dao.history(id)
        if (expectedSequence != null && rows.firstOrNull { it.sequence > 0 }?.sequence != expectedSequence) return@withTransaction
        val state = rows.firstOrNull { it.sequence == 0 } ?: ReminderOccurrence("state:$id", id, 0, "pending")
        val now = System.currentTimeMillis()
        when (action) {
            "snooze" -> {
                dao.putRule(rule.copy(enabled = true, status = "pending", updatedAtUtcMillis = now))
                dao.putOccurrence(state.copy(status = "pending", snoozedUntilUtcMillis = now + 600_000, deliveredAtUtcMillis = null, acknowledgedAtUtcMillis = null))
                rows.firstOrNull { it.sequence > 0 }?.let { dao.putOccurrence(it.copy(status = "snoozed", snoozedUntilUtcMillis = now + 600_000)) }
            }
            "complete", "skip" -> {
                dao.putRule(rule.copy(enabled = false, status = "completed", updatedAtUtcMillis = now))
                dao.putOccurrence(state.copy(status = "completed", acknowledgedAtUtcMillis = now))
                rows.firstOrNull { it.sequence > 0 }?.let { dao.putOccurrence(it.copy(status = if (action == "skip") "skipped" else "acknowledged", acknowledgedAtUtcMillis = now)) }
            }
            "toggle" -> {
                val enabled = !rule.enabled
                dao.putRule(rule.copy(enabled = enabled, status = if (enabled) "pending" else "inactive", updatedAtUtcMillis = now))
                if (enabled) dao.putOccurrence(state.copy(status = "pending", deliveredAtUtcMillis = null, snoozedUntilUtcMillis = null, acknowledgedAtUtcMillis = null, badSinceUtcMillis = null, active = false))
            }
            "remove" -> dao.delete(id)
        }
        com.pittech.UnifiedReminderNotifications.cancel(context, rule)
    }
    /**
     * PL-01 (issue #172): the physical action this rule announced when it
     * fired, resolved from its source recipe snapshot milestone. Null when
     * the rule announced no action — callers must ask, never preselect.
     */
    suspend fun announcedAction(rule: ReminderRule): String? {
        val trigger = runCatching { ReminderTriggerCodec.decode(rule.triggerConfig) }.getOrNull() ?: return null
        val milestoneAction = rule.sourceMilestoneId?.let { milestone ->
            rule.sourceRecipeRevisionId?.let { db.recipeDao().revision(it) }
                ?.let { runCatching { RecipeSnapshotCodec.decode(it.snapshotJson) }.getOrNull() }
                ?.observations?.firstOrNull { it.sourceEventId == milestone }?.action
        }
        return ReminderVocabulary.announcedAction(trigger, milestoneAction)
    }
    suspend fun confirmAction(context: Context, id: String, action: String, cooks: CookRepository): String = db.withTransaction {
        val rule = dao.rule(id) ?: error("Reminder unavailable.")
        require(action in CookPhases.phaseActions)
        val receipt = dao.history(id).firstOrNull { it.sequence > 0 } ?: dao.history(id).first { it.sequence == 0 }
        val eventId = receipt.actionEventId ?: "reminder-action-${rule.id}-${receipt.sequence}"
        if (db.cookDao().getTimelineEvent(eventId) == null) cooks.notes.save(CookNoteDraft(id = eventId, cookId = rule.cookId, dishId = rule.dishId, category = "action", action = action, title = PlaybookCodec.actions[action].orEmpty()))
        dao.putOccurrence(receipt.copy(actionEventId = eventId)); change(context, id, "complete")
        eventId
    }
    suspend fun reconcileAll(context: Context) { dao.rules().map { it.cookId }.distinct().forEach { reconcile(context, it) } }
    suspend fun reconcile(context: Context, cookId: String) {
        val cook = db.cookDao().getCook(cookId) ?: return
        val events = notePlanEvents(db.cookDao().getTimelineEventsForCook(cookId))
        val readings = db.cookDao().observeSensorReadings(cookId).first()
        val recording = db.recordingDao().getRecording(cookId)
        val assignments = db.recordingDao().getAllAssignments().filter { it.cookId == cookId && it.endedAtUtcMillis == null }
        dao.rules().filter { it.cookId == cookId }.forEach { candidate ->
            var notification: ReminderOccurrence? = null
            var wake: Long? = null
            var currentRule = candidate
            db.withTransaction {
                val rule = dao.rule(candidate.id) ?: return@withTransaction
                currentRule = rule
                val now = System.currentTimeMillis()
                if (cook.status == CookStatus.COMPLETED || !rule.enabled || rule.status in setOf("completed", "cancelled", "inactive")) {
                    if (cook.status == CookStatus.COMPLETED && rule.enabled) dao.putRule(rule.copy(enabled = false, status = "cancelled", updatedAtUtcMillis = now))
                    return@withTransaction
                }
                val trigger = ReminderTriggerCodec.decode(rule.triggerConfig)
                val rows = dao.history(rule.id)
                val old = rows.firstOrNull { it.sequence == 0 } ?: ReminderOccurrence("state:${rule.id}", rule.id, 0, "pending")
                val previous = ReminderState(old.active, old.badSinceUtcMillis, old.lastSampleAtUtcMillis, old.deliveredAtUtcMillis, old.snoozedUntilUtcMillis, old.status == "completed")
                val sample = (trigger as? ReminderTrigger.Probe)?.let { probe ->
                    readings.filter { it.channelIdentity() == probe.seriesId && it.probeId == probe.probeId && it.measurementType == probe.measurementType && (rule.dishId == null || it.dishId == rule.dishId) }.maxByOrNull { it.measuredAtUtcMillis }?.let { r ->
                        val telemetry = r.source.startsWith("telemetry:")
                        val runtime = if (telemetry) (context.applicationContext as? com.pittech.PitTechApplication)?.telemetryRuntime else null
                        val selected = runtime?.store?.profiles?.value?.firstNotNullOfOrNull { p ->
                            p.sources.firstOrNull { it.deviceId in p.selectedDeviceIds && TelemetryRecordingRepository(db).deviceKey(cookId, it) == r.sourceDeviceId }
                                ?.let { p to it }
                        }
                        val telemetryValid = selected?.let { (p, source) -> p.cookId == cookId && p.monitoring && runtime.states.value[p.id + ":" + source.deviceId]?.startsWith("Connected") == true && source.channels.any { channel -> channel.kind.storageType == r.measurementType && TelemetryRecordingRepository(db).channelKey(cookId, source, channel.id) == r.probeId && runtime.states.value[p.id + ":" + source.deviceId + ":" + channel.id] == "Connected" } } == true
                        val cloud = r.source == "controller_cloud"
                        val continuous = telemetry && telemetryValid && selected?.first?.onLogOnly == false || cloud && recording?.status == CookRecordingEntity.RECORDING && recording.samplingMode != "on_log"
                        val assignmentValid = if (telemetry && r.measurementType == "food_probe") assignments.any { it.probeId == r.probeId && it.dishId == r.dishId && it.endedAtUtcMillis == null && r.measuredAtUtcMillis >= it.startedAtUtcMillis } else !cloud || recording?.deviceId == r.sourceDeviceId && assignments.any { it.probeId == r.probeId && it.dishId == r.dishId && r.measuredAtUtcMillis >= it.startedAtUtcMillis }
                        val valid = (!telemetry || telemetryValid) && r.qualityStatus == "valid" && r.unit in setOf("°F", "°C") && (!cloud || recording?.status == CookRecordingEntity.RECORDING && recording.gapStartedAtUtcMillis == null && r.measuredAtUtcMillis >= recording.resumedAtUtcMillis)
                        ReminderProbeSample(r.channelIdentity(), r.probeId, r.measurementType, AlertSample(CookPlanEngine.fahrenheit(r.value, r.unit), r.measuredAtUtcMillis, r.recordedAtUtcMillis, valid,
                            if (cloud || telemetry) com.pittech.devices.GrillSamplingPolicy.receiptWindow(r.samplingIntervalMillis) else 330_000, r.timestampBasis != "cloud_receipt"), assignmentValid, continuous)
                    }
                }
                val result = ReminderEngine.evaluate(trigger, previous, events, rule.dishId, now, sample)
                val awaitingAcknowledgment = rows.firstOrNull { it.sequence > 0 }?.status in setOf("queued", "delivered") && old.snoozedUntilUtcMillis == null
                val status = if (result.ready || awaitingAcknowledgment) "due" else if (result.dueAt != null) "scheduled" else "waiting"
                if (rule.status != status) dao.putRule(rule.copy(status = status, updatedAtUtcMillis = now))
                val state = old.copy(status = status, dueAtUtcMillis = result.dueAt, active = result.state.active, badSinceUtcMillis = result.state.badSince, lastSampleAtUtcMillis = result.state.lastSampleAt)
                if (state != old) dao.putOccurrence(state)
                notification = rows.firstOrNull { it.sequence > 0 && it.status == "queued" }
                if (notification == null && result.ready && com.pittech.UnifiedReminderNotifications.canNotify(context)) {
                    val sequence = (rows.maxOfOrNull { it.sequence } ?: 0) + 1
                    notification = ReminderOccurrence("${rule.id}:$sequence", rule.id, sequence, "queued", result.dueAt ?: now)
                    dao.putOccurrence(notification!!)
                    dao.putOccurrence(state.copy(deliveredAtUtcMillis = now, snoozedUntilUtcMillis = null))
                }
                wake = if (notification == null) result.dueAt?.takeIf { it > now } else null
            }
            val receipt = notification
            if (receipt != null && com.pittech.UnifiedReminderNotifications.show(context, currentRule, receipt, announcedAction(currentRule))) {
                db.withTransaction {
                    val persisted = dao.rule(currentRule.id) ?: return@withTransaction
                    dao.putOccurrence(receipt.copy(status = "delivered", deliveredAtUtcMillis = System.currentTimeMillis()))
                    // Time reminders and one-shot threshold reminders remain visible until acknowledged.
                    if (persisted.status != "due") dao.putRule(persisted.copy(status = "due"))
                }
            }
            if (!currentRule.enabled || cook.status == CookStatus.COMPLETED) com.pittech.UnifiedReminderNotifications.cancel(context, currentRule)
            else if (wake != null) com.pittech.CookGuidanceNotifications.schedule(context, cookId, "reminder:${currentRule.id}", wake!!, calendar = ReminderTriggerCodec.decode(currentRule.triggerConfig) is ReminderTrigger.Clock)
            else com.pittech.CookGuidanceNotifications.cancelAlarm(context, cookId, "reminder:${currentRule.id}")
        }
    }
}
