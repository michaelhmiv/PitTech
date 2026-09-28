package com.pittech.ui

import android.content.Context
import com.pittech.devices.BluetoothGattInspectionReport
import com.pittech.devices.BluetoothGattInspector
import com.pittech.devices.BluetoothScanSummary
import com.pittech.devices.ControllerBleDiscovery
import com.pittech.devices.ControllerProbeReport
import com.pittech.devices.MongooseBleRpcProbe
import com.pittech.devices.NearbyBluetoothDevice

/** Boundary between the diagnostics screen and hardware-backed BLE work. */
internal interface ControllerDiagnosticsEngine {
    val requiresBluetoothPermissions: Boolean

    fun startScan(
        onDevices: (List<NearbyBluetoothDevice>) -> Unit,
        onFinished: (BluetoothScanSummary, String?) -> Unit,
    )

    fun stopScan()

    fun inspect(
        address: String,
        onProgress: (String) -> Unit,
        onFinished: (BluetoothGattInspectionReport) -> Unit,
    )

    fun probe(
        address: String,
        inspection: BluetoothGattInspectionReport,
        onProgress: (String) -> Unit,
        onFinished: (ControllerProbeReport) -> Unit,
    )

    fun close()
}

internal class AndroidControllerDiagnosticsEngine(context: Context) : ControllerDiagnosticsEngine {
    private val discovery = ControllerBleDiscovery(context)
    private val gattInspector = BluetoothGattInspector(context)
    private val mongooseProbe = MongooseBleRpcProbe(context)

    override val requiresBluetoothPermissions: Boolean = true

    override fun startScan(
        onDevices: (List<NearbyBluetoothDevice>) -> Unit,
        onFinished: (BluetoothScanSummary, String?) -> Unit,
    ) = discovery.startScan(onDevices, onFinished)

    override fun stopScan() = discovery.stopScan()

    override fun inspect(
        address: String,
        onProgress: (String) -> Unit,
        onFinished: (BluetoothGattInspectionReport) -> Unit,
    ) = gattInspector.inspect(address, onProgress, onFinished)

    override fun probe(
        address: String,
        inspection: BluetoothGattInspectionReport,
        onProgress: (String) -> Unit,
        onFinished: (ControllerProbeReport) -> Unit,
    ) = mongooseProbe.probe(address, inspection, onProgress, onFinished)

    override fun close() {
        discovery.stopScan()
        gattInspector.closeSilently()
        mongooseProbe.closeSilently()
    }
}

internal fun interface ControllerReportSubmitter {
    fun submit(
        request: com.pittech.FeedbackRequest,
        onResult: (com.pittech.FeedbackSubmitResult) -> Unit,
    )
}
