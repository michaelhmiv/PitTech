package com.pittech.data

import com.pittech.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID

/** Private edit metadata; never encoded into a library export or public body. */
internal data class PublicationEditContext(val receipt: PublicationReceipt,val rootSource: String,val memberIds: Map<String,String>,val memberSources: Map<String,String>,val instructionIds: Map<String,String>,val assetIds: Map<String,String>,val measuredAttemptId: String?,val memberMethods: Map<String,String> = emptyMap()) {
    fun validate() {
        receipt.validate(); require(receipt.kind!=null && memberIds.size in 1..20 && memberIds.keys==memberSources.keys && memberIds.values.distinct().size==memberIds.size && instructionIds.size<=2000 && assetIds.size<=600 && assetIds.values.distinct().size==assetIds.size)
        require((memberIds.keys+memberIds.values+instructionIds.keys+instructionIds.values+assetIds.keys+assetIds.values).all(::publicationUuid) && (measuredAttemptId==null || publicationUuid(measuredAttemptId)) && (receipt.kind!="attempt" || measuredAttemptId!=null))
        require(memberMethods.all { (key,value)->key in memberIds && value.length<=160 })
        (memberSources.values+rootSource).forEach { source(JSONTokener(it).nextValue(),receipt.id) }
    }
    fun json(): JSONObject {
        fun ids(map: Map<String,String>)=JSONObject(map)
        val sources=JSONObject();memberSources.forEach { (key,value)->sources.put(key,JSONTokener(value).nextValue()) }
        return JSONObject().put("receipt",receipt.json()).put("rootSource",JSONTokener(rootSource).nextValue()).put("memberMethods",ids(memberMethods)).put("memberIds",ids(memberIds)).put("memberSources",sources).put("instructionIds",ids(instructionIds)).put("assetIds",ids(assetIds)).put("measuredAttemptId",measuredAttemptId ?: JSONObject.NULL)
    }
    fun apply(prepared: PreparedPublication,document: SharingDocument,kind: String): PreparedPublication {
        validate();require(kind in setOf("recipe","collection","attempt")); val selected=document.entries.filter { it.included }; val payload=JSONObject(prepared.payload.toString()); val rows=payload.getJSONArray("recipes"); require(rows.length()==selected.size)
        val photos=linkedMapOf<String,ByteArray>()
        selected.forEachIndexed { index,entry ->
            val recipe=rows.getJSONObject(index); require(recipe.getString("id")==PublicationProjection.id("recipe:"+entry.id))
            memberIds[entry.id]?.let { recipe.put("id",it); recipe.put("source",JSONTokener(memberSources.getValue(entry.id)).nextValue());recipe.put("method",memberMethods[entry.id] ?: JSONObject.NULL) }
            require(recipe.optJSONObject("source")?.getString("itemId")!=receipt.id)
            recipe.rows("instructions").forEach { step->instructionIds[step.getString("id")]?.let { step.put("id",it) } }
            recipe.rows("assets").forEach { asset ->
                val id=asset.getString("id");val target=assetIds[id] ?: id;require(!photos.containsKey(target));photos[target]=checkNotNull(prepared.photos[id]);asset.put("id",target).put("path","attachments/$target.jpg")
            }
        }
        payload.put("source",JSONTokener(rootSource).nextValue()).put("kind",kind)
        if(kind=="attempt") { require(receipt.kind=="attempt" && rows.length()==1 && measuredAttemptId!=null && rows.getJSONObject(0).optJSONObject("story")?.getString("attemptId")==measuredAttemptId);payload.put("measuredAttemptId",measuredAttemptId) }
        else payload.put("measuredAttemptId",JSONObject.NULL)
        if(!payload.isNull("heroAssetId"))payload.put("heroAssetId",assetIds[payload.getString("heroAssetId")] ?: payload.getString("heroAssetId"))
        return PublicationProjection.pack(payload,photos)
    }
    companion object {
        fun source(value: Any?,excluding: String): String {
            if(value==null || value===JSONObject.NULL)return "null"
            val row=value as? JSONObject ?: error("Invalid source credit.");val item=row.getString("itemId");val revision=row.getString("revisionId")
            require(publicationUuid(item) && item!=excluding && publicationUuid(revision) && row.get("removed") is Boolean)
            return JSONObject().put("itemId",item).put("revisionId",revision).put("removed",row.getBoolean("removed")).toString()
        }
        fun parse(o: JSONObject): PublicationEditContext {
            val receipt=PublicationReceipt.parse(o.getJSONObject("receipt"))
            fun ids(key: String)=o.getJSONObject(key).let { map->map.keys().asSequence().associateWith(map::getString) }
            val sources=o.getJSONObject("memberSources").let { map->map.keys().asSequence().associateWith { source(map.get(it),receipt.id) } }
            return PublicationEditContext(receipt,source(o.get("rootSource"),receipt.id),ids("memberIds"),sources,ids("instructionIds"),ids("assetIds"),if(o.isNull("measuredAttemptId"))null else o.getString("measuredAttemptId"),if(o.has("memberMethods"))ids("memberMethods") else emptyMap()).also { it.validate() }
        }
    }
}
internal data class OwnedDraftPhoto(val id: String,val entryId: String,val caption: String,val bytes: ByteArray)
internal data class OwnedPublicationDraft(val id: String,val document: SharingDocument,val photos: List<OwnedDraftPhoto>,val context: PublicationEditContext)
internal object OwnedPublication {
    fun draft(prepared: PreparedPublication,input: PublicationReceipt): OwnedPublicationDraft {
        input.validate();val payload=prepared.payload;val receipt=input.copy(kind=payload.getString("kind"));receipt.validate()
        val recipes=payload.rows("recipes");require(recipes.size in 1..20)
        val entries=mutableListOf<SharingEntry>();val photos=mutableListOf<OwnedDraftPhoto>();val members=linkedMapOf<String,String>();val sources=linkedMapOf<String,String>();val steps=linkedMapOf<String,String>();val assets=linkedMapOf<String,String>();val methods=linkedMapOf<String,String>();var hero: String?=null
        for(recipe in recipes) {
            val publicId=recipe.getString("id");require(publicationUuid(publicId));val entryId=UUID.randomUUID().toString();val story=recipe.optJSONObject("story");val origin=946684800000L
            val anchor=story?.longOrNull("foodOnOffsetMillis")?.let { origin+it }
            val observations=story?.rows("milestones").orEmpty().map { row ->
                val at=origin+row.getLong("offsetMillis"); JSONObject().put("sourceEventId",UUID.randomUUID().toString()).put("occurredAtUtcMillis",at).put("timeZoneId","UTC").put("title",row.optString("text").ifBlank { row.stringOrNull("action") ?: "Recorded action" }).put("details",row.getString("text")).put("action",row.opt("action") ?: JSONObject.NULL).put("offsetMillis",anchor?.let { at-it } ?: JSONObject.NULL).put("recordedTemperatures",JSONArray())
            }.toMutableList()
            for((key,action,label) in listOf(Triple("foodOnOffsetMillis","food_on","Food on"),Triple("foodOffOffsetMillis","remove","Food off"))) {
                story?.longOrNull(key)?.let { offset->if(observations.none { it.optString("action")==action }) { val at=origin+offset;observations+=JSONObject().put("sourceEventId",UUID.randomUUID().toString()).put("occurredAtUtcMillis",at).put("timeZoneId","UTC").put("title",label).put("details","").put("action",action).put("offsetMillis",anchor?.let { at-it } ?: JSONObject.NULL).put("recordedTemperatures",JSONArray()) } }
            }
            val channels=story?.rows("channels").orEmpty().associateBy { it.getString("id") };val probes=channels.keys.associateWith { UUID.randomUUID().toString() }
            val readings=story?.rows("measurements").orEmpty().map { row ->
                val id=row.getString("channelId"); val channel=checkNotNull(channels[id])
                JSONObject().put("id",UUID.randomUUID().toString()).put("probeId",probes.getValue(id)).put("probeName",channel.getString("label")).put("measurementType",row.getString("role")).put("value",row.getDouble("valueCelsius")).put("unit","°C").put("measuredAtUtcMillis",origin+row.getLong("offsetMillis")).put("timeZoneId","UTC").put("source",if(row.getString("quality")=="manual")"manual" else "recorded").put("qualityStatus",if(row.getString("quality")=="recovered")"recovered" else "valid").put("samplingIntervalMillis",0)
            }
            val instructions=recipe.rows("instructions").map { row ->
                val id=UUID.randomUUID().toString();steps[PublicationProjection.id("instruction:$id")]=row.getString("id")
                JSONObject().put("id",id).put("title",row.getString("title")).put("details",row.getString("details")).put("method",row.opt("method") ?: JSONObject.NULL).put("action",row.opt("action") ?: JSONObject.NULL).put("offsetMillis",row.opt("suggestedOffsetMillis") ?: JSONObject.NULL).put("durationMillis",JSONObject.NULL).put("sourceEventId",JSONObject.NULL)
            }
            val ingredients=recipe.rows("ingredients").map { row->JSONObject().put("name",row.getString("name")).put("stage","main_ingredient").put("brand","").put("amount","").put("unit",row.getString("amount")) }
            val name=recipe.getString("title");val verdict=story?.getJSONObject("verdict") ?: JSONObject()
            val setup=JSONObject().put("title",name).put("dishes",JSONArray().put(JSONObject().put("name",name).put("foodType",recipe.getString("foodType")).put("cut",recipe.stringOrNull("cut") ?: "").put("weight",recipe.doubleOrNull("weightGrams")?.let { (it/1000).toString() } ?: "").put("weightUnit","kg").put("prep",recipe.stringOrNull("method") ?: "").put("ingredients",JSONArray(ingredients))))
            val snapshot=RecipeSnapshotCodec.decode(JSONObject().put("version",1).put("name",name).put("description",recipe.getString("description")).put("tags",recipe.getJSONArray("tags")).put("draft",setup).put("instructions",JSONArray(instructions)).put("observations",JSONArray(observations)).put("targets",JSONArray()).put("resultNotes",verdict.stringOrNull("notes") ?: "").put("rating",verdict.opt("rating") ?: JSONObject.NULL).put("cookAgain",verdict.opt("cookAgain") ?: JSONObject.NULL).put("smokingAnchorUtcMillis",anchor ?: JSONObject.NULL).put("preferredPitTemperatureF",JSONObject.NULL).put("servings","").put("publicSource",JSONObject().put("itemId",receipt.id).put("revisionId",receipt.revisionId).put("displayName","a PitTech cook").put("removed",false).put("relativeTiming",true)).toString())
            val gaps=story?.rows("gaps").orEmpty().flatMap { listOf(origin+it.getLong("fromMillis"),origin+it.getLong("toMillis")) }
            val frozen=JSONObject().put("version",1).put("attemptId",story?.opt("attemptId") ?: JSONObject.NULL).put("anchorUtcMillis",anchor ?: JSONObject.NULL).put("observations",JSONArray(observations)).put("readings",JSONArray(readings)).put("connectionGaps",JSONArray(gaps)).put("results",JSONArray()).put("publicStory",story ?: JSONObject.NULL)
            val photoIds=recipe.rows("assets").map { asset ->
                val original=asset.getString("id");val id=UUID.randomUUID().toString();photos+=OwnedDraftPhoto(id,entryId,asset.getString("caption"),CookPackPhoto.derivative(checkNotNull(prepared.photos[original])));assets[PublicationProjection.id("photo:$id")]=original
                if(payload.stringOrNull("heroAssetId")==original)hero=id;id
            }
            entries+=SharingEntry(id=entryId,snapshot=snapshot,storyJson=frozen.toString(),includeStory=story!=null,photoIds=photoIds)
            recipe.stringOrNull("method")?.let { methods[entryId]=it };members[entryId]=publicId;sources[entryId]=PublicationEditContext.source(recipe.opt("source"),receipt.id)
        }
        val document=SharingDocument(payload.getString("title"),entries,identityMode=payload.getString("identityMode"),heroPhotoId=hero);SharingDraftCodec.validate(document)
        val context=PublicationEditContext(receipt,PublicationEditContext.source(payload.opt("source"),receipt.id),members,sources,steps,assets,payload.stringOrNull("measuredAttemptId"),methods);context.validate()
        return OwnedPublicationDraft(UUID.randomUUID().toString(),document,photos,context)
    }
}
private fun JSONObject.rows(key: String)=getJSONArray(key).let { a->(0 until a.length()).map(a::getJSONObject) }
private fun JSONObject.stringOrNull(key: String)=if(isNull(key))null else getString(key)
private fun JSONObject.longOrNull(key: String)=if(isNull(key))null else getLong(key)
private fun JSONObject.doubleOrNull(key: String)=if(isNull(key))null else getDouble(key)
