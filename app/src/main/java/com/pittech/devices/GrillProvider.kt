package com.pittech.devices

/** Platforms, rather than brand guesses: replacement controllers may use another cloud. */
internal enum class GrillProvider(val label: String, val emailCode: Boolean, val setup: String) {
    GRILLIRG("GrillirG", true, "For grills already added to GrillirG, including compatible replacement controllers. Both grill and phone need internet."),
    PIT_BOSS("Pit Boss / Louisiana Grills", false, "For Dansons Wi-Fi controllers already provisioned in the vendor app. Enter the actual controller ID (such as PBL-xxxxxx) from its settings or Bluetooth name. It is not the printed grill model. GrillirG replacement controllers use GrillirG instead."),
    TRAEGER("Traeger WiFIRE", false, "For WiFIRE grills already paired in the Traeger app. Sign in with the Traeger account that owns the grill. Wi-Fi hardware is required; Bluetooth-only accessories are not supported."),
}
