package com.pittech.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Small local documents have explicit domain codecs. Cook-owned documents cascade with a cook;
 * saved playbooks and equipment remain independent. No credentials or controller state belong here. */
@Entity(tableName = "companion_records", foreignKeys = [ForeignKey(entity = CookEntity::class,
    parentColumns = ["id"], childColumns = ["cookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("cookId"), Index("kind")])
data class CompanionRecord(
    @PrimaryKey val id: String,
    val kind: String,
    val cookId: String? = null,
    val title: String,
    val payload: String,
    val createdAtUtcMillis: Long,
    val updatedAtUtcMillis: Long,
)

@Entity(tableName = "companion_photos", foreignKeys = [ForeignKey(entity = CompanionRecord::class,
    parentColumns = ["id"], childColumns = ["recordId"], onDelete = ForeignKey.CASCADE)], indices = [Index("recordId")])
data class CompanionPhoto(
    @PrimaryKey val id: String,
    val recordId: String,
    val relativePath: String,
    val mimeType: String,
    val caption: String,
    val dishIndex: Int?,
    val action: String?,
)

@Dao
interface CookCompanionDao {
    @Query("SELECT * FROM companion_records ORDER BY updatedAtUtcMillis DESC")
    fun observeAll(): Flow<List<CompanionRecord>>
    @Query("SELECT * FROM companion_records WHERE cookId = :cookId")
    fun observeCook(cookId: String): Flow<List<CompanionRecord>>
    @Query("SELECT * FROM companion_records ORDER BY createdAtUtcMillis ASC")
    suspend fun all(): List<CompanionRecord>
    @Query("SELECT * FROM companion_records WHERE id = :id")
    suspend fun get(id: String): CompanionRecord?
    @Query("SELECT * FROM companion_records WHERE cookId = :cookId")
    suspend fun forCook(cookId: String): List<CompanionRecord>
    @Upsert
    suspend fun put(record: CompanionRecord)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun restore(records: List<CompanionRecord>)
    @Query("DELETE FROM companion_records WHERE id = :id")
    suspend fun delete(id: String)
    @Query("SELECT * FROM companion_photos")
    suspend fun photos(): List<CompanionPhoto>
    @Query("SELECT * FROM companion_photos WHERE recordId = :recordId")
    suspend fun photosFor(recordId: String): List<CompanionPhoto>
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putPhotos(photos: List<CompanionPhoto>)
}
