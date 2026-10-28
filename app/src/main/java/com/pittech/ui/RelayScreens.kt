package com.pittech.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.pittech.CooksViewModel
import com.pittech.data.RelayOwnedShare
import com.pittech.data.RelayProtocol
import com.pittech.data.RelayState
import java.time.Instant

@Composable private fun RelayProgress(state: RelayState, cancel: () -> Unit) {
    if (state.busy) {
        Text(state.label, style = PitTechTextStyles.monoMeta, color = pitTechTokens().inkMuted)
        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
        TextButton(cancel, Modifier.heightIn(min = 48.dp)) { Text("Cancel request") }
    }
    state.message?.let { TurnInStatusStrip(it, pitTechTokens().cobalt, Modifier.testTag("relay-message")) }
}

@Composable internal fun RelayImportDialog(viewModel: CooksViewModel) {
    val state by viewModel.relay.state.collectAsStateWithLifecycle()
    if (!state.showingImport) return
    Dialog(onDismissRequest = viewModel.relay::closeImport, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = pitTechTokens().ground) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("Import by code", style = PitTechTextStyles.bannerTitle)
                Text("Enter the code from a card or paste a PitTech share link. Preview the pack and choose individual dishes before saving.", color = pitTechTokens().inkMuted)
                TurnInLiteralBoxedField("Cook Pack code or link", Modifier.fillMaxWidth()) {
                    OutlinedTextField(state.input, viewModel.relay::input, modifier = Modifier.fillMaxWidth().testTag("relay-code-input"), singleLine = true)
                }
                Button(viewModel.relay::download, enabled = !state.busy && state.input.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("relay-download")) { Text("Download & preview") }
                Text("No account required. Nothing is added to your recipes or history until you choose to save it.", style = MaterialTheme.typography.bodySmall, color = pitTechTokens().inkMuted)
                RelayProgress(state, viewModel.relay::cancel)
                TextButton(viewModel.relay::closeImport, Modifier.heightIn(min = 48.dp)) { Text("Done") }
            }
        }
    }
}

@Composable internal fun RelayShareDialog(id: String, includeHistory: Boolean, viewModel: CooksViewModel, onClose: () -> Unit) {
    val state by viewModel.relay.state.collectAsStateWithLifecycle()
    var title by remember(id) { mutableStateOf("") }
    var summary by remember(id) { mutableStateOf<List<String>>(emptyList()) }
    var revision by remember(id) { mutableLongStateOf(0) }
    var card by remember { mutableStateOf(false) }
    LaunchedEffect(id, state.shares) {
        viewModel.relay.refresh()
        val row = viewModel.sharing.get(id) ?: return@LaunchedEffect
        title = row.title; revision = row.updatedAtUtcMillis
        summary = com.pittech.domain.SharingDraftCodec.decode(row.payload).entries.filter { it.included }.map { "${it.snapshot.name} · ${it.photoIds.size} selected photos · " + if (it.includeStory) "Recorded story included" else "Instructions only" }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = pitTechTokens().ground) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("Share by code", style = PitTechTextStyles.bannerTitle)
                Text(title, style = PitTechTextStyles.bannerCard)
                Text("Review the selected pack", style = PitTechTextStyles.monoMeta, color = pitTechTokens().inkMuted)
                summary.forEach { Text(it) }
                Text(if (includeHistory) "Includes this recorded meal; recipients can choose whether to add it to history." else "Recipients can save selected recipes without importing your meal history.")
                Text("Anyone with the code can download this selected pack for 180 days. It is not listed publicly. Reading the code does not renew it. Renewal and revocation are controlled from this device.", color = pitTechTokens().inkMuted)
                Button({ viewModel.relay.upload(id, includeHistory) }, enabled = !state.busy && summary.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("relay-upload")) { Text("Upload selected pack") }
                if (viewModel.relay.card(id, revision) != null) {
                    OutlinedButton({ card = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Preview card with this code") }
                    Text("This code refers to a frozen upload. Later recipe edits do not change its contents.", style = MaterialTheme.typography.bodySmall, color = pitTechTokens().inkMuted)
                }
                RelayProgress(state, viewModel.relay::cancel)
                state.shares.filter { it.document == id }.forEach { OwnedRelayRow(it, state.busy, viewModel) }
                TextButton(onClose, Modifier.heightIn(min = 48.dp)) { Text("Back to share a file or image") }
            }
        }
    }
    if (card) ShareCardPreview(id, viewModel, onPack = { card = false; onClose(); viewModel.createShareCookPack(id, includeHistory) }, onClose = { card = false }, relay = viewModel.relay.card(id, revision), onProThemes = { viewModel.subscription.openPro(com.pittech.billing.ProPlacement.THEMES) })
}

