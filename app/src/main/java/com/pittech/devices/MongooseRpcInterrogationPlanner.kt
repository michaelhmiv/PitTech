package com.pittech.devices

import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque

/** Pure request planner shared by the Android BLE probe and simulated-controller tests. */
internal class MongooseRpcInterrogationPlanner {
    private val queue = ArrayDeque<SafeRpcRequest>()
    private val inventory = linkedSetOf<String>()
    private val descriptions = linkedMapOf<String, String>()
    private var started = false

    val rpcMethods: List<String> get() = inventory.sorted()
    val rpcDescriptions: Map<String, String> get() = descriptions.toSortedMap()

    fun start() {
        if (started) return
        started = true
        ControllerProbePolicy.initialRequests().forEach(queue::addLast)
    }

    fun next(): SafeRpcRequest? = if (queue.isEmpty()) null else queue.removeFirst()

    fun accept(request: SafeRpcRequest, response: MongooseRpcResponse) {
        when (response.kind) {
            MongooseRpcResponseKind.RPC_ERROR -> {
                if (request.method == "RPC.List" && response.isMethodUnavailable()) {
                    queue.addFirst(SafeRpcRequest("RPC method inventory (ListEx)", "RPC.ListEx"))
                }
            }
            MongooseRpcResponseKind.SUCCESS -> when (request.method) {
                "RPC.List", "RPC.ListEx" -> {
                    val methods = extractMethods(response.result)
                    if (methods.isNotEmpty() && inventory.isEmpty()) {
                        inventory.addAll(methods)
                        val methodSet = methods.toSet()
                        ControllerProbePolicy.plannedReads(methodSet).forEach(queue::addLast)
                        if ("RPC.Describe" in methods) {
                            ControllerProbePolicy.describeCandidates(methodSet).forEach { method ->
                                queue.addLast(
                                    SafeRpcRequest(
                                        label = "Describe $method",
                                        method = "RPC.Describe",
                                        params = mapOf("name" to method),
                                    ),
                                )
                            }
                        }
                    }
                }
                "RPC.Describe" -> {
                    request.params["name"]?.takeIf { it.isNotBlank() }?.let { method ->
                        descriptions[method] = response.resultJson.take(MAX_DESCRIPTION_CHARS)
                    }
                }
            }
            MongooseRpcResponseKind.MALFORMED, MongooseRpcResponseKind.STALE_RESPONSE -> Unit
        }
    }

    private fun extractMethods(result: Any?): List<String> {
        val methods = linkedSetOf<String>()
        fun visit(value: Any?) {
            when (value) {
                is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index))
                is JSONObject -> {
                    value.optString("name").takeIf { it.contains('.') }?.let(methods::add)
                    value.optJSONArray("methods")?.let(::visit)
                    value.optJSONArray("result")?.let(::visit)
                }
                is String -> if (value.contains('.')) methods += value
            }
        }
        visit(result)
        return methods.sorted()
    }

    private companion object {
        const val MAX_DESCRIPTION_CHARS = 1_500
    }
}
