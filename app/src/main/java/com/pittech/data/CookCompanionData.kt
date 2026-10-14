package com.pittech.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.ByteArrayOutputStream

/** Local documents have explicit domain codecs. Cook-owned documents cascade with a cook;
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

/** Header and bounded BLOB slices avoid Android's per-row CursorWindow limit without changing JSON. */
data class CompanionRecordHeader(
    val id: String, val kind: String, val cookId: String?, val title: String,
    val payloadBytes: Int, val createdAtUtcMillis: Long, val updatedAtUtcMillis: Long,
)

@Dao
abstract class CookCompanionDao {
    @Query("SELECT id, kind, cookId, title, length(CAST(payload AS BLOB)) AS payloadBytes, createdAtUtcMillis, updatedAtUtcMillis FROM companion_records ORDER BY createdAtUtcMillis ASC")
    abstract suspend fun headers(): List<CompanionRecordHeader>
    @Query("SELECT id, kind, cookId, title, length(CAST(payload AS BLOB)) AS payloadBytes, createdAtUtcMillis, updatedAtUtcMillis FROM companion_records ORDER BY updatedAtUtcMillis DESC")
    abstract fun observeHeaders(): Flow<List<CompanionRecordHeader>>
    @Query("SELECT id, kind, cookId, title, length(CAST(payload AS BLOB)) AS payloadBytes, createdAtUtcMillis, updatedAtUtcMillis FROM companion_records WHERE id = :id")
    abstract suspend fun header(id: String): CompanionRecordHeader?
    @Query("SELECT substr(CAST(payload AS BLOB), :offset, :count) FROM companion_records WHERE id = :id")
    abstract suspend fun payloadSlice(id: String, offset: Int, count: Int): ByteArray?
    private suspend fun complete(header: CompanionRecordHeader): CompanionRecord {
        val output = ByteArrayOutputStream(header.payloadBytes.coerceAtMost(512 * 1024))
        var offset = 1
        while (offset <= header.payloadBytes) {
            val bytes = payloadSlice(header.id, offset, minOf(512 * 1024, header.payloadBytes - offset + 1)) ?: error("This saved document is no longer available.")
            check(bytes.isNotEmpty()) { "A saved document could not be reopened." }
            output.write(bytes); offset += bytes.size
        }
        return CompanionRecord(header.id, header.kind, header.cookId, header.title, output.toString(Charsets.UTF_8.name()), header.createdAtUtcMillis, header.updatedAtUtcMillis)
    }
    @Transaction
    open suspend fun all(): List<CompanionRecord> = headers().map { complete(it) }
    @Transaction
    open suspend fun get(id: String): CompanionRecord? = header(id)?.let { complete(it) }
    @Transaction
    open suspend fun forCook(cookId: String): List<CompanionRecord> = headers().filter { it.cookId == cookId }.map { complete(it) }
    fun observeAll(): Flow<List<CompanionRecord>> = observeHeaders().map { all().sortedByDescending { record -> record.updatedAtUtcMillis } }
    fun observeCook(cookId: String): Flow<List<CompanionRecord>> = observeHeaders().map { forCook(cookId) }
    @Upsert
    abstract suspend fun put(record: CompanionRecord)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun restore(records: List<CompanionRecord>)
    @Query("DELETE FROM companion_records WHERE id = :id")
    abstract suspend fun delete(id: String)
    @Query("SELECT * FROM companion_photos")
    abstract suspend fun photos(): List<CompanionPhoto>
    @Query("SELECT * FROM companion_photos WHERE recordId = :recordId")
    abstract suspend fun photosFor(recordId: String): List<CompanionPhoto>
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun putPhotos(photos: List<CompanionPhoto>)
}
