package com.pittech.devices

import org.junit.Assert.*
import org.junit.Test

/** PL-05 (#172): the Devices stepper's presentation rules. */
class TelemetryDevicesPresentationTest {
    @Test fun noDevicesFoundOnlyRendersAfterDiscoverySettles() {
        assertEquals("Looking for devices…", devicesEmptyStateText(discoveryInFlight = true, discoverySettled = false))
        assertEquals("Looking for devices…", devicesEmptyStateText(discoveryInFlight = true, discoverySettled = true))
        assertTrue(devicesEmptyStateText(discoveryInFlight = false, discoverySettled = true).startsWith("No devices found."))
        assertEquals("Connect this connection to discover its devices.", devicesEmptyStateText(discoveryInFlight = false, discoverySettled = false))
    }

    @Test fun stepperStartsOnConnectUntilAConnectionExists() {
        assertEquals(1, initialDevicesStep(hasProfiles = false))
        assertEquals(2, initialDevicesStep(hasProfiles = true))
    }

    @Test fun draftSeedsFromTheAttachedSourceAndCook() {
        val source = TelemetrySource("meater", "probe-1", "Probe", channels = listOf(
            TelemetryChannel("internal", "Internal", TelemetryKind.FOOD),
            TelemetryChannel("ambient", "Ambient", TelemetryKind.AMBIENT)))
        val profile = TelemetryProfile("p1", "meater", "Probe", sources = listOf(source), cookId = "cook-1", selectedDeviceIds = setOf("probe-1"))
        val draft = seedAssignmentDraft(profile, "cook-active")
        assertEquals("probe-1", draft.sourceId)
        assertEquals("cook-1", draft.cookId)
        assertEquals(TelemetryKind.FOOD, draft.roles["internal"])
        assertEquals(TelemetryKind.AMBIENT, draft.roles["ambient"])
        val unattached = seedAssignmentDraft(profile.copy(cookId = null, selectedDeviceIds = emptySet()), "cook-active")
        assertEquals("probe-1", unattached.sourceId)
        assertEquals("cook-active", unattached.cookId)
    }
}
