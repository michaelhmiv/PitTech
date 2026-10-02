package com.pittech.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Values are copied at entry creation; later edits/deletion of samples cannot change an entry. */
data class ContextTemperature(
    val name: String,
    val value: Double,
    val unit: String,
    val observedAtUtcMillis: Long,
    val source: String,
    val timestampBasis: String,
)

object TemperatureContext {
    fun select(readings: List<SensorReadingEntity>, time: Long, dishId: String?): List<ContextTemperature> = readings
        .filter { it.qualityStatus == "valid" && it.value.isFinite() && it.measuredAtUtcMillis <= time &&
            time - it.measuredAtUtcMillis <= if (it.source == "controller_cloud") 45_000L else 300_000L }
        .filter { dishId == null || it.dishId == dishId || it.measurementType in setOf("pit_ambient", "setpoint") }
        .groupBy { it.probeId ?: "${it.source}:${it.probeName}" }
        .values.mapNotNull { it.maxByOrNull { reading -> reading.measuredAtUtcMillis } }
        .sortedBy { it.probeName }.take(12)
        .map { ContextTemperature(it.probeName, it.value, it.unit, it.measuredAtUtcMillis, it.source, it.timestampBasis) }

    fun encode(values: List<ContextTemperature>): String? = values.takeIf { it.isNotEmpty() }?.let {
        JSONArray().apply { it.forEach { value -> put(JSONObject()
            .put("name", value.name).put("value", value.value).put("unit", value.unit)
            .put("observedAtUtcMillis", value.observedAtUtcMillis).put("source", value.source)
            .put("timestampBasis", value.timestampBasis)) } }.toString()
    }

    fun decode(json: String?): List<ContextTemperature> {
        if (json == null) return emptyList()
        return runCatching {
            require(json.length <= 12_000)
            val array = JSONArray(json)
            require(array.length() <= 12)
            (0 until array.length()).map { index ->
                val o = array.getJSONObject(index)
                val value = o.getDouble("value")
                val unit = o.getString("unit")
                require(value.isFinite() && value in -100.0..1000.0 && unit in setOf("°F", "°C"))
                val name = o.getString("name")
                require(name.length in 1..120)
                val source = o.getString("source")
                val basis = o.optString("timestampBasis", "measurement")
                require(source in setOf("manual", "controller_cloud") && basis in setOf("measurement", "cloud_receipt"))
                ContextTemperature(name, value, unit, o.getLong("observedAtUtcMillis"), source, basis)
            }
        }.getOrDefault(emptyList())
    }

    fun summary(json: String?): String = decode(json).joinToString(" · ") {
        "${it.name} ${String.format(Locale.US, "%.0f", it.value)} ${it.unit}"
    }
}
