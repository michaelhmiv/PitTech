package com.pittech.data

import androidx.room.withTransaction
import com.pittech.devices.CookTelemetryPolicy
import com.pittech.devices.GrillSamplingMode
import com.pittech.devices.GrillSamplingPolicy
import com.pittech.devices.PolarisDevice
import com.pittech.devices.PolarisMonitorState
import com.pittech.devices.PolarisSample
import java.time.ZoneId
import java.util.UUID

internal class CookRecordingRepository(private val database: PitTechDatabase) {
    private val dao = database.cookDao()
    private val recordings = database.recordingDao()

    suspend fun attach(cookId: String, device: PolarisDevice, unit: String, probeDishes: Map<String, String?> = emptyMap(), now: Long = System.currentTimeMillis(), sampling: GrillSamplingPolicy? = null) = database.withTransaction {
        val cook = dao.getCook(cookId) ?: error("This cook could not be found.")
        require(cook.status == CookStatus.ACTIVE) { "Resume this cook before recording temperatures." }
        require(unit in setOf("°F", "°C")) { "Choose a temperature unit." }
        val active = recordings.getActiveRecording()
        require(active == null || active.cookId == cookId) { "Pause or stop recording for the other cook first. One grill can record at a time." }
        val key = CookTelemetryPolicy.deviceKey(device.id)
        val previous = recordings.getRecording(cookId)
        val policy = sampling ?: previous?.let { GrillSamplingPolicy.stored(it.samplingMode, it.samplingIntervalMillis) } ?: GrillSamplingPolicy()
        val same = previous?.controllerKey == key
        val deviceId = if (same) previous!!.deviceId else UUID.randomUUID().toString()
        if (!same) {
            dao.insertDevicesIgnoringDuplicates(listOf(DeviceEntity(deviceId, cookId, device.brand, device.grillModel ?: device.controllerModel, device.name, "grill", device.firmware, now)))
            dao.insertProbesIgnoringDuplicates(listOf(
                ProbeEntity("$deviceId:chamber", cookId, deviceId, name = "Chamber", measurementType = "pit_ambient", source = "controller_cloud", createdAtUtcMillis = now),
                ProbeEntity("$deviceId:setpoint", cookId, deviceId, name = "Setpoint", measurementType = "setpoint", source = "controller_cloud", createdAtUtcMillis = now),
            ).plus((1..device.probeCount.coerceIn(1, 4)).map { index ->
                ProbeEntity("$deviceId:probe$index", cookId, deviceId, name = "Probe $index", measurementType = "food_probe", source = "controller_cloud", createdAtUtcMillis = now)
            }))
        }
        val recording = if (same) previous!!.copy(status = CookRecordingEntity.RECORDING, resumedAtUtcMillis = now,
            gapStartedAtUtcMillis = if (policy.mode == GrillSamplingMode.ON_LOG) null else previous.gapStartedAtUtcMillis ?: previous.lastReceivedAtUtcMillis?.plus(GrillSamplingPolicy.receiptWindow(previous.samplingIntervalMillis))?.takeIf { it < now },
            pendingSetpoint = null, pendingSetpointCount = 0, message = "Waiting for a new cloud reading.")
        else CookRecordingEntity(cookId, deviceId, key, CookRecordingEntity.RECORDING, unit, now, now)
        recordings.saveRecording(recording.copy(samplingMode = policy.mode.key, samplingIntervalMillis = policy.intervalMillis))
        event(cookId, if (same) "recording_resumed" else "recording_started", if (same) "Temperature recording resumed" else "Grill attached · temperature recording started", now)
        val probes = dao.getProbesForCook(cookId).filter { it.deviceId == deviceId && it.measurementType == "food_probe" }
        probes.forEach { probe ->
            val channel = probe.id.substringAfterLast(':')
            if (!same || probeDishes.containsKey(channel)) assignProbe(probe.id, cookId, probeDishes[channel], now)
        }
    }

    suspend fun configureSampling(cookId: String, policy: GrillSamplingPolicy, now: Long = System.currentTimeMillis()) = database.withTransaction {
        val current = recordings.getRecording(cookId) ?: return@withTransaction
        require(dao.getCook(cookId)?.status != CookStatus.COMPLETED) { "This cook is finished." }
        if (current.samplingMode == policy.mode.key && current.samplingIntervalMillis == policy.intervalMillis) return@withTransaction
        recordings.saveRecording(current.copy(samplingMode = policy.mode.key, samplingIntervalMillis = policy.intervalMillis,
            resumedAtUtcMillis = now, lastReceivedAtUtcMillis = null, gapStartedAtUtcMillis = null,
            pendingSetpoint = null, pendingSetpointCount = 0, message = "Collection: ${policy.label}."))
        event(cookId, "recording_schedule_changed", "Temperature collection: ${policy.label}", now)
    }

