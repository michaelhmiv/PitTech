package com.pittech.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ActivityTimer::class,
        SavedRecipe::class, RecipeRevision::class, DishRecipeSource::class, RecipeAsset::class, MealPreset::class,
        CookEntity::class,
        DishEntity::class,
        IngredientEntity::class,
        TimelineEventEntity::class,
        DeviceEntity::class,
        ProbeEntity::class,
        SensorReadingEntity::class,
        TargetEntity::class,
        CookResultEntity::class,
        PhotoEntity::class,
        CookReminderEntity::class,
        CookRecordingEntity::class,
        ProbeAssignmentEntity::class,
        CompanionRecord::class,
        CompanionPhoto::class,
        LogReadingLink::class,
        CookLifecycle::class,
        CookLogDraft::class,
        TargetHistory::class,
    ],
    version = 9,
    exportSchema = true,
)
abstract class PitTechDatabase : RoomDatabase() {
    abstract fun activityTimerDao(): ActivityTimerDao
    abstract fun recipeDao(): RecipeDao
    abstract fun cookDao(): CookDao
    abstract fun recordingDao(): CookRecordingDao
    abstract fun companionDao(): CookCompanionDao
    abstract fun experienceDao(): CookExperienceDao

    companion object {
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE ingredients ADD COLUMN amountText TEXT")
                database.execSQL("CREATE TABLE IF NOT EXISTS activity_timers (id TEXT NOT NULL PRIMARY KEY, cookId TEXT NOT NULL, dishId TEXT, title TEXT NOT NULL, method TEXT, startedAtUtcMillis INTEGER NOT NULL, stoppedAtUtcMillis INTEGER, desiredDurationMillis INTEGER, noteId TEXT NOT NULL, status TEXT NOT NULL, FOREIGN KEY(cookId) REFERENCES cooks(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(dishId) REFERENCES dishes(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_activity_timers_cookId ON activity_timers(cookId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_activity_timers_dishId ON activity_timers(dishId)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_activity_timers_noteId ON activity_timers(noteId)")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS saved_recipes (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, description TEXT NOT NULL, tagsJson TEXT NOT NULL, currentRevisionId TEXT NOT NULL, createdAtUtcMillis INTEGER NOT NULL, updatedAtUtcMillis INTEGER NOT NULL, archivedAtUtcMillis INTEGER, favorite INTEGER NOT NULL)")
                database.execSQL("CREATE TABLE IF NOT EXISTS recipe_revisions (id TEXT NOT NULL PRIMARY KEY, recipeId TEXT NOT NULL, parentRevisionId TEXT, revisionNumber INTEGER NOT NULL, sourceCookId TEXT, sourceDishId TEXT, snapshotSchemaVersion INTEGER NOT NULL, snapshotJson TEXT NOT NULL, createdAtUtcMillis INTEGER NOT NULL, changeNotes TEXT NOT NULL, FOREIGN KEY(recipeId) REFERENCES saved_recipes(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_recipe_revisions_recipeId ON recipe_revisions(recipeId)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_recipe_revisions_recipeId_revisionNumber ON recipe_revisions(recipeId, revisionNumber)")
                database.execSQL("CREATE TABLE IF NOT EXISTS dish_recipe_sources (dishId TEXT NOT NULL PRIMARY KEY, recipeId TEXT NOT NULL, recipeRevisionId TEXT NOT NULL, appliedAtUtcMillis INTEGER NOT NULL, FOREIGN KEY(dishId) REFERENCES dishes(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                database.execSQL("CREATE TABLE IF NOT EXISTS recipe_assets (id TEXT NOT NULL PRIMARY KEY, recipeRevisionId TEXT NOT NULL, relativeFilePath TEXT NOT NULL, mimeType TEXT NOT NULL, caption TEXT NOT NULL, sourcePhotoId TEXT, createdAtUtcMillis INTEGER NOT NULL, FOREIGN KEY(recipeRevisionId) REFERENCES recipe_revisions(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_recipe_assets_recipeRevisionId ON recipe_assets(recipeRevisionId)")
                database.execSQL("CREATE TABLE IF NOT EXISTS meal_presets (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, itemsJson TEXT NOT NULL, createdAtUtcMillis INTEGER NOT NULL, updatedAtUtcMillis INTEGER NOT NULL)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE timeline_events ADD COLUMN noteMetadataJson TEXT")
                database.execSQL("CREATE TABLE IF NOT EXISTS log_reading_links (readingId TEXT NOT NULL PRIMARY KEY, eventId TEXT NOT NULL, FOREIGN KEY(eventId) REFERENCES timeline_events(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(readingId) REFERENCES sensor_readings(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_log_reading_links_eventId ON log_reading_links(eventId)")
                database.execSQL("CREATE TABLE IF NOT EXISTS cook_lifecycle (cookId TEXT NOT NULL PRIMARY KEY, timingMode TEXT NOT NULL DEFAULT 'legacy_preserved', FOREIGN KEY(cookId) REFERENCES cooks(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                database.execSQL("INSERT INTO cook_lifecycle(cookId, timingMode) SELECT id, 'legacy_preserved' FROM cooks")
                database.execSQL("CREATE TABLE IF NOT EXISTS cook_log_drafts (id TEXT NOT NULL PRIMARY KEY, cookId TEXT NOT NULL, payload TEXT NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(cookId) REFERENCES cooks(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_cook_log_drafts_cookId ON cook_log_drafts(cookId)")
                database.execSQL("CREATE TABLE IF NOT EXISTS target_history (id TEXT NOT NULL PRIMARY KEY, cookId TEXT NOT NULL, dishId TEXT, targetType TEXT NOT NULL, value REAL NOT NULL, unit TEXT NOT NULL, effectiveAt INTEGER NOT NULL, FOREIGN KEY(cookId) REFERENCES cooks(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(dishId) REFERENCES dishes(id) ON UPDATE NO ACTION ON DELETE SET NULL)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_target_history_cookId ON target_history(cookId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_target_history_dishId ON target_history(dishId)")
            }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS companion_records (id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, cookId TEXT, title TEXT NOT NULL, payload TEXT NOT NULL, createdAtUtcMillis INTEGER NOT NULL, updatedAtUtcMillis INTEGER NOT NULL, FOREIGN KEY(cookId) REFERENCES cooks(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_companion_records_cookId ON companion_records(cookId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_companion_records_kind ON companion_records(kind)")
                database.execSQL("CREATE TABLE IF NOT EXISTS companion_photos (id TEXT NOT NULL PRIMARY KEY, recordId TEXT NOT NULL, relativePath TEXT NOT NULL, mimeType TEXT NOT NULL, caption TEXT NOT NULL, dishIndex INTEGER, action TEXT, FOREIGN KEY(recordId) REFERENCES companion_records(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_companion_photos_recordId ON companion_photos(recordId)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE cook_recordings ADD COLUMN samplingMode TEXT NOT NULL DEFAULT 'periodic'")
                database.execSQL("ALTER TABLE cook_recordings ADD COLUMN samplingIntervalMillis INTEGER NOT NULL DEFAULT 15000")
                database.execSQL("ALTER TABLE sensor_readings ADD COLUMN samplingIntervalMillis INTEGER NOT NULL DEFAULT 15000")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE timeline_events ADD COLUMN temperatureContextJson TEXT")
                database.execSQL("ALTER TABLE photos ADD COLUMN temperatureContextJson TEXT")
                database.execSQL("ALTER TABLE sensor_readings ADD COLUMN timestampBasis TEXT NOT NULL DEFAULT 'measurement'")
                database.execSQL("CREATE TABLE IF NOT EXISTS cook_recordings (cookId TEXT NOT NULL PRIMARY KEY, deviceId TEXT NOT NULL, controllerKey TEXT NOT NULL, status TEXT NOT NULL, unit TEXT NOT NULL, startedAtUtcMillis INTEGER NOT NULL, resumedAtUtcMillis INTEGER NOT NULL, lastProcessedAtUtcMillis INTEGER, lastReceivedAtUtcMillis INTEGER, gapStartedAtUtcMillis INTEGER, message TEXT NOT NULL, confirmedSetpoint REAL, pendingSetpoint REAL, pendingSetpointCount INTEGER NOT NULL, FOREIGN KEY(cookId) REFERENCES cooks(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_cook_recordings_deviceId ON cook_recordings(deviceId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_cook_recordings_status ON cook_recordings(status)")
                database.execSQL("CREATE TABLE IF NOT EXISTS probe_assignments (id TEXT NOT NULL PRIMARY KEY, cookId TEXT NOT NULL, probeId TEXT NOT NULL, dishId TEXT, startedAtUtcMillis INTEGER NOT NULL, endedAtUtcMillis INTEGER, FOREIGN KEY(cookId) REFERENCES cooks(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(probeId) REFERENCES probes(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(dishId) REFERENCES dishes(id) ON UPDATE NO ACTION ON DELETE SET NULL)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_probe_assignments_cookId ON probe_assignments(cookId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_probe_assignments_probeId_startedAtUtcMillis ON probe_assignments(probeId, startedAtUtcMillis)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_probe_assignments_dishId ON probe_assignments(dishId)")
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE cooks ADD COLUMN fuelType TEXT")
                database.execSQL("ALTER TABLE cooks ADD COLUMN woodOrPelletBlend TEXT")
                database.execSQL("ALTER TABLE cooks ADD COLUMN outdoorTemperatureValue REAL")
                database.execSQL("ALTER TABLE cooks ADD COLUMN outdoorTemperatureUnit TEXT")
                database.execSQL("ALTER TABLE cooks ADD COLUMN weatherNotes TEXT")
                database.execSQL("ALTER TABLE cooks ADD COLUMN windNotes TEXT")
                database.execSQL("ALTER TABLE dishes ADD COLUMN gradeOrSource TEXT")
                database.execSQL("ALTER TABLE dishes ADD COLUMN thicknessNotes TEXT")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS cook_reminders (" +
                        "id TEXT NOT NULL, cookId TEXT NOT NULL, title TEXT NOT NULL, dueAtUtcMillis INTEGER NOT NULL, " +
                        "timeZoneId TEXT NOT NULL, status TEXT NOT NULL, createdAtUtcMillis INTEGER NOT NULL, " +
                        "completedAtUtcMillis INTEGER, PRIMARY KEY(id), " +
                        "FOREIGN KEY(cookId) REFERENCES cooks(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_cook_reminders_cookId_status_dueAtUtcMillis ON cook_reminders(cookId, status, dueAtUtcMillis)")
            }
        }
    }
}
