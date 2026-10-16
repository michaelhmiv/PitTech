package com.pittech.data

import com.pittech.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

internal data class PublicationPhoto(val id: String,val entryId: String,val caption: String,val bytes: ByteArray)
internal data class PreparedPublication(val payload: JSONObject,val bytes: ByteArray,val photos: Map<String,ByteArray>)
/** Explicit selected-draft projection. A whole library/backup is never a publication body. */
internal object PublicationProjection {
    fun id(value: String): String {
        val h=RelayProtocol.hash(("PitTech public v1:"+value).toByteArray()).take(32)
        return h.take(8)+"-"+h.substring(8,12)+"-5"+h.substring(13,16)+"-a"+h.substring(17,20)+"-"+h.substring(20,32)
    }
    private fun text(value: String,max: Int): String { require(value.length<=max) { "This public preview exceeds the sharing limits. Choose fewer dishes, photos or recorded stories." };return value }
    private fun offset(value: Long): Long { require(value in -604800000L..2592000000L) { "This recorded story exceeds the supported timeline. Your draft is unchanged." };return value }
    private fun source(value: PublicRecipeSource?): Any = value?.let { JSONObject().put("itemId",it.itemId).put("revisionId",it.revisionId).put("removed",it.removed) } ?: JSONObject.NULL
    private fun rows(o: JSONObject,key: String)=o.optJSONArray(key)?.let { a->(0 until a.length()).map(a::getJSONObject) }.orEmpty()
    private fun optional(o: JSONObject,key: String): String?=if(o.isNull(key))null else o.getString(key)
    private fun story(entry: SharingEntry,actualMeal: Boolean): Any {
        if(!entry.includeStory)return JSONObject.NULL
        val raw=JSONObject(entry.storyJson);val attempt=raw.getString("attemptId");require(attempt.isNotBlank())
        if(entry.snapshot.publicSource!=null && !actualMeal && raw.optJSONObject("publicStory")!=null) {
            val frozen=raw.getJSONObject("publicStory");require(frozen.getString("attemptId")==attempt);return JSONObject(frozen.toString())
        }
        val observations=rows(raw,"observations");val readings=rows(raw,"readings")
        val anchor=if(raw.isNull("anchorUtcMillis"))null else raw.getLong("anchorUtcMillis")
        val origin=anchor ?: (observations.map { it.getLong("occurredAtUtcMillis") }+readings.map { it.getLong("measuredAtUtcMillis") }).minOrNull() ?: 0L
        val pitTypes=setOf("pit","pit_ambient","grill_temperature","pit_temperature","pit_probe","ambient_temperature")
        val channelIds=linkedMapOf<String,String>();val channels=mutableListOf<JSONObject>();val samples=mutableListOf<JSONObject>()
        for(row in readings) {
            val rawRole=row.getString("measurementType");if(rawRole=="setpoint")continue
            require(rawRole in pitTypes || rawRole in setOf("food","food_probe","internal_temperature"))
            val role=if(rawRole in pitTypes)"pit" else "food";val manual=row.getString("source")=="manual"
            val key=role+":"+(optional(row,"probeId") ?: if(manual)"manual" else row.getString("id"))
            if(key !in channelIds) {
                val channel=id("channel:$attempt:$key");channelIds[key]=channel
                channels+=JSONObject().put("id",channel).put("role",role).put("label",text(if(manual)"Manual readings" else row.getString("probeName"),160))
            }
            require(channels.size<=32);val value=row.getDouble("value");val unit=row.getString("unit");require(unit in setOf("°C","°F"))
            val celsius=if(unit=="°C")value else (value-32)*5/9;require(celsius.isFinite() && celsius in -100.0..1000.0)
            samples+=JSONObject().put("channelId",channelIds[key]).put("role",role).put("offsetMillis",offset(row.getLong("measuredAtUtcMillis")-origin))
                .put("valueCelsius",celsius).put("quality",if(manual)"manual" else if(row.optString("qualityStatus")=="recovered")"recovered" else "measured")
        }
        require(samples.size<=250000 && observations.size<=1000)
        val milestones=observations.map { row->
            val title=row.optString("title");val details=row.optString("details")
            JSONObject().put("id",id("event:"+row.getString("sourceEventId"))).put("offsetMillis",offset(row.getLong("occurredAtUtcMillis")-origin))
                .put("action",text(optional(row,"action") ?: "note",80)).put("text",text(title+if(details.isEmpty())"" else " — $details",2000))
        }
        val off=observations.filter { it.optString("action") in setOf("remove","rest_start","hold_start","dish_done") }.maxOfOrNull { it.getLong("occurredAtUtcMillis") }
        val results=rows(raw,"results");val rating=results.firstOrNull { it.optString("type") in setOf("overall_rating","overall") }?.takeUnless { it.isNull("numericValue") }?.getDouble("numericValue")
        val again=results.firstOrNull { it.optString("type")=="cook_again" }?.takeUnless { it.isNull("numericValue") }?.getDouble("numericValue")
        val notes=results.mapNotNull { optional(it,"textValue") }.joinToString("\n")
        require(rating==null || rating.isFinite() && rating in 1.0..5.0)
        val attemptId=if(entry.snapshot.publicSource!=null && !actualMeal)attempt else id("attempt:$attempt")
        val markers=raw.optJSONArray("connectionGaps") ?: JSONArray();require(markers.length()*channels.size<=1000)
        val gaps=channels.flatMap { channel->(0 until markers.length()).map { i->
            val position=offset(markers.getLong(i)-origin)
            JSONObject().put("channelId",channel.getString("id")).put("fromMillis",position).put("toMillis",position)
        } } // Local outage markers have unknown duration. Never invent an end time.
        return JSONObject().put("attemptId",attemptId).put("foodOnOffsetMillis",anchor?.let { offset(it-origin) } ?: JSONObject.NULL)
            .put("foodOffOffsetMillis",off?.let { offset(it-origin) } ?: JSONObject.NULL).put("channels",JSONArray(channels)).put("measurements",JSONArray(samples))
            .put("milestones",JSONArray(milestones)).put("gaps",JSONArray(gaps)).put("verdict",JSONObject().put("rating",rating ?: JSONObject.NULL)
                .put("cookAgain",again?.let { it==1.0 } ?: JSONObject.NULL).put("notes",text(notes,2000)))
    }
    fun build(document: SharingDocument,updated: Long,photos: List<PublicationPhoto>,kind: String): PreparedPublication {
        SharingDraftCodec.validate(document);val selected=document.entries.filter { it.included }
        require(selected.size in 1..20 && kind in setOf("recipe","collection","attempt") && (kind!="recipe" || selected.size==1))
        require(kind!="attempt" || document.sourceCookId!=null && selected.size==1 && selected.single().includeStory)
        val images=linkedMapOf<String,ByteArray>();var expanded=0L
        val recipes=selected.map { entry->
            val snapshot=entry.snapshot;val setup=JSONObject(RecipeSnapshotCodec.encode(snapshot)).getJSONObject("draft").getJSONArray("dishes").getJSONObject(0)
            val unit=setup.optString("weightUnit","lb");val weight=setup.optString("weight").toDoubleOrNull()
            val multipliers=mapOf("g" to 1.0,"kg" to 1000.0,"lb" to 453.59237,"lbs" to 453.59237,"oz" to 28.349523125)
            require(weight==null || unit in multipliers);val grams=weight?.let { it*(multipliers[unit] ?: 1.0) };require(grams==null || grams.isFinite() && grams>0 && grams<=1_000_000)
            require(snapshot.tags.size<=20 && snapshot.instructions.size<=100 && entry.photoIds.size<=30)
            val ingredients=rows(setup,"ingredients").map { row->JSONObject().put("name",text(row.getString("name"),160))
                .put("amount",text(listOf(row.optString("amount"),row.optString("unit")).filter { it.isNotEmpty() }.joinToString(" "),2000)) }
            val instructions=snapshot.instructions.map { step->JSONObject().put("id",id("instruction:"+step.id)).put("title",text(step.title,160)).put("details",text(step.details,2000))
                .put("method",step.method?.let { text(it,160) } ?: JSONObject.NULL).put("action",step.action?.let { text(it,80) } ?: JSONObject.NULL)
                .put("suggestedOffsetMillis",step.offsetMillis?.let(::offset) ?: JSONObject.NULL) }
            val assets=entry.photoIds.map { photoId->
                val photo=photos.firstOrNull { it.id==photoId && it.entryId==entry.id } ?: error("A selected photo is unavailable. Your draft is unchanged.")
                val assetId=id("photo:$photoId");val path="attachments/$assetId.jpg";val bytes=CookPackPhoto.derivative(photo.bytes);expanded+=bytes.size
                require(expanded<=64*1024*1024);images[assetId]=bytes
                JSONObject().put("id",assetId).put("path",path).put("mimeType","image/jpeg").put("sha256",RelayProtocol.hash(bytes)).put("caption",text(photo.caption,2000))
            }
            JSONObject().put("id",id("recipe:"+entry.id)).put("revisionId",id("revision:${entry.id}:$updated")).put("title",text(snapshot.name,160))
                .put("description",text(snapshot.description,2000)).put("foodType",text(setup.optString("foodType"),160)).put("cut",optional(setup,"cut")?.let { text(it,160) } ?: JSONObject.NULL)
                .put("weightGrams",grams ?: JSONObject.NULL).put("method",JSONObject.NULL).put("ingredients",JSONArray(ingredients)).put("instructions",JSONArray(instructions))
                .put("tags",JSONArray(snapshot.tags.map { text(it,40) })).put("story",story(entry,document.sourceCookId!=null)).put("assets",JSONArray(assets)).put("source",source(snapshot.publicSource))
        }
        val attempts=recipes.mapNotNull { it.optJSONObject("story")?.getString("attemptId") };require(attempts.distinct().size==attempts.size) { "Include each recorded attempt once in a public collection." }
        val first=selected.first().snapshot.publicSource;val common=first?.takeIf { selected.all { e->e.snapshot.publicSource?.let { it.itemId==first.itemId && it.revisionId==first.revisionId }==true } }
        val payload=JSONObject().put("contractVersion",1).put("kind",kind).put("identityMode",document.identityMode).put("title",text(document.title,160))
            .put("recipes",JSONArray(recipes)).put("source",source(common)).put("measuredAttemptId",if(kind=="attempt")recipes[0].getJSONObject("story").getString("attemptId") else JSONObject.NULL)
            .put("heroAssetId",document.heroPhotoId?.let { id("photo:$it") } ?: JSONObject.NULL)
        return pack(payload,images)
    }
    fun pack(payload: JSONObject, photos: Map<String,ByteArray>): PreparedPublication {
        val recipes=payload.getJSONArray("recipes"); require(recipes.length() in 1..20)
        val attachments=linkedMapOf<String,ByteArray>(); val ids=mutableSetOf<String>()
        for(index in 0 until recipes.length()) {
            val assets=recipes.getJSONObject(index).getJSONArray("assets")
            for(a in 0 until assets.length()) {
                val asset=assets.getJSONObject(a); val id=asset.getString("id"); val bytes=photos[id] ?: error("Selected public photo unavailable.")
                require(publicationUuid(id) && ids.add(id) && asset.getString("path")=="attachments/$id.jpg" && asset.getString("mimeType")=="image/jpeg" && bytes.size<=16*1024*1024 && RelayProtocol.hash(bytes)==asset.getString("sha256"))
                attachments["attachments/$id.jpg"]=bytes
            }
        }
        require(ids==photos.keys)
        val json=payload.toString().toByteArray();require(json.size<=8*1024*1024 && json.size.toLong()+photos.values.sumOf { it.size.toLong() }<=64*1024*1024 && attachments.size+1<=1000)
        val bytes=ByteArrayOutputStream().use { output->ZipOutputStream(output).use { zip->
            (mapOf("publication.json" to json)+attachments).toSortedMap().forEach { (path,content)->zip.putNextEntry(ZipEntry(path));zip.write(content);zip.closeEntry() }
        };output.toByteArray() };require(bytes.size<=64*1024*1024)
        return PreparedPublication(payload,bytes,photos)
    }
}
internal suspend fun SharingRepository.preparePublication(id: String,kind: String,editContext: PublicationEditContext? = null): PreparedPublication=withContext(Dispatchers.IO) {
    val row=get(id) ?: error("This sharing draft is unavailable.");val document=SharingDraftCodec.decode(row.payload)
    val selected=document.entries.filter { it.included }.flatMap { entry->entry.photoIds.map { it to entry.id } }.toSet()
    val photos=assets(id).filter { it.action?.let { entry -> (it.id to entry) in selected } == true }.map { photo->
        coroutineContext.ensureActive()
        PublicationPhoto(photo.id,photo.action.orEmpty(),photo.caption,photoBytes(photo.relativePath) ?: error("A selected photo is unavailable."))
    }
    coroutineContext.ensureActive(); val prepared=PublicationProjection.build(document,row.updatedAtUtcMillis,photos,if(kind=="attempt" && editContext?.receipt?.kind=="attempt")"recipe" else kind)
    editContext?.apply(prepared,document,kind) ?: prepared
}

