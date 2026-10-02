package com.pittech.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
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
    ],
    version = 4,
    exportSchema = true,
)
abstract class PitTechDatabase : RoomDatabase() {
    abstract fun cookDao(): CookDao
    abstract fun recordingDao(): CookRecordingDao

    companion object {
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
