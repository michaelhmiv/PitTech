package com.pittech.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal interface PublicationSession {
    suspend fun userId(): String?
    suspend fun access(forceRefresh: Boolean): String
}
internal class AccountPublicationSession(private val account: AccountManager): PublicationSession {
    override suspend fun userId() = account.publicationUserId()
    override suspend fun access(forceRefresh: Boolean) = account.access(forceRefresh)
}
internal fun publicationUuid(value: String) = value.matches(Regex("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}"))
internal data class PublicationReceipt(val id: String, val revisionId: String, val revision: Int, val url: String, val kind: String? = null) {
    fun validate() { require(publicationUuid(id) && publicationUuid(revisionId) && revision > 0 && url == "https://pittech.app/c/$id" && (kind==null || kind in setOf("recipe","collection","attempt"))) }
    fun json() = JSONObject().put("id",id).put("revisionId",revisionId).put("revision",revision).put("url",url).put("kind",kind ?: JSONObject.NULL)
    companion object { fun parse(o: JSONObject) = PublicationReceipt(o.getString("id"),o.getString("revisionId"),o.getInt("revision"),o.getString("url"),if(o.isNull("kind"))null else o.getString("kind")).also { it.validate() } }
}
internal data class PublicationOperation(val id: String, val userId: String, val method: String, val path: String, val contentType: String, val sha256: String, val bytes: Int, val expectedRevision: String? = null, val draftId: String? = null, val rejected: Boolean = false, val publicationKind: String? = null) {
    fun validate() {
        require(publicationUuid(id) && publicationUuid(userId) && sha256.matches(Regex("[a-f0-9]{64}")) && bytes in 0..64*1024*1024 && (expectedRevision == null || publicationUuid(expectedRevision)) && (draftId == null || publicationUuid(draftId)))
        val parts=path.split('/'); val item=parts.size>=4 && parts[0]=="" && parts[1]=="v1" && parts[2]=="recipes" && publicationUuid(parts[3])
        val recipe=item && parts.size==4; val like=item && parts.size==5 && parts[4]=="like"
        val archive=method=="POST" && path=="/v1/recipes" || method=="PUT" && recipe
        require(publicationKind==null || archive && publicationKind in setOf("recipe","collection","attempt"))
        require(archive && contentType=="application/zip" && bytes>0 && (method!="PUT" || expectedRevision!=null) || !archive && draftId==null && contentType=="application/json" && bytes<=8192 && (path=="/v1/me/profile" && method=="PUT" || like && method in setOf("POST","DELETE") || recipe && method=="DELETE" && expectedRevision!=null))
    }
    fun json() = JSONObject().put("id",id).put("userId",userId).put("method",method).put("path",path).put("contentType",contentType).put("sha256",sha256).put("bytes",bytes).put("expectedRevision",expectedRevision ?: JSONObject.NULL).put("draftId",draftId ?: JSONObject.NULL).put("rejected",rejected).put("publicationKind",publicationKind ?: JSONObject.NULL)
    companion object {
        fun parse(o: JSONObject) = PublicationOperation(o.getString("id"),o.getString("userId"),o.getString("method"),o.getString("path"),o.getString("contentType"),o.getString("sha256"),o.getInt("bytes"),if(o.isNull("expectedRevision"))null else o.getString("expectedRevision"),if(o.isNull("draftId"))null else o.getString("draftId"),o.getBoolean("rejected"),if(o.isNull("publicationKind"))null else o.getString("publicationKind")).also { it.validate() }
    }
}
/** Device-only, one durable exact request per account. Credentials are never persisted here. */
internal class PublicationOperationsStore(val directory: File) {
    constructor(context: Context): this(File(context.noBackupFilesDir,"publication-operations-v1"))
    companion object { private val lock=Any() }
    private fun stateFile(user: String): File { require(publicationUuid(user)); return File(directory,RelayProtocol.hash(user.toByteArray())+".json") }
    private fun bounded(input: InputStream): ByteArray = input.use {
        val result=ByteArrayOutputStream(); val buffer=ByteArray(8192)
        while(true) { val count=it.read(buffer,0,minOf(buffer.size,8*1024*1024+1-result.size())); if(count<0)break; result.write(buffer,0,count); require(result.size()<=8*1024*1024) }; result.toByteArray()
    }
    private fun load(user: String): JSONObject {
        val file=stateFile(user); if(!file.exists() && !File(file.path+".bak").exists())return JSONObject().put("version",1).put("pending",JSONObject.NULL).put("bindings",JSONObject())
        val bytes=bounded(AtomicFile(file).openRead()); require(bytes.size<=8*1024*1024)
        return JSONObject(String(bytes,Charsets.UTF_8)).also { state ->
            require(state.getInt("version")==1)
            state.optJSONObject("pending")?.let { require(PublicationOperation.parse(it).userId==user) }
            val bindings=state.getJSONObject("bindings"); bindings.keys().forEach { PublicationReceipt.parse(bindings.getJSONObject(it)) }
            if(state.has("editContexts") && !state.isNull("editContexts"))state.getJSONObject("editContexts").let { edits -> edits.keys().forEach { draft -> val context=PublicationEditContext.parse(edits.getJSONObject(draft));require(publicationUuid(draft) && bindings.getJSONObject(draft).getString("id")==context.receipt.id) } }
        }
    }
    private fun write(file: File, bytes: ByteArray) {
        check(directory.isDirectory || directory.mkdirs()); val atomic=AtomicFile(file); val stream=atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) } catch(e: Throwable) { atomic.failWrite(stream); throw e }
        atomic.openRead().close()
        check(file.length()==bytes.size.toLong() && RelayProtocol.hash(file)==RelayProtocol.hash(bytes)) { "Publication storage could not be committed." }
    }
    private fun save(user: String, state: JSONObject) { val bytes=state.toString().toByteArray(); require(bytes.size<=8*1024*1024); write(stateFile(user),bytes) }
    fun body(op: PublicationOperation): File { op.validate(); return File(directory,op.id+".body") }
    fun pending(user: String): PublicationOperation? = synchronized(lock) { load(user).optJSONObject("pending")?.let(PublicationOperation::parse) }
    fun binding(user: String, draft: String): PublicationReceipt? = synchronized(lock) { load(user).getJSONObject("bindings").optJSONObject(draft)?.let(PublicationReceipt::parse) }
    fun editContext(user: String,draft: String): PublicationEditContext? = synchronized(lock) { load(user).optJSONObject("editContexts")?.optJSONObject(draft)?.let(PublicationEditContext::parse) }
    fun attachEditContext(user: String,draft: String,context: PublicationEditContext) = synchronized(lock) {
        context.validate();require(publicationUuid(draft));val state=load(user);check(state.isNull("pending") && !state.getJSONObject("bindings").has(draft))
        state.getJSONObject("bindings").put(draft,context.receipt.json());val edits=state.optJSONObject("editContexts") ?: JSONObject();edits.put(draft,context.json());state.put("editContexts",edits);save(user,state)
    }
    fun prepare(user: String, method: String, path: String, bytes: ByteArray, archive: Boolean, expected: String? = null, draft: String? = null, kind: String? = null): PublicationOperation = synchronized(lock) {
        val state=load(user); check(state.isNull("pending")) { "Recover the previous publication before starting another." }
        check(directory.isDirectory || directory.mkdirs())
        // Count all accounts; clean only bodies with no durable pending reference.
        val files=directory.listFiles().orEmpty(); val referenced=mutableSetOf<String>()
        for(file in files.filter { it.extension=="json" || it.name.endsWith(".json.bak") }) {
            val base=if(file.name.endsWith(".bak"))File(file.path.removeSuffix(".bak")) else file
            val data=bounded(AtomicFile(base).openRead()); require(data.size<=8*1024*1024)
            JSONObject(String(data,Charsets.UTF_8)).optJSONObject("pending")?.let { referenced+=PublicationOperation.parse(it).id+".body" }
        }
        var used=0L
        for(file in files.filter { it.extension=="body" }) { if(file.name in referenced)used+=file.length() else check(file.delete()) }
        require(used+bytes.size<=128*1024*1024)
        val op=PublicationOperation(UUID.randomUUID().toString(),user,method,path,if(archive)"application/zip" else "application/json",RelayProtocol.hash(bytes),bytes.size,expected,draft,publicationKind=kind); op.validate()
        val target=body(op); write(target,bytes)
        // Keep the body if metadata persistence has an ambiguous failure; orphan cleanup is safe on the next prepare.
        state.put("pending",op.json()); save(user,state); op
    }
    fun verify(op: PublicationOperation): File = synchronized(lock) {
        check(pending(op.userId)?.id==op.id); val file=body(op)
        require(file.length()==op.bytes.toLong() && RelayProtocol.hash(file)==op.sha256); file
    }
    fun reject(op: PublicationOperation) = synchronized(lock) {
        val state=load(op.userId); check(pending(op.userId)?.id==op.id); state.put("pending",op.copy(rejected=true).json()); save(op.userId,state)
    }
    fun discardRejected(user: String) = synchronized(lock) {
        val state=load(user); val op=checkNotNull(pending(user)); check(op.rejected)
        state.put("pending",JSONObject.NULL); save(user,state); body(op).delete(); Unit
    }
    fun finish(op: PublicationOperation, response: JSONObject) = synchronized(lock) {
        val state=load(op.userId); check(pending(op.userId)?.id==op.id)
        if(op.contentType=="application/zip") {
            val receipt=PublicationReceipt.parse(response).copy(kind=op.publicationKind); if(op.method=="PUT")require(op.path=="/v1/recipes/"+receipt.id)
            op.draftId?.let { draft ->
                val edits=state.optJSONObject("editContexts");edits?.optJSONObject(draft)?.let { old ->
                    val context=PublicationEditContext.parse(old);if(context.receipt.id==receipt.id)edits.put(draft,context.copy(receipt=receipt).json()) else edits.remove(draft)
                }
                state.getJSONObject("bindings").put(draft,receipt.json())
            }
        } else {
            if(op.path=="/v1/me/profile") {
                require(response.getString("displayName").length<=160 && response.get("isPublic") is Boolean)
                require(response.has("handle") && (response.isNull("handle") || response.getString("handle").matches(Regex("[a-z0-9_]{3,24}"))))
            } else {
                require(response.getString("id")==op.path.split('/')[3])
                if(op.path.endsWith("/like"))require(response.get("liked") is Boolean && response.getBoolean("liked")== (op.method=="POST"))
                else require(response.get("unpublished") is Boolean && response.getBoolean("unpublished"))
            }
        }
        state.put("pending",JSONObject.NULL); save(op.userId,state); body(op).delete(); Unit
    }
}
internal interface PublicationAPI {
    suspend fun read(path: String, access: String): JSONObject
    suspend fun write(operation: PublicationOperation, file: File, access: String): JSONObject
    suspend fun archive(id: String,revision: String,to: File,access: String): File { throw UnsupportedOperationException("Owner archive unavailable.") }
}
internal class PublicationClient(client: OkHttpClient = OkHttpClient()): PublicationAPI {
    private val client=client.newBuilder().followRedirects(false).followSslRedirects(false).cookieJar(CookieJar.NO_COOKIES).cache(null).callTimeout(90,TimeUnit.SECONDS).build()
    private fun request(path: String, access: String): Request.Builder {
        require(AccountProtocol.credential(access)); return Request.Builder().url("https://api.pittech.app$path").header("Authorization","Bearer $access").header("Accept","application/json").header("Cache-Control","no-store")
    }
    override suspend fun read(path: String, access: String): JSONObject {
        val parts=path.split('/'); val exactOwner=parts.size==5 && parts[0]=="" && parts[1]=="v1" && parts[2]=="me" && parts[3]=="recipes" && publicationUuid(parts[4])
        require(exactOwner || path=="/v1/me/profile" || path.matches(Regex("/v1/me/recipes(\\?cursor=[a-f0-9-]{36})?")))
        return call(request(path,access).get().build(),65536)
    }
    override suspend fun archive(id: String,revision: String,to: File,access: String): File {
        require(publicationUuid(id) && publicationUuid(revision))
        try {
            return suspendCancellableCoroutine { continuation ->
                val call=client.newCall(request("/v1/me/recipes/$id/archive",access).header("Accept","application/zip").header("If-Match",revision).get().build());continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object: Callback {
                    override fun onFailure(call: Call,e: IOException) { if(continuation.isActive)continuation.resumeWithException(e) }
                    override fun onResponse(call: Call,response: Response) {
                        try {
                            response.use {
                                if(!it.isSuccessful)throw AccountFailure(it.code)
                                val body=checkNotNull(it.body);val length=body.contentLength();val hash=it.header("X-Content-SHA256").orEmpty()
                                require(length in 1..64L*1024*1024 && hash.matches(Regex("[a-f0-9]{64}")))
                                val digest=java.security.MessageDigest.getInstance("SHA-256");var size=0L
                                body.byteStream().use { input->to.outputStream().use { output->val buffer=ByteArray(65536);while(true) { val count=input.read(buffer);if(count<0)break;size+=count;require(size<=length);digest.update(buffer,0,count);output.write(buffer,0,count) } } }
                                require(size==length && digest.digest().joinToString("") { byte->"%02x".format(byte) }==hash)
                            }
                            if(continuation.isActive)continuation.resume(to) else to.delete()
                        } catch(e: Exception) { to.delete();if(continuation.isActive)continuation.resumeWithException(e) }
                    }
                })
            }
        } catch(e: Exception) { to.delete();throw e }
    }
    override suspend fun write(operation: PublicationOperation, file: File, access: String): JSONObject {
        operation.validate(); val request=request(operation.path,access).header("Idempotency-Key",operation.id).method(operation.method,file.asRequestBody(operation.contentType.toMediaType()))
        operation.expectedRevision?.let { request.header("If-Match",it) }; return call(request.build(),8192)
    }
    private suspend fun call(request: Request, limit: Int): JSONObject = suspendCancellableCoroutine { continuation ->
        val call=client.newCall(request); continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object: Callback {
            override fun onFailure(call: Call, e: IOException) { if(continuation.isActive)continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result=response.use { if(!it.isSuccessful)throw AccountFailure(it.code); val source=it.body?.source(); val buffer=okio.Buffer()
                        if(source!=null)while(buffer.size<=limit && source.read(buffer,minOf(8192L,limit+1L-buffer.size)) != -1L) { }
                        require(buffer.size<=limit); JSONObject(String(buffer.readByteArray(),Charsets.UTF_8)) }
                    if(continuation.isActive)continuation.resume(result)
                } catch(e: Exception) { if(continuation.isActive)continuation.resumeWithException(e) }
            }
        })
    }
}
internal class PublicationWriter(private val session: PublicationSession, val store: PublicationOperationsStore, private val api: PublicationAPI = PublicationClient()) {
    private val mutex=Mutex()
    suspend fun pending(): PublicationOperation? {
        val user=session.userId() ?: return null; val value=withContext(Dispatchers.IO) { store.pending(user) }; check(session.userId()==user); return value
    }
    suspend fun binding(draft: String): PublicationReceipt? {
        val user=session.userId() ?: return null; val value=withContext(Dispatchers.IO) { store.binding(user,draft) }; check(session.userId()==user); return value
    }
    private suspend fun perform(op: PublicationOperation): JSONObject {
        check(!op.rejected) { "Review the rejected preview before starting another publication." }
        check(session.userId()==op.userId) { "Sign in with the account that started this publication." }
        val file=store.verify(op); val token=session.access(false)
        check(session.userId()==op.userId); coroutineContext.ensureActive()
        val response=try { api.write(op,file,token) } catch(e: AccountFailure) {
            if(e.status!=401)throw e
            val refreshed=session.access(true); check(session.userId()==op.userId); coroutineContext.ensureActive(); api.write(op,file,refreshed)
        }
        require(response.toString().toByteArray().size<=8192); store.finish(op,response)
        check(session.userId()==op.userId); return response
    }
    private suspend fun execute(op: PublicationOperation): JSONObject = try { perform(op) } catch(e: AccountFailure) {
        if(e.status in setOf(400,403,404,409,413,415,422))store.reject(op); throw e
    }
    suspend fun recover(): JSONObject? = mutex.withLock { withContext(Dispatchers.IO) { pending()?.let { execute(it) } } }
    suspend fun submit(draft: String, prepared: PreparedPublication, update: PublicationReceipt? = null): JSONObject = mutex.withLock { withContext(Dispatchers.IO) {
        val user=checkNotNull(session.userId()); update?.validate()
        execute(store.prepare(user,if(update==null)"POST" else "PUT",update?.let { "/v1/recipes/"+it.id } ?: "/v1/recipes",prepared.bytes,true,update?.revisionId,draft,prepared.payload.getString("kind")))
    } }
    suspend fun json(method: String, path: String, body: JSONObject = JSONObject(), expected: String? = null): JSONObject = mutex.withLock { withContext(Dispatchers.IO) {
        execute(store.prepare(checkNotNull(session.userId()),method,path,body.toString().toByteArray(),false,expected))
    } }
    suspend fun editContext(draft: String): PublicationEditContext? {
        val user=session.userId() ?: return null;val value=withContext(Dispatchers.IO) { store.editContext(user,draft) };check(session.userId()==user);return value
    }
    suspend fun ownedArchive(receipt: PublicationReceipt,to: File): File {
        receipt.validate();val user=checkNotNull(session.userId())
        try {
            val token=session.access(false);check(session.userId()==user);coroutineContext.ensureActive()
            val file=try { api.archive(receipt.id,receipt.revisionId,to,token) } catch(e: AccountFailure) {
                if(e.status!=401)throw e;val refreshed=session.access(true);check(session.userId()==user);coroutineContext.ensureActive();api.archive(receipt.id,receipt.revisionId,to,refreshed)
            }
            coroutineContext.ensureActive();check(file==to && session.userId()==user);return file
        } catch(e: Exception) { to.delete();throw e }
    }
    suspend fun read(path: String): JSONObject {
        val user=checkNotNull(session.userId()); val token=session.access(false); check(session.userId()==user)
        val response=try { api.read(path,token) } catch(e: AccountFailure) {
            if(e.status!=401)throw e; val refreshed=session.access(true); check(session.userId()==user); coroutineContext.ensureActive(); api.read(path,refreshed)
        }; check(session.userId()==user); require(response.toString().toByteArray().size<=65536); return response
    }
}
