package com.pittech.data

import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.pittech.devices.GrillSamplingPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID

/**
 * MCP device actions (issue #114, §7): this phone registers itself with
 * the account, opts into named classes of remote app actions, and —
 * when the user opens the app and checks — claims queued requests from
 * the assistant, performs them against the real repositories, and
 * reports the truth back: a request counts as applied only when this
 * code posts that receipt. Nothing here can touch grill hardware; the
 * action enum the server accepts has no hardware class, and every
 * executor below maps to an app-behavior repository call.
 *
 * The device id and the class opt-ins live in a device-only
 * preferences file: they never enter the synced library or an export.
 */

internal val MCP_DEVICE_CLASSES = listOf("reminder", "timer", "guidance", "collection", "probe", "preferences")

internal fun mcpDeviceClassLabel(id: String): String = when (id) {
    "reminder" -> "Reminders"
    "timer" -> "Timers"
    "guidance" -> "Guided cooking"
    "collection" -> "Temperature collection"
    "probe" -> "Probe assignments"
    "preferences" -> "Display & units"
    else -> id
}

internal interface McpDeviceIdentity {
    val deviceId: String
    fun classes(): Set<String>
    fun setClass(id: String, enabled: Boolean)
}

internal class McpDeviceStore(context: Context) : McpDeviceIdentity {
    private val prefs = context.getSharedPreferences("mcp-device-actions", Context.MODE_PRIVATE)
    override val deviceId: String
        get() = prefs.getString("device-id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device-id", it).apply()
        }
    override fun classes(): Set<String> = MCP_DEVICE_CLASSES.filter { prefs.getBoolean("class:$it", false) }.toSet()
    override fun setClass(id: String, enabled: Boolean) { prefs.edit().putBoolean("class:$id", enabled).apply() }
}

internal data class McpDeviceCommand(
    val id: String, val action: String, val params: JSONObject,
) {
    companion object {
        fun parse(o: JSONObject): McpDeviceCommand = McpDeviceCommand(
            o.getString("id"), o.getString("action"), o.optJSONObject("params") ?: JSONObject(),
        )
    }
}

internal data class DeviceActionReceipt(
    val outcome: String, // applied | needs_attention | failed
    val detail: String? = null,
    val result: JSONObject? = null,
)

/** The native capabilities a device action can use — one seam so the
 * dispatch logic is unit-testable without Android or Room. */
internal interface DeviceActionPorts {
    suspend fun reminderRule(ruleId: String): ReminderRuleInfo?
    /** action is the repository's own vocabulary: toggle | snooze | complete. */
    suspend fun reminderChange(ruleId: String, action: String)
    suspend fun reminderReconcile(cookId: String)
    fun notificationsEnabled(): Boolean
    suspend fun candidateCookIds(): List<String>
    suspend fun cookStatus(cookId: String): String?
    suspend fun startTimer(cookId: String, title: String, durationMillis: Long): String
    suspend fun stopTimer(timerId: String): Boolean
    suspend fun activeRecordingCookId(): String?
    suspend fun pausedRecordingCookId(): String?
    suspend fun pauseCollection(cookId: String)
    suspend fun resumeCollection(cookId: String)
    suspend fun setCollectionCadence(cookId: String, intervalSeconds: Long)
    suspend fun dishCookId(dishId: String): String?
    suspend fun assignProbe(probeId: String, cookId: String, dishId: String?)
    suspend fun unassignProbe(probeId: String): Boolean
    fun applyPreferences(values: Map<String, String>)
    /** null = no such playbook on this phone; true/false = guided phases on/off for the meal. */
    suspend fun playbookGuidanceActive(playbookId: String, mealId: String): Boolean?
}

internal data class ReminderRuleInfo(val cookId: String, val enabled: Boolean, val status: String)