    suspend fun assignProbe(probeId: String, cookId: String, dishId: String?, now: Long = System.currentTimeMillis()) = database.withTransaction {
        val cook = dao.getCook(cookId) ?: error("This cook could not be found.")
        require(cook.status != CookStatus.COMPLETED) { "This cook is finished." }
        val probe = dao.getProbesForCook(cookId).firstOrNull { it.id == probeId && it.measurementType == "food_probe" }
            ?: error("This probe is not part of this cook.")
        require(dishId == null || dao.getDish(dishId)?.cookId == cookId) { "This dish is not part of this cook." }
        val current = recordings.currentAssignment(probeId)
        if (current != null && current.dishId == dishId) return@withTransaction
        current?.let { recordings.updateAssignment(it.copy(endedAtUtcMillis = now)) }
        recordings.insertAssignment(ProbeAssignmentEntity(UUID.randomUUID().toString(), cookId, probeId, dishId, now))
        dao.updateProbe(probe.copy(assignedDishId = dishId))
        val name = dishId?.let { dao.getDish(it)?.name } ?: "Unassigned"
        event(cookId, "probe_assigned", "${probe.name} → $name", now, dishId)
    }

    suspend fun pause(cookId: String, message: String = "Temperature recording paused.", now: Long = System.currentTimeMillis()) = database.withTransaction {
        val recording = recordings.getRecording(cookId) ?: return@withTransaction
        if (recording.status != CookRecordingEntity.RECORDING) return@withTransaction
        recordings.saveRecording(recording.copy(status = CookRecordingEntity.PAUSED, message = message,
            pendingSetpoint = null, pendingSetpointCount = 0))
        event(cookId, "recording_paused", message, now)
    }

    suspend fun stop(cookId: String, now: Long = System.currentTimeMillis()) = database.withTransaction {
        val recording = recordings.getRecording(cookId) ?: return@withTransaction
        if (recording.status == CookRecordingEntity.STOPPED) return@withTransaction
        recordings.saveRecording(recording.copy(status = CookRecordingEntity.STOPPED, message = "Temperature recording stopped.", pendingSetpoint = null, pendingSetpointCount = 0))
        event(cookId, "recording_stopped", "Temperature recording stopped", now)
    }

    suspend fun markGap(cookId: String, reason: String, now: Long = System.currentTimeMillis()) = database.withTransaction {
        val recording = recordings.getRecording(cookId) ?: return@withTransaction
        if (recording.status != CookRecordingEntity.RECORDING) return@withTransaction
        if (recording.samplingMode == GrillSamplingMode.ON_LOG.key) {
            recordings.saveRecording(recording.copy(message = reason, pendingSetpoint = null, pendingSetpointCount = 0))
            return@withTransaction
        }
        if (recording.gapStartedAtUtcMillis == null) {
            val started = recording.lastReceivedAtUtcMillis?.plus(GrillSamplingPolicy.receiptWindow(recording.samplingIntervalMillis))?.coerceAtMost(now) ?: now
            event(cookId, "connection_gap", "Temperature recording interrupted", started, details = reason)
            recordings.saveRecording(recording.copy(gapStartedAtUtcMillis = started, message = reason, pendingSetpoint = null, pendingSetpointCount = 0))
        } else if (recording.message != reason) recordings.saveRecording(recording.copy(message = reason))
    }

