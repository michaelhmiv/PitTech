package com.pittech.data

import com.pittech.domain.FoodCategories
import com.pittech.online.OnlineConfiguration

import android.content.Context
import android.graphics.BitmapFactory
import android.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class PublicFailure(val status: Int = 0) : Exception(when(status) {
    404 -> "This public recipe is unavailable. Any copy you already saved stays in your cookbook."
    409 -> "This recipe changed. Refresh its preview before saving."
    429 -> "Too many requests. Wait a minute, then refresh."
    else -> "Couldn't reach community recipes. Your local cookbook is available offline."
})
internal data class PublicItem(val id: String,val revisionId: String,val title: String,val kind: String,val credit: String,val likes: Int,val forks: Int,val categoryId: String = FoodCategories.DEFAULT_ID,val heroAssetId: String? = null) {
    val categoryLabel: String get() = FoodCategories.labelFor(categoryId)
    companion object { fun parse(o: JSONObject): PublicItem {
        // Category is parsed tolerantly: cached pages written before the
        // taxonomy (or by an older server) may carry no category or an
        // unknown id, and those count as Other rather than failing to parse.
        // The hero asset id (Slice 5, element 4.1) is likewise tolerant:
        // a cached page without one simply renders its card photo-less.
        val item=PublicItem(o.getString("id"),o.getString("revisionId"),o.getString("title"),o.getString("kind"),o.getJSONObject("credit").getString("displayName"),o.getInt("likes"),o.getInt("forks"),FoodCategories.normalizedId(o.optString("category")),o.optString("heroAssetId").takeIf { publicUUID(it) })
        require(publicUUID(item.id) && publicUUID(item.revisionId) && item.title.length in 1..160 && item.credit.length <= 160 && item.kind in setOf("recipe","collection","attempt") && item.likes in 0..1_000_000_000 && item.forks in 0..1_000_000_000);return item
    } }
}
/** What a Discover card can honestly show about a publication before
 * it is opened (Slice 5, element 4.1): the recorded cook's duration
 * and the pit temperature it ran at, read from the frozen story of
 * the recipe the hero photo belongs to. A slot is null when the
 * publication carries no such fact — absence renders as absence. */
