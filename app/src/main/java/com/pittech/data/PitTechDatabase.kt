package com.pittech.data

import androidx.room.Database
import androidx.room.RoomDatabase

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
    version = 1,
    exportSchema = true,
)
abstract class PitTechDatabase : RoomDatabase() {
    abstract fun cookDao(): CookDao
}
