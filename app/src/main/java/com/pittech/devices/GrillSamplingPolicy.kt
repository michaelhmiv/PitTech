package com.pittech.devices

internal enum class GrillSamplingMode(val key: String) { PERIODIC("periodic"), ON_LOG("on_log") }

/** Requested collection cadence, not a claim that Android or the cloud guarantees delivery. */
internal data class GrillSamplingPolicy(
    val mode: GrillSamplingMode = GrillSamplingMode.PERIODIC,
    val intervalMillis: Long = 60_000L,
) {
    init { require(intervalMillis in INTERVALS) }
    val label: String get() = if (mode == GrillSamplingMode.ON_LOG) "When I log something" else "Every " + intervalLabel(intervalMillis)
    val sampleIntervalMillis: Long get() = if (mode == GrillSamplingMode.ON_LOG) 0L else intervalMillis
    companion object {
        val INTERVALS = listOf(15_000L, 30_000L, 60_000L, 120_000L, 300_000L)
        fun intervalLabel(value: Long): String = when (value) {
            15_000L -> "15 seconds"
            30_000L -> "30 seconds"
            60_000L -> "1 minute"
            120_000L -> "2 minutes"
            300_000L -> "5 minutes"
            else -> error("Unsupported collection interval")
        }
        fun stored(mode: String, interval: Long): GrillSamplingPolicy = GrillSamplingPolicy(
            GrillSamplingMode.entries.firstOrNull { it.key == mode } ?: error("Unsupported collection mode"), interval)
        fun receiptWindow(interval: Long): Long = if (interval == 0L) 15_000L else maxOf(45_000L, interval + 30_000L)
        fun validSampleInterval(interval: Long) = interval == 0L || interval in INTERVALS
    }
}
