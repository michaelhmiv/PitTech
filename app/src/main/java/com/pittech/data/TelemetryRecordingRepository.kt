package com.pittech.data

import androidx.room.withTransaction
import com.pittech.devices.*
import java.time.ZoneId
import java.util.UUID

/**
 * Parallel-source recorder. Reuses Room's existing device/probe/reading/assignment
 * tables and therefore the existing archive/export pipeline. The legacy
 * cook_recordings single-source table and historical samples remain unchanged.
 */
internal class TelemetryRecordingRepository(private val db: PitTechDatabase) {
    private val cooks = db.cookDao()
    private val assignments = db.recordingDao()

    fun deviceKey(cookId: String, source: TelemetrySource) =
        "telemetry:" + TelemetryNormalizer.localKey(cookId, source.connectorId, source.deviceId)

    fun channelKey(cookId: String, source: TelemetrySource, channelId: String) =
        deviceKey(cookId, source) + ":" + TelemetryNormalizer.localKey(channelId)

    suspend fun attach(
        cookId: String, source: TelemetrySource,
        foodAssignments: Map<String, String?> = emptyMap(),
        now: Long = System.currentTimeMillis(),
    ) = db.withTransaction {
        TelemetryConnectorRegistry.requirePlanned(source.connectorId)
        require(cooks.getCook(cookId)?.status == CookStatus.ACTIVE) { "Cook must be active" }
        require(foodAssignments.keys.all { key -> source.channels.any { it.id == key && it.kind == TelemetryKind.FOOD } })
        require(foodAssignments.values.filterNotNull().all { cooks.getDish(it)?.cookId == cookId })
        val id = deviceKey(cookId, source)
        cooks.insertDevicesIgnoringDuplicates(listOf(DeviceEntity(
            id, cookId, source.manufacturer, source.model, source.label, "telemetry", null, now
        )))
        cooks.insertProbesIgnoringDuplicates(source.channels.map {
            ProbeEntity(channelKey(cookId, source, it.id), cookId, id, null, it.name, it.kind.storageType,
                "telemetry:" + source.connectorId, now)
        })
        for ((channelId, dishId) in foodAssignments) {
            assignFoodInternal(cookId, source, channelId, dishId, now)
        }
    }

    suspend fun assignFood(
        cookId: String, source: TelemetrySource, channelId: String,
        dishId: String?, atMillis: Long = System.currentTimeMillis(),
    ) = db.withTransaction {
        require(cooks.getCook(cookId)?.status == CookStatus.ACTIVE)
        require(dishId == null || cooks.getDish(dishId)?.cookId == cookId)
        assignFoodInternal(cookId, source, channelId, dishId, atMillis)
    }

    private suspend fun assignFoodInternal(cookId: String, source: TelemetrySource, channelId: String,
                                           dishId: String?, at: Long) {
        require(source.channels.any { it.id == channelId && it.kind == TelemetryKind.FOOD })
        val probeId = channelKey(cookId, source, channelId)
        val probe = cooks.getProbesForCook(cookId).firstOrNull { it.id == probeId }
            ?: error("Attach source before assigning")
        val prior = assignments.currentAssignment(probeId)
        require(at >= (prior?.startedAtUtcMillis ?: probe.createdAtUtcMillis))
        if (prior?.dishId == dishId) return
        prior?.let { assignments.updateAssignment(it.copy(endedAtUtcMillis = at)) }
        assignments.insertAssignment(ProbeAssignmentEntity(UUID.randomUUID().toString(), cookId, probeId, dishId, at))
        cooks.updateProbe(probe.copy(assignedDishId = dishId))
    }

    /** Room ignores duplicates using deterministic event IDs. Missing samples remain gaps. */
    suspend fun ingest(cookId: String, source: TelemetrySource, observations: List<TelemetryObservation>): Int =
        db.withTransaction {
            val cook = cooks.getCook(cookId) ?: return@withTransaction 0
            if (cook.status != CookStatus.ACTIVE) return@withTransaction 0
            val id = deviceKey(cookId, source)
            val existing = cooks.getProbesForCook(cookId).filter { it.deviceId == id }.associateBy { it.id }
            if (existing.isEmpty()) return@withTransaction 0
            val channels = source.channels.associateBy { it.id }
            val candidate = observations.mapNotNull { item ->
                val channel = channels[item.channelId] ?: return@mapNotNull null
                val probe = existing[channelKey(cookId, source, channel.id)] ?: return@mapNotNull null
                val value = TelemetryNormalizer.normalize(item) ?: return@mapNotNull null
                if (value.measuredAtMillis < cook.startedAtUtcMillis ||
                    value.measuredAtMillis > (cook.endedAtUtcMillis ?: Long.MAX_VALUE)) return@mapNotNull null
                val dish = if (channel.kind == TelemetryKind.FOOD)
                    assignments.assignmentAt(probe.id, value.measuredAtMillis)?.dishId else null
                SensorReadingEntity(
                    id = "telemetry:" + TelemetryNormalizer.localKey(
                        cookId, source.connectorId, source.deviceId, channel.id, value.eventKey),
                    cookId = cookId, dishId = dish, probeId = probe.id,
                    probeName = probe.name, measurementType = channel.kind.storageType,
                    value = value.fahrenheit, unit = "°F",
                    measuredAtUtcMillis = value.measuredAtMillis,
                    timeZoneId = ZoneId.systemDefault().id, source = "telemetry:" + source.connectorId,
                    sourceDeviceId = id, qualityStatus = "valid",
                    recordedAtUtcMillis = value.receivedAtMillis,
                    timestampBasis = value.timestampBasis, samplingIntervalMillis = 0L,
                )
            }
            cooks.insertReadingsIgnoringDuplicates(candidate)
            candidate.size
        }
}
