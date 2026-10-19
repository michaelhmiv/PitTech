package com.pittech.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.pittech.CooksViewModel
import com.pittech.data.prepareShareCard
import com.pittech.domain.SharingDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ShareCardOptionsEditor(document: SharingDocument, onChange: (SharingDocument) -> Unit) {
    val value = document.card
    val photos = document.entries.filter { it.included }.flatMap { entry -> entry.photoIds.mapIndexed { i,id -> id to "${entry.snapshot.name} · photo ${i+1}" } }
    Text("Share-card appearance", style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("photo" to "Photo", "chart" to "Recorded chart", "none" to "No hero").forEach { (key,label) ->
            FilterChip(value.hero == key, { onChange(document.copy(card = value.copy(hero = key), heroPhotoId = if (key == "photo") document.heroPhotoId?.takeIf { id -> photos.any { it.first == id } } ?: photos.firstOrNull()?.first else document.heroPhotoId)) }, { Text(label) }, enabled = key != "photo" || photos.isNotEmpty())
        }
    }
    if (value.hero == "photo") {
        ChoiceField("Cover photo", photos.firstOrNull { it.first == document.heroPhotoId }?.second ?: "Choose photo", photos.map { it.second }) { label -> onChange(document.copy(heroPhotoId = photos.first { it.second == label }.first)) }
        listOf("Horizontal crop" to value.cropX, "Vertical crop" to value.cropY, "Zoom" to value.zoom).forEachIndexed { i, (label,current) ->
            Text(label, style = MaterialTheme.typography.labelLarge)
            Slider(current.toFloat(), { n -> onChange(document.copy(card = when (i) { 0 -> value.copy(cropX = n.toDouble()); 1 -> value.copy(cropY = n.toDouble()); else -> value.copy(zoom = n.toDouble()) })) }, valueRange = if (i == 2) 1f..3f else 0f..1f)
        }
    }
    ChoiceField("Temperature unit", value.unit, listOf("auto", "°C", "°F")) { onChange(document.copy(card = value.copy(unit = it))) }
    OutlinedTextField(value.note, { input ->
        val n = input.codePointCount(0,input.length); val text = if (n > 2000) input.substring(0,input.offsetByCodePoints(0,2000)) else input
        onChange(document.copy(card = value.copy(note = text)))
    }, label = { Text("Card note (optional)") }, modifier = Modifier.fillMaxWidth().testTag("card-note"), maxLines = 4)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(value.includeVerdict, { onChange(document.copy(card = value.copy(includeVerdict = it))) }, enabled = document.entries.any { it.included && it.includeStory })
        Text("Include recorded verdict", modifier = Modifier.weight(1f))
    }
    Text("Only selected photos and enabled recorded stories appear. Full text and original measurements remain in the Cook Pack.", style = MaterialTheme.typography.bodySmall)
}

/** Handed-off URIs have an owner and a bounded cache lifetime; preview cancel creates no file. */
internal object ShareCardFiles {
    private const val TTL = 24 * 60 * 60 * 1000L
    fun prune(context: Context, now: Long = System.currentTimeMillis()) {
        val folder = File(context.cacheDir,"exports/share-cards")
        val files = folder.listFiles()?.filter { it.name.matches(Regex("[a-f0-9-]+\\.png")) }.orEmpty().sortedByDescending { it.lastModified() }
        files.forEachIndexed { i,file -> if (now - file.lastModified() > TTL || i >= 12) file.delete() }
    }
    fun create(context: Context, bytes: ByteArray): File {
        prune(context)
        return File(context.cacheDir,"exports/share-cards/${UUID.randomUUID()}.png").apply { parentFile!!.mkdirs(); outputStream().use { it.write(bytes) } }
    }
}

@Composable internal fun ShareCardPreview(id: String, viewModel: CooksViewModel, onPack: () -> Unit, onClose: () -> Unit, relay: com.pittech.data.ShareCardRelay? = null, onProThemes: (() -> Unit)? = null) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var bytes by remember(id) { mutableStateOf<ByteArray?>(null) }; var error by remember(id) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }; var working by remember { mutableStateOf(true) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) bytes?.let { image -> scope.launch { runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri,"w")?.use { it.write(image) } ?: kotlin.error("This image could not be saved.") } }.onFailure { error = it.message } } }
    }
    LaunchedEffect(id,retry,relay?.code) {
        working = true; error = null
        try { bytes = withContext(Dispatchers.Default) { ShareCardRenderer.render(viewModel.sharing.prepareShareCard(id, relay)) } }
        catch (e: Exception) { error = e.message }
        finally { working = false }
    }
    val image = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it,0,it.size) } }
    DisposableEffect(image) { onDispose { image?.recycle() } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(16.dp).heightIn(max = 850.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Share card", style = MaterialTheme.typography.titleLarge)
                if (working) CircularProgressIndicator()
                image?.let { Image(it.asImageBitmap(), contentDescription = "Exact exported card preview", modifier = Modifier.fillMaxWidth().aspectRatio(1080f/1350f).testTag("share-card-preview")) }
                Text("1080 × 1350 PNG · default theme", style = MaterialTheme.typography.bodySmall)
                if (onProThemes != null) ProEntryRow(com.pittech.billing.ProPlacement.THEMES) { onProThemes() }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error); OutlinedButton({ retry++ }) { Text("Retry image") } }
                Button(onClick = {
                    bytes?.let { value -> scope.launch {
                        runCatching {
                            val file = withContext(Dispatchers.IO) { ShareCardFiles.create(context,value) }
                            val uri = FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",file)
                            val intent = Intent(Intent.ACTION_SEND).apply { type = "image/png"; putExtra(Intent.EXTRA_STREAM,uri); clipData = ClipData.newUri(context.contentResolver,"PitTech card",uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                            context.startActivity(Intent.createChooser(intent,"Share PitTech card"))
                        }.onFailure { error = it.message }
                    } }
                }, enabled = bytes != null && !working, modifier = Modifier.fillMaxWidth().testTag("card-share")) { Text("Share image") }
                OutlinedButton({ export.launch("PitTech-card.png") }, enabled = bytes != null && !working, modifier = Modifier.fillMaxWidth().testTag("card-save")) { Text("Save PNG") }
                TextButton(onPack, Modifier.fillMaxWidth()) { Text("Share Cook Pack file") }
                TextButton(onClose, Modifier.fillMaxWidth().testTag("card-close")) { Text("Back to edit") }
            }
        }
    }
}
