package com.pittech

import android.app.Application
import androidx.room.Room
import com.pittech.data.CookRepository
import com.pittech.data.PitTechDatabase
import com.pittech.data.PitTechDataTransfer
import com.pittech.data.PhotoStorage
import com.pittech.data.CookRecordingRepository
import com.pittech.devices.PrimePolarisMonitor
import com.pittech.devices.PrimePolarisApi
import com.pittech.devices.AndroidPolarisSessionStore
import kotlinx.coroutines.flow.MutableStateFlow

class PitTechApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashDiagnostics.install(this)
    }

    val database: PitTechDatabase by lazy {
        Room.databaseBuilder(this, PitTechDatabase::class.java, "pittech-local.db")
            .addMigrations(PitTechDatabase.MIGRATION_1_2)
            .addMigrations(PitTechDatabase.MIGRATION_2_3)
            .addMigrations(PitTechDatabase.MIGRATION_3_4)
            .build()
    }

    val cookRepository: CookRepository by lazy {
        CookRepository(database, PhotoStorage(this))
    }

    val dataTransfer: PitTechDataTransfer by lazy {
        PitTechDataTransfer(this, cookRepository)
    }

    private val sharedGrillMonitor by lazy { PrimePolarisMonitor(PrimePolarisApi(), AndroidPolarisSessionStore(this)) }
    internal var grillMonitorForTests: PrimePolarisMonitor? = null
    internal val grillMonitor: PrimePolarisMonitor get() = grillMonitorForTests ?: sharedGrillMonitor
    internal val recordingRepository by lazy { CookRecordingRepository(database) }
    internal val recordingServiceRunning = MutableStateFlow(false)
}
