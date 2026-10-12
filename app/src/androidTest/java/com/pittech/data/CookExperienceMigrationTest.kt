package com.pittech.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*

class CookExperienceMigrationTest {
    @Test fun versionSixPreservesLegacyIdentityTimeAndContextWithoutInferringFoodOn() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "experience-migration"
        context.deleteDatabase(name)
        val schema = JSONObject(instrumentation.context.assets.open("com.pittech.data.PitTechDatabase/6.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        val old = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null)
        try {
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO cooks (id,title,status,startedAtUtcMillis,startedTimeZoneId,createdAtUtcMillis,updatedAtUtcMillis) VALUES ('legacy','Overnight','active',42,'UTC',42,42)")
            old.execSQL("INSERT INTO timeline_events (id,cookId,eventType,title,occurredAtUtcMillis,recordedAtUtcMillis,timeZoneId,source,createdAtUtcMillis,updatedAtUtcMillis,temperatureContextJson) VALUES ('old-note','legacy','note','Bark',52,62,'UTC','manual',62,62,'[]')")
            old.version = 6
        } finally { old.close() }
        // Room's real open path runs the migration and validates every generated table,
        // index and foreign key against the current database implementation.
        val room = Room.databaseBuilder(context, PitTechDatabase::class.java, name).addMigrations(PitTechDatabase.MIGRATION_6_7, PitTechDatabase.MIGRATION_7_8, PitTechDatabase.MIGRATION_8_9, PitTechDatabase.MIGRATION_9_10).build()
        try {
            val db = room.openHelper.writableDatabase
            db.query("SELECT startedAtUtcMillis,status FROM cooks WHERE id='legacy'").use { it.moveToFirst(); assertEquals(42L, it.getLong(0)); assertEquals("active", it.getString(1)) }
            db.query("SELECT occurredAtUtcMillis,temperatureContextJson,noteMetadataJson FROM timeline_events").use { it.moveToFirst(); assertEquals(52L, it.getLong(0)); assertEquals("[]", it.getString(1)); assertTrue(it.isNull(2)) }
            db.query("SELECT timingMode FROM cook_lifecycle").use { it.moveToFirst(); assertEquals("legacy_preserved", it.getString(0)) }
            db.query("SELECT COUNT(*) FROM log_reading_links").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        } finally { room.close(); context.deleteDatabase(name) }
    }
}
