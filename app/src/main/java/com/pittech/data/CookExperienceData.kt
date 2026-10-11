package com.pittech.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import com.pittech.domain.CookNoteMetadata
import com.pittech.domain.PlanEvent
import com.pittech.domain.PlaybookCodec

@Entity(tableName = "log_reading_links",
    foreignKeys = [ForeignKey(entity = TimelineEventEntity::class, parentColumns = ["id"], childColumns = ["eventId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = SensorReadingEntity::class, parentColumns = ["id"], childColumns = ["readingId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["eventId"])])
data class LogReadingLink(@PrimaryKey val readingId: String, val eventId: String)

@Entity(tableName = "cook_lifecycle",
    foreignKeys = [ForeignKey(entity = CookEntity::class, parentColumns = ["id"], childColumns = ["cookId"], onDelete = ForeignKey.CASCADE)])
data class CookLifecycle(@PrimaryKey val cookId: String,
    @ColumnInfo(defaultValue = "'legacy_preserved'") val timingMode: String = "legacy_preserved")

@Entity(tableName = "cook_log_drafts",
    foreignKeys = [ForeignKey(entity = CookEntity::class, parentColumns = ["id"], childColumns = ["cookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["cookId"])])
data class CookLogDraft(@PrimaryKey val id: String, val cookId: String, val payload: String, val updatedAt: Long)

@Entity(tableName = "target_history",
    foreignKeys = [ForeignKey(entity = CookEntity::class, parentColumns = ["id"], childColumns = ["cookId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = DishEntity::class, parentColumns = ["id"], childColumns = ["dishId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index(value = ["cookId"]), Index(value = ["dishId"])])
data class TargetHistory(@PrimaryKey val id: String, val cookId: String, val dishId: String?, val targetType: String,
    val value: Double, val unit: String, val effectiveAt: Long)

@Dao interface CookExperienceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun linkReadings(links: List<LogReadingLink>)
    @Query("SELECT l.* FROM log_reading_links l JOIN timeline_events e ON e.id = l.eventId WHERE e.cookId = :cookId")
    fun observeLinks(cookId: String): Flow<List<LogReadingLink>>
    @Query("SELECT * FROM log_reading_links") suspend fun allLinks(): List<LogReadingLink>
    @Query("SELECT r.* FROM sensor_readings r JOIN log_reading_links l ON l.readingId = r.id WHERE l.eventId = :eventId")
    suspend fun readingsForLog(eventId: String): List<SensorReadingEntity>
    @Query("SELECT * FROM sensor_readings WHERE id = :id") suspend fun reading(id: String): SensorReadingEntity?
    @Query("SELECT * FROM log_reading_links WHERE readingId = :id") suspend fun readingLink(id: String): LogReadingLink?
    @Query("DELETE FROM log_reading_links WHERE eventId = :id") suspend fun unlink(id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putLifecycle(value: CookLifecycle)
    @Query("SELECT * FROM cook_lifecycle WHERE cookId = :id") suspend fun lifecycle(id: String): CookLifecycle?
    @Query("SELECT * FROM cook_lifecycle WHERE cookId = :id") fun observeLifecycle(id: String): Flow<CookLifecycle?>
    @Query("SELECT * FROM cook_lifecycle") suspend fun allLifecycles(): List<CookLifecycle>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDraft(value: CookLogDraft)
    @Query("SELECT * FROM cook_log_drafts WHERE cookId = :id ORDER BY updatedAt DESC") fun observeDrafts(id: String): Flow<List<CookLogDraft>>
    @Query("SELECT * FROM cook_log_drafts WHERE id = :id") suspend fun draft(id: String): CookLogDraft?
    @Query("DELETE FROM cook_log_drafts WHERE id = :id") suspend fun deleteDraft(id: String)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun putHistory(values: List<TargetHistory>)
    @Query("SELECT * FROM target_history WHERE cookId = :id ORDER BY effectiveAt") fun observeHistory(id: String): Flow<List<TargetHistory>>
    @Query("SELECT * FROM target_history") suspend fun allHistory(): List<TargetHistory>
    @Query("SELECT * FROM photos WHERE id = :id") suspend fun photo(id: String): PhotoEntity?
    @Update suspend fun updatePhoto(value: PhotoEntity)
}

fun notePlanEvents(events: List<TimelineEventEntity>): List<PlanEvent> = events.flatMap { e ->
    val scopes = CookNoteMetadata.decode(e.noteMetadataJson)?.dishIds.orEmpty()
    if (scopes.isEmpty()) listOf(PlanEvent(e.id, PlaybookCodec.action(e.eventType, e.noteMetadataJson), e.dishId, e.occurredAtUtcMillis))
    else scopes.map { PlanEvent(e.id, PlaybookCodec.action(e.eventType, e.noteMetadataJson), it, e.occurredAtUtcMillis) }
}
