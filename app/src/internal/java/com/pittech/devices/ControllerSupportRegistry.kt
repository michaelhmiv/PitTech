package com.pittech.devices

import java.util.Locale

internal enum class ControllerSupportStatus {
    VERIFIED,
    LIKELY_MATCH,
    UNKNOWN,
    INCOMPATIBLE,
}

internal data class ControllerSupportMatch(
    val status: ControllerSupportStatus,
    val profileId: String? = null,
    val reason: String,
)

/**
 * Physical verification registry.
 *
 * Discovery evidence can identify a likely family, but only a profile that has
 * passed end-to-end hardware testing can produce VERIFIED status.
 */
internal object ControllerSupportRegistry {
    private data class VerifiedProfile(
        val id: String,
        val manufacturer: String,
        val productFamily: String,
        val protocolFamilies: Set<ControllerProtocolFamily>,
        val requiredGattServices: Set<String> = emptySet(),
        val requiredRpcMethods: Set<String> = emptySet(),
        val namePrefixes: Set<String> = emptySet(),
        val manufacturerIds: Set<String> = emptySet(),
        val minimumProbeVersion: Int = CURRENT_PROBE_VERSION,
    )

    /**
     * Keep this empty until a controller has passed physical discovery,
     * connection, state-read and intended control-operation testing.
     */
    private val verifiedProfiles = emptyList<VerifiedProfile>()

    fun evaluate(
        device: NearbyBluetoothDevice,
        inspection: BluetoothGattInspectionReport? = null,
        probe: ControllerProbeReport? = null,
    ): ControllerSupportMatch {
        val gattServices = inspection
            ?.services
            ?.map { it.uuid.lowercase(Locale.ROOT) }
            ?.toSet()
            .orEmpty()
        val rpcMethods = probe?.rpcMethods?.toSet().orEmpty()
        val protocols = probe?.protocols
            ?: inspection?.let(ControllerProtocolDetector::detect)
            ?: emptySet()
        val advertisedServices = device.advertisements
            .flatMap { it.serviceUuids }
            .map { it.lowercase(Locale.ROOT) }
            .toSet()
        val manufacturerIds = device.advertisements
            .flatMap { it.manufacturerData.keys }
            .map { it.uppercase(Locale.ROOT) }
            .toSet()

        verifiedProfiles.firstOrNull { profile ->
            val nameMatches = profile.namePrefixes.isEmpty() ||
                profile.namePrefixes.any { prefix ->
                    device.advertisedName.orEmpty().startsWith(prefix, ignoreCase = true)
                }
            val manufacturerMatches = profile.manufacturerIds.isEmpty() ||
                manufacturerIds.containsAll(profile.manufacturerIds.map { it.uppercase(Locale.ROOT) })
            val protocolMatches = profile.protocolFamilies.isEmpty() ||
                protocols.containsAll(profile.protocolFamilies)
            val requiredServices = profile.requiredGattServices.map { it.lowercase(Locale.ROOT) }.toSet()
            val gattMatches = requiredServices.isEmpty() ||
                gattServices.containsAll(requiredServices) ||
                advertisedServices.containsAll(requiredServices)
            val rpcMatches = profile.requiredRpcMethods.isEmpty() ||
                rpcMethods.containsAll(profile.requiredRpcMethods)

            nameMatches && manufacturerMatches && protocolMatches && gattMatches && rpcMatches &&
                CURRENT_PROBE_VERSION >= profile.minimumProbeVersion
        }?.let { profile ->
            return ControllerSupportMatch(
                status = ControllerSupportStatus.VERIFIED,
                profileId = profile.id,
                reason = "${profile.manufacturer} ${profile.productFamily} passed the registered hardware profile.",
            )
        }

        if (protocols.isNotEmpty() && ControllerProtocolFamily.UNKNOWN !in protocols) {
            return ControllerSupportMatch(
                status = ControllerSupportStatus.LIKELY_MATCH,
                reason = "Known protocol evidence was detected, but no physically verified PitTech profile matches yet.",
            )
        }

        return ControllerSupportMatch(
            status = ControllerSupportStatus.UNKNOWN,
            reason = "No physically verified PitTech profile matches this controller yet.",
        )
    }

    fun isApproved(
        device: NearbyBluetoothDevice,
        inspection: BluetoothGattInspectionReport? = null,
        probe: ControllerProbeReport? = null,
    ): Boolean = evaluate(device, inspection, probe).status == ControllerSupportStatus.VERIFIED

    fun label(
        device: NearbyBluetoothDevice,
        inspection: BluetoothGattInspectionReport? = null,
        probe: ControllerProbeReport? = null,
    ): String = when (evaluate(device, inspection, probe).status) {
        ControllerSupportStatus.VERIFIED -> "Verified by PitTech"
        ControllerSupportStatus.LIKELY_MATCH -> "Known protocol · not yet hardware verified"
        ControllerSupportStatus.UNKNOWN -> "Not yet verified by PitTech"
        ControllerSupportStatus.INCOMPATIBLE -> "Known incompatible controller"
    }

    const val CURRENT_PROBE_VERSION = 1
}
