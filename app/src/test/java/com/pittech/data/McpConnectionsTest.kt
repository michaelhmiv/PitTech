package com.pittech.data

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class McpConnectionsTest {
    private class Memory(var value: AccountState = AccountState()) : AccountStorage {
        override fun load() = value
        override fun save(value: AccountState) { this.value = AccountState.parse(value.json()) }
    }
    private val token = "A".repeat(43)
    private val session = AccountSession(token, token, "2100-01-01T00:00:00.000Z", "2100-02-01T00:00:00.000Z", "12345678-1234-4234-8234-123456789abc")
    private val now = 1_800_000_000_000L
    private fun grant(id: String, nickname: String = "ChatGPT", revoked: Long? = null) = JSONObject()
        .put("id", id).put("nickname", nickname).put("scopes", JSONArray(listOf("library:read", "library:write")))
        .put("createdAt", now - 1000).put("expiresAt", now + 90L * 86_400_000)
        .put("revokedAt", revoked ?: JSONObject.NULL).put("lastUsedAt", JSONObject.NULL).put("clientId", JSONObject.NULL)

    private fun fixture(api: McpAPI): Pair<McpConnectionsManager, CompletableDeferred<Unit>> {
        val memory = Memory(AccountState(session = session))
        val account = AccountManager(memory, CoroutineScope(Dispatchers.Unconfined), AccountAPI { _, _, _ -> fail("account API unused"); JSONObject() }, true)
        val manager = McpConnectionsManager(account, CoroutineScope(Dispatchers.Unconfined), api) { now }
        return manager to CompletableDeferred()
    }
    private suspend fun awaitIdle(manager: McpConnectionsManager) {
        // The manager runs on Unconfined in tests; a couple of yields flush launches.
        repeat(5) { yield(); if (!manager.presentation.value.busy) return }
    }

    @Test fun refreshParsesGrantsAndCreateCapturesTheTokenOnce() = runBlocking {
        val grantId = "123e4567-e89b-42d3-a456-426614174000"
        val calls = mutableListOf<Triple<String, String, JSONObject?>>()
        val api = McpAPI { method, path, body, access ->
            assertEquals(token, access); calls += Triple(method, path, body)
            when {
                method == "GET" -> JSONObject().put("grants", JSONArray(listOf(grant(grantId))))
                method == "POST" -> JSONObject().put("grant", grant(grantId, body!!.getString("nickname"))).put("token", "ptm_" + "x".repeat(43))
                else -> JSONObject().put("revoked", true)
            }
        }
        val (manager, _) = fixture(api)
        manager.refresh(); awaitIdle(manager)
        assertEquals(1, manager.presentation.value.grants.size)
        assertEquals("Connected", manager.presentation.value.grants[0].status(now))
        manager.create("Muse", setOf("library:read"), 90); awaitIdle(manager)
        assertEquals("ptm_" + "x".repeat(43), manager.presentation.value.createdToken)
        assertEquals("Muse", manager.presentation.value.createdNickname)
        manager.dismissCreatedToken()
        assertNull(manager.presentation.value.createdToken)
        manager.revoke(grantId); awaitIdle(manager)
        assertEquals("Disconnected", manager.presentation.value.grants[0].status(now))
        assertTrue(calls.any { it.first == "DELETE" && it.second == "/v1/me/mcp/grants/$grantId" })
        assertTrue(calls.any { it.first == "DELETE" || it.first == "POST" })
    }

    @Test fun server404MeansConnectionsAreNotAvailableYet() = runBlocking {
        val api = McpAPI { _, _, _, _ -> throw AccountFailure(404) }
        val (manager, _) = fixture(api)
        manager.refresh(); awaitIdle(manager)
        assertTrue(manager.presentation.value.loaded)
        assertFalse(manager.presentation.value.available)
        assertNull(manager.presentation.value.message)
    }

    @Test fun approvePostsTheCodeAndUnknownCodeExplainsItself() = runBlocking {
        var approved: String? = null
        val okApi = McpAPI { method, path, body, _ ->
            if (path.endsWith("approve")) { approved = body!!.getString("userCode"); JSONObject().put("approved", true) }
            else JSONObject().put("grants", JSONArray())
        }
        val (manager, _) = fixture(okApi)
        manager.approve(" abcd-2345 "); awaitIdle(manager)
        assertEquals("ABCD-2345", approved)
        assertTrue(manager.presentation.value.message!!.contains("Approved"))
        val (badManager, _) = fixture(McpAPI { _, path, _, _ -> if (path.endsWith("approve")) throw AccountFailure(404) else JSONObject().put("grants", JSONArray()) })
        badManager.approve("XXXX-XXXX"); awaitIdle(badManager)
        assertTrue(badManager.presentation.value.message!!.contains("isn't valid"))
    }
}
