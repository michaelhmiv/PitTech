package com.pittech.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "cooks",
    indices = [Index(value = ["status"]), Index(value = ["startedAtUtcMillis"])],
)
data class CookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val status: String,
    val startedAtUtcMillis: Long,
    val startedTimeZoneId: String,
    val endedAtUtcMillis: Long? = null,
    val smokerName: String? = null,
    val initialSetpointValue: Double? = null,
    val initialSetpointUnit: String? = null,
    val notes: String? = null,
    val createdAtUtcMillis: Long,
    val updatedAtUtcMillis: Long,
    val fuelType: String? = null,
    val woodOrPelletBlend: String? = null,
    val outdoorTemperatureValue: Double? = null,
    val outdoorTemperatureUnit: String? = null,
    val weatherNotes: String? = null,
    val windNotes: String? = null,
)

@Entity(
    tableName = "dishes",
    foreignKeys = [
        ForeignKey(
            entity = CookEntity::class,
            parentColumns = ["id"],
            childColumns = ["cookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["cookId"])],
)
data class DishEntity(
    @PrimaryKey val id: String,
    val cookId: String,
    val name: String,
    val foodType: String,
    val cut: String?,
    val weightValue: Double? = null,
    val weightUnit: String? = null,
    val pieceCount: Int? = null,
    val startingCondition: String? = null,
    val boneIn: Boolean? = null,
    val placement: String? = null,
    val prepNotes: String? = null,
    val createdAtUtcMillis: Long,
    val updatedAtUtcMillis: Long,
    val gradeOrSource: String? = null,
    val thicknessNotes: String? = null,
)

@Entity(
    tableName = "ingredients",
    foreignKeys = [
        ForeignKey(
            entity = CookEntity::class,
            parentColumns = ["id"],
            childColumns = ["cookId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = DishEntity::class,
            parentColumns = ["id"],
            childColumns = ["dishId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["cookId"]), Index(value = ["dishId"])],
)
data class IngredientEntity(
    @PrimaryKey val id: String,
    val cookId: String,
    val dishId: String,
    val stage: String,
    val name: String,
    val brand: String? = null,
    val amountValue: Double? = null,
    val amountUnit: String? = null,
    val appliedAtUtcMillis: Long? = null,
    val notes: String? = null,
    val createdAtUtcMillis: Long,
)

@Entity(
    tableName = "timeline_events",
    foreignKeys = [
        ForeignKey(
            entity = CookEntity::class,
            parentColumns = ["id"],
            childColumns = ["cookId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = DishEntity::class,
            parentColumns = ["id"],
            childColumns = ["dishId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["cookId", "occurredAtUtcMillis"]), Index(value = ["dishId"])],
)
data class TimelineEventEntity(
    @PrimaryKey val id: String,
    val cookId: String,
    val dishId: String? = null,
    val eventType: String,
    val title: String,
    val details: String? = null,
    val occurredAtUtcMillis: Long,
    val recordedAtUtcMillis: Long,
    val timeZoneId: String,
    val source: String,
    val sourceDeviceId: String? = null,
    val createdAtUtcMillis: Long,
    val updatedAtUtcMillis: Long,
    val temperatureContextJson: String? = null,
)

@Entity(
    tableName = "devices",
    foreignKeys = [
        ForeignKey(
            entity = CookEntity::class,
            parentColumns = ["id"],
            childColumns = ["cookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["cookId"])],
)
data class DeviceEntity(
    @PrimaryKey val id: String,
    val cookId: String,
    val brand: String? = null,
    val model: String? = null,
    val deviceName: String,
    val role: String,
    val firmwareVersion: String? = null,
    val createdAtUtcMillis: Long,
)

@Entity(
    tableName = "probes",
    foreignKeys = [
        ForeignKey(
            entity = CookEntity::class,
            parentColumns = ["id"],
            childColumns = ["cookId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = DeviceEntity::class,
            parentColumns = ["id"],
            childColumns = ["deviceId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = DishEntity::class,
            parentColumns = ["id"],
            childColumns = ["assignedDishId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["cookId"]), Index(value = ["deviceId"]), Index(value = ["assignedDishId"])],
)
data class ProbeEntity(
    @PrimaryKey val id: String,
    val cookId: String,
    val deviceId: String? = null,
    val assignedDishId: String? = null,
    val name: String,
    val measurementType: String,
    val source: String,
    val createdAtUtcMillis: Long,
)

@Entity(
    tableName = "sensor_readings",
    foreignKeys = [
        ForeignKey(
            entity = CookEntity::class,
            parentColumns = ["id"],
            childColumns = ["cookId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = DishEntity::class,
            parentColumns = ["id"],
            childColumns = ["dishId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = ProbeEntity::class,
            parentColumns = ["id"],
            childColumns = ["probeId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["cookId", "measuredAtUtcMillis"]),
        Index(value = ["dishId"]),
        Index(value = ["probeId"]),
    ],
)
data class SensorReadingEntity(
    @PrimaryKey val id: String,
    val cookId: String,
    val dishId: String? = null,
    val probeId: String? = null,
    val probeName: String,
    val measurementType: String,
    val value: Double,
    val unit: String,
    val measuredAtUtcMillis: Long,
    val timeZoneId: String,
    val source: String,
    val sourceDeviceId: String? = null,
    val qualityStatus: String = "valid",
    val recordedAtUtcMillis: Long,
    @androidx.room.ColumnInfo(defaultValue = "'measurement'") val timestampBasis: String = "measurement",
    @androidx.room.ColumnInfo(defaultValue = "15000") val samplingIntervalMillis: Long = 15_000L,
)

@Entity(
    tableName = "targets",
    foreignKeys = [
        ForeignKey(
            entity = CookEntity::class,
            parentColumns = ["id"],
            childColumns = ["cookId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = DishEntity::class,
            parentColumns = ["id"],
            childColumns = ["dishId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["cookId"]), Index(value = ["dishId"])],
)
data class TargetEntity(
    @PrimaryKey val id: String,
    val cookId: String,
    val dishId: String? = null,
    val targetType: String,
    val value: Double,
    val unit: String,
    val scope: String,
    val explanation: String? = null,
    val sourceReference: String? = null,
    val createdAtUtcMillis: Long,
)

@Entity(
    tableName = "cook_results",
    foreignKeys = [
        ForeignKey(
            entity = CookEntity::class,
            parentColumns = ["id"],
            childColumns = ["cookId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = DishEntity::class,
            parentColumns = ["id"],
            childColumns = ["dishId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["cookId"]), Index(value = ["dishId"])],
)
data class CookResultEntity(
    @PrimaryKey val id: String,
    val cookId: String,
    val dishId: String? = null,
    val resultType: String,
    val numericValue: Double? = null,
    val unit: String? = null,
    val textValue: String? = null,
    val notes: String? = null,
    val recordedAtUtcMillis: Long,
)

@Entity(
    tableName = "photos",
    foreignKeys = [
        ForeignKey(
            entity = CookEntity::class,
            parentColumns = ["id"],
            childColumns = ["cookId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = DishEntity::class,
            parentColumns = ["id"],
            childColumns = ["dishId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = TimelineEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["cookId"]), Index(value = ["dishId"]), Index(value = ["eventId"])],
)
data class PhotoEntity(
    @PrimaryKey val id: String,
    val cookId: String,
    val dishId: String? = null,
    val eventId: String? = null,
    val originalFileName: String,
    val relativePath: String,
    val mimeType: String,
    val caption: String? = null,
    val capturedAtUtcMillis: Long? = null,
    val addedAtUtcMillis: Long,
    val temperatureContextJson: String? = null,
)

@Entity(
    tableName = "cook_reminders",
    foreignKeys = [ForeignKey(entity = CookEntity::class, parentColumns = ["id"], childColumns = ["cookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["cookId", "status", "dueAtUtcMillis"])],
)
data class CookReminderEntity(
    @PrimaryKey val id: String,
    val cookId: String,
    val title: String,
    val dueAtUtcMillis: Long,
    val timeZoneId: String,
    val status: String = STATUS_PENDING,
    val createdAtUtcMillis: Long,
    val completedAtUtcMillis: Long? = null,
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_CANCELLED = "cancelled"
    }
}

data class CookWithDishes(
    @Embedded val cook: CookEntity,
    @Relation(parentColumn = "id", entityColumn = "cookId")
    val dishes: List<DishEntity>,
)

data class CookDetailData(
    val cook: CookEntity,
    val dishes: List<DishEntity>,
    val ingredients: List<IngredientEntity>,
    val events: List<TimelineEventEntity>,
    val readings: List<SensorReadingEntity>,
    val targets: List<TargetEntity>,
    val results: List<CookResultEntity>,
    val photos: List<PhotoEntity>,
    val reminders: List<CookReminderEntity>,
    val recording: CookRecordingEntity? = null,
    val probes: List<ProbeEntity> = emptyList(),
    val assignments: List<ProbeAssignmentEntity> = emptyList(),
)

data class InsightsSnapshot(
    val cooks: List<CookWithDishes>,
    val readings: List<SensorReadingEntity>,
    val results: List<CookResultEntity>,
    val connectionGaps: List<TimelineEventEntity> = emptyList(),
)

@Dao
interface CookDao {
    @Query("SELECT * FROM timeline_events WHERE source = 'controller_cloud' AND eventType = 'connection_gap' ORDER BY occurredAtUtcMillis")
    fun observeConnectionGaps(): Flow<List<TimelineEventEntity>>
    @Query("SELECT * FROM probes WHERE cookId = :cookId ORDER BY name ASC")
    fun observeProbes(cookId: String): Flow<List<ProbeEntity>>

    @Query("SELECT * FROM probes WHERE cookId = :cookId ORDER BY name ASC")
    suspend fun getProbesForCook(cookId: String): List<ProbeEntity>

    @Update
    suspend fun updateProbe(probe: ProbeEntity)

    @Query("SELECT * FROM dishes WHERE cookId = :cookId ORDER BY rowid ASC")
    suspend fun getDishesForCook(cookId: String): List<DishEntity>

    @Query("SELECT * FROM sensor_readings WHERE cookId = :cookId AND measuredAtUtcMillis BETWEEN :after AND :before ORDER BY measuredAtUtcMillis DESC LIMIT 160")
    suspend fun getReadingsForContext(cookId: String, after: Long, before: Long): List<SensorReadingEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCook(cook: CookEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDishes(dishes: List<DishEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertIngredients(ingredients: List<IngredientEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTimelineEvents(events: List<TimelineEventEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPhotos(photos: List<PhotoEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReminder(reminder: CookReminderEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRemindersIgnoringDuplicates(reminders: List<CookReminderEntity>)

    @Update
    suspend fun updateReminder(reminder: CookReminderEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCooksIgnoringDuplicates(cooks: List<CookEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDishesIgnoringDuplicates(dishes: List<DishEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIngredientsIgnoringDuplicates(ingredients: List<IngredientEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEventsIgnoringDuplicates(events: List<TimelineEventEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReadingsIgnoringDuplicates(readings: List<SensorReadingEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTargetsIgnoringDuplicates(targets: List<TargetEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertResultsIgnoringDuplicates(results: List<CookResultEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDevicesIgnoringDuplicates(devices: List<DeviceEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertProbesIgnoringDuplicates(probes: List<ProbeEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPhotosIgnoringDuplicates(photos: List<PhotoEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertResult(result: CookResultEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTimelineEvent(event: TimelineEventEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSensorReading(reading: SensorReadingEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDish(dish: DishEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTarget(target: TargetEntity)

    @Delete
    suspend fun deleteTarget(target: TargetEntity)

    @Query("DELETE FROM cook_results WHERE id = :id")
    suspend fun deleteResult(id: String)

    @Update
    suspend fun updateCook(cook: CookEntity)

    @Update
    suspend fun updateDish(dish: DishEntity)

    @Delete
    suspend fun deleteDish(dish: DishEntity)

    @Update
    suspend fun updateTimelineEvent(event: TimelineEventEntity)

    @Update
    suspend fun updateSensorReading(reading: SensorReadingEntity)

    @Delete
    suspend fun deleteTimelineEvent(event: TimelineEventEntity)

    @Delete
    suspend fun deleteSensorReading(reading: SensorReadingEntity)

    @Transaction
    @Query("SELECT * FROM cooks ORDER BY startedAtUtcMillis DESC")
    fun observeCooks(): Flow<List<CookWithDishes>>

    @Transaction
    @Query("SELECT * FROM cooks WHERE id = :cookId LIMIT 1")
    fun observeCook(cookId: String): Flow<CookWithDishes?>

    @Query("SELECT * FROM cooks ORDER BY startedAtUtcMillis DESC")
    suspend fun getAllCooks(): List<CookEntity>

    @Query("SELECT * FROM dishes ORDER BY createdAtUtcMillis ASC")
    suspend fun getAllDishes(): List<DishEntity>

    @Query("SELECT * FROM ingredients ORDER BY createdAtUtcMillis ASC")
    suspend fun getAllIngredients(): List<IngredientEntity>

    @Query("SELECT * FROM timeline_events ORDER BY occurredAtUtcMillis ASC")
    suspend fun getAllTimelineEvents(): List<TimelineEventEntity>

    @Query("SELECT * FROM sensor_readings ORDER BY measuredAtUtcMillis ASC")
    suspend fun getAllSensorReadings(): List<SensorReadingEntity>

    @Query("SELECT * FROM targets")
    suspend fun getAllTargets(): List<TargetEntity>

    @Query("SELECT * FROM cook_results")
    suspend fun getAllResults(): List<CookResultEntity>

    @Query("SELECT * FROM devices")
    suspend fun getAllDevices(): List<DeviceEntity>

    @Query("SELECT * FROM probes")
    suspend fun getAllProbes(): List<ProbeEntity>

    @Query("SELECT * FROM photos")
    suspend fun getAllPhotos(): List<PhotoEntity>

    @Query("SELECT * FROM cook_reminders")
    suspend fun getAllReminders(): List<CookReminderEntity>

    @Query("SELECT * FROM cook_reminders WHERE status = 'pending' ORDER BY dueAtUtcMillis ASC")
    suspend fun getPendingReminders(): List<CookReminderEntity>

    @Query("SELECT * FROM cook_reminders WHERE cookId = :cookId AND status = 'pending' ORDER BY dueAtUtcMillis ASC")
    suspend fun getPendingRemindersForCook(cookId: String): List<CookReminderEntity>

    @Query("SELECT * FROM cook_reminders WHERE id = :reminderId LIMIT 1")
    suspend fun getReminder(reminderId: String): CookReminderEntity?

    @Query("SELECT * FROM cooks WHERE id = :cookId LIMIT 1")
    suspend fun getCook(cookId: String): CookEntity?

    @Query("SELECT * FROM dishes WHERE id = :dishId LIMIT 1")
    suspend fun getDish(dishId: String): DishEntity?

    @Query("SELECT * FROM dishes WHERE cookId = :cookId ORDER BY createdAtUtcMillis ASC")
    fun observeDishes(cookId: String): Flow<List<DishEntity>>

    @Query("SELECT * FROM ingredients WHERE cookId = :cookId ORDER BY createdAtUtcMillis ASC")
    fun observeIngredients(cookId: String): Flow<List<IngredientEntity>>

    @Query("SELECT * FROM timeline_events WHERE cookId = :cookId ORDER BY occurredAtUtcMillis ASC")
    fun observeTimelineEvents(cookId: String): Flow<List<TimelineEventEntity>>

    @Query("SELECT * FROM timeline_events WHERE id = :eventId LIMIT 1")
    suspend fun getTimelineEvent(eventId: String): TimelineEventEntity?

    @Query("SELECT * FROM sensor_readings WHERE cookId = :cookId ORDER BY measuredAtUtcMillis ASC")
    fun observeSensorReadings(cookId: String): Flow<List<SensorReadingEntity>>

    @Query("SELECT * FROM targets WHERE cookId = :cookId")
    fun observeTargets(cookId: String): Flow<List<TargetEntity>>

    @Query("SELECT * FROM cook_results WHERE cookId = :cookId")
    fun observeResults(cookId: String): Flow<List<CookResultEntity>>

    @Query("SELECT * FROM photos WHERE cookId = :cookId ORDER BY addedAtUtcMillis ASC")
    fun observePhotos(cookId: String): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM cook_reminders WHERE cookId = :cookId ORDER BY dueAtUtcMillis ASC")
    fun observeReminders(cookId: String): Flow<List<CookReminderEntity>>

    @Query("SELECT * FROM photos WHERE cookId = :cookId")
    suspend fun getPhotosForCook(cookId: String): List<PhotoEntity>

    @Query("SELECT * FROM sensor_readings ORDER BY measuredAtUtcMillis ASC")
    fun observeAllSensorReadings(): Flow<List<SensorReadingEntity>>

    @Query("SELECT * FROM cook_results")
    fun observeAllResults(): Flow<List<CookResultEntity>>

    @Query("SELECT * FROM ingredients WHERE cookId = :cookId ORDER BY createdAtUtcMillis ASC")
    suspend fun getIngredientsForCook(cookId: String): List<IngredientEntity>

    @Query("SELECT * FROM timeline_events WHERE cookId = :cookId ORDER BY occurredAtUtcMillis ASC")
    suspend fun getTimelineEventsForCook(cookId: String): List<TimelineEventEntity>

    @Query("UPDATE cooks SET status = 'completed', endedAtUtcMillis = :endedAtUtcMillis, updatedAtUtcMillis = :endedAtUtcMillis WHERE id = :cookId")
    suspend fun finishCook(cookId: String, endedAtUtcMillis: Long)

    @Delete
    suspend fun deleteCook(cook: CookEntity)
}
