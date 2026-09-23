package com.pittech

import android.app.Application
import androidx.room.Room
import com.pittech.data.CookRepository
import com.pittech.data.PitTechDatabase
import com.pittech.data.PhotoStorage

class PitTechApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashDiagnostics.install(this)
    }

    val database: PitTechDatabase by lazy {
        Room.databaseBuilder(this, PitTechDatabase::class.java, "pittech-local.db")
            .build()
    }

    val cookRepository: CookRepository by lazy {
        CookRepository(database, PhotoStorage(this))
    }
}
