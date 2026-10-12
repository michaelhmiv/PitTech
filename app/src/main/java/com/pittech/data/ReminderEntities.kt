package com.pittech.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "reminder_rules", foreignKeys = [ForeignKey(entity = CookEntity::class, parentColumns = ["id"], childColumns = ["cookId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = DishEntity::class, parentColumns = ["id"], childColumns = ["dishId"], onDelete = ForeignKey.CASCADE)], indices = [Index("cookId"), Index("dishId")])
data class ReminderRule(@PrimaryKey val id: String, val cookId: String, val dishId: String?, val title: String, val kind: String,
    val triggerConfig: String, val sourceRecipeRevisionId: String?, val sourceMilestoneId: String?, val enabled: Boolean,
    val status: String, val createdAtUtcMillis: Long, val updatedAtUtcMillis: Long, val schemaVersion: Int = 1)

/** Sequence zero stores evaluation state; later sequences are distinct durable delivery receipts. */
@Entity(tableName = "reminder_occurrences", foreignKeys = [ForeignKey(entity = ReminderRule::class, parentColumns = ["id"], childColumns = ["ruleId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["ruleId", "sequence"], unique = true)])
data class ReminderOccurrence(@PrimaryKey val id: String, val ruleId: String, val sequence: Int, val status: String,
    val dueAtUtcMillis: Long? = null, val deliveredAtUtcMillis: Long? = null, val acknowledgedAtUtcMillis: Long? = null,
    val snoozedUntilUtcMillis: Long? = null, val badSinceUtcMillis: Long? = null, val lastSampleAtUtcMillis: Long? = null,
    val active: Boolean = false, val actionEventId: String? = null)

@Dao interface ReminderDao {
    @Query("SELECT * FROM reminder_rules") fun observeRules(): Flow<List<ReminderRule>>
    @Query("SELECT * FROM reminder_occurrences") fun observeOccurrences(): Flow<List<ReminderOccurrence>>
    @Query("SELECT * FROM reminder_rules") suspend fun rules(): List<ReminderRule>
    @Query("SELECT * FROM reminder_occurrences") suspend fun occurrences(): List<ReminderOccurrence>
    @Query("SELECT * FROM reminder_rules WHERE id = :id") suspend fun rule(id: String): ReminderRule?
    @Query("SELECT * FROM reminder_occurrences WHERE ruleId = :id ORDER BY sequence DESC") suspend fun history(id: String): List<ReminderOccurrence>
    @Upsert suspend fun putRule(rule: ReminderRule)
    @Upsert suspend fun putOccurrence(occurrence: ReminderOccurrence)
    @Query("DELETE FROM reminder_rules WHERE id = :id") suspend fun delete(id: String)
}
