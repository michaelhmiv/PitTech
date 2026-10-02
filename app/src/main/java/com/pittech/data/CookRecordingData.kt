package com.pittech.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** The cloud identifier is never stored here: controllerKey is a local SHA-256 binding. */
@Entity(
    tableName = "cook_recordings",
    foreignKeys = [ForeignKey(entity = CookEntity::class, parentColumns = ["id"], childColumns = ["cookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["deviceId"]), Index(value = ["status"])],
)
data class CookRecordingEntity(
    @PrimaryKey val cookId: String,
    val deviceId: String,
    val controllerKey: String,
    val status: String,
    val unit: String,
    val startedAtUtcMillis: Long,
    val resumedAtUtcMillis: Long,
    val lastProcessedAtUtcMillis: Long? = null,
    val lastReceivedAtUtcMillis: Long? = null,
    val gapStartedAtUtcMillis: Long? = null,
    val message: String = "Waiting for a cloud reading.",
    val confirmedSetpoint: Double? = null,
    val pendingSetpoint: Double? = null,
    val pendingSetpointCount: Int = 0,
) {
    companion object {
        const val RECORDING = "recording"
        const val PAUSED = "paused"
        const val STOPPED = "stopped"
    }
}

@Entity(
    tableName = "probe_assignments",
    foreignKeys = [
        ForeignKey(entity = CookEntity::class, parentColumns = ["id"], childColumns = ["cookId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ProbeEntity::class, parentColumns = ["id"], childColumns = ["probeId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = DishEntity::class, parentColumns = ["id"], childColumns = ["dishId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index(value = ["cookId"]), Index(value = ["probeId", "startedAtUtcMillis"]), Index(value = ["dishId"])],
)
data class ProbeAssignmentEntity(
    @PrimaryKey val id: String,
    val cookId: String,
    val probeId: String,
    val dishId: String?,
    val startedAtUtcMillis: Long,
    val endedAtUtcMillis: Long? = null,
)

@Dao
interface CookRecordingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveRecording(recording: CookRecordingEntity)

    @Query("SELECT * FROM cook_recordings WHERE cookId = :cookId LIMIT 1")
    suspend fun getRecording(cookId: String): CookRecordingEntity?

    @Query("SELECT * FROM cook_recordings WHERE cookId = :cookId LIMIT 1")
    fun observeRecording(cookId: String): Flow<CookRecordingEntity?>

    @Query("SELECT * FROM cook_recordings WHERE status = 'recording' LIMIT 1")
    fun observeActiveRecording(): Flow<CookRecordingEntity?>

    @Query("SELECT * FROM cook_recordings WHERE status = 'recording' LIMIT 1")
    suspend fun getActiveRecording(): CookRecordingEntity?

    @Query("SELECT * FROM cook_recordings")
    suspend fun getAllRecordings(): List<CookRecordingEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRecordings(recordings: List<CookRecordingEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAssignment(assignment: ProbeAssignmentEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAssignments(assignments: List<ProbeAssignmentEntity>)

    @Update
    suspend fun updateAssignment(assignment: ProbeAssignmentEntity)

    @Query("SELECT * FROM probe_assignments WHERE cookId = :cookId ORDER BY startedAtUtcMillis ASC")
    fun observeAssignments(cookId: String): Flow<List<ProbeAssignmentEntity>>

    @Query("SELECT * FROM probe_assignments WHERE probeId = :probeId AND startedAtUtcMillis <= :time AND (endedAtUtcMillis IS NULL OR endedAtUtcMillis > :time) ORDER BY startedAtUtcMillis DESC LIMIT 1")
    suspend fun assignmentAt(probeId: String, time: Long): ProbeAssignmentEntity?

    @Query("SELECT * FROM probe_assignments WHERE probeId = :probeId AND endedAtUtcMillis IS NULL ORDER BY startedAtUtcMillis DESC LIMIT 1")
    suspend fun currentAssignment(probeId: String): ProbeAssignmentEntity?

    @Query("SELECT * FROM probe_assignments")
    suspend fun getAllAssignments(): List<ProbeAssignmentEntity>
}
