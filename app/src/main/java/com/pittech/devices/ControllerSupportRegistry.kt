package com.pittech.devices

/**
 * A controller is approved only after physical hardware testing establishes
 * reliable discovery and connection behavior. Protocol guesses are not approval.
 */
internal object ControllerSupportRegistry {
    private data class VerifiedProfile(
        val namePrefix: String? = null,
        val serviceUuids: Set<String> = emptySet(),
        val manufacturerIds: Set<String> = emptySet(),
    )

    // Keep empty until a controller has passed an end-to-end hardware test.
    private val verifiedProfiles = emptyList<VerifiedProfile>()

    fun isApproved(device: NearbyBluetoothDevice): Boolean =
        verifiedProfiles.any { profile ->
            val nameMatches = profile.namePrefix == null ||
                device.advertisedName.orEmpty().startsWith(profile.namePrefix, ignoreCase = true)
            val serviceMatches = profile.serviceUuids.isEmpty() ||
                device.advertisements.any { sample -> sample.serviceUuids.toSet().containsAll(profile.serviceUuids) }
            val manufacturerMatches = profile.manufacturerIds.isEmpty() ||
                device.advertisements.any { sample -> sample.manufacturerData.keys.toSet().containsAll(profile.manufacturerIds) }
            nameMatches && serviceMatches && manufacturerMatches
        }

    fun label(device: NearbyBluetoothDevice): String =
        if (isApproved(device)) "Verified by PitTech" else "Not yet verified by PitTech"
}
