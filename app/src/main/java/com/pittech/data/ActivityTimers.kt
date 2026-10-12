package com.pittech.data

import androidx.room.*
import androidx.room.withTransaction
import com.pittech.domain.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Entity(tableName = "activity_timers", foreignKeys = [
    ForeignKey(entity = CookEntity::class, parentColumns = ["id"], childColumns = ["cookId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = DishEntity::class, parentColumns = ["id"], childColumns = ["dishId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("cookId"), Index("dishId"), Index(value = ["noteId"], unique = true)])
data class ActivityTimer(@PrimaryKey val id: String, val cookId: String, val dishId: String?, val title: String,
    val method: String?, val startedAtUtcMillis: Long, val stoppedAtUtcMillis: Long?, val desiredDurationMillis: Long?,
    val noteId: String, val status: String)

@Dao
interface ActivityTimerDao {
    @Query("SELECT * FROM activity_timers WHERE cookId = :cookId ORDER BY startedAtUtcMillis") fun observe(cookId: String): Flow<List<ActivityTimer>>
    @Query("SELECT * FROM activity_timers") suspend fun all(): List<ActivityTimer>
    @Query("SELECT * FROM activity_timers WHERE id = :id") suspend fun get(id: String): ActivityTimer?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(timer: ActivityTimer)
    @Update suspend fun update(timer: ActivityTimer)
    @Query("UPDATE activity_timers SET status = 'logged' WHERE noteId = :noteId AND cookId = :cookId AND status = 'stopped'") suspend fun markLogged(noteId: String, cookId: String)
}

class ActivityTimerRepository(private val db: PitTechDatabase) {
    fun observe(cookId: String) = db.activityTimerDao().observe(cookId)
    suspend fun start(cookId: String, dishId: String?, title: String, method: String?, desiredDurationMillis: Long? = null,
        at: Long = System.currentTimeMillis()): ActivityTimer = db.withTransaction {
        require(db.cookDao().getCook(cookId)?.status in setOf(CookStatus.ACTIVE, CookStatus.PAUSED)) { "Start timers in an active meal." }
        require(dishId == null || db.cookDao().getDish(dishId)?.cookId == cookId)
        require(title.isNotBlank() && (method == null || method in CookingMethods.labels) && at > 0 && at <= System.currentTimeMillis())
        require(desiredDurationMillis == null || desiredDurationMillis in 1..2_592_000_000L)
        ActivityTimer(UUID.randomUUID().toString(), cookId, dishId, title.trim(), method, at, null,
            desiredDurationMillis, UUID.randomUUID().toString(), "running").also { db.activityTimerDao().insert(it) }
    }
    /** Stop is durable; accepting the offered note records the measured duration once. */
    suspend fun stop(id: String, at: Long = System.currentTimeMillis()): CookNoteDraft = db.withTransaction {
        val timer = db.activityTimerDao().get(id) ?: error("This timer is unavailable.")
        require(timer.status in setOf("running", "stopped")) { "This timer has already been saved or disabled." }
        val end = timer.stoppedAtUtcMillis ?: at
        require(end >= timer.startedAtUtcMillis)
        db.activityTimerDao().update(timer.copy(stoppedAtUtcMillis = end, status = "stopped"))
        CookNoteDraft(id = timer.noteId, cookId = timer.cookId, dishId = timer.dishId, title = timer.title,
            occurredAt = end, method = timer.method, durationMillis = end - timer.startedAtUtcMillis,
            startedAtUtcMillis = timer.startedAtUtcMillis, endedAtUtcMillis = end)
    }
    suspend fun cancel(id: String) = db.withTransaction {
        db.activityTimerDao().get(id)?.let { db.activityTimerDao().update(it.copy(status = "cancelled")) }
    }
    suspend fun cancelForCook(cookId: String) {
        db.activityTimerDao().all().filter { it.cookId == cookId && it.status == "running" }.forEach { db.activityTimerDao().update(it.copy(status = "cancelled")) }
    }
}
