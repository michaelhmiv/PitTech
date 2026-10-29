package com.pittech.data

import android.content.Context
import androidx.room.withTransaction
import com.pittech.domain.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject

class CookAlertRepository(private val db: PitTechDatabase) {
    suspend fun save(cookId: String, title: String, rule: CookAlertRule) = db.withTransaction {
        require(db.cookDao().getCook(cookId)?.status != CookStatus.COMPLETED) { "This cook is finished." }
        require(title.isNotBlank()) { "Give the alert a name." }
        require(rule.dishId == null || db.cookDao().getDish(rule.dishId)?.cookId == cookId) { "Choose a dish in this cook." }
        val old = db.companionDao().get("alert:${rule.id}")
        val now = System.currentTimeMillis()
        db.companionDao().put(CompanionRecord("alert:${rule.id}", "alert", cookId, title.trim(), CookAlertEngine.encode(rule), old?.createdAtUtcMillis ?: now, now))
    }
    suspend fun change(id: String, action: String, now: Long = System.currentTimeMillis()) = db.withTransaction {
        val record = db.companionDao().get(id) ?: return@withTransaction
        val (rule, state) = CookAlertEngine.decode(record.payload)
        val updated = when (action) { "acknowledge" -> state.copy(acknowledged = true); "snooze" -> state.copy(snoozedUntil = now + 600_000, acknowledged = false, notifiedCount = 0); "lid" -> state.copy(suppressedUntil = now + 600_000, badSince = null); else -> state }
        val r = if (action == "toggle") rule.copy(enabled = !rule.enabled) else rule
        db.companionDao().put(record.copy(payload = CookAlertEngine.encode(r, updated), updatedAtUtcMillis = now))
    }
    suspend fun reportControllerStatus(cookId: String, onlineStatus: Int?, at: Long?) {
        if (onlineStatus == null || at == null) return
        val old = db.companionDao().get("monitor-status:$cookId")
        val payload = JSONObject().put("online", onlineStatus).put("at", at).toString()
        if (old?.payload == payload) return
        db.companionDao().put(CompanionRecord("monitor-status:$cookId", "monitor_status", cookId, "Last reported controller status", payload, old?.createdAtUtcMillis ?: at, at))
    }
    suspend fun reconcileAll(context: Context) { db.companionDao().all().filter { it.kind == "alert" }.mapNotNull { it.cookId }.distinct().forEach { reconcile(context, it) } }

    /**
     * Flowing Slice 2 (E1): missing-readings alerts are DEFAULT-ON for any
     * attached source — the cloud recording or any telemetry device,
     * Bluetooth included. Provisioned only when a source is actually
     * attached and no missing rule exists for the cook; an existing rule
     * (including one the cook disabled) is respected, never duplicated or
     * resurrected.
     */
    suspend fun ensureMissingReadingsDefault(cookId: String) {
        val cook = db.cookDao().getCook(cookId) ?: return
        if (cook.status == CookStatus.COMPLETED) return
        val hasMissing = db.companionDao().forCook(cookId).any { it.kind == "alert" && CookAlertEngine.decode(it.payload).first.type == "missing" }
        if (hasMissing) return
        val recording = db.recordingDao().getRecording(cookId)
        val attached = (recording != null && recording.status != CookRecordingEntity.STOPPED) ||
            db.cookDao().devicesForCook(cookId).any { it.role == "telemetry" }
        if (!attached) return
        save(cookId, "Missing readings", CookAlertRule(type = "missing"))
    }

