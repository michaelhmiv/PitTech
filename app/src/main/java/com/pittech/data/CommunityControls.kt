package com.pittech.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

internal data class CommunityBlock(val alias:String,val title:String){fun json()=JSONObject().put("alias",alias).put("title",title)}
internal data class CommunityReportReceipt(val id:String,val status:String,val userId:String?=null){fun json()=JSONObject().put("id",id).put("status",status).put("userId",userId?:JSONObject.NULL)}
internal data class CommunityPending(val userId:String?,val operationId:String,val kind:String,val body:String,val sha256:String,val rejected:Boolean=false){
    override fun toString()="CommunityPending(redacted)"
    fun validate(){
        require(publicationUuid(operationId) && (userId==null || publicationUuid(userId)) && kind in setOf("report","block") && body.toByteArray().size<=8192 && RelayProtocol.hash(body.toByteArray())==sha256)
        val value=JSONObject(body);require(value.getString("operationId")==operationId)
        if(kind=="block")require(userId!=null && value.keys().asSequence().toSet()==setOf("operationId","alias","blocked") && publicationUuid(value.getString("alias")) && value.get("blocked") is Boolean)
        else require(value.keys().asSequence().toSet()==setOf("operationId","itemId","assetId","kind","reason","details") && publicationUuid(value.getString("itemId")) && (value.isNull("assetId") || publicationUuid(value.getString("assetId"))) && value.getString("kind") in setOf("content","publisher") && value.getString("reason") in CommunityControls.reasons && value.getString("details").length<=2000 && (value.getString("kind")!="publisher" || value.isNull("assetId")))
    }
    fun json()=JSONObject().put("userId",userId?:JSONObject.NULL).put("operationId",operationId).put("kind",kind).put("body",body).put("sha256",sha256).put("rejected",rejected)
    companion object{fun parse(o:JSONObject)=CommunityPending(if(o.isNull("userId"))null else o.getString("userId"),o.getString("operationId"),o.getString("kind"),o.getString("body"),o.getString("sha256"),o.getBoolean("rejected")).also{it.validate()}}
}
internal data class CommunityControlsState(val session:String,val generation:String=UUID.randomUUID().toString(),val blocks:List<CommunityBlock> = emptyList(),val pending:CommunityPending?=null,val reports:List<CommunityReportReceipt> = emptyList()){
    override fun toString()="CommunityControlsState(redacted)"
    fun validate(){require(AccountProtocol.credential(session) && publicationUuid(generation) && blocks.size<=100 && blocks.map{it.alias.lowercase()}.distinct().size==blocks.size && blocks.all{publicationUuid(it.alias) && it.title.length<=160} && reports.size<=20 && reports.all{publicationUuid(it.id) && it.status in setOf("received","open","reviewing","resolved","dismissed")});pending?.validate()}
    fun json()=JSONObject().put("session",session).put("generation",generation).put("blocks",JSONArray(blocks.map{it.json()})).put("pending",pending?.json()?:JSONObject.NULL).put("reports",JSONArray(reports.map{it.json()}))
    companion object{fun parse(o:JSONObject):CommunityControlsState{
        val b=o.getJSONArray("blocks");val r=o.getJSONArray("reports")
        return CommunityControlsState(o.getString("session"),o.getString("generation"),(0 until b.length()).map{val v=b.getJSONObject(it);CommunityBlock(v.getString("alias"),v.getString("title"))},o.optJSONObject("pending")?.let(CommunityPending::parse),(0 until r.length()).map{val v=r.getJSONObject(it);CommunityReportReceipt(v.getString("id"),v.getString("status"),if(v.isNull("userId"))null else v.getString("userId"))}).also{it.validate()}
    }}
}
internal interface CommunityStorage{fun load():CommunityControlsState?;fun save(value:CommunityControlsState)}
internal class CommunityVault(context:Context):CommunityStorage{
    private val vault=AccountVault(context,"community-controls")
    private val accountVault=AccountVault(context)
    override fun load():CommunityControlsState?{val bytes=vault.loadBytes()?:return null;return try{CommunityControlsState.parse(JSONObject(String(bytes,Charsets.UTF_8)))}finally{bytes.fill(0)}}
    override fun save(value:CommunityControlsState){value.validate();val bytes=value.json().toString().toByteArray();try{vault.saveBytes(bytes)}finally{bytes.fill(0)}}
    fun aliases()=load()?.blocks?.map{it.alias}?.sorted()?:emptyList()
    fun publicAccount():AccountSession?{val state=accountVault.load();if(state.deletion!=null)return null;val session=state.session?:return null;session.validate();val now=java.time.Instant.now();if(java.time.Instant.parse(session.sessionExpiresAt)<=now)throw PublicFailure(401);return session}
    fun audience()=(load()?.generation?:"empty")+"|"+(publicAccount()?.userId?:"anonymous")
}
internal fun interface CommunityAPI{suspend fun call(path:String,body:String?,access:String?,session:String):JSONObject}
internal class CommunityClient(client:OkHttpClient=OkHttpClient()):CommunityAPI{
    private val client=client.newBuilder().followRedirects(false).followSslRedirects(false).cookieJar(CookieJar.NO_COOKIES).cache(null).callTimeout(30,TimeUnit.SECONDS).build()
    override suspend fun call(path:String,body:String?,access:String?,session:String):JSONObject{
        require(AccountProtocol.credential(session) && (body?.toByteArray()?.size?:0)<=8192 && (path in setOf("/v1/reports","/v1/me/blocks") || path.matches(Regex("/v1/reports/[a-fA-F0-9-]{36}"))) && (path!="/v1/reports" || body!=null))
        val builder=Request.Builder().url("https://api.pittech.app$path").header("Accept","application/json").header("Cache-Control","no-store").header("X-PitTech-Report-Session",session)
        if(body!=null)builder.post(body.toRequestBody("application/json".toMediaType()))
        if(access!=null){require(AccountProtocol.credential(access));builder.header("Authorization","Bearer $access")}
        return suspendCancellableCoroutine{c->val call=client.newCall(builder.build());c.invokeOnCancellation{call.cancel()};call.enqueue(object:Callback{
            override fun onFailure(call:Call,e:IOException){if(c.isActive)c.resumeWithException(PublicFailure())}
            override fun onResponse(call:Call,response:Response){try{val result=response.use{if(!it.isSuccessful)throw PublicFailure(it.code);val source=it.body?.source()?:throw PublicFailure();val buffer=okio.Buffer();while(buffer.size<8193 && source.read(buffer,minOf(8192L,8193L-buffer.size)) != -1L){};require(buffer.size<=8192);JSONObject(String(buffer.readByteArray(),Charsets.UTF_8))};if(c.isActive)c.resume(result)}catch(e:Exception){if(c.isActive)c.resumeWithException(if(e is PublicFailure)e else PublicFailure())}}
        })}
    }
}
internal data class CommunityPresentation(val blocks:List<CommunityBlock> = emptyList(),val pending:CommunityPending?=null,val reports:List<CommunityReportReceipt> = emptyList(),val busy:Boolean=false,val message:String?=null)
internal class CommunityControls(private val storage:CommunityStorage,private val scope:CoroutineScope,private val api:CommunityAPI=CommunityClient()){
    companion object{val reasons=listOf("spam","harassment","unsafe","privacy","copyright","other")}
    private var state:CommunityControlsState?=null;private var ready=true
    private val _presentation=MutableStateFlow(CommunityPresentation());val presentation=_presentation.asStateFlow()
    init{try{state=storage.load();state?.validate();publish()}catch(_:Exception){ready=false;publish(message="This device's community controls are unavailable. Try again before browsing.")}}
    private fun publish(busy:Boolean=false,message:String?=null){_presentation.value=CommunityPresentation(state?.blocks?:emptyList(),state?.pending,state?.reports?:emptyList(),busy,message)}
    private fun current():CommunityControlsState{check(ready);return state?:CommunityControlsState(AccountProtocol.verifier()).also{save(it)}}
    private fun save(value:CommunityControlsState){value.validate();require(value.json().toString().toByteArray().size<=16384);storage.save(value);state=value}
    private fun perform(work:suspend()->Unit){if(_presentation.value.busy)return;publish(busy=true);scope.launch{try{work();publish(message=_presentation.value.message)}catch(e:Exception){publish(message="Community change was not confirmed. Your saved request and device blocks are kept. Retry or review it here.");if(e is CancellationException)throw e}}}
    fun report(item:String,asset:String?,publisher:Boolean,reason:String,details:String,account:AccountManager)=perform{
        val old=current();check(old.pending==null);val operation=UUID.randomUUID().toString()
        val body=JSONObject().put("operationId",operation).put("itemId",item).put("assetId",asset?:JSONObject.NULL).put("kind",if(publisher)"publisher" else "content").put("reason",reason).put("details",details).toString()
        save(old.copy(pending=CommunityPending(account.publicationUserId(),operation,"report",body,RelayProtocol.hash(body.toByteArray()))));send(account)
    }
    fun block(alias:String,title:String,blocked:Boolean,account:AccountManager,changed:()->Unit={})=perform{
        val old=current();check(old.pending==null);require(publicationUuid(alias) && title.length<=160)
        val blocks=old.blocks.filterNot{it.alias.equals(alias,true)}.let{if(blocked)it+CommunityBlock(alias.lowercase(),title) else it}
        val user=account.publicationUserId();val operation=UUID.randomUUID().toString();val body=JSONObject().put("operationId",operation).put("alias",alias.lowercase()).put("blocked",blocked).toString()
        save(old.copy(generation=UUID.randomUUID().toString(),blocks=blocks,pending=user?.let{CommunityPending(it,operation,"block",body,RelayProtocol.hash(body.toByteArray()))}));publish(busy=true);changed()
        if(user!=null)send(account) else publish(busy=true,message=if(blocked)"Publisher blocked on this device." else "Publisher unblocked on this device.")
    }
    private suspend fun send(account:AccountManager){
        var old=current();val pending=requireNotNull(old.pending);check(!pending.rejected);pending.validate()
        val path=if(pending.kind=="report")"/v1/reports" else "/v1/me/blocks"
        val access=pending.userId?.let{check(account.publicationUserId()==it);account.access().also{check(account.publicationUserId()==pending.userId)}}
        val result=try{try{api.call(path,pending.body,access,old.session)}catch(e:PublicFailure){if(e.status!=401)throw e;val user=requireNotNull(pending.userId);check(account.publicationUserId()==user);val refreshed=account.access(true);check(account.publicationUserId()==user);api.call(path,pending.body,refreshed,old.session)}}catch(e:PublicFailure){if(e.status in setOf(400,403,404,409,413,415,422)){old=old.copy(pending=pending.copy(rejected=true));save(old)};throw e}
        check(pending.userId==null || pending.userId==account.publicationUserId())
        if(pending.kind=="report"){
            val id=result.getString("reportId");require(publicationUuid(id) && result.getString("status")=="received")
            old=old.copy(reports=(listOf(CommunityReportReceipt(id,"received",pending.userId))+old.reports.filterNot{it.id==id}).take(20));publish(busy=true,message="Report received. Moderator notes and account identities stay private.")
        }else{val body=JSONObject(pending.body);require(result.getString("alias")==body.getString("alias") && result.getBoolean("blocked")==body.getBoolean("blocked"));publish(busy=true,message="Account block change confirmed.")}
        save(old.copy(pending=null))
    }
    fun checkReport(receipt:CommunityReportReceipt,account:AccountManager)=perform{
        val old=current();val access=receipt.userId?.let{check(account.publicationUserId()==it);account.access()}
        val result=api.call("/v1/reports/"+receipt.id,null,access,old.session)
        check(receipt.userId==null || receipt.userId==account.publicationUserId());require(result.getString("reportId")==receipt.id)
        val status=result.getString("status");require(status in setOf("open","reviewing","resolved","dismissed"))
        save(old.copy(reports=old.reports.map{if(it.id==receipt.id)it.copy(status=status) else it}));publish(busy=true,message="Report status updated.")
    }
    fun retry(account:AccountManager)=perform{send(account)}
    fun discardRejected(){if(_presentation.value.busy)return;val old=state?:return;if(old.pending?.rejected!=true)return;try{save(old.copy(pending=null));publish(message="Rejected request discarded. Device blocks are unchanged.")}catch(_:Exception){publish(message="The saved request could not be cleared.")}}
}
