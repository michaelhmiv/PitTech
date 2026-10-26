package com.pittech.devices

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** No passwords or tokens; local endpoint/device settings are excluded from backup/export. */
internal data class TelemetryProfile(
    val id: String, val provider: String, val label: String,
    val endpoint: String = "", val deviceHint: String = "",
    val sources: List<TelemetrySource> = emptyList(),
    val cookId: String? = null, val monitoring: Boolean = false,
    val background: Boolean = false, val selectedDeviceIds: Set<String> = emptySet(),
    val onLogOnly: Boolean = false, val intervalMillis: Long = 60_000L, val generation: Long = 0L,
) {
    init {
        TelemetryConnectorRegistry.requirePlanned(provider)
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,80}")) && label.isNotBlank())
        require(intervalMillis in listOf(15_000L, 30_000L, 60_000L, 120_000L, 300_000L))
        require(sources.all { it.connectorId == provider })
    }
}

internal class TelemetryProfileStore(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "telemetry-profiles-v1.json"))
    val profiles = MutableStateFlow(read())
    @Synchronized fun save(value: TelemetryProfile) = write(profiles.value.filterNot { it.id == value.id } + value)
    @Synchronized fun remove(id: String) = write(profiles.value.filterNot { it.id == id })
    private fun read(): List<TelemetryProfile> {
        if (!file.baseFile.exists()) return emptyList()
        return runCatching {
            require(file.baseFile.length() <= 1_048_576)
            val array = JSONArray(file.readFully().toString(Charsets.UTF_8))
            (0 until array.length().coerceAtMost(50)).mapNotNull { index ->
                runCatching { decode(array.getJSONObject(index)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }
    private fun write(values: List<TelemetryProfile>) {
        require(values.size <= 50)
        val data = JSONArray(values.map(::encode)).toString().toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try { stream.write(data); file.finishWrite(stream); profiles.value = values }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    internal companion object {
        fun encode(p: TelemetryProfile) = JSONObject().put("id", p.id).put("provider", p.provider)
            .put("label", p.label).put("endpoint", p.endpoint).put("deviceHint", p.deviceHint)
            .put("cookId", p.cookId ?: JSONObject.NULL).put("monitoring", p.monitoring)
            .put("background", p.background).put("selectedDevices", JSONArray(p.selectedDeviceIds.toList()))
            .put("generation", p.generation).put("onLogOnly", p.onLogOnly).put("intervalMillis", p.intervalMillis)
            .put("sources", JSONArray(p.sources.map { s ->
                JSONObject().put("deviceId", s.deviceId).put("label", s.label).put("scope", s.identityScope)
                    .put("manufacturer", s.manufacturer ?: JSONObject.NULL).put("model", s.model ?: JSONObject.NULL)
                    .put("channels", JSONArray(s.channels.map { c ->
                        JSONObject().put("id", c.id).put("name", c.name).put("kind", c.kind.name)
                    }))
            }))
        fun decode(j: JSONObject): TelemetryProfile {
            val provider = j.getString("provider")
            val list = j.optJSONArray("sources") ?: JSONArray()
            val sources = (0 until list.length()).map { i ->
                val s = list.getJSONObject(i); val cs = s.getJSONArray("channels")
                TelemetrySource(provider, s.getString("deviceId"), s.getString("label"),
                    s.optString("manufacturer").takeUnless { s.isNull("manufacturer") },
                    s.optString("model").takeUnless { s.isNull("model") },
                    (0 until cs.length()).map { c -> cs.getJSONObject(c).let {
                        TelemetryChannel(it.getString("id"), it.getString("name"), TelemetryKind.valueOf(it.getString("kind")))
                    } }, identityScope = s.optString("scope"))
            }
            return TelemetryProfile(j.getString("id"), provider, j.getString("label"),
                j.optString("endpoint"), j.optString("deviceHint"), sources,
                j.optString("cookId").takeUnless { j.isNull("cookId") }, j.optBoolean("monitoring"),
                j.optBoolean("background"), (j.optJSONArray("selectedDevices") ?: JSONArray()).let { (0 until it.length()).map { i -> it.getString(i) }.toSet() }, j.optBoolean("onLogOnly"), j.optLong("intervalMillis", 60_000L), j.optLong("generation", 0L))
        }
    }
}

/**
 * PL-05 (#172): an in-progress attach/assign edit for one connection. Held
 * by the runtime so it survives the Devices sheet being dismissed; a
 * successful attach consumes it.
 */
internal data class TelemetryAssignmentDraft(
    val profileId: String,
    val sourceId: String? = null,
    val cookId: String? = null,
    val roles: Map<String, TelemetryKind> = emptyMap(),
    val dishes: Map<String, String?> = emptyMap(),
)

/**
 * PL-05: the empty-devices line. "No devices found" is a settled-discovery
 * verdict; before discovery settles the sheet says it is still looking (or
 * that a connect is needed first) instead of flashing a false empty state.
 */
internal fun devicesEmptyStateText(discoveryInFlight: Boolean, discoverySettled: Boolean): String = when {
    discoveryInFlight -> "Looking for devices…"
    discoverySettled -> "No devices found. Check Bluetooth or the provider's cloud connection, then reconnect."
    else -> "Connect this connection to discover its devices."
}

/** PL-05: the stepper opens on Connect until a first connection exists, then on Attach. */
internal fun initialDevicesStep(hasProfiles: Boolean): Int = if (hasProfiles) 2 else 1

/** PL-05: a fresh draft seeded from a connection's saved attach/assign state. */
internal fun seedAssignmentDraft(profile: TelemetryProfile, activeCookId: String?): TelemetryAssignmentDraft {
    val source = profile.sources.firstOrNull { it.deviceId in profile.selectedDeviceIds } ?: profile.sources.firstOrNull()
    return TelemetryAssignmentDraft(
        profileId = profile.id,
        sourceId = source?.deviceId,
        cookId = profile.cookId ?: activeCookId,
        roles = source?.channels?.associate { it.id to it.kind } ?: emptyMap(),
    )
}

/** Each provider branch supplies its own factory. Missing adapters stay unavailable. */
internal interface TelemetryConnectorFactory {
    suspend fun revoke(context: Context, profile: TelemetryProfile) { TelemetryTokenStore(context).clear(profile.provider, profile.id) }
    suspend fun connect(context: Context, profile: TelemetryProfile, user: String, secret: String): ReadOnlyTelemetryConnector
    suspend fun restore(context: Context, profile: TelemetryProfile): ReadOnlyTelemetryConnector
}
internal object TelemetryFactories {
    private val classes = mapOf("home_assistant" to "HomeAssistantFactory", "meater" to "MeaterFactory",
        "fireboard" to "FireBoardFactory", "flame_boss" to "FlameBossFactory", "combustion" to "CombustionFactory")
    fun get(provider: String): TelemetryConnectorFactory? {
        TelemetryConnectorRegistry.requirePlanned(provider)
        return runCatching { Class.forName("com.pittech.devices." + classes.getValue(provider))
            .getDeclaredConstructor().newInstance() as TelemetryConnectorFactory }.getOrNull()
    }
}