internal class DeviceActionDispatcher(private val ports: DeviceActionPorts) {
    suspend fun execute(command: McpDeviceCommand): DeviceActionReceipt {
        val p = command.params
        return try {
            when (command.action) {
                "reminder.activate" -> {
                    val rule = ports.reminderRule(p.getString("ruleId")) ?: return notOnPhone("That reminder")
                    if (!rule.enabled) { ports.reminderChange(p.getString("ruleId"), "toggle"); ports.reminderReconcile(rule.cookId) }
                    if (!ports.notificationsEnabled()) DeviceActionReceipt("needs_attention",
                        "The reminder is on, but notifications are off for PitTech — turn them on so it can alert you.",
                        JSONObject().put("enabled", true))
                    else DeviceActionReceipt("applied", result = JSONObject().put("enabled", true))
                }
                "reminder.cancel" -> {
                    val rule = ports.reminderRule(p.getString("ruleId")) ?: return notOnPhone("That reminder")
                    if (rule.enabled) { ports.reminderChange(p.getString("ruleId"), "toggle"); ports.reminderReconcile(rule.cookId) }
                    DeviceActionReceipt("applied", result = JSONObject().put("enabled", false))
                }
                "reminder.snooze" -> {
                    val rule = ports.reminderRule(p.getString("ruleId")) ?: return notOnPhone("That reminder")
                    val requested = p.optInt("minutes", 10)
                    ports.reminderChange(p.getString("ruleId"), "snooze"); ports.reminderReconcile(rule.cookId)
                    // The app's snooze is its standard 10 minutes; the
                    // receipt says what actually happened, not what was asked.
                    DeviceActionReceipt("applied", result = JSONObject().put("snoozedMinutes", 10).put("requestedMinutes", requested))
                }
                "reminder.acknowledge" -> {
                    val rule = ports.reminderRule(p.getString("ruleId")) ?: return notOnPhone("That reminder")
                    if (rule.status != "completed") { ports.reminderChange(p.getString("ruleId"), "complete"); ports.reminderReconcile(rule.cookId) }
                    DeviceActionReceipt("applied")
                }
                "timer.start" -> {
                    val explicit = if (p.isNull("mealId")) null else p.getString("mealId")
                    val cookId = explicit ?: ports.candidateCookIds().singleOrNull()
                        ?: return DeviceActionReceipt("needs_attention",
                            if (explicit == null) "No meal was named and this phone doesn't have exactly one meal in progress — name the meal, or start the timer in the app."
                            else "That meal isn't on this phone.")
                    if (ports.cookStatus(cookId) == CookStatus.COMPLETED) return DeviceActionReceipt("needs_attention", "That meal is finished — timers can't be added to it.")
                    val timerId = ports.startTimer(cookId, p.getString("label"), p.getInt("durationMinutes") * 60_000L)
                    DeviceActionReceipt("applied", result = JSONObject().put("timerId", timerId))
                }
                "timer.stop" -> {
                    if (!ports.stopTimer(p.getString("timerId"))) return DeviceActionReceipt("needs_attention", "That timer isn't on this phone.")
                    DeviceActionReceipt("applied")
                }
                "guidance.apply_playbook" -> {
                    when (ports.playbookGuidanceActive(p.getString("playbookId"), p.getString("mealId"))) {
                        null -> DeviceActionReceipt("needs_attention", "That playbook isn't on this phone yet — sync, then ask again.")
                        true -> DeviceActionReceipt("applied", result = JSONObject().put("guidance", "active"))
                        else -> DeviceActionReceipt("needs_attention", "Guided phases are off for this meal — open the playbook in PitTech and turn on guided phases to apply it.")
                    }
                }
                "collection.pause" -> {
                    val cookId = ports.activeRecordingCookId() ?: return DeviceActionReceipt("needs_attention", "No temperature collection is running on this phone.")
                    ports.pauseCollection(cookId); DeviceActionReceipt("applied")
                }
                "collection.resume" -> {
                    val cookId = ports.pausedRecordingCookId() ?: return DeviceActionReceipt("needs_attention", "No paused temperature collection to resume on this phone.")
                    ports.resumeCollection(cookId); DeviceActionReceipt("applied")
                }
                "collection.set_cadence" -> {
                    val cookId = ports.activeRecordingCookId() ?: return DeviceActionReceipt("needs_attention", "No temperature collection is running on this phone.")
                    val seconds = p.getInt("intervalSeconds").toLong()
                    ports.setCollectionCadence(cookId, seconds)
                    DeviceActionReceipt("applied", result = JSONObject().put("intervalSeconds", seconds))
                }
                "probe.assign" -> {
                    val dishId = p.getString("dishId")
                    val cookId = ports.dishCookId(dishId) ?: return DeviceActionReceipt("needs_attention", "That dish isn't on this phone.")
                    ports.assignProbe(p.getString("probeId"), cookId, dishId)
                    DeviceActionReceipt("applied")
                }
                "probe.unassign" -> {
                    ports.unassignProbe(p.getString("probeId"))
                    DeviceActionReceipt("applied", result = JSONObject().put("assigned", false))
                }
                "preferences.update" -> {
                    val values = mutableMapOf<String, String>()
                    if (!p.isNull("themeMode")) values["theme-mode"] = p.getString("themeMode")
                    if (!p.isNull("temperatureUnit")) values["temperature-unit"] = p.getString("temperatureUnit")
                    if (!p.isNull("weightUnit")) values["weight-unit"] = p.getString("weightUnit")
                    ports.applyPreferences(values)
                    DeviceActionReceipt("applied", result = JSONObject().put("effective", "next app refresh"))
                }
                else -> DeviceActionReceipt("failed", "This phone's PitTech doesn't know that action.")
            }
        } catch (e: CancellationException) { throw e }
        catch (e: IllegalArgumentException) { DeviceActionReceipt("needs_attention", e.message ?: "That action can't be done as asked.") }
        catch (e: IllegalStateException) { DeviceActionReceipt("needs_attention", e.message ?: "That action can't be done right now.") }
        catch (e: Exception) { DeviceActionReceipt("failed", e.message?.take(200) ?: "The action failed on this phone.") }
    }
    private fun notOnPhone(what: String) = DeviceActionReceipt("needs_attention", "$what isn't on this phone yet — sync, then ask again.")
}

