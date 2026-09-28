package com.pittech.devices

import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper

data class NearbyPitBossController(
    val identifier: String,
    val rssi: Int,
)

/**
 * Foreground-only BLE discovery for the first Pit Boss connection test.
 *
 * The advertised local name is used as the controller identifier; no MAC
 * address or scan payload is retained.
 */
class PitBossBleDiscovery(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var activeScanner: BluetoothLeScanner? = null
    private var activeCallback: ScanCallback? = null
    private var timeout: Runnable? = null

    fun startScan(
        onDevices: (List<NearbyPitBossController>) -> Unit,
        onFinished: (String?) -> Unit,
    ) {
        stopScan()

        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val scanner = try {
            manager?.adapter?.bluetoothLeScanner
        } catch (_: SecurityException) {
            onFinished("Bluetooth scan permission was not granted.")
            return
        }
        if (scanner == null) {
            onFinished("Bluetooth is unavailable or turned off.")
            return
        }

        val found = linkedMapOf<String, NearbyPitBossController>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val advertisedName = result.scanRecord?.deviceName ?: return
                val identifier = PitBossRelayProtocol.identifierFromBluetoothName(advertisedName) ?: return
                found[identifier] = NearbyPitBossController(identifier, result.rssi)
                onDevices(found.values.sortedByDescending { it.rssi })
            }

            override fun onScanFailed(errorCode: Int) {
                finishScan(this, onFinished, "Bluetooth scan failed (code $errorCode).")
            }
        }

        activeScanner = scanner
        activeCallback = callback
        try {
            scanner.startScan(
                null,
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build(),
                callback,
            )
        } catch (_: SecurityException) {
            activeScanner = null
            activeCallback = null
            onFinished("Bluetooth scan permission was not granted.")
            return
        } catch (_: IllegalStateException) {
            activeScanner = null
            activeCallback = null
            onFinished("Bluetooth is unavailable or turned off.")
            return
        }
        if (activeCallback !== callback) return

        val timeoutTask = Runnable {
            val message = if (found.isEmpty()) {
                "No controller ID was found. Turn on the controller and scan again."
            } else {
                null
            }
            finishScan(callback, onFinished, message)
        }
        timeout = timeoutTask
        handler.postDelayed(timeoutTask, SCAN_DURATION_MILLIS)
    }

    fun stopScan() {
        timeout?.let(handler::removeCallbacks)
        timeout = null

        val scanner = activeScanner
        val callback = activeCallback
        activeScanner = null
        activeCallback = null
        if (scanner != null && callback != null) {
            try {
                scanner.stopScan(callback)
            } catch (_: SecurityException) {
                // Permission may have been revoked while the screen was open.
            } catch (_: IllegalStateException) {
                // Bluetooth may have been switched off during discovery.
            }
        }
    }

    private fun finishScan(
        callback: ScanCallback,
        onFinished: (String?) -> Unit,
        message: String?,
    ) {
        if (activeCallback !== callback) return
        stopScan()
        onFinished(message)
    }

    private companion object {
        const val SCAN_DURATION_MILLIS = 12_000L
    }
}
