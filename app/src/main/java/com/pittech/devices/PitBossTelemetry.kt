package com.pittech.devices

import org.json.JSONObject
import java.util.Locale
import kotlin.math.floor

/** Static read-only frame layouts audited against pytboss's vendor catalogue.
 * No vendor JavaScript, model command tables, or writable RPCs are evaluated.
 */
internal object PitBossTelemetry {
    private data class Layout(val length: Int, val probes: List<Int>, val target: Int, val chamber: Int, val unit: Int, val fahrenheitWire: Boolean)
    private val layouts = buildMap {
        fun group(names: String, layout: Layout) { names.split(' ').forEach { put(it, layout) } }
        group("LBL", Layout(24, listOf(5, 8, 11, 14), 17, 20, 23, false))
        group("LFS", Layout(24, listOf(5, 8, 11, 14), 17, 20, 23, true))
        group("PBB PBD", Layout(30, listOf(8, 11, 14, 17), 23, 26, 29, false))
        group("PBA PBE PBT", Layout(30, listOf(8, 11, 14, 17), 23, 26, 29, true))
        group("PBC PBC2 PBG PBL PBL2 PBP", Layout(27, listOf(5, 8, 11, 14), 20, 23, 26, false))
        group("PBL3 PBV PBV2", Layout(27, listOf(5, 8, 11, 14), 20, 23, 26, true))
        group("PBM PBM2", Layout(21, listOf(5, 8, 11), 14, 17, 20, true))
        // PBVA's fourth probe offset is the setpoint: explicitly excluded.
        group("PBVA", Layout(22, listOf(5, 8), 14, 17, 20, true))
    }
    val families: Set<String> = layouts.keys + "PBX1"
    fun controllerId(value: String): String {
        val entered = value.trim()
        val id = entered.substringBefore('-').uppercase(Locale.ROOT) + "-" + entered.substringAfter('-', "")
        require(id.length <= 160 && id.substringBefore('-') in families && id.substringAfter('-').isNotBlank() &&
            id.substringAfter('-').trim() == id.substringAfter('-') && id.none { it.isISOControl() || it in "/?#\\" }) {
            "Enter the complete supported controller ID from the vendor app or Bluetooth name."
        }
        return id
    }
    fun probeCount(controllerId: String): Int = if (controllerId.substringBefore('-') == "PBX1") 2 else layouts.getValue(controllerId.substringBefore('-')).probes.size
    fun parse(controllerId: String, result: JSONObject): PolarisPayload {
        val family = controllerId.substringBefore('-')
        val raw = mutableMapOf("onlineStatus" to 0.0)
        val hex = (result.opt("sc_12") as? String)?.takeIf { it.isNotBlank() }
            ?: if (family == "PBX1") result.opt("sc_11") as? String else null
        val bytes = frame(hex)
        if (family == "PBX1") {
            if (bytes.size < 16 || bytes[0] != 254 || bytes[1] != 26) throw PolarisFailure(PolarisFailureKind.SCHEMA)
            val fahrenheit = bytes[15] and 8 == 0
            raw["tempUnit"] = if (fahrenheit) 0.0 else 1.0
            fun encoded(key: String, offset: Int) {
                val value = (bytes[offset] shl 7) or (bytes[offset + 1] and 127)
                if (value == 0 || value - 8192 == 960) return
                val temp = (value - 8192).toDouble()
                raw[key] = if (fahrenheit) temp else floor((temp - 32) / 1.8)
            }
            encoded("furnaceTempMeasured", 2)
            encoded("furnaceTempSetting", 4)
            encoded("probeP1Measured", 6)
            encoded("probeP2Measured", 10)
            raw["vendorPowerState"] = if (bytes[15] and 16 != 0) 1.0 else 0.0
        } else {
            val layout = layouts[family] ?: throw PolarisFailure(PolarisFailureKind.SCHEMA)
            if (bytes.size < layout.length || bytes[0] != 254 || bytes[1] != 12 || bytes[layout.unit] !in 0..1)
                throw PolarisFailure(PolarisFailureKind.SCHEMA)
            val fahrenheit = bytes[layout.unit] == 1
            raw["tempUnit"] = if (fahrenheit) 0.0 else 1.0
            fun digits(key: String, offset: Int) {
                val digits = bytes.slice(offset..offset + 2)
                if (digits.any { it !in 0..9 }) return
                val temp = digits[0] * 100 + digits[1] * 10 + digits[2]
                if (temp == 960) return
                var value = temp.toDouble()
                if (!fahrenheit && layout.fahrenheitWire) {
                    value = if (family == "PBVA" && key == "furnaceTempSetting") {
                        val f = listOf(100,115,125,135,145,150,160,170,180,190,195,205,215,225,235,240,250,260,270,280,285,290,300,310,320)
                        val index = f.indexOf(temp)
                        if (index >= 0) (40 + index * 5).toDouble() else floor((value - 32) / 1.8)
                    } else floor((value - 32) / 1.8)
                }
                raw[key] = value
            }
            digits("furnaceTempMeasured", layout.chamber)
            digits("furnaceTempSetting", layout.target)
            layout.probes.forEachIndexed { index, offset -> digits("probeP" + (index + 1) + "Measured", offset) }
        }
        if (!raw.containsKey("furnaceTempMeasured") && raw.keys.none { it.startsWith("probeP") }) throw PolarisFailure(PolarisFailureKind.SCHEMA)
        return PolarisPayload(raw, raw.keys.sorted(), result.length().coerceAtLeast(0), null, zeroProbeMeansUnavailable = false, probeCount = probeCount(controllerId))
    }
    private fun frame(hex: String?): List<Int> {
        if (hex == null || hex.length !in 4..512 || hex.length % 2 != 0 || !Regex("[A-Fa-f0-9]+").matches(hex))
            throw PolarisFailure(PolarisFailureKind.SCHEMA)
        return hex.chunked(2).map { it.toInt(16) }
    }
}