/** The production ports: every call lands on the app's real
 * repositories — the same ones the UI uses. */
internal class NativeDeviceActionPorts(private val context: Context, private val db: PitTechDatabase) : DeviceActionPorts {
    private val reminders = ReminderRepository(db)
    private val timers = ActivityTimerRepository(db)
    private val recordings = CookRecordingRepository(db)
    override suspend fun reminderRule(ruleId: String): ReminderRuleInfo? =
        reminders.rule(ruleId)?.let { ReminderRuleInfo(it.cookId, it.enabled, it.status) }
    override suspend fun reminderChange(ruleId: String, action: String) { reminders.change(context, ruleId, action) }
    override suspend fun reminderReconcile(cookId: String) { reminders.reconcile(context, cookId) }
    override fun notificationsEnabled(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
    override suspend fun candidateCookIds(): List<String> =
        db.cookDao().getAllCooks().filter { it.status == CookStatus.ACTIVE || it.status == CookStatus.PAUSED }.map { it.id }
    override suspend fun cookStatus(cookId: String): String? = db.cookDao().getCook(cookId)?.status
    override suspend fun startTimer(cookId: String, title: String, durationMillis: Long): String =
        timers.start(cookId, null, title, null, durationMillis).id
    override suspend fun stopTimer(timerId: String): Boolean {
        if (db.activityTimerDao().get(timerId) == null) return false
        timers.stop(timerId); return true
    }
    override suspend fun activeRecordingCookId(): String? = db.recordingDao().getActiveRecording()?.cookId
    override suspend fun pausedRecordingCookId(): String? = recordings.pausedRecording()?.cookId
    override suspend fun pauseCollection(cookId: String) { recordings.pause(cookId) }
    override suspend fun resumeCollection(cookId: String) { recordings.resume(cookId) }
    override suspend fun setCollectionCadence(cookId: String, intervalSeconds: Long) {
        val recording = db.recordingDao().getRecording(cookId) ?: error("No temperature collection for this meal.")
        recordings.configureSampling(cookId, GrillSamplingPolicy.stored(recording.samplingMode, intervalSeconds * 1000L))
    }
    override suspend fun dishCookId(dishId: String): String? = db.cookDao().getDish(dishId)?.cookId
    override suspend fun assignProbe(probeId: String, cookId: String, dishId: String?) { recordings.assignProbe(probeId, cookId, dishId) }
    override suspend fun unassignProbe(probeId: String): Boolean = recordings.unassignProbe(probeId)
    override fun applyPreferences(values: Map<String, String>) {
        val edit = context.getSharedPreferences("pittech-preferences", Context.MODE_PRIVATE).edit()
        values.forEach { (key, value) -> edit.putString(key, value) }
        edit.apply()
    }
    override suspend fun playbookGuidanceActive(playbookId: String, mealId: String): Boolean? {
        val header = db.companionDao().header(playbookId) ?: return null
        if (header.kind != "playbook") return null
        return db.experienceDao().lifecycle(mealId)?.timingMode == "explicit_phases"
    }
}

internal data class McpDevicePresentation(
    val registered: Boolean = false,
    val available: Boolean = true,
    val classes: Set<String> = emptySet(),
    val busy: Boolean = false,
    val message: String? = null,
    val lastCheckSummary: String? = null,
)

internal class McpDeviceActionsManager(
    private val account: AccountManager,
    private val scope: CoroutineScope,
    private val store: McpDeviceIdentity,
    private val dispatcher: DeviceActionDispatcher,
    private val api: McpAPI = McpClient(),
    private val deviceName: () -> String = { Build.MODEL ?: "Android phone" },
) {
    private val _presentation = MutableStateFlow(McpDevicePresentation(classes = store.classes()))
    val presentation = _presentation.asStateFlow()
    private val mutex = Mutex()
    private fun publish(next: McpDevicePresentation) { _presentation.value = next }
    private fun perform(operation: suspend () -> Unit) {
        if (_presentation.value.busy) return
        publish(_presentation.value.copy(busy = true, message = null))
        scope.launch {
            mutex.withLock {
                try { operation() }
                catch (e: CancellationException) { publish(_presentation.value.copy(busy = false, message = "Request interrupted. Try again.")); throw e }
                catch (e: AccountFailure) {
                    publish(_presentation.value.copy(busy = false, available = e.status != 404, message = when (e.status) {
                        401 -> "Sign in again to manage device actions."
                        404 -> null
                        else -> "Couldn't reach PitTech. Nothing was changed."
                    }))
                }
                catch (e: Exception) { publish(_presentation.value.copy(busy = false, message = "Something went wrong. Nothing was changed.")) }
            }
        }
    }

    /** Registers (or re-registers with the current opt-ins), then
     * checks for waiting requests. Safe to call whenever the account
     * screen shows: registration is an idempotent upsert. */
    fun refresh() = perform {
        if (!account.presentation.value.signedIn) { publish(McpDevicePresentation(classes = store.classes())); return@perform }
        registerLocked()
        checkLocked()
    }

    fun setClass(id: String, enabled: Boolean) {
        store.setClass(id, enabled)
        publish(_presentation.value.copy(classes = store.classes()))
        if (account.presentation.value.signedIn) perform { registerLocked() }
    }

    fun checkNow() = perform {
        if (!account.presentation.value.signedIn) return@perform
        if (!_presentation.value.registered) registerLocked()
        checkLocked()
    }

    fun unregister() = perform {
        val token = account.access()
        api.call("DELETE", "/v1/me/mcp/devices/${store.deviceId}", null, token)
        publish(_presentation.value.copy(registered = false, busy = false, message = "This phone no longer takes assistant requests. Waiting requests for it were canceled."))
    }

    private suspend fun registerLocked() {
        val token = account.access()
        val body = JSONObject()
            .put("deviceId", store.deviceId)
            .put("name", deviceName())
            .put("platform", "android")
            .put("actionClasses", org.json.JSONArray(store.classes().toList()))
        api.call("POST", "/v1/me/mcp/devices", body, token)
        publish(_presentation.value.copy(registered = true, available = true, classes = store.classes(), busy = false))
    }

    private suspend fun checkLocked() {
        val token = account.access()
        val claimed = api.call("POST", "/v1/me/mcp/devices/${store.deviceId}/claim", JSONObject(), token)
        val commands = claimed.optJSONArray("commands")?.let { array ->
            List(array.length()) { McpDeviceCommand.parse(array.getJSONObject(it)) }
        } ?: emptyList()
        var applied = 0; var attention = 0
        for (command in commands) {
            val receipt = dispatcher.execute(command)
            if (receipt.outcome == "applied") applied++ else attention++
            val body = JSONObject().put("commandId", command.id).put("outcome", receipt.outcome)
            receipt.detail?.let { body.put("detail", it) }
            receipt.result?.let { body.put("result", it) }
            try { api.call("POST", "/v1/me/mcp/devices/${store.deviceId}/receipt", body, token) } catch (ignored: AccountFailure) { }
        }
        publish(_presentation.value.copy(busy = false, lastCheckSummary = when {
            commands.isEmpty() -> "No waiting requests."
            attention == 0 -> "Handled ${commands.size} request${if (commands.size == 1) "" else "s"}."
            else -> "Handled $applied, $attention need${if (attention == 1) "s" else ""} your attention — the assistant can see the details."
        }))
    }
}
