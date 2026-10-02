package com.pittech

import android.app.Application
import androidx.room.Room
import com.pittech.data.CookRepository
import com.pittech.data.PitTechDatabase
import com.pittech.data.PitTechDataTransfer
import com.pittech.data.PhotoStorage
import com.pittech.data.CookRecordingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class PitTechApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashDiagnostics.install(this)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            kotlinx.coroutines.flow.combine(database.companionDao().observeAll(), database.cookDao().observeAllTimelineEvents(), database.cookDao().observeAllSensorReadings()) { _, _, _ -> Unit }
                .collect { companionRepository.reconcileAll(this@PitTechApplication) }
        }
    }

    val database: PitTechDatabase by lazy {
        Room.databaseBuilder(this, PitTechDatabase::class.java, "pittech-local.db")
            .addMigrations(PitTechDatabase.MIGRATION_1_2)
            .addMigrations(PitTechDatabase.MIGRATION_2_3)
            .addMigrations(PitTechDatabase.MIGRATION_3_4)
            .addMigrations(PitTechDatabase.MIGRATION_4_5)
            .addMigrations(PitTechDatabase.MIGRATION_5_6)
            .build()
    }

    val cookRepository: CookRepository by lazy {
        CookRepository(database, PhotoStorage(this))
    }

    val companionRepository by lazy { com.pittech.data.CookCompanionRepository(database, PhotoStorage(this)) }

    val dataTransfer: PitTechDataTransfer by lazy {
        PitTechDataTransfer(this, cookRepository)
    }

    private val sharedGrillMonitor by lazy { com.pittech.devices.ConnectedGrillMonitor.create(this) }
    internal var grillMonitorForTests: com.pittech.devices.PolarisMonitorEngine? = null
    internal val grillMonitor: com.pittech.devices.PolarisMonitorEngine get() = grillMonitorForTests ?: sharedGrillMonitor
    internal val recordingRepository by lazy { CookRecordingRepository(database) }
    internal val recordingServiceRunning = MutableStateFlow(false)
    internal var loggingRecordingActive = false
}
