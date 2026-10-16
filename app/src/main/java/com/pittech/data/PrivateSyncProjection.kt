package com.pittech.data

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.floor

/** Pure projection; no account attachment, network, import, recording or rule activation. */
class PrivateSyncProjection(rawContract: String) {
    private val contract = JSONObject(rawContract).also { require(it.getInt("contractVersion") == 1 && it.has("recordTypes")) }
    private fun keys(o: JSONObject) = o.keys().asSequence().toSet()
    private fun strings(a: JSONArray?) = a?.let { (0 until it.length()).map { i -> it.getString(i) } }.orEmpty()
    private fun value(v: Any?, descriptor: JSONObject, depth: Int = 0, parent: JSONObject? = null) {
        require(depth <= 40) { "Unsupported sync data" }
        val n = descriptor.optString("ref").takeIf { it.isNotEmpty() }?.let { contract.getJSONObject("definitions").getJSONObject(it) } ?: descriptor
        require(!n.optBoolean("unsupported")) { "Some saved information cannot be synced safely yet. Your journal is unchanged." }
        n.optString("selectSibling").takeIf { it.isNotEmpty() }?.let { selector ->
            val selected = n.getJSONObject("cases").optJSONObject(parent?.optString(selector) ?: "")
            require(selected != null) { "Some saved information cannot be synced safely yet. Your journal is unchanged." }
            value(v, selected, depth + 1); return
        }
        if ((v == null || v === JSONObject.NULL) && n.optBoolean("nullable")) return
        n.optJSONArray("any")?.let { a -> for (i in 0 until a.length()) if (runCatching { value(v, a.getJSONObject(i), depth + 1) }.isSuccess) return; error("Unsupported sync data") }
        n.optString("select").takeIf { it.isNotEmpty() }?.let { select -> require(v is JSONObject && v.get(select) is String); value(v, n.getJSONObject("cases").getJSONObject(v.getString(select)), depth + 1); return }
        n.optJSONArray("enum")?.let { a -> require((0 until a.length()).any { i -> PrivateSyncMerge.stable(a.get(i)) == PrivateSyncMerge.stable(v ?: JSONObject.NULL) }) }
        when (n.optString("type")) {
            "object" -> { require(v is JSONObject); val fields = n.getJSONObject("fields"); require(keys(v).all { fields.has(it) } && strings(n.optJSONArray("required")).all { v.has(it) }); for (key in keys(v)) value(v.get(key), fields.getJSONObject(key), depth + 1, v) }
            "array" -> {
                require(v is JSONArray && v.length() >= n.optInt("min", 0) && v.length() <= n.optInt("max", 100000))
                for (i in 0 until v.length()) value(v.get(i), n.getJSONObject("items"), depth + 1)
                n.optString("uniqueBy").takeIf { it.isNotEmpty() }?.let { key -> require((0 until v.length()).map { v.getJSONObject(it).getString(key) }.distinct().size == v.length()) }
            }
            "string", "id", "uuid", "json" -> {
                require(v is String && v.length <= n.optInt("max", 32768))
                require(!n.optBoolean("nonBlank") || v.isNotBlank())
                when (n.getString("type")) {
                    "id" -> require(v.isNotEmpty() && !Regex("[^A-Za-z0-9:_-]").containsMatchIn(v))
                    "uuid" -> require(v.length == 36 && Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}").matches(v))
                    "json" -> { StrictPrivateJson(v).check(); val parsed = org.json.JSONTokener(v); val result = parsed.nextValue(); require(parsed.nextClean() == '\u0000'); value(result, contract.getJSONObject("definitions").getJSONObject(n.getString("refJson")), depth + 1) }
                }
            }
            "integer", "number" -> { require(v is Number); val number = v.toDouble(); require(number.isFinite() && number >= n.optDouble("min", -9007199254740991.0) && number <= n.optDouble("max", 9007199254740991.0) && (!n.has("exclusiveMin") || number > n.getDouble("exclusiveMin")) && (n.getString("type") != "integer" || floor(number) == number && abs(number) <= 9007199254740991.0)) }
            "boolean" -> require(v is Boolean)
            else -> error("Unsupported sync data")
        }
    }
    fun project(archive: JSONObject, assets: Map<String, String> = emptyMap()): JSONArray {
        val specs = contract.getJSONObject("recordTypes")
        require(keys(archive).all { it in keys(specs) + strings(contract.optJSONArray("excludedRootFields")) })
        val rows = mutableListOf<JSONObject>()
        for (type in keys(specs).sorted()) {
            val spec = specs.getJSONObject(type); val schema = spec.getJSONObject("payload"); val fields = schema.getJSONObject("fields")
            require(!archive.has(type) || archive.get(type) is JSONArray)
            val input = archive.optJSONArray(type) ?: JSONArray()
            for (i in 0 until input.length()) {
                val source = JSONObject(input.getJSONObject(i).toString())
                if (type == "companionRecords" && source.optString("kind") in strings(contract.optJSONArray("excludedDocumentKinds"))) continue
                val aliases = spec.optJSONObject("aliases") ?: JSONObject()
                for (local in keys(aliases)) if (source.has(local)) {
                    val key = aliases.getString(local)
                    require(!source.has(key) || PrivateSyncMerge.stable(source.get(key)) == PrivateSyncMerge.stable(source.get(local)))
                    source.put(key, source.get(local)); source.remove(local)
                }
                val id = source.get(spec.getString("key")); require(id is String)
                require(keys(source).all { it in keys(fields) + strings(spec.optJSONArray("omit")) })
                for (key in strings(spec.optJSONArray("parseJsonFields"))) if (source.opt(key) is String) {
                    val raw = source.getString(key); StrictPrivateJson(raw).check(); source.put(key, org.json.JSONTokener(raw).nextValue())
                }
                val payload = JSONObject(); for (key in keys(source)) if (fields.has(key)) payload.put(key, source.get(key))
                if (fields.has("assetId")) payload.put("assetId", requireNotNull(assets["$type|$id"]))
                value(payload, schema)
                rows += JSONObject().put("recordType", type).put("recordId", id).put("payload", payload).put("assetIds", JSONArray(if (payload.has("assetId")) listOf(payload.getString("assetId")) else emptyList<String>()))
            }
        }
        val result = JSONArray(rows.sortedBy { it.getString("recordType") + "|" + it.getString("recordId") }); validateRows(result); return result
    }
    fun validateRows(rows: JSONArray) {
        require(rows.length() <= 100000)
        val all = (0 until rows.length()).map { rows.getJSONObject(it) }; val live = all.associate { it.getString("recordType") + "|" + it.getString("recordId") to it.getJSONObject("payload") }; require(live.size == all.size)
        val media = mutableSetOf<String>()
        for (row in all) {
            val type = row.getString("recordType"); val spec = contract.getJSONObject("recordTypes").getJSONObject(type); val payload = row.getJSONObject("payload"); val schema = spec.getJSONObject("payload")
            require(payload.get(spec.getString("key")) == row.getString("recordId")); value(payload, schema)
            val assets = strings(row.getJSONArray("assetIds")); if (schema.getJSONObject("fields").has("assetId")) require(assets == listOf(payload.getString("assetId")) && media.add(row.getString("recordId"))) else require(assets.isEmpty())
            val refs = spec.optJSONArray("references") ?: JSONArray()
            for (i in 0 until refs.length()) {
                val ref = refs.getJSONObject(i); val field = ref.getString("field"); if (payload.isNull(field)) continue
                val parent = requireNotNull(live[ref.getString("target") + "|" + payload.getString(field)])
                val same = ref.optJSONObject("same") ?: JSONObject(); for (key in keys(same)) require(payload.opt(key) == parent.opt(same.getString(key)))
                if (ref.optBoolean("parentOrdinal")) require(parent.getDouble("revisionNumber") < payload.getDouble("revisionNumber"))
            }
            if (type == "logReadingLinks") { val reading = requireNotNull(live["readings|" + payload.getString("readingId")]); val event = requireNotNull(live["events|" + payload.getString("eventId")]); require(reading.get("cookId") == event.get("cookId") && reading.getString("source") == "manual") }
            if (type == "reminderRules") require(JSONObject(payload.getString("triggerConfig")).getString("type") == payload.getString("kind") && payload.getInt("schemaVersion") == 1)
            if (type == "events" && payload.opt("noteMetadataJson") is String) {
                for (id in strings(JSONObject(payload.getString("noteMetadataJson")).optJSONArray("dishIds"))) require(live["dishes|$id"]?.opt("cookId") == payload.opt("cookId"))
            }
            if (type == "companionRecords") {
                val rule = contract.getJSONObject("documentRules").getJSONObject(payload.getString("kind")); val doc = payload.getJSONObject("payload")
                require(!(rule.getString("scope") == "global" && !payload.isNull("cookId")) && !(rule.getString("scope") == "cook" && payload.isNull("cookId")))
                val same = rule.optJSONObject("sameFields") ?: JSONObject()
                for (key in keys(same)) require(PrivateSyncMerge.stable(doc.opt(key)) == PrivateSyncMerge.stable(payload.opt(same.getString(key))))
                val references = rule.optJSONArray("references") ?: JSONArray()
                for (i in 0 until references.length()) {
                    val ref = references.getJSONObject(i); val parent = live[ref.getString("target") + "|" + doc.getString(ref.getString("field"))]
                    if (parent == null && ref.optBoolean("allowMissing")) continue
                    require(parent != null && parent.opt("cookId") == if (ref.getString("sameCook") == "\$record") payload.opt("cookId") else doc.opt(ref.getString("sameCook")))
                }
            }
        }
    }
}

