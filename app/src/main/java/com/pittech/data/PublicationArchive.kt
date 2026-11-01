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
    /** The payload session object for a cook publication, rebuilt from the
     * draft's captured session facts with times rebased to session-relative
     * offsets (the payload stays relative-timing only, like every story). */
    private fun sessionObject(sessionRaw: JSONObject): JSONObject {
        val started=sessionRaw.getLong("startedAtUtcMillis")
        val ended=if(sessionRaw.isNull("endedAtUtcMillis"))null else sessionRaw.getLong("endedAtUtcMillis")
        fun str(key: String): Any=if(sessionRaw.isNull(key))JSONObject.NULL else sessionRaw.getString(key)
        val setpoint=if(sessionRaw.isNull("setpointValue") || sessionRaw.isNull("setpointUnit"))JSONObject.NULL else JSONObject().put("value",sessionRaw.getDouble("setpointValue")).put("unit",sessionRaw.getString("setpointUnit"))
        return JSONObject().put("smokerName",str("smokerName")).put("fuelType",str("fuelType")).put("woodOrPelletBlend",str("woodOrPelletBlend"))
            .put("setpoint",setpoint)
            .put("outdoorTemperatureValue",if(sessionRaw.isNull("outdoorTemperatureValue"))JSONObject.NULL else sessionRaw.getDouble("outdoorTemperatureValue"))
            .put("outdoorTemperatureUnit",str("outdoorTemperatureUnit"))
            .put("weatherNotes",sessionRaw.optString("weatherNotes")).put("windNotes",sessionRaw.optString("windNotes")).put("notes",sessionRaw.optString("notes"))
            .put("serveAtOffsetMillis",(if(sessionRaw.isNull("serveAtUtcMillis"))null else sessionRaw.getLong("serveAtUtcMillis"))?.let { offset(it-started) } ?: JSONObject.NULL)
            .put("durationMillis",ended?.let { it-started }?.takeIf { it>=0 }?.let { offset(it) } ?: JSONObject.NULL)
    }
    /** Publishable Cooks (issue #250): place an actual dish story on the
     * session clock and lift its recorded cook inputs into the story, so the
     * publication renders one shared timeline and per-dish inputs without the
     * private results rows. Frozen stories (owner updates of an existing cook
     * publication) already carry both from their original publication and are
     * left untouched — recorded facts stay frozen. */
    private fun placeOnSession(entry: SharingEntry,storyJson: JSONObject,sessionRaw: JSONObject) {
        val raw=JSONObject(entry.storyJson)
        if(raw.optJSONObject("publicStory")!=null)return
        val started=sessionRaw.getLong("startedAtUtcMillis")
        val ended=if(sessionRaw.isNull("endedAtUtcMillis"))null else sessionRaw.getLong("endedAtUtcMillis")
        val anchor=if(raw.isNull("anchorUtcMillis"))null else raw.getLong("anchorUtcMillis")
        val origin=anchor ?: (rows(raw,"observations").map { it.getLong("occurredAtUtcMillis") }+rows(raw,"readings").map { it.getLong("measuredAtUtcMillis") }).minOrNull()
        if(origin!=null) {
            require(origin>=started && (ended==null || origin<=ended)) { "A dish's recorded story doesn't belong to this cook session, so it can't join the cook publication. Exclude it, or publish the meal as a collection instead." }
            storyJson.put("anchorOffsetMillis",offset(origin-started))
        } else storyJson.put("anchorOffsetMillis",JSONObject.NULL)
        storyJson.put("inputs",cookInputs(raw) ?: JSONObject.NULL)
    }
    private fun cookInputs(raw: JSONObject): JSONObject? {
        // The story's results are the dish's own rows; the derivation mirrors
        // CookInputVocabulary.fromResults (a spritz action on the timeline is
        // a recorded YES even when the prompt went unanswered).
        val results=rows(raw,"results")
        fun result(type: String)=results.firstOrNull { it.optString("type")==type }?.let { if(it.isNull("textValue"))null else it.getString("textValue") }?.takeIf { it.isNotBlank() }
        val wrap=result(CookInputVocabulary.TYPE_WRAP_MATERIAL)?.let { WrapMaterial.fromArchive(it)?.archiveValue }
        val rub=result(CookInputVocabulary.TYPE_RUB)
        var spritz=when(result(CookInputVocabulary.TYPE_SPRITZ)) { "yes"->true; "no"->false; else->null }
        if(spritz==null && rows(raw,"observations").any { it.optString("action")=="spritz" })spritz=true
        val medium=result(CookInputVocabulary.TYPE_SPRITZ_MEDIUM)
        if(wrap==null && rub==null && spritz==null && medium==null)return null
        return JSONObject().put("wrapMaterial",wrap ?: JSONObject.NULL).put("rub",rub ?: JSONObject.NULL)
            .put("spritz",spritz ?: JSONObject.NULL).put("spritzMedium",medium ?: JSONObject.NULL)
    }
    fun build(document: SharingDocument,updated: Long,photos: List<PublicationPhoto>,kind: String): PreparedPublication {
        SharingDraftCodec.validate(document);val selected=document.entries.filter { it.included }
        require(selected.size in 1..20 && kind in setOf("recipe","collection","attempt","cook") && (kind!="recipe" || selected.size==1))
        require(kind!="attempt" || document.sourceCookId!=null && selected.size==1 && selected.single().includeStory)
        // A cook publication IS the recorded session: it needs the captured
        // session facts and every selected dish's story. A dish without its
        // story is excluded from the cook, never published story-less.
        require(kind!="cook" || document.sessionJson!=null && selected.all { it.includeStory }) { "A cook publication needs the recorded session and every dish's story. Exclude a dish to leave it out of the cook." }
        val sessionRaw=document.sessionJson?.takeIf { kind=="cook" }?.let(::JSONObject)
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
            val storyValue=story(entry,document.sourceCookId!=null)
            if(sessionRaw!=null && storyValue is JSONObject)placeOnSession(entry,storyValue,sessionRaw)
            JSONObject().put("id",id("recipe:"+entry.id)).put("revisionId",id("revision:${entry.id}:$updated")).put("title",text(snapshot.name,160))
                .put("description",text(snapshot.description,2000)).put("foodType",text(setup.optString("foodType"),160)).put("cut",optional(setup,"cut")?.let { text(it,160) } ?: JSONObject.NULL)
                .put("weightGrams",grams ?: JSONObject.NULL).put("method",JSONObject.NULL).put("ingredients",JSONArray(ingredients)).put("instructions",JSONArray(instructions))
                .put("tags",JSONArray(snapshot.tags.map { text(it,40) })).put("story",storyValue).put("assets",JSONArray(assets)).put("source",source(snapshot.publicSource))
        }
        val attempts=recipes.mapNotNull { it.optJSONObject("story")?.getString("attemptId") };require(attempts.distinct().size==attempts.size) { "Include each recorded attempt once in a public collection." }
        val first=selected.first().snapshot.publicSource;val common=first?.takeIf { selected.all { e->e.snapshot.publicSource?.let { it.itemId==first.itemId && it.revisionId==first.revisionId }==true } }
        val payload=JSONObject().put("contractVersion",1).put("kind",kind).put("identityMode",document.identityMode).put("category",document.categoryId).put("title",text(document.title,160))
            .put("recipes",JSONArray(recipes)).put("source",source(common)).put("measuredAttemptId",if(kind=="attempt")recipes[0].getJSONObject("story").getString("attemptId") else JSONObject.NULL)
            .put("heroAssetId",document.heroPhotoId?.let { id("photo:$it") } ?: JSONObject.NULL)
        if(sessionRaw!=null)payload.put("session",sessionObject(sessionRaw))
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
