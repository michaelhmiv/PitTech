package com.pittech.devices

/** A working neighbor must not make an unavailable food probe appear live. */
internal object TelemetryChannelHealth {
    fun validChannels(source: TelemetrySource, batch: List<TelemetryObservation>): Set<String> = source.channels.mapNotNull { channel ->
        batch.filter { it.channelId == channel.id }.maxByOrNull { it.observedAtMillis ?: it.receivedAtMillis }
            ?.takeIf { TelemetryNormalizer.normalize(it) != null }?.channelId
    }.toSet()
}