internal data class PublicCardFacts(val timeLabel: String?,val tempLabel: String?)
internal fun publicCardFacts(detail: PublicDetail,temperatureUnit: String): PublicCardFacts {
    val heroAssetId=detail.payload.optString("heroAssetId")
    val recipe=detail.recipes.firstOrNull { r->r.optJSONArray("assets")?.let { a->(0 until a.length()).any { a.getJSONObject(it).optString("id")==heroAssetId } }==true } ?: detail.recipes.firstOrNull()
    val story=recipe?.optJSONObject("story")
    val timeLabel=if(story!=null && !story.isNull("foodOnOffsetMillis") && !story.isNull("foodOffOffsetMillis")) {
        val totalMinutes=(story.getLong("foodOffOffsetMillis")-story.getLong("foodOnOffsetMillis"))/60_000
        if(totalMinutes>0) { val h=totalMinutes/60;val m=totalMinutes%60;when { h>0 && m>0->"${h}H ${m}M";h>0->"${h}H";else->"${m}M" } } else null
    } else null
    val tempLabel=story?.optJSONArray("measurements")?.let { a->
        val pit=(0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("role")=="pit" }.map { it.getDouble("valueCelsius") }.sorted()
        if(pit.isEmpty())null else {
            val median=if(pit.size%2==1)pit[pit.size/2] else (pit[pit.size/2-1]+pit[pit.size/2])/2.0
            val display=if(temperatureUnit=="°C")median else median*9.0/5.0+32.0
            "${Math.round(display)}°"
        }
    }
    return PublicCardFacts(timeLabel,tempLabel)
}
/** The Discover request path: the category filter is appended only when a category is selected (empty/null means All). */
internal fun publicDiscoverPath(sort: String,food: String,query: String,category: String? = null): String {
    val base="/v1/discover?sort="+java.net.URLEncoder.encode(sort,"UTF-8")+"&food="+java.net.URLEncoder.encode(food,"UTF-8")+"&q="+java.net.URLEncoder.encode(query,"UTF-8")
    return if(category.isNullOrEmpty()) base else base+"&category="+java.net.URLEncoder.encode(category,"UTF-8")
}
internal data class PublicDetail(val id: String,val revisionId: String,val payload: JSONObject,val credit: String,val media: List<JSONObject>,val profileUrl: String? = null,val publisherAlias:String?=null,val likes:Int?=null,val forks:Int?=null) {
    val title get()=payload.getString("title")
    val recipes get()=payload.getJSONArray("recipes").let { a->(0 until a.length()).map(a::getJSONObject) }
    val hero get()=media.firstOrNull { it.getString("id") == payload.optString("heroAssetId") }
    companion object { fun parse(o: JSONObject): PublicDetail {
        val media=o.getJSONArray("media").let { a->(0 until a.length()).map(a::getJSONObject) }
        // Counts are read tolerantly: responses cached before the server
        // carried them simply have no counts to show (§4.5's slot rule).
        val likes=if(o.has("likes") && !o.isNull("likes"))o.optInt("likes").takeIf { it in 0..1_000_000_000 } else null
        val forks=if(o.has("forks") && !o.isNull("forks"))o.optInt("forks").takeIf { it in 0..1_000_000_000 } else null
        val value=PublicDetail(o.getString("id"),o.getString("revisionId"),o.getJSONObject("payload"),o.getJSONObject("credit").getString("displayName"),media,o.getJSONObject("credit").optString("profileUrl").takeIf { it.isNotEmpty() },if(o.isNull("publisherAlias"))null else o.getString("publisherAlias"),likes,forks)
        require(publicUUID(value.id) && publicUUID(value.revisionId) && value.title.length in 1..160 && value.credit.length <= 160 && (value.publisherAlias==null || publicUUID(value.publisherAlias)) && value.recipes.size in 1..20 && media.size <= 600 && media.map { it.getString("id") }.distinct().size == media.size)
        value.recipes.forEach { r-> require(publicUUID(r.getString("id")) && r.getString("title").length in 1..160 && r.optString("description").length <= 2000 && r.getJSONArray("ingredients").length() <= 100 && r.getJSONArray("instructions").length() <= 100) }
        media.forEach { m->require(publicUUID(m.getString("id")) && m.getString("url") == "${OnlineConfiguration.current.apiOrigin}/v1/recipes/${value.id}/assets/${m.getString("id")}" && m.getString("sha256").matches(Regex("[a-f0-9]{64}")) && m.getString("mimeType") in setOf("image/jpeg","image/png","image/webp")) }
        value.profileUrl?.let { require(PublicLink.destination(it).startsWith("profile:")) }
        return value
    } }
}
private fun publicUUID(value: String)=value.matches(Regex("[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}"))
internal interface PublicAPI {
    suspend fun json(path: String): ByteArray
    suspend fun pack(id: String,revision: String,to: File): File
    suspend fun image(id: String,asset: JSONObject): ByteArray
}
internal class PublicClient(client: OkHttpClient = OkHttpClient(),private val blocks:()->List<String> = {emptyList()},private val access:()->String?={null}) : PublicAPI {
    private val client=client.newBuilder().followRedirects(false).followSslRedirects(false).cookieJar(CookieJar.NO_COOKIES).cache(null).callTimeout(30,TimeUnit.SECONDS).build()
    private fun request(path: String): Request.Builder {
        require(path.matches(Regex("/v1/profiles/[a-z0-9_]{3,24}(\\?cursor=[a-fA-F0-9-]{36})?")) || path.startsWith("/v1/discover?") || path.matches(Regex("/v1/recipes/[a-fA-F0-9-]{36}(/pack|/assets/[a-fA-F0-9-]{36})?")))
        val request=Request.Builder().url("${OnlineConfiguration.current.apiOrigin}$path").header("Cache-Control","no-store").header("Accept","application/json")
        val aliases=blocks();require(aliases.size<=100 && aliases.all(::publicUUID));if(aliases.isNotEmpty())request.header("X-PitTech-Blocks",aliases.joinToString(","))
        access()?.let{require(AccountProtocol.credential(it));request.header("Authorization","Bearer $it")};return request
    }
    private suspend fun <T> perform(request: Request,read: (Response)->T): T=suspendCancellableCoroutine { c->
        val call=client.newCall(request);c.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call,e: IOException) { if(c.isActive)c.resumeWithException(PublicFailure()) }
            override fun onResponse(call: Call,response: Response) {
                try { val result=response.use { if(!it.isSuccessful)throw PublicFailure(it.code);read(it) };if(c.isActive)c.resume(result) }
                catch(e: Exception) { if(c.isActive)c.resumeWithException(if(e is PublicFailure)e else PublicFailure()) }
            }
        })
    }
    private fun bounded(response: Response,limit: Long): ByteArray {
        val source=response.body?.source() ?: throw PublicFailure();val buffer=okio.Buffer()
        while(buffer.size <= limit && source.read(buffer,minOf(8192L,limit+1-buffer.size)) != -1L) { }
        require(buffer.size <= limit);return buffer.readByteArray()
    }
    override suspend fun json(path: String)=perform(request(path).build()) { bounded(it,8*1024*1024) }
    override suspend fun pack(id: String,revision: String,to: File): File {
        require(publicUUID(id) && publicUUID(revision))
        try {
            return perform(request("/v1/recipes/$id/pack").header("If-Match",revision).header("Accept","application/octet-stream").build()) { response->
                val body=response.body ?: throw PublicFailure();val length=body.contentLength();val hash=response.header("X-Content-SHA256").orEmpty()
                require(length in 1..RelayProtocol.MAX_BYTES && hash.matches(Regex("[a-f0-9]{64}")))
                var size=0L
                body.byteStream().use { input->to.outputStream().use { output-> val buffer=ByteArray(65536)
                    while(true){val n=input.read(buffer);if(n<0)break;size+=n;require(size<=length);output.write(buffer,0,n)}
                } }
                require(size==length && RelayProtocol.hash(to)==hash);to
            }
        }catch(e: Exception){to.delete();throw e}
    }
    override suspend fun image(id: String,asset: JSONObject): ByteArray {
        require(publicUUID(id) && publicUUID(asset.getString("id")))
        return perform(request("/v1/recipes/$id/assets/${asset.getString("id")}").header("Accept",asset.getString("mimeType")).build()) { response->
            val bytes=bounded(response,16*1024*1024);require(RelayProtocol.hash(bytes)==asset.getString("sha256"))
            val options=BitmapFactory.Options().apply { inJustDecodeBounds=true };BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
            require(options.outWidth>0 && options.outHeight>0 && options.outWidth.toLong()*options.outHeight<=20_000_000);bytes
        }
    }
}
internal class PublicCache(context: Context,namespace: String = OnlineConfiguration.current.environment) {
    init { require(namespace.matches(Regex("[a-z0-9-]{1,64}"))) }
    private val directory=File(context.noBackupFilesDir,"public-browsing-$namespace-v1").apply { check(exists() || mkdirs()) }
    private fun file(key: String)=File(directory,RelayProtocol.hash(key.toByteArray())+".data")
    @Synchronized fun read(key: String,limit: Int = 8*1024*1024): ByteArray? {
        val file=file(key);if(!file.exists())return null
        if(file.length()>limit){file.delete();return null};return AtomicFile(file).readFully()
    }
    @Synchronized fun remove(key: String){file(key).delete()}
    @Synchronized fun save(key: String,bytes: ByteArray){
        require(bytes.size<=16*1024*1024);val atomic=AtomicFile(file(key));val output=atomic.startWrite()
        try{output.write(bytes);atomic.finishWrite(output)}catch(e: Exception){atomic.failWrite(output);throw e}
        val files=directory.listFiles().orEmpty().filter { it.extension=="data" };var size=files.sumOf { it.length() }
        for(old in files.sortedBy { it.lastModified() })if(size>32*1024*1024){val count=old.length();check(old.delete());size-=count}
    }
}
internal data class PublicPresentation(val appliedSort: String = "liked",val appliedFood: String = "",val appliedQuery: String = "",val appliedCategory: String = "",val items: List<PublicItem> = emptyList(),val nextCursor: String? = null,val loading: Boolean = false,val message: String? = null,val detailId: String? = null,val detail: PublicDetail? = null,val detailLoading: Boolean = false,val image: ByteArray? = null,val preparing: Boolean = false,val profileHandle: String? = null,val profileName: String? = null,val profileLoading: Boolean = false,val profileItems: List<PublicItem> = emptyList(),val profileCursor: String? = null,val savedDetailId: String? = null,val forking: Boolean = false,val forkedRecipes: List<PublicationFork.SavedFork> = emptyList())
internal class PublicManager(private val context: Context,private val scope: CoroutineScope,private val onPreview: suspend (File,Boolean,String,String)->Unit,private val api: PublicAPI = PublicClient(blocks={CommunityVault(context).aliases()},access={CommunityVault(context).publicAccount()?.accessToken}),val enabled: Boolean = OnlineConfiguration.current.enabled,private val cache: PublicCache = PublicCache(context),private val audienceProvider:()->String={CommunityVault(context).audience()},private val onFork: (suspend (PublicDetail,List<PublicationFork.Tweak>)->List<PublicationFork.SavedFork>)? = null,private val onPlanRecipe: ((String,String)->Unit)? = null) {
    private val _state=MutableStateFlow(PublicPresentation());val state=_state.asStateFlow()
    private val _linkSequence=MutableStateFlow(0L);val linkSequence=_linkSequence.asStateFlow()
    private var profileJob: Job?=null;private var profileReturn: String?=null
    private var pageJob:Job?=null;private var controlsGeneration=0
    private var pageKey="";private var detailJob: Job?=null;private var downloadJob: Job?=null;private var forkJob: Job?=null
    private suspend fun cached(path: String): Pair<ByteArray,Boolean> {
        val audience=audienceProvider();val key=audience+"|"+path
        try{
            val bytes=api.json(path);check(audienceProvider()==audience);val json=JSONObject(String(bytes,Charsets.UTF_8))
            if(path.startsWith("/v1/discover?"))page(json) else { require(PublicDetail.parse(json).id==path.substringAfterLast('/')) }
            try{withContext(Dispatchers.IO){cache.save(key,bytes)}}catch(e: CancellationException){throw e}catch(_: Exception){};check(audienceProvider()==audience);return bytes to false
        }catch(e: CancellationException){throw e}
        catch(e: Exception){
            if(e is PublicFailure && e.status==404){withContext(Dispatchers.IO){cache.remove(key)};throw e}
            check(audienceProvider()==audience);val old=withContext(Dispatchers.IO){cache.read(key)};check(audienceProvider()==audience);if(old!=null)return old to true;throw e
        }
    }
    private fun page(o: JSONObject): Pair<List<PublicItem>,String?> {
        val array=o.getJSONArray("items");require(array.length()<=20);val items=(0 until array.length()).map { PublicItem.parse(array.getJSONObject(it)) }
        val cursor=if(o.isNull("nextCursor"))null else o.getString("nextCursor");require((cursor?.length?:0)<=256 && items.map { it.id }.distinct().size==items.size);return items to cursor
    }
    fun refresh(sort: String = "liked",food: String = "",query: String = "",category: String? = null,more: Boolean = false){
        if(!enabled || _state.value.loading || sort !in setOf("liked","forked") || food.length>80 || query.length>80 || (!category.isNullOrEmpty() && !FoodCategories.isValid(category)))return
        val appliedCategory=category.orEmpty()
        val base=publicDiscoverPath(sort,food,query,appliedCategory)
        if(more && (pageKey!=base || _state.value.nextCursor==null))return
        val path=base+(if(more)"&cursor="+java.net.URLEncoder.encode(_state.value.nextCursor,"UTF-8") else "")
        _state.value=_state.value.copy(loading=true,message=null,appliedSort=sort,appliedFood=food,appliedQuery=query,appliedCategory=appliedCategory,nextCursor=if(!more && base!=pageKey)null else _state.value.nextCursor,items=if(!more && base!=pageKey)emptyList() else _state.value.items)
        val generation=controlsGeneration
        pageJob=scope.launch {
            try{val (bytes,saved)=cached(path);if(generation!=controlsGeneration)return@launch;val (items,cursor)=page(JSONObject(String(bytes,Charsets.UTF_8)))
                _state.value=_state.value.copy(items=if(more)(_state.value.items+items).distinctBy { it.id } else items,nextCursor=cursor,loading=false,message=if(saved)"Showing saved browsing results. Refresh when you can connect." else null);pageKey=base
            }catch(e: Exception){if(generation!=controlsGeneration)return@launch;_state.value=_state.value.copy(loading=false,message=if(e is CancellationException)"Request canceled." else (e as? PublicFailure)?.message ?: PublicFailure().message);if(e is CancellationException)throw e}
        }
    }
    fun controlsChanged(){controlsGeneration++;pageJob?.cancel();profileJob?.cancel();detailJob?.cancel();downloadJob?.cancel();forkJob?.cancel();pageKey="";_state.value=PublicPresentation(message="Community controls changed. Refresh to browse with your current blocks.")}
    /** PL-19: after a save from a detail, that detail's primary action
     * becomes "Open in Recipes" — the user lands where the thing went. */
    fun markSaved(id: String) { _state.value = _state.value.copy(savedDetailId = id) }
    /**
     * Slice 5 (element 4.2): fork the open publication as mine, with
     * the tweak-lite decisions the sheet collected. No Cook Pack is
     * downloaded: the ViewModel's handler converts the detail payload
     * already on the device ([PublicationFork]) and writes it through
     * the same restore path a pack import confirms into.
     */
    fun fork(tweaks: List<PublicationFork.Tweak>){
        val detail=_state.value.detail ?: return;val handler=onFork ?: return
        if(!enabled || _state.value.forking)return
        _state.value=_state.value.copy(forking=true,message=null)
        forkJob=scope.launch {
            try{
                val saved=handler(detail,tweaks)
                _state.value=_state.value.copy(forking=false,savedDetailId=if(_state.value.detailId==detail.id)detail.id else _state.value.savedDetailId,forkedRecipes=if(_state.value.detailId==detail.id)saved else _state.value.forkedRecipes)
            }catch(e: Exception){_state.value=_state.value.copy(forking=false,message=if(e is CancellationException)"Canceled. Nothing was added to your cookbook." else "That fork couldn't be saved. Your cookbook is unchanged.");if(e is CancellationException)throw e}
        }
    }
    /**
     * Slice 5 (element 4.4): "Plan this" on the publication page —
     * direct Discover→plan-lane seeding. The fork is what brings the
     * recipe into the library (element 4.2's machinery), so an
     * unforked publication is forked exactly as written first; the
     * planner then opens on the forked recipe, pre-filled.
     */
    fun planThis(){
        val detail=_state.value.detail ?: return;val plan=onPlanRecipe ?: return
        if(_state.value.savedDetailId==detail.id){
            val first=_state.value.forkedRecipes.firstOrNull()
            if(first!=null){ plan(first.recipeId,first.revisionId);return }
        }
        if(onFork==null || !enabled || _state.value.forking)return
        _state.value=_state.value.copy(forking=true,message=null)
        forkJob=scope.launch {
            try{
                val saved=onFork.invoke(detail,PublicationFork.untweaked(detail))
                _state.value=_state.value.copy(forking=false,savedDetailId=detail.id,forkedRecipes=saved)
                saved.firstOrNull()?.let { plan(it.recipeId,it.revisionId) }
            }catch(e: Exception){_state.value=_state.value.copy(forking=false,message=if(e is CancellationException)"Canceled. Nothing was added to your cookbook." else "That fork couldn't be saved. Your cookbook is unchanged.");if(e is CancellationException)throw e}
        }
    }
    /** Element 4.1: a Discover card's facts, from the cached detail —
     * the same fetch opening the publication would make. Null when
     * the detail can't be read; the card renders without the slots. */
    suspend fun cardFacts(item: PublicItem,temperatureUnit: String): PublicCardFacts? {
        try{return publicCardFacts(detailForReview(item.id).first,temperatureUnit)}
        catch(e: CancellationException){throw e}
        catch(e: Exception){return null}
    }
    /** Element 4.1: a Discover card's hero photo — hash-verified
     * through the same image path the detail view uses. */
    suspend fun cardImage(item: PublicItem): ByteArray? {
        val heroId=item.heroAssetId ?: return null
        try{
            val detail=detailForReview(item.id).first
            val media=detail.media.firstOrNull { it.getString("id")==heroId } ?: return null
            return image(item.id,media)
        }catch(e: CancellationException){throw e}
        catch(e: Exception){return null}
    }
    fun receivePublic(input: String): Boolean {
        if(!enabled)return false
        val destination=runCatching { PublicLink.destination(input) }.getOrNull() ?: return false
        profileReturn=null
        if(destination.startsWith("profile:"))openProfile(destination.substringAfter(':')) else open(destination)
        _linkSequence.value+=1;return true
    }
    fun openProfile(handle: String,cursor: String? = null){
        if(!enabled || !handle.matches(Regex("[a-z0-9_]{3,24}")) || cursor!=null && !publicUUID(cursor))return
        profileJob?.cancel();detailJob?.cancel();downloadJob?.cancel()
        val previous=if(cursor!=null && _state.value.profileHandle==handle)_state.value.profileItems else emptyList()
        _state.value=_state.value.copy(detailId=null,detail=null,detailLoading=false,image=null,preparing=false,profileHandle=handle,profileName=if(cursor==null)null else _state.value.profileName,profileItems=previous,profileCursor=null,profileLoading=true,message=null)
        profileJob=scope.launch {
            try {
                val audience=audienceProvider()
                // Public profile visibility is checked live; no stale profile cache restores a hidden identity.
                val json=JSONObject(String(api.json("/v1/profiles/$handle"+(cursor?.let { "?cursor=$it" } ?: "")),Charsets.UTF_8))
                require(json.getString("handle")==handle && json.getString("displayName").length<=160)
                val rows=json.getJSONArray("items");require(rows.length()<=20)
                val items=mutableListOf<PublicItem>()
                for(i in 0 until rows.length()) {
                    val row=rows.getJSONObject(i);val id=row.getString("id");require(publicUUID(id) && row.getString("url")=="${OnlineConfiguration.current.publicOrigin}/c/$id")
                    try {
                        val raw=JSONObject(String(api.json("/v1/recipes/$id"),Charsets.UTF_8));val detail=PublicDetail.parse(raw)
                        require(detail.id==id && detail.payload.getString("identityMode")=="named")
                        items+=PublicItem(id,detail.revisionId,detail.title,detail.payload.getString("kind"),detail.credit,raw.getInt("likes"),raw.getInt("forks"),FoodCategories.normalizedId(detail.payload.optString("category")),detail.payload.optString("heroAssetId").takeIf { publicUUID(it) })
                    } catch(e: PublicFailure) { if(e.status!=404)throw e }
                }
                val next=if(json.isNull("nextCursor"))null else json.getString("nextCursor");require(next==null || publicUUID(next));ensureActive()
                check(audienceProvider()==audience);_state.value=_state.value.copy(profileName=json.getString("displayName"),profileItems=(previous+items).distinctBy { it.id },profileCursor=next,profileLoading=false)
            } catch(e: Exception) { if(e is CancellationException)throw e;_state.value=_state.value.copy(profileName=null,profileItems=emptyList(),profileLoading=false,message=(e as? PublicFailure)?.message ?: PublicFailure().message) }
        }
    }
    suspend fun detailForReview(id: String): Pair<PublicDetail,Boolean> {
        require(enabled && publicUUID(id));val (bytes,saved)=cached("/v1/recipes/$id")
        val detail=PublicDetail.parse(JSONObject(String(bytes,Charsets.UTF_8)));require(detail.id==id);return detail to saved
    }
    fun open(id: String){
        if(!enabled || !publicUUID(id))return;profileReturn=_state.value.profileHandle;profileJob?.cancel();detailJob?.cancel();_state.value=_state.value.copy(profileHandle=null,profileName=null,profileLoading=false,detailId=id,detail=null,detailLoading=true,image=null,message=null)
        detailJob=scope.launch {
            try{
                val (bytes,saved)=cached("/v1/recipes/$id");val detail=PublicDetail.parse(JSONObject(String(bytes,Charsets.UTF_8)));ensureActive()
                if(_state.value.detailId!=id)return@launch
                _state.value=_state.value.copy(detail=detail,detailLoading=false,message=if(saved)"Showing a saved preview. Connect to check for updates." else null)
                detail.hero?.let { hero->
                    val image=image(id,hero)
                    if(_state.value.detailId==id)_state.value=_state.value.copy(image=image)
                }
            }catch(e: Exception){if(e !is CancellationException && _state.value.detailId==id)_state.value=_state.value.copy(detailLoading=false,message=(e as? PublicFailure)?.message ?: PublicFailure().message)}
        }
    }
    suspend fun image(id: String,asset: JSONObject): ByteArray {
        val audience=audienceProvider();val key=audience+"|image:"+asset.getString("sha256")
        val old=withContext(Dispatchers.IO){cache.read(key,16*1024*1024)}
        if(old!=null && RelayProtocol.hash(old)==asset.getString("sha256")){check(audienceProvider()==audience);return old}
        val bytes=api.image(id,asset);check(audienceProvider()==audience)
        try{withContext(Dispatchers.IO){cache.save(key,bytes)}}catch(e: CancellationException){throw e}catch(_: Exception){}
        check(audienceProvider()==audience);return bytes
    }
    fun back(){
        val returning=profileReturn;profileReturn=null
        if(_state.value.detailId!=null && returning!=null){openProfile(returning);return}
        profileJob?.cancel();_state.value=_state.value.copy(profileHandle=null,profileName=null,profileLoading=false,profileItems=emptyList(),profileCursor=null)
        detailJob?.cancel();downloadJob?.cancel();forkJob?.cancel();_state.value=_state.value.copy(detailId=null,detail=null,detailLoading=false,image=null,preparing=false,forking=false,forkedRecipes=emptyList(),message=null)}
    fun preview(cook: Boolean){
        val detail=_state.value.detail ?: return;if(!enabled || _state.value.preparing)return
        _state.value=_state.value.copy(preparing=true,message=null)
        downloadJob=scope.launch {
            val file=File(context.cacheDir,UUID.randomUUID().toString()+".pittech")
            try{val audience=audienceProvider();val downloaded=api.pack(detail.id,detail.revisionId,file);ensureActive();check(audienceProvider()==audience);onPreview(downloaded,cook,detail.id,detail.revisionId)}
            catch(e: Exception){_state.value=_state.value.copy(message=if(e is CancellationException)"Canceled. Nothing was added to your cookbook." else (e as? PublicFailure)?.message ?: PublicFailure().message);if(e is CancellationException)throw e}
            finally{file.delete();_state.value=_state.value.copy(preparing=false)}
        }
    }
    fun cancel(){downloadJob?.cancel()}
}
