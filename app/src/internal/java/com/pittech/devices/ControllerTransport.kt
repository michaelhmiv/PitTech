package com.pittech.devices

/**
 * Minimal connection boundary for controller-specific transports.
 * Each adapter can use BLE, a vendor cloud relay, local Wi-Fi, or another protocol.
 */
interface ControllerTransport {
    fun connect(identifier: String)
    fun disconnect()
}