    suspend fun reconcile(context: Context, cookId: String) {
        val now = System.currentTimeMillis()
        val cook = db.cookDao().getCook(cookId) ?: return
        ensureMissingReadingsDefault(cookId)
        val recording = db.recordingDao().getRecording(cookId)
        val events = db.cookDao().getTimelineEventsForCook(cookId).let { com.pittech.data.notePlanEvents(it) }
        val readings = db.cookDao().observeSensorReadings(cookId).first()
        val status = db.companionDao().get("monitor-status:$cookId")?.let { JSONObject(it.payload) }
        val offline = status?.takeIf { now - it.getLong("at") in 0..330_000 }?.let { it.getInt("online") != 0 }
        // E1: telemetry sources (Bluetooth included) feed the missing
        // evaluation on equal footing with the cloud recording: the newest
        // receipt across every attached source is the silence baseline,
        // and any attached telemetry device counts as a running source.
        val telemetryDevices = db.cookDao().devicesForCook(cookId).filter { it.role == "telemetry" }
        val telemetryDeviceIds = telemetryDevices.map { it.id }.toSet()
        val telemetryReceipt = readings.filter { it.sourceDeviceId in telemetryDeviceIds }.maxOfOrNull { it.recordedAtUtcMillis }
        val lastReceipt = listOfNotNull(recording?.lastReceivedAtUtcMillis, recording?.resumedAtUtcMillis, telemetryReceipt).maxOrNull()
        val cloudRunningNow = recording?.status == CookRecordingEntity.RECORDING && recording.samplingMode != "on_log"
        val anySourceRunning = cloudRunningNow || telemetryDevices.isNotEmpty()
        db.companionDao().forCook(cookId).filter { it.kind == "alert" }.forEach { record ->
            db.withTransaction {
                val current = db.companionDao().get(record.id) ?: return@withTransaction
                val (rule, state) = CookAlertEngine.decode(current.payload)
                val cloudRunning = recording?.status == CookRecordingEntity.RECORDING && recording.samplingMode != "on_log"
                val hasRecording = recording != null && recording.status != CookRecordingEntity.STOPPED
                val reading = readings.filter { r -> (rule.dishId == null || r.dishId == rule.dishId) &&
                    (rule.probeName.isBlank() || r.probeName == rule.probeName) && when(rule.type) { "target" -> r.measurementType in setOf("food", "food_probe"); "pit_range" -> r.measurementType in setOf("pit", "pit_ambient"); else -> true } &&
                    (rule.type != "target" || r.measuredAtUtcMillis >= (events.filter { e -> e.action == "food_on" && (e.dishId == null || e.dishId == r.dishId) }.maxOfOrNull { e -> e.at } ?: Long.MAX_VALUE)) &&
                    (r.source != "controller_cloud" || (cloudRunning && r.sourceDeviceId == recording?.deviceId && r.measuredAtUtcMillis >= recording.resumedAtUtcMillis)) }
                    .maxByOrNull { it.measuredAtUtcMillis }
                val sample = reading?.let { AlertSample(CookPlanEngine.fahrenheit(it.value, it.unit), it.measuredAtUtcMillis, it.recordedAtUtcMillis, it.qualityStatus == "valid" && (it.source != "controller_cloud" || recording?.gapStartedAtUtcMillis == null),
                    if (it.source == "controller_cloud") com.pittech.devices.GrillSamplingPolicy.receiptWindow(it.samplingIntervalMillis) else 330_000, it.timestampBasis != "cloud_receipt") }
                val stage = (rule.dishId ?: reading?.dishId)?.let { CookPlanEngine.stage(events, it) } ?: db.cookDao().getDishesForCook(cookId).map { CookPlanEngine.stage(events, it.id) }.let { stages -> if (stages.distinct().size == 1) stages.first() else "mixed" }
                val result = CookAlertEngine.evaluate(rule, state, sample, lastReceipt, offline, stage, now,
                    cook.status != CookStatus.COMPLETED && if (rule.type in setOf("missing", "offline")) anySourceRunning else reading?.source == "manual" || !hasRecording || cloudRunning)
                val canDeliver = com.pittech.CookAlertNotifications.canNotify(context)
                val nextState = if (result.notify && canDeliver) result.state.copy(lastNotifiedAt = now, notifiedCount = state.notifiedCount + 1) else result.state
                val payload = CookAlertEngine.encode(rule, nextState)
                if (payload != current.payload) db.companionDao().put(current.copy(payload = payload, updatedAtUtcMillis = now))
                if (result.notify && canDeliver) com.pittech.CookAlertNotifications.show(context, current, result.detail)
                if (!result.state.active || !rule.enabled || cook.status == CookStatus.COMPLETED) com.pittech.CookAlertNotifications.cancel(context, current.id)
                val wake = if (result.notify && canDeliver) now + rule.repeatMillis else result.nextWake
                if (wake != null && wake > now) com.pittech.CookGuidanceNotifications.schedule(context, cookId, current.id, wake)
                else com.pittech.CookGuidanceNotifications.cancelAlarm(context, cookId, current.id)
            }
        }
    }
}
