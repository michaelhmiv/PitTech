package com.pittech.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.coroutineContext

internal data class RelayState(val busy: Boolean = false, val progress: Float = 0f, val label: String = "", val message: String? = null,
    val shares: List<RelayOwnedShare> = emptyList(), val showingImport: Boolean = false, val input: String = "")

internal class RelayManager(private val context: Context, private val scope: CoroutineScope,
    private val prepare: suspend (String, Boolean, File) -> Pair<Long, String>, private val preview: suspend (File) -> Unit) {
    private val vault = RelayVault(context)
    private val client = RelayClient()
    private val _state = MutableStateFlow(RelayState())
    val state = _state.asStateFlow()
    private var job: Job? = null
    fun openImport(input: String = "") { _state.update { it.copy(showingImport = true, input = input, message = null) } }
    fun input(value: String) { _state.update { it.copy(input = value.take(256)) } }
    fun closeImport() { cancel(); _state.update { it.copy(showingImport = false) } }
    fun cancel() { job?.cancel() }
    fun refresh() { scope.launch(Dispatchers.IO) { reload() } }
    private fun reload() {
        try {
            val all = vault.all(); all.filter { it.receipt != null }.forEach { vault.pack(it).delete() }
            _state.update { it.copy(shares = all) }
        } catch (_: Exception) { _state.update { it.copy(message = "This device's saved sharing capabilities are unavailable. Existing public copies may still be accessible.") } }
    }
    private fun run(label: String, action: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, progress = 0f, label = label, message = null) }
        job = scope.launch(Dispatchers.IO) {
            try { action(); coroutineContext.ensureActive() }
            catch (e: CancellationException) { _state.update { it.copy(message = "Canceled. Your local draft and any saved upload retry are unchanged.") }; throw e }
            catch (e: Exception) { _state.update { it.copy(message = e.message ?: "This request could not be completed. Your local draft is unchanged.") } }
            finally { _state.update { it.copy(busy = false) }; reload() }
        }
    }
    private fun progress(done: Long, total: Long) { _state.update { it.copy(progress = if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f) } }
    fun upload(document: String, history: Boolean) = run("Preparing the selected Cook Pack") {
        val source = File.createTempFile("relay-prepare-", ".pittech", context.cacheDir)
        val value = try { val (revision, title) = prepare(document, history, source); coroutineContext.ensureActive(); vault.prepare(document, revision, title, source) } finally { source.delete() }
        coroutineContext.ensureActive(); send(value)
    }
    fun retry(value: RelayOwnedShare) = run("Retrying the same saved upload") { send(value) }
    private suspend fun send(value: RelayOwnedShare) {
        _state.update { it.copy(label = "Uploading the selected Cook Pack") }
        val receipt = client.upload(value, vault.pack(value), ::progress); coroutineContext.ensureActive()
        vault.finish(value, receipt)
        _state.update { it.copy(message = "Your code is ready. Anyone with it can access this selected pack.") }
    }
    fun download() = run("Downloading for preview") {
        val code = RelayProtocol.code(_state.value.input)
        val file = File.createTempFile("relay-download-", ".pittech", context.cacheDir)
        try { client.download(code, file, ::progress); coroutineContext.ensureActive(); preview(file); _state.update { it.copy(showingImport = false) } }
        finally { file.delete() }
    }
    fun renew(value: RelayOwnedShare) = run("Renewing this code") {
        val receipt = client.renew(value); coroutineContext.ensureActive(); vault.save(value.copy(receipt = receipt))
        _state.update { it.copy(message = "Renewed for 180 days. Reading a code never renews it.") }
    }
    fun revoke(value: RelayOwnedShare) = run("Revoking this code") {
        client.revoke(value); coroutineContext.ensureActive(); vault.save(value.copy(revoked = true))
        _state.update { it.copy(message = "Code revoked. Copies already saved by recipients remain theirs.") }
    }
    fun card(document: String, revision: Long): ShareCardRelay? = _state.value.shares.firstOrNull {
        it.document == document && it.revision == revision && !it.revoked && it.receipt?.expired == false
    }?.receipt?.let { ShareCardRelay(it.code, RelayProtocol.link(it.code)) }
}
