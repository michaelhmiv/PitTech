package com.pittech.data

import org.json.JSONArray
import org.json.JSONObject

/** Pure three-way merge only; transport/projection and durable relational application remain gated. */
internal object PrivateSyncMerge {
    private const val MAX=9007199254740991L
    private val types=setOf("cooks","dishes","ingredients","events","readings","targets","results","devices","probes","photos","recipes","recipeRevisions","recipeAssets","companionRecords","companionPhotos","mealPresets","dishRecipeSources","lifecycles","targetHistory","logReadingLinks","reminderRules")
    private val immutable=setOf("readings","recipeRevisions")
    internal fun manualReadingEdit(a:JSONObject,b:JSONObject):Boolean {
        val x=a.optJSONObject("payload")?:return false;val y=b.optJSONObject("payload")?:return false
        return a.optString("recordType")=="readings" && b.optString("recordType")=="readings" && a.optString("recordId")==b.optString("recordId") &&
            !a.optBoolean("deleted") && !b.optBoolean("deleted") && x.optString("source")=="manual" && y.optString("source")=="manual" &&
            listOf("id","cookId","sourceDeviceId","timestampBasis","samplingIntervalMillis").all{stable(x.opt(it))==stable(y.opt(it))}
    }
    private data class Row(val type:String,val id:String,val counter:Long,val client:String,val deleted:Boolean,val payload:JSONObject?,val assets:List<String>){
        val key get()="$type|$id"
        fun json()=JSONObject().put("recordType",type).put("recordId",id).put("version",JSONObject().put("counter",counter).put("clientId",client)).put("deleted",deleted).put("payload",payload?.let{JSONObject(it.toString())}?:JSONObject.NULL).put("assetIds",JSONArray(assets))
        fun content()=PrivateSyncMerge.stable(JSONObject().put("deleted",deleted).put("payload",payload?.let{JSONObject(it.toString())}?:JSONObject.NULL).put("assetIds",JSONArray(assets)))
        fun same(other:Row)=PrivateSyncMerge.stable(json())==PrivateSyncMerge.stable(other.json())
    }
    /**
     * The canonical serialization of a sync value: recursively sorted object
     * keys with JSON.stringify-compatible number and string formatting. This
     * must stay byte-identical to the server's stable() (api-service
     * pack.ts): the server re-serializes every parsed envelope with it, the
     * snapshot store persists those bytes, and the commit receipt's sha256
     * covers them. The commit body is serialized with this function so the
     * receipt covers exactly the bytes sent (issue #262). Shared vectors:
     * contracts/fixtures/sync-canonical-v1.json.
     */
    internal fun stable(value:Any?):String=when(value){
        null,JSONObject.NULL->"null"
        is JSONObject->value.keys().asSequence().toList().sorted().joinToString(",","{","}"){quote(it)+":"+stable(value.get(it))}
        is JSONArray->(0 until value.length()).joinToString(",","[","]"){stable(value.get(it))}
        is Number->number(value)
        is Boolean->value.toString()
        is String->quote(value)
        else->error("Invalid sync value")
    }
    /** JSON.stringify number formatting: integral doubles print without a
     * fraction (225.0 -> "225"); exponential notation appears only outside
     * [1e-6, 1e21), with a JS-style exponent (explicit +, no leading zeros). */
    private fun number(value:Number):String{
        if(value is Double || value is Float){
            val d=value.toDouble();require(d.isFinite()){"Invalid sync number"}
            if(d==0.0)return "0"
            val magnitude=kotlin.math.abs(d)
            if(magnitude<1e-6 || magnitude>=1e21){
                val raw=d.toString();val split=raw.indexOf('E')
                val mantissa=java.math.BigDecimal(raw.substring(0,split)).stripTrailingZeros().toPlainString()
                val exponent=raw.substring(split+1).toInt()
                return mantissa+"e"+(if(exponent>=0)"+" else "")+exponent
            }
            return java.math.BigDecimal(d.toString()).stripTrailingZeros().toPlainString()
        }
        return value.toString()
    }
    /** JSON.stringify string quoting. JSONObject.quote is NOT equivalent: it
     * additionally escapes ranges such as U+2019 and U+20AC that
     * JSON.stringify (and therefore the server's canonical bytes) emit raw. */
    private fun quote(value:String):String=buildString(value.length+2){
        append('"')
        for(c in value)when(c){
            '"'->append("\\\"")
            '\\'->append("\\\\")
            '\b'->append("\\b")
            '\u000C'->append("\\f")
            '\n'->append("\\n")
            '\r'->append("\\r")
            '\t'->append("\\t")
            else->if(c<' ')append("\\u"+c.code.toString(16).padStart(4,'0'))else append(c)
        }
        append('"')
    }
    private fun bounded(value:Any?,depth:Int=0){
        require(depth<=24)
        when(value){
            is Number->require(value.toDouble().isFinite())
            is String->require(value.length<=16*1024*1024)
            is JSONArray->{require(value.length()<=100000);for(i in 0 until value.length())bounded(value.get(i),depth+1)}
            is JSONObject->value.keys().forEach{require(it !in setOf("__proto__","constructor","prototype"));bounded(value.get(it),depth+1)}
        }
    }
    private fun rows(input:JSONArray):List<Row>{
        require(input.length()<=100000 && input.toString().toByteArray().size<=32*1024*1024)
        val seen=mutableSetOf<String>()
        return (0 until input.length()).map{index->
            val r=input.getJSONObject(index);require(r.keys().asSequence().toSet()==setOf("recordType","recordId","version","deleted","payload","assetIds"))
            val v=r.getJSONObject("version");require(v.keys().asSequence().toSet()==setOf("counter","clientId"))
            val n=v.get("counter");require(n is Number && n.toDouble().isFinite() && n.toDouble()==n.toLong().toDouble());val counter=n.toLong();val client=v.getString("clientId");require(v.get("clientId") is String)
            require(counter>=1 && counter<MAX && publicationUuid(client) && client==client.lowercase())
            val type=r.getString("recordType");val id=r.getString("recordId");require(r.get("recordType") is String && r.get("recordId") is String);require(type in types && id.matches(Regex("[A-Za-z0-9:_-]{1,200}")) && r.get("deleted") is Boolean)
            val deleted=r.getBoolean("deleted");val a=r.getJSONArray("assetIds");val assets=(0 until a.length()).map{require(a.get(it) is String);a.getString(it)};require(assets.size<=100 && assets.distinct().size==assets.size && assets.all{publicationUuid(it) && it==it.lowercase()})
            val payload=if(r.isNull("payload"))null else r.getJSONObject("payload");require(if(deleted)payload==null && assets.isEmpty() else payload!=null)
            bounded(payload);require(stable(payload).toByteArray().size<=16*1024*1024)
            Row(type,id,counter,client,deleted,payload?.let{JSONObject(it.toString())},assets).also{require(seen.add(it.key))}
        }
    }
    /** Structural envelope checks shared by transport validation. Pure; no storage or network. */
    internal fun validateRecords(records:JSONArray){rows(records)}
    internal fun validateConflicts(conflicts:JSONArray){
        require(conflicts.length()<=10000)
        val ids=mutableSetOf<String>()
        for(i in 0 until conflicts.length()){
            val c=conflicts.getJSONObject(i);require(c.keys().asSequence().toSet()==setOf("id","winner","loser","reason"))
            val id=c.getString("id");require(c.get("id") is String && id.matches(Regex("[a-f0-9]{64}")) && ids.add(id))
            val reason=c.getString("reason");require(c.get("reason") is String && reason in setOf("edit_edit","delete_edit","immutable_identity"))
            val winner=c.getJSONObject("winner");val loser=c.getJSONObject("loser")
            rows(JSONArray().put(winner));rows(JSONArray().put(loser))
            fun field(r:JSONObject,name:String)=r.getString(name)
            require(field(winner,"recordType")==field(loser,"recordType") && field(winner,"recordId")==field(loser,"recordId"))
            val computed=RelayProtocol.hash(listOf(field(winner,"recordType"),field(winner,"recordId"),reason,winner.getJSONObject("version").getLong("counter").toString(),winner.getJSONObject("version").getString("clientId"),loser.getJSONObject("version").getLong("counter").toString(),loser.getJSONObject("version").getString("clientId")).joinToString("|").toByteArray())
            require(id==computed)
            val deleted=winner.getBoolean("deleted") to loser.getBoolean("deleted")
            val contentW=stable(JSONObject().put("deleted",winner.get("deleted")).put("payload",winner.get("payload")).put("assetIds",winner.get("assetIds")))
            val contentL=stable(JSONObject().put("deleted",loser.get("deleted")).put("payload",loser.get("payload")).put("assetIds",loser.get("assetIds")))
            require(contentW!=contentL)
            val orderW=compareValuesBy(winner,loser,{it.getJSONObject("version").getLong("counter")},{it.getJSONObject("version").getString("clientId")})
            require(orderW!=0)
            if(reason=="delete_edit")require(deleted.first && !deleted.second)
            else{require(!deleted.first && !deleted.second);if(reason=="immutable_identity")require(field(winner,"recordType") in immutable)else require((field(winner,"recordType") !in immutable || manualReadingEdit(winner,loser)) && orderW>0)}
        }
    }
    private val order=compareBy<Row>{it.counter}.thenBy{it.client}
    fun merge(baseInput:JSONArray,localInput:JSONArray,remoteInput:JSONArray):JSONObject{
        val base=rows(baseInput).associateBy{it.key};val local=rows(localInput).associateBy{it.key};val remote=rows(remoteInput).associateBy{it.key}
        val records=mutableListOf<Row>();val conflicts=JSONArray();var blocked=false
        fun conflict(winner:Row,loser:Row,reason:String){
            val identity=listOf(winner.type,winner.id,reason,winner.counter.toString(),winner.client,loser.counter.toString(),loser.client).joinToString("|")
            conflicts.put(JSONObject().put("id",RelayProtocol.hash(identity.toByteArray())).put("winner",winner.json()).put("loser",loser.json()).put("reason",reason));if(reason=="immutable_identity")blocked=true
        }
        for(key in (base.keys+local.keys+remote.keys).sorted()){
            val old=base[key];val a=local[key];val b=remote[key]
            if(old!=null){require(a!=null && b!=null);for(r in listOf(a,b))if(!r.same(old)){require(r.counter>old.counter && (!old.deleted || r.deleted))}
                require(order.compare(a,b)!=0 || a.same(b))
                if(!old.deleted && old.type in immutable){val bad=listOf(a,b).filter{!it.deleted && it.content()!=old.content() && !manualReadingEdit(old.json(),it.json())}.distinctBy{stable(it.json())}.sortedWith(order);if(bad.isNotEmpty()){records+=old;bad.forEach{conflict(old,it,"immutable_identity")};continue}}
            }
            if(a==null || b==null){records+=checkNotNull(a?:b);continue};if(a.same(b)){records+=a;continue};require(order.compare(a,b)!=0)
            val changedA=old==null || !a.same(old);val changedB=old==null || !b.same(old)
            if(!changedA || !changedB){records+=if(changedA)a else b;continue}
            val deletion=a.deleted!=b.deleted;val winner=if(deletion){if(a.deleted)a else b}else if(order.compare(a,b)>0)a else b;val loser=if(winner===a)b else a;records+=winner
            if(a.content()!=b.content())conflict(winner,loser,if(deletion)"delete_edit" else if(winner.type in immutable && !manualReadingEdit(a.json(),b.json()))"immutable_identity" else "edit_edit")
        }
        val output=JSONArray(records.map{it.json()});rows(output)
        val observed=(base.values+local.values+remote.values).maxOfOrNull{it.counter}?:0L;require(observed<MAX-1)
        return JSONObject().put("records",output).put("conflicts",conflicts).put("blocked",blocked).put("nextCounter",observed+1).also{require(it.toString().toByteArray().size<=64*1024*1024)}
    }
}
