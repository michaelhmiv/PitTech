package com.pittech.data

import org.json.JSONArray
import org.json.JSONObject

/** Pure three-way merge only; transport/projection and durable relational application remain gated. */
internal object PrivateSyncMerge {
    private const val MAX=9007199254740991L
    private val types=setOf("cooks","dishes","ingredients","events","readings","targets","results","devices","probes","photos","recipes","recipeRevisions","recipeAssets","companionRecords","companionPhotos","mealPresets","dishRecipeSources","lifecycles","targetHistory","logReadingLinks","reminderRules")
    private val immutable=setOf("readings","recipeRevisions")
    private data class Row(val type:String,val id:String,val counter:Long,val client:String,val deleted:Boolean,val payload:JSONObject?,val assets:List<String>){
        val key get()="$type|$id"
        fun json()=JSONObject().put("recordType",type).put("recordId",id).put("version",JSONObject().put("counter",counter).put("clientId",client)).put("deleted",deleted).put("payload",payload?.let{JSONObject(it.toString())}?:JSONObject.NULL).put("assetIds",JSONArray(assets))
        fun content()=PrivateSyncMerge.stable(JSONObject().put("deleted",deleted).put("payload",payload?.let{JSONObject(it.toString())}?:JSONObject.NULL).put("assetIds",JSONArray(assets)))
        fun same(other:Row)=PrivateSyncMerge.stable(json())==PrivateSyncMerge.stable(other.json())
    }
    internal fun stable(value:Any?):String=when(value){
        null,JSONObject.NULL->"null"
        is JSONObject->value.keys().asSequence().toList().sorted().joinToString(",","{","}"){JSONObject.quote(it)+":"+stable(value.get(it))}
        is JSONArray->(0 until value.length()).joinToString(",","[","]"){stable(value.get(it))}
        is Number->java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
        is Boolean->value.toString()
        is String->JSONObject.quote(value)
        else->error("Invalid sync value")
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
                if(!old.deleted && old.type in immutable){val bad=listOf(a,b).filter{!it.deleted && it.content()!=old.content()}.distinctBy{stable(it.json())}.sortedWith(order);if(bad.isNotEmpty()){records+=old;bad.forEach{conflict(old,it,"immutable_identity")};continue}}
            }
            if(a==null || b==null){records+=checkNotNull(a?:b);continue};if(a.same(b)){records+=a;continue};require(order.compare(a,b)!=0)
            val changedA=old==null || !a.same(old);val changedB=old==null || !b.same(old)
            if(!changedA || !changedB){records+=if(changedA)a else b;continue}
            val deletion=a.deleted!=b.deleted;val winner=if(deletion){if(a.deleted)a else b}else if(order.compare(a,b)>0)a else b;val loser=if(winner===a)b else a;records+=winner
            if(a.content()!=b.content())conflict(winner,loser,if(deletion)"delete_edit" else if(winner.type in immutable)"immutable_identity" else "edit_edit")
        }
        val output=JSONArray(records.map{it.json()});rows(output)
        val observed=(base.values+local.values+remote.values).maxOfOrNull{it.counter}?:0L;require(observed<MAX-1)
        return JSONObject().put("records",output).put("conflicts",conflicts).put("blocked",blocked).put("nextCounter",observed+1).also{require(it.toString().toByteArray().size<=64*1024*1024)}
    }
}
