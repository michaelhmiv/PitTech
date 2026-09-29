package com.pittech.devices

import java.security.MessageDigest

/** Retains notification statistics only; raw payloads remain transient callback data. */
internal class PassiveNotificationAccumulator(
    private val serviceUuid: String,
    private val characteristicUuid: String,
) {
    var eventCount: Int = 0
        private set
    var payloadChangeCount: Int = 0
        private set
    var totalPayloadBytes: Int = 0
        private set
    private var previousPayloadHash: String? = null

    fun record(payload: ByteArray) {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (eventCount == 0 || digest != previousPayloadHash) payloadChangeCount++
        previousPayloadHash = digest
        eventCount++
        totalPayloadBytes += payload.size
    }

    fun snapshot(durationMillis: Long, error: String? = null): BluetoothGattNotificationObservation =
        BluetoothGattNotificationObservation(
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid,
            eventCount = eventCount,
            payloadChangeCount = payloadChangeCount,
            totalPayloadBytes = totalPayloadBytes,
            observationDurationMillis = durationMillis.coerceAtLeast(0),
            error = error,
        )
}