    /** Atomic binding check + insert + watermark. Completion, pause and assignment changes serialize with this write. */
    suspend fun ingest(cookId: String, controllerKey: String, sample: PolarisSample, state: PolarisMonitorState) = database.withTransaction {
        var recording = recordings.getRecording(cookId) ?: return@withTransaction
        val cook = dao.getCook(cookId) ?: return@withTransaction
        if (recording.status != CookRecordingEntity.RECORDING || cook.status != CookStatus.ACTIVE ||
            recording.controllerKey != controllerKey || state.selectedDeviceId?.let(CookTelemetryPolicy::deviceKey) != controllerKey ||
            sample.fetchedAtMillis < recording.resumedAtUtcMillis || sample.fetchedAtMillis <= (recording.lastProcessedAtUtcMillis ?: Long.MIN_VALUE)) return@withTransaction
        CookAlertRepository(database).reportControllerStatus(cookId, state.onlineStatus, state.statusFetchedAtMillis)
        if (state.onlineStatus?.let { it != 0 } == true || state.readingRequestFailed || sample.payload.reportedAtMillis?.let { sample.fetchedAtMillis - it > 45_000L || it - sample.fetchedAtMillis > 300_000L } == true) {
            markGap(cookId, "Grill offline or cloud readings unavailable.", sample.fetchedAtMillis)
            recordings.getRecording(cookId)?.let { recordings.saveRecording(it.copy(lastProcessedAtUtcMillis = sample.fetchedAtMillis)) }
            return@withTransaction
        }
        val values = CookTelemetryPolicy.values(sample, recording.unit)
        if (values.none { it.quality == "valid" && it.type != "setpoint" }) {
            markGap(cookId, "No usable temperatures or temperature unit were reported.", sample.fetchedAtMillis)
            recordings.getRecording(cookId)?.let { recordings.saveRecording(it.copy(lastProcessedAtUtcMillis = sample.fetchedAtMillis)) }
            return@withTransaction
        }
        val time = sample.fetchedAtMillis
        // An OS/process suspension can leave no callback. Detect the gap from the saved watermark.
        val gap = if (recording.samplingMode == GrillSamplingMode.ON_LOG.key) null else recording.gapStartedAtUtcMillis ?: recording.lastReceivedAtUtcMillis?.plus(GrillSamplingPolicy.receiptWindow(recording.samplingIntervalMillis))?.takeIf { it < time }
        if (gap != null) {
            if (recording.gapStartedAtUtcMillis == null) event(cookId, "connection_gap", "Temperature recording interrupted", gap)
            event(cookId, "connection_restored", "Temperature recording resumed", time, details = "No readings were recorded for ${(time - gap).coerceAtLeast(0) / 1000} seconds. Missing periods stay blank.")
        }
        val probes = dao.getProbesForCook(cookId).filter { it.deviceId == recording.deviceId }.associateBy { it.name }
        val readings = values.mapNotNull { value ->
            val probe = probes[value.name] ?: return@mapNotNull null
            val dishId = if (value.type == "food_probe") recordings.assignmentAt(probe.id, time)?.dishId else null
            SensorReadingEntity("${recording.deviceId}:$time:${value.channel}", cookId, dishId, probe.id, value.name, value.type,
                value.value, recording.unit, time, ZoneId.systemDefault().id, "controller_cloud", recording.deviceId,
                value.quality, time, "cloud_receipt", if (recording.samplingMode == GrillSamplingMode.ON_LOG.key) 0L else recording.samplingIntervalMillis)
        }
        dao.insertReadingsIgnoringDuplicates(readings)
        val setpoint = values.firstOrNull { it.type == "setpoint" && it.quality == "valid" }?.value
        if (setpoint != null) {
            if (recording.confirmedSetpoint == null) recording = recording.copy(confirmedSetpoint = setpoint)
            else if (setpoint == recording.confirmedSetpoint) recording = recording.copy(pendingSetpoint = null, pendingSetpointCount = 0)
            else {
                val count = if (recording.pendingSetpoint == setpoint && gap == null) recording.pendingSetpointCount + 1 else 1
                if (count >= 2) {
                    event(cookId, "setpoint_changed", "Grill setpoint changed to ${String.format(java.util.Locale.US, "%.0f", setpoint)} ${recording.unit}", time,
                        details = "Observed in two consecutive cloud readings. PitTech did not change the grill.", context = TemperatureContext.encode(TemperatureContext.select(readings, time, null)))
                    recording = recording.copy(confirmedSetpoint = setpoint, pendingSetpoint = null, pendingSetpointCount = 0)
                } else recording = recording.copy(pendingSetpoint = setpoint, pendingSetpointCount = count)
            }
        } else recording = recording.copy(pendingSetpoint = null, pendingSetpointCount = 0)
        recordings.saveRecording(recording.copy(lastProcessedAtUtcMillis = time, lastReceivedAtUtcMillis = time,
            gapStartedAtUtcMillis = null, message = "Collection: ${GrillSamplingPolicy.stored(recording.samplingMode, recording.samplingIntervalMillis).label}. Readings show cloud receipt time."))
    }

    private suspend fun event(cookId: String, type: String, title: String, time: Long, dishId: String? = null, details: String? = null, context: String? = null) {
        dao.insertTimelineEvent(TimelineEventEntity(UUID.randomUUID().toString(), cookId, dishId, type, title, details, time, time,
            ZoneId.systemDefault().id, "controller_cloud", createdAtUtcMillis = time, updatedAtUtcMillis = time, temperatureContextJson = context))
    }
}