/** org.json accepts JavaScript-like syntax. Validate RFC JSON before decoding
 * serialized fields so Android and the server reject the same malformed input. */
private class StrictPrivateJson(private val text: String) {
    private var at = 0
    private fun whitespace() { while (at < text.length && text[at] in " \t\r\n") at++ }
    private fun take(c: Char): Boolean { whitespace(); return if (at < text.length && text[at] == c) { at++; true } else false }
    fun check() { item(0); whitespace(); require(at == text.length) }
    private fun string() {
        require(take('"'))
        while (at < text.length) {
            val c = text[at++]
            if (c == '"') return
            require(c >= ' ')
            if (c == '\\') {
                require(at < text.length)
                when (text[at++]) {
                    '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                    'u' -> { require(at + 4 <= text.length && text.substring(at, at + 4).all { it in "0123456789abcdefABCDEF" }); at += 4 }
                    else -> error("Invalid JSON escape")
                }
            }
        }
        error("Unterminated JSON string")
    }
    private fun item(depth: Int) {
        require(depth <= 40); whitespace(); require(at < text.length)
        when (text[at]) {
            '{' -> { at++; if (!take('}')) { do { string(); require(take(':')); item(depth + 1) } while (take(',')); require(take('}')) } }
            '[' -> { at++; if (!take(']')) { do { item(depth + 1) } while (take(',')); require(take(']')) } }
            '"' -> string()
            't', 'f', 'n' -> { val literal = when (text[at]) { 't' -> "true"; 'f' -> "false"; else -> "null" }; require(text.startsWith(literal, at)); at += literal.length }
            else -> {
                val number = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?").find(text, at)
                require(number != null && number.range.first == at); at = number.range.last + 1
            }
        }
    }
}
