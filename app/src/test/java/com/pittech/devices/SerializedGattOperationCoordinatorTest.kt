package com.pittech.devices

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SerializedGattOperationCoordinatorTest {
    @Test
    fun onlyOneGattOperationCanBeInFlightAndCallbackMustMatchItsTarget() {
        val queue = SerializedGattOperationCoordinator()
        assertTrue(queue.begin(GattOperationKind.DISCOVER_SERVICES))
        assertFalse(queue.begin(GattOperationKind.READ_CHARACTERISTIC, "service/char-a"))
        assertFalse(queue.complete(GattOperationKind.READ_CHARACTERISTIC, "service/char-a"))
        assertTrue(queue.complete(GattOperationKind.DISCOVER_SERVICES))
        assertTrue(queue.begin(GattOperationKind.READ_CHARACTERISTIC, "service/char-a"))
        assertFalse(queue.complete(GattOperationKind.READ_CHARACTERISTIC, "service/char-b"))
        assertTrue(queue.timedOut(GattOperationKind.READ_CHARACTERISTIC, "service/char-a"))
        assertTrue(queue.begin(GattOperationKind.READ_CHARACTERISTIC, "service/char-b"))
    }
}
