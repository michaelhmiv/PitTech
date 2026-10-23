package com.pittech.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class McpDeviceActionsTest {
    private class Memory(var value: AccountState = AccountState()) : AccountStorage {
        override fun load() = value
        override fun save(value: AccountState) { this.value = AccountState.parse(value.json()) }
    }
    private class FakeIdentity(override val deviceId: String = "11111111-2222-4333-8444-555555555555") : McpDeviceIdentity {
        private val enabled = mutableSetOf<String>()
        override fun classes(): Set<String> = enabled.toSet()
        override fun setClass(id: String, enabled: Boolean) { if (enabled) this.enabled += id else this.enabled -= id }
    }
    private class FakePorts : DeviceActionPorts {
        var rule: ReminderRuleInfo? = ReminderRuleInfo("cook-1", false, "pending")
        val changes = mutableListOf<String>()
        var notifications = true
        var candidates = listOf("cook-1")
        var statuses = mutableMapOf("cook-1" to CookStatus.ACTIVE)
        var startedTimer: Triple<String, String, Long>? = null
        var knownTimer = true
        var recordingCook: String? = "cook-1"
        var pausedCook: String? = null
        var cadence: Pair<String, Long>? = null
        var dishCook = "cook-1"
        var assigned: Triple<String, String, String?>? = null
        var unassigned = false
        var prefs: Map<String, String>? = null
        var guidance: Boolean? = true
        override suspend fun reminderRule(ruleId: String) = rule
        override suspend fun reminderChange(ruleId: String, action: String) { changes += action }
        override suspend fun reminderReconcile(cookId: String) { }
        override fun notificationsEnabled() = notifications
        override suspend fun candidateCookIds() = candidates
        override suspend fun cookStatus(cookId: String): String? = statuses[cookId]
        override suspend fun startTimer(cookId: String, title: String, durationMillis: Long): String { startedTimer = Triple(cookId, title, durationMillis); return "timer-9" }
        override suspend fun stopTimer(timerId: String) = knownTimer
        override suspend fun activeRecordingCookId() = recordingCook
        override suspend fun pausedRecordingCookId() = pausedCook
        override suspend fun pauseCollection(cookId: String) { recordingCook = null; pausedCook = cookId }
        override suspend fun resumeCollection(cookId: String) { pausedCook = null; recordingCook = cookId }
        override suspend fun setCollectionCadence(cookId: String, intervalSeconds: Long) { cadence = cookId to intervalSeconds }
        override suspend fun dishCookId(dishId: String) = dishCook
        override suspend fun assignProbe(probeId: String, cookId: String, dishId: String?) { assigned = Triple(probeId, cookId, dishId) }
        override suspend fun unassignProbe(probeId: String): Boolean { unassigned = true; return true }
        override fun applyPreferences(values: Map<String, String>) { prefs = values }
        override suspend fun playbookGuidanceActive(playbookId: String, mealId: String) = guidance
    }

    private val token = "A".repeat(43)
    private val session = AccountSession(token, token, "2100-01-01T00:00:00.000Z", "2100-02-01T00:00:00.000Z", "12345678-1234-4234-8234-123456789abc")
    private val deviceId = "11111111-2222-4333-8444-555555555555"

    private fun command(id: String, action: String, params: JSONObject = JSONObject()) =
        McpDeviceCommand(id, action, params)

    @Test fun activateTogglesARuleOnAndReportsNotificationsHonestly() = runBlocking {
        val ports = FakePorts()
        val dispatcher = DeviceActionDispatcher(ports)
        val receipt = dispatcher.execute(command("c1", "reminder.activate", JSONObject().put("ruleId", "r1")))
        assertEquals("applied", receipt.outcome)
        assertEquals(listOf("toggle"), ports.changes)
        // Already on: no second toggle.
        ports.rule = ReminderRuleInfo("cook-1", true, "pending")
        assertEquals("applied", dispatcher.execute(command("c2", "reminder.activate", JSONObject().put("ruleId", "r1"))).outcome)
        assertEquals(listOf("toggle"), ports.changes)
        // Notifications off: the enable happened, but the user must act.
        ports.notifications = false
        val attention = dispatcher.execute(command("c3", "reminder.activate", JSONObject().put("ruleId", "r1")))
        assertEquals("needs_attention", attention.outcome)
        assertTrue(attention.detail!!.contains("notifications are off"))
    }

    @Test fun missingRuleAndAmbiguousTimerTargetsNeedAttention() = runBlocking {
        val ports = FakePorts(); ports.rule = null
        val dispatcher = DeviceActionDispatcher(ports)
        assertEquals("needs_attention", dispatcher.execute(command("c1", "reminder.cancel", JSONObject().put("ruleId", "nope"))).outcome)
        ports.candidates = listOf("a", "b")
        assertEquals("needs_attention", dispatcher.execute(command("c2", "timer.start",
            JSONObject().put("label", "Rest").put("durationMinutes", 30))).outcome)
        ports.candidates = listOf("cook-1")
        val started = dispatcher.execute(command("c3", "timer.start", JSONObject().put("label", "Rest").put("durationMinutes", 30)))
        assertEquals("applied", started.outcome)
        assertEquals("timer-9", started.result!!.getString("timerId"))
        assertEquals(30 * 60_000L, ports.startedTimer!!.third)
        ports.statuses["cook-1"] = CookStatus.COMPLETED
        assertEquals("needs_attention", dispatcher.execute(command("c4", "timer.start",
            JSONObject().put("label", "Rest").put("durationMinutes", 30).put("mealId", "cook-1"))).outcome)
    }

    @Test fun snoozeReportsTheAppsRealDuration() = runBlocking {
        val ports = FakePorts()
        val receipt = DeviceActionDispatcher(ports).execute(command("c1", "reminder.snooze",
            JSONObject().put("ruleId", "r1").put("minutes", 45)))
        assertEquals("applied", receipt.outcome)
        assertEquals(10, receipt.result!!.getInt("snoozedMinutes"))
        assertEquals(45, receipt.result!!.getInt("requestedMinutes"))
    }

    @Test fun collectionGuidanceAndPreferencesDispatch() = runBlocking {
        val ports = FakePorts()
        val dispatcher = DeviceActionDispatcher(ports)
        assertEquals("applied", dispatcher.execute(command("c1", "collection.set_cadence", JSONObject().put("intervalSeconds", 120))).outcome)
        assertEquals("cook-1" to 120L, ports.cadence)
        assertEquals("applied", dispatcher.execute(command("c2", "collection.pause")).outcome)
        assertEquals("needs_attention", dispatcher.execute(command("c3", "collection.pause")).outcome)
        assertEquals("applied", dispatcher.execute(command("c4", "collection.resume")).outcome)
        ports.guidance = false
        assertEquals("needs_attention", dispatcher.execute(command("c5", "guidance.apply_playbook",
            JSONObject().put("playbookId", "p1").put("mealId", "cook-1"))).outcome)
        ports.guidance = null
        assertEquals("needs_attention", dispatcher.execute(command("c6", "guidance.apply_playbook",
            JSONObject().put("playbookId", "p1").put("mealId", "cook-1"))).outcome)
        val prefs = dispatcher.execute(command("c7", "preferences.update", JSONObject().put("themeMode", "dark").put("temperatureUnit", "°C")))
        assertEquals("applied", prefs.outcome)
        assertEquals(mapOf("theme-mode" to "dark", "temperature-unit" to "°C"), ports.prefs)
        assertEquals("failed", dispatcher.execute(command("c8", "grill.ignite", JSONObject())).outcome)
    }

    @Test fun managerRegistersClaimsExecutesAndReportsReceipts() = runBlocking {
        val calls = mutableListOf<Triple<String, String, JSONObject?>>()
        val cmdId = "22222222-3333-4444-8555-666666666666"
        val api = McpAPI { method, path, body, access ->
            assertEquals(token, access); calls += Triple(method, path, body)
            when {
                path == "/v1/me/mcp/devices" -> JSONObject().put("device", JSONObject().put("deviceId", deviceId))
                path.endsWith("/claim") -> JSONObject().put("commands", JSONArray(listOf(
                    JSONObject().put("id", cmdId).put("action", "timer.start")
                        .put("params", JSONObject().put("label", "Rest").put("durationMinutes", 15)),
                )))
                path.endsWith("/receipt") -> JSONObject().put("resolved", true)
                else -> { fail("unexpected path $path"); JSONObject() }
            }
        }
        val memory = Memory(AccountState(session = session))
        val account = AccountManager(memory, CoroutineScope(Dispatchers.Unconfined), AccountAPI { _, _, _ -> fail("account API unused"); JSONObject() }, true)
        val identity = FakeIdentity()
        identity.setClass("timer", true)
        val ports = FakePorts()
        val manager = McpDeviceActionsManager(account, CoroutineScope(Dispatchers.Unconfined), identity, DeviceActionDispatcher(ports), api) { "Pixel" }
        manager.refresh()
        repeat(10) { yield() }
        val state = manager.presentation.value
        assertTrue(state.registered)
        assertEquals(setOf("timer"), state.classes)
        val register = calls.first { it.second == "/v1/me/mcp/devices" }
        assertEquals(deviceId, register.third!!.getString("deviceId"))
        assertEquals("android", register.third!!.getString("platform"))
        assertEquals("timer", register.third!!.getJSONArray("actionClasses").getString(0))
        val receipt = calls.first { it.second.endsWith("/receipt") }
        assertEquals(cmdId, receipt.third!!.getString("commandId"))
        assertEquals("applied", receipt.third!!.getString("outcome"))
        assertEquals("timer-9", receipt.third!!.getJSONObject("result").getString("timerId"))
        assertEquals("Handled 1 request.", state.lastCheckSummary)
    }
}
