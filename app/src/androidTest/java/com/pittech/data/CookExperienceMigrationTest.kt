package com.pittech.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class CookExperienceMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), PitTechDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())
    @Test fun versionSixPreservesLegacyIdentityTimeAndContextWithoutInferringFoodOn() {
        helper.createDatabase("experience-migration", 6).apply {
            execSQL("INSERT INTO cooks (id,title,status,startedAtUtcMillis,startedTimeZoneId,createdAtUtcMillis,updatedAtUtcMillis) VALUES ('legacy','Overnight','active',42,'UTC',42,42)")
            execSQL("INSERT INTO timeline_events (id,cookId,eventType,title,occurredAtUtcMillis,recordedAtUtcMillis,timeZoneId,source,createdAtUtcMillis,updatedAtUtcMillis,temperatureContextJson) VALUES ('old-note','legacy','note','Bark',52,62,'UTC','manual',62,62,'[]')")
            close()
        }
        helper.runMigrationsAndValidate("experience-migration", 7, true, PitTechDatabase.MIGRATION_6_7).apply {
            query("SELECT startedAtUtcMillis,status FROM cooks WHERE id='legacy'").use { it.moveToFirst(); assertEquals(42L, it.getLong(0)); assertEquals("active", it.getString(1)) }
            query("SELECT occurredAtUtcMillis,temperatureContextJson,noteMetadataJson FROM timeline_events").use { it.moveToFirst(); assertEquals(52L, it.getLong(0)); assertEquals("[]", it.getString(1)); assertTrue(it.isNull(2)) }
            query("SELECT timingMode FROM cook_lifecycle").use { it.moveToFirst(); assertEquals("legacy_preserved", it.getString(0)) }
            query("SELECT COUNT(*) FROM log_reading_links").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            close()
        }
    }
}