@Composable internal fun SharedRelayCodesDialog(viewModel: CooksViewModel, onClose: () -> Unit) {
    val state by viewModel.relay.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.relay.refresh() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = pitTechTokens().ground) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("My shared codes", style = PitTechTextStyles.bannerTitle)
                Text("Your management capability stays on this device. Revocation stops new downloads; recipients keep copies already saved.", color = pitTechTokens().inkMuted)
                RelayProgress(state, viewModel.relay::cancel)
                if (state.shares.isEmpty()) TurnInDashedCard(Modifier.fillMaxWidth()) { Text(EmptyStateCopy.SHARED_CODES_EMPTY) }
                state.shares.forEach { OwnedRelayRow(it, state.busy, viewModel) }
                TextButton(onClose, Modifier.heightIn(min = 48.dp)) { Text("Done") }
            }
        }
    }
}

@Composable private fun OwnedRelayRow(value: RelayOwnedShare, busy: Boolean, viewModel: CooksViewModel) {
    val context = LocalContext.current
    var confirming by remember(value.operation) { mutableStateOf(false) }
    var showingQR by remember(value.operation) { mutableStateOf(false) }
    TurnInCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(value.title, style = PitTechTextStyles.bannerCard)
            val receipt = value.receipt
            if (receipt == null) {
                Text("Upload not confirmed. Retry the saved bytes to recover the same code if the server already accepted them.")
                Button({ viewModel.relay.retry(value) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry saved upload") }
            } else {
                SelectionContainer { Text(receipt.code, style = PitTechTextStyles.monoReadout, modifier = Modifier.testTag("relay-owned-code")) }
                Text(if (value.revoked) "Revoked" else if (receipt.expired) "Expired" else "Available until ${Instant.parse(receipt.expiresAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()}", style = PitTechTextStyles.monoMeta, color = if (value.revoked || receipt.expired) pitTechTokens().flag else pitTechTokens().inkMuted)
                if (!value.revoked && !receipt.expired) {
                    TextButton({ showingQR = !showingQR }, Modifier.heightIn(min = 48.dp)) { Text(if (showingQR) "Hide QR code" else "Show QR code") }
                    if (showingQR) {
                        val bitmap = remember(receipt.code) {
                            val matrix = QRCodeWriter().encode(RelayProtocol.link(receipt.code), BarcodeFormat.QR_CODE, 512, 512)
                            Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply { setPixels(IntArray(512 * 512) { i -> if (matrix[i % 512, i / 512]) Color.BLACK else Color.WHITE }, 0, 512, 0, 0, 512, 512) }
                        }
                        Image(bitmap.asImageBitmap(), "QR code for this Cook Pack", Modifier.size(240.dp))
                    }
                    TextButton({ context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Cook Pack code", receipt.code)) }, Modifier.heightIn(min = 48.dp)) { Text("Copy code") }
                    TextButton({ context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, RelayProtocol.link(receipt.code)), "Share Cook Pack link")) }, Modifier.heightIn(min = 48.dp)) { Text("Share link") }
                    TextButton({ viewModel.relay.renew(value) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Renew for 180 days") }
                    TextButton({ confirming = true }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Revoke code") }
                }
            }
        }
    }
    if (confirming) AlertDialog(onDismissRequest = { confirming = false }, title = { Text("Stop new downloads for this code?") }, text = { Text("Copies already saved by recipients remain available to them.") }, confirmButton = { TextButton({ confirming = false; viewModel.relay.revoke(value) }) { Text("Revoke code") } }, dismissButton = { TextButton({ confirming = false }) { Text("Cancel") } })
}