/** Exact preview of a verified device-owned pending request, with bounded ZIP reads. */
internal fun pendingPublication(file: java.io.File): PreparedPublication = java.util.zip.ZipFile(file).use { zip ->
    fun read(path: String, limit: Int): ByteArray {
        val entry=zip.getEntry(path) ?: error("Saved public preview is unavailable."); require(!entry.isDirectory && entry.size in 0..limit.toLong())
        val bytes=zip.getInputStream(entry).use { input ->
            val output=ByteArrayOutputStream(); val buffer=ByteArray(8192)
            while(true) { val count=input.read(buffer,0,minOf(buffer.size,limit+1-output.size())); if(count<0)break; output.write(buffer,0,count); require(output.size()<=limit) }; output.toByteArray()
        }
        val crc=java.util.zip.CRC32().apply { update(bytes) }; require(crc.value==entry.crc); return bytes
    }
    val payload=JSONObject(String(read("publication.json",8*1024*1024),Charsets.UTF_8)); val recipes=payload.getJSONArray("recipes"); require(recipes.length() in 1..20)
    val photos=linkedMapOf<String,ByteArray>()
    for(index in 0 until recipes.length()) {
        val assets=recipes.getJSONObject(index).getJSONArray("assets")
        for(a in 0 until assets.length()) {
            val asset=assets.getJSONObject(a); val id=asset.getString("id"); val suffix=mapOf("image/jpeg" to "jpg","image/png" to "png","image/webp" to "webp")[asset.getString("mimeType")] ?: error("Unsupported public photo.")
            require(publicationUuid(id) && asset.getString("path")=="attachments/$id.$suffix")
            val bytes=read("attachments/$id.$suffix",16*1024*1024); require(RelayProtocol.hash(bytes)==asset.getString("sha256")); photos[id]=bytes
        }
    }
    PreparedPublication(payload,file.readBytes(),photos)
}
