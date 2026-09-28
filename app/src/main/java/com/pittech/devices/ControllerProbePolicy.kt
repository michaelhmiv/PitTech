package com.pittech.devices

internal data class SafeRpcRequest(
    val label: String,
    val method: String,
    val params: Map<String, String> = emptyMap(),
)

internal object ControllerProbePolicy {
    private val mutatingFragments = listOf(
        "set", "save", "put", "remove", "rename", "reboot", "update", "ota",
        "firmware", "write", "erase", "password", "credential", "ignite",
        "primer", "auger", "light.on", "light.off", "sendmcucommand",
    )

    private val sensitiveReadFragments = listOf(
        "wifi.scan", "fs.get", "fs.list", "config.getall", "credential", "password",
        "certificate", "private", "token", "secret",
    )

    private val exactSafeMethods = setOf(
        "RPC.Ping",
        "RPC.List",
        "RPC.ListEx",
        "RPC.Describe",
        "Sys.GetInfo",
        "PB.GetFirmwareVersion",
        "PBL.GetLoaderVersion",
    )

    val safeConfigKeys = listOf(
        "http.enable",
        "http.listen_addr",
        "rpc.http.enable",
        "bt.enable",
        "bt.keep_enabled",
        "bt.config_enable",
    )

    fun classify(method: String): RpcSafetyClass {
        if (method in exactSafeMethods) return RpcSafetyClass.SAFE_AUTOPROBE
        val lower = method.lowercase()
        if (mutatingFragments.any(lower::contains)) return RpcSafetyClass.KNOWN_MUTATING
        if (sensitiveReadFragments.any(lower::contains)) return RpcSafetyClass.SENSITIVE_READ
        return RpcSafetyClass.UNKNOWN
    }

    fun canAutoExecute(method: String, params: Map<String, String> = emptyMap()): Boolean {
        if (method == "Config.Get") {
            val key = params["key"].orEmpty()
            return key in safeConfigKeys
        }
        return classify(method) == RpcSafetyClass.SAFE_AUTOPROBE
    }

    fun plannedReads(methods: Set<String>): List<SafeRpcRequest> = buildList {
        if ("Sys.GetInfo" in methods) add(SafeRpcRequest("System information", "Sys.GetInfo"))
        if ("PB.GetFirmwareVersion" in methods) {
            add(SafeRpcRequest("Pit Boss firmware version", "PB.GetFirmwareVersion"))
        }
        if ("PBL.GetLoaderVersion" in methods) {
            add(SafeRpcRequest("Pit Boss loader version", "PBL.GetLoaderVersion"))
        }
        if ("Config.Get" in methods) {
            safeConfigKeys.forEach { key ->
                add(
                    SafeRpcRequest(
                        label = "Configuration capability: $key",
                        method = "Config.Get",
                        params = mapOf("key" to key),
                    ),
                )
            }
        }
    }

    fun describeCandidates(methods: Set<String>): List<String> {
        val priorityPrefixes = listOf("PB.", "PBL.", "Wifi.", "WiFi.", "RPC.", "Sys.", "Config.")
        return methods
            .sortedWith(
                compareBy<String> { method ->
                    priorityPrefixes.indexOfFirst(method::startsWith).let { if (it < 0) Int.MAX_VALUE else it }
                }.thenBy { it },
            )
            .take(MAX_DESCRIPTIONS)
    }

    const val MAX_DESCRIPTIONS = 32
}
