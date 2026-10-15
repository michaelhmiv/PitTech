package com.pittech.data

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
internal data class PublicItem(val id: String,val revisionId: String,val title: String,val kind: String,val credit: String,val likes: Int,val forks: Int) {
    companion object { fun parse(o: JSONObject): PublicItem {
        val item=PublicItem(o.getString("id"),o.getString("revisionId"),o.getString("title"),o.getString("kind"),o.getJSONObject("credit").getString("displayName"),o.getInt("likes"),o.getInt("forks"))
        require(publicUUID(item.id) && publicUUID(item.revisionId) && item.title.length in 1..160 && item.credit.length <= 160 && item.kind in setOf("recipe","collection","attempt") && item.likes in 0..1_000_000_000 && item.forks in 0..1_000_000_000);return item
    } }
}
internal data class PublicDetail(val id: String,val revisionId: String,val payload: JSONObject,val credit: String,val media: List<JSONObject>) {
    val title get()=payload.getString("title")
    val recipes get()=payload.getJSONArray("recipes").let { a->(0 until a.length()).map(a::getJSONObject) }
    val hero get()=media.firstOrNull { it.getString("id") == payload.optString("heroAssetId") }
    companion object { fun parse(o: JSONObject): PublicDetail {
        val media=o.getJSONArray("media").let { a->(0 until a.length()).map(a::getJSONObject) }
        val value=PublicDetail(o.getString("id"),o.getString("revisionId"),o.getJSONObject("payload"),o.getJSONObject("credit").getString("displayName"),media)
        require(publicUUID(value.id) && publicUUID(value.revisionId) && value.title.length in 1..160 && value.credit.length <= 160 && value.recipes.size in 1..20 && media.size <= 600 && media.map { it.getString("id") }.distinct().size == media.size)
        value.recipes.forEach { r-> require(publicUUID(r.getString("id")) && r.getString("title").length in 1..160 && r.optString("description").length <= 2000 && r.getJSONArray("ingredients").length() <= 100 && r.getJSONArray("instructions").length() <= 100) }
        media.forEach { m->require(publicUUID(m.getString("id")) && m.getString("url") == "https://api.pittech.app/v1/recipes/${value.id}/assets/${m.getString("id")}" && m.getString("sha256").matches(Regex("[a-f0-9]{64}")) && m.getString("mimeType") in setOf("image/jpeg","image/png","image/webp")) }
        return value
    } }
}
private fun publicUUID(value: String)=value.matches(Regex("[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}"))
internal interface PublicAPI {
    suspend fun json(path: String): ByteArray
    suspend fun pack(id: String,revision: String,to: File): File
    suspend fun image(id: String,asset: JSONObject): ByteArray
}
internal class PublicClient(client: OkHttpClient = OkHttpClient()) : PublicAPI {
    private val client=client.newBuilder().followRedirects(false).followSslRedirects(false).cookieJar(CookieJar.NO_COOKIES).cache(null).callTimeout(30,TimeUnit.SECONDS).build()
    private fun request(path: String): Request.Builder {
        require(path.startsWith("/v1/discover?") || path.matches(Regex("/v1/recipes/[a-fA-F0-9-]{36}(/pack|/assets/[a-fA-F0-9-]{36})?")))
        return Request.Builder().url("https://api.pittech.app$path").header("Cache-Control","no-store").header("Accept","application/json")
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
internal class PublicCache(context: Context,namespace: String = "production") {
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
internal data class PublicPresentation(val appliedSort: String = "liked",val appliedFood: String = "",val appliedQuery: String = "",val items: List<PublicItem> = emptyList(),val nextCursor: String? = null,val loading: Boolean = false,val message: String? = null,val detailId: String? = null,val detail: PublicDetail? = null,val detailLoading: Boolean = false,val image: ByteArray? = null,val preparing: Boolean = false)
internal class PublicManager(private val context: Context,private val scope: CoroutineScope,private val onPreview: suspend (File,Boolean,String,String)->Unit,private val api: PublicAPI = PublicClient(),val enabled: Boolean = false,private val cache: PublicCache = PublicCache(context)) {
    private val _state=MutableStateFlow(PublicPresentation());val state=_state.asStateFlow()
    private var pageKey="";private var detailJob: Job?=null;private var downloadJob: Job?=null
    private suspend fun cached(path: String): Pair<ByteArray,Boolean> {
        try{
            val bytes=api.json(path);val json=JSONObject(String(bytes,Charsets.UTF_8))
            if(path.startsWith("/v1/discover?"))page(json) else { require(PublicDetail.parse(json).id==path.substringAfterLast('/')) }
            try{withContext(Dispatchers.IO){cache.save(path,bytes)}}catch(e: CancellationException){throw e}catch(_: Exception){};return bytes to false
        }catch(e: CancellationException){throw e}
        catch(e: Exception){
            if(e is PublicFailure && e.status==404){withContext(Dispatchers.IO){cache.remove(path)};throw e}
            val old=withContext(Dispatchers.IO){cache.read(path)};if(old!=null)return old to true;throw e
        }
    }
    private fun page(o: JSONObject): Pair<List<PublicItem>,String?> {
        val array=o.getJSONArray("items");require(array.length()<=20);val items=(0 until array.length()).map { PublicItem.parse(array.getJSONObject(it)) }
        val cursor=if(o.isNull("nextCursor"))null else o.getString("nextCursor");require((cursor?.length?:0)<=256 && items.map { it.id }.distinct().size==items.size);return items to cursor
    }
    fun refresh(sort: String = "liked",food: String = "",query: String = "",more: Boolean = false){
        if(!enabled || _state.value.loading || sort !in setOf("liked","forked") || food.length>80 || query.length>80)return
        val base="/v1/discover?sort="+java.net.URLEncoder.encode(sort,"UTF-8")+"&food="+java.net.URLEncoder.encode(food,"UTF-8")+"&q="+java.net.URLEncoder.encode(query,"UTF-8")
        if(more && (pageKey!=base || _state.value.nextCursor==null))return
        val path=base+(if(more)"&cursor="+java.net.URLEncoder.encode(_state.value.nextCursor,"UTF-8") else "")
        _state.value=_state.value.copy(loading=true,message=null,appliedSort=sort,appliedFood=food,appliedQuery=query,nextCursor=if(!more && base!=pageKey)null else _state.value.nextCursor,items=if(!more && base!=pageKey)emptyList() else _state.value.items)
        scope.launch {
            try{val (bytes,saved)=cached(path);val (items,cursor)=page(JSONObject(String(bytes,Charsets.UTF_8)))
                _state.value=_state.value.copy(items=if(more)(_state.value.items+items).distinctBy { it.id } else items,nextCursor=cursor,loading=false,message=if(saved)"Showing saved browsing results. Refresh when you can connect." else null);pageKey=base
            }catch(e: Exception){_state.value=_state.value.copy(loading=false,message=if(e is CancellationException)"Request canceled." else (e as? PublicFailure)?.message ?: PublicFailure().message);if(e is CancellationException)throw e}
        }
    }
    fun open(id: String){
        if(!enabled || !publicUUID(id))return;detailJob?.cancel();_state.value=_state.value.copy(detailId=id,detail=null,detailLoading=true,image=null,message=null)
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
        val key="image:"+asset.getString("sha256")
        val old=withContext(Dispatchers.IO){cache.read(key,16*1024*1024)}
        if(old!=null && RelayProtocol.hash(old)==asset.getString("sha256"))return old
        val bytes=api.image(id,asset)
        try{withContext(Dispatchers.IO){cache.save(key,bytes)}}catch(e: CancellationException){throw e}catch(_: Exception){}
        return bytes
    }
    fun back(){detailJob?.cancel();downloadJob?.cancel();_state.value=_state.value.copy(detailId=null,detail=null,detailLoading=false,image=null,preparing=false,message=null)}
    fun preview(cook: Boolean){
        val detail=_state.value.detail ?: return;if(!enabled || _state.value.preparing)return
        _state.value=_state.value.copy(preparing=true,message=null)
        downloadJob=scope.launch {
            val file=File(context.cacheDir,UUID.randomUUID().toString()+".pittech")
            try{val downloaded=api.pack(detail.id,detail.revisionId,file);ensureActive();onPreview(downloaded,cook,detail.id,detail.revisionId)}
            catch(e: Exception){_state.value=_state.value.copy(message=if(e is CancellationException)"Canceled. Nothing was added to your cookbook." else (e as? PublicFailure)?.message ?: PublicFailure().message);if(e is CancellationException)throw e}
            finally{file.delete();_state.value=_state.value.copy(preparing=false)}
        }
    }
    fun cancel(){downloadJob?.cancel()}
}
