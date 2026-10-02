package com.pittech.devices

import java.security.MessageDigest

internal data class CookTelemetryValue(val channel: String, val name: String, val type: String, val value: Double, val quality: String = "valid")

internal object CookTelemetryPolicy {
    fun deviceKey(id: String): String = MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun convert(value: Double, from: String, to: String): Double = when {
        from == to -> value
        from == "°F" && to == "°C" -> (value - 32.0) * 5.0 / 9.0
        from == "°C" && to == "°F" -> value * 9.0 / 5.0 + 32.0
        else -> error("Temperature unit is unknown.")
    }

    fun values(sample: PolarisSample, unit: String): List<CookTelemetryValue> {
        val raw = sample.payload.values
        val from = when (raw["tempUnit"]) { 0.0 -> "°F"; 1.0 -> "°C"; else -> return emptyList() }
        return listOf(
            Triple("furnaceTempMeasured", "Chamber", "pit_ambient"),
            Triple("furnaceTempSetting", "Setpoint", "setpoint"),
        ).plus((1..sample.payload.probeCount.coerceIn(1, 4)).map { Triple("probeP" + it + "Measured", "Probe " + it, "food_probe") }).mapNotNull { (key, name, type) ->
            val reported = raw[key]?.takeIf { it.isFinite() && it in -100.0..1000.0 }
            val value = reported ?: 0.0
            // Zero probe readings were physically confirmed with unplugged probes. Keep them as
            // unavailable samples, not food temperatures. ProbeStatus describes alarm arming,
            // NOT physical attachment. Do not use it to infer that a probe is connected.
            val quality = if (reported == null || ((type == "setpoint" || type == "food_probe" && sample.payload.zeroProbeMeansUnavailable) && value == 0.0)) "unavailable" else "valid"
            CookTelemetryValue(key, name, type, convert(value, from, unit), quality)
        }
    }
}
