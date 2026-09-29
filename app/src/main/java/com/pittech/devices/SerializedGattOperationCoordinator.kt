package com.pittech.devices

/**
 * Tracks the single Android GATT request allowed to be in flight for a
 * controller session. Callbacks must match both operation kind and target
 * before the next request can start.
 */
internal class SerializedGattOperationCoordinator {
    private var active: GattOperation? = null

    fun begin(kind: GattOperationKind, target: String = ""): Boolean {
        if (active != null) return false
        active = GattOperation(kind, target)
        return true
    }

    fun complete(kind: GattOperationKind, target: String = ""): Boolean {
        val expected = active ?: return false
        if (expected.kind != kind || expected.target != target) return false
        active = null
        return true
    }

    /** Release an operation whose callback timed out; its late callback is ignored. */
    fun timedOut(kind: GattOperationKind, target: String = ""): Boolean = complete(kind, target)

    fun clear() {
        active = null
    }

    fun hasInFlightOperation(): Boolean = active != null

    private data class GattOperation(val kind: GattOperationKind, val target: String)
}

internal enum class GattOperationKind {
    DISCOVER_SERVICES,
    READ_CHARACTERISTIC,
    WRITE_CHARACTERISTIC,
    WRITE_DESCRIPTOR,
}
