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
    ],
    version = 2,
    exportSchema = true,
)
abstract class PitTechDatabase : RoomDatabase() {
    abstract fun cookDao(): CookDao

    companion object {
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
    }
}
