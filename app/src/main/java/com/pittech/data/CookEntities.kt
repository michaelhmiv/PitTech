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
)

data class CookWithDishes(
    @Embedded val cook: CookEntity,
    @Relation(parentColumn = "id", entityColumn = "cookId")
    val dishes: List<DishEntity>,
)

@Dao
interface CookDao {
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

    @Transaction
    @Query("SELECT * FROM cooks ORDER BY startedAtUtcMillis DESC")
    fun observeCooks(): Flow<List<CookWithDishes>>

    @Transaction
    @Query("SELECT * FROM cooks WHERE id = :cookId LIMIT 1")
    fun observeCook(cookId: String): Flow<CookWithDishes?>

    @Query("SELECT * FROM ingredients WHERE cookId = :cookId ORDER BY createdAtUtcMillis ASC")
    suspend fun getIngredientsForCook(cookId: String): List<IngredientEntity>

    @Query("SELECT * FROM timeline_events WHERE cookId = :cookId ORDER BY occurredAtUtcMillis ASC")
    suspend fun getTimelineEventsForCook(cookId: String): List<TimelineEventEntity>

    @Query("UPDATE cooks SET status = 'completed', endedAtUtcMillis = :endedAtUtcMillis, updatedAtUtcMillis = :endedAtUtcMillis WHERE id = :cookId")
    suspend fun finishCook(cookId: String, endedAtUtcMillis: Long)

    @Delete
    suspend fun deleteCook(cook: CookEntity)
}
