package com.pittech.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.pittech.data.PitTechDataTransfer
import com.pittech.domain.RecipeSnapshotCodec

@Composable internal fun CookPackImportDialog(source: PitTechDataTransfer.ImportDraft, saving: Boolean, error: String?, onCancel: () -> Unit, onSave: (Set<String>) -> Unit, onAddHistory: () -> Unit, historyAdded: Boolean = false) {
    var selected by remember(source) { mutableStateOf(source.snapshot.recipes.map { it.currentRevisionId }.toSet()) }
    // PL-22 (DD-6): choosing what to save from a Cook Pack is a multi-item
    // selection — full screen.
    FullScreenSurface(onDismiss = { if (!saving) onCancel() }, title = "Cook Pack", dismissEnabled = !saving, actions = {
        TextButton(onClick = onCancel, enabled = !saving) { Text("Cancel") }
        TextButton(onClick = { onSave(selected) }, enabled = selected.isNotEmpty() && !saving, modifier = Modifier.testTag("pack-save-recipes")) { Text("Save ${selected.size} ${if (selected.size == 1) "recipe" else "recipes"}" + if (source.publicCookOnSave) " & set up meal" else "") }
    }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Choose what to save", style = PitTechTextStyles.bannerCard)
            Text("Save either dish or the whole collection. Your copies can be edited and cooked offline.", color = pitTechTokens().inkMuted)
            source.snapshot.recipes.forEach { recipe ->
                val revision = source.snapshot.recipeRevisions.first { it.id == recipe.currentRevisionId }
                val snapshot = remember(revision.id) { RecipeSnapshotCodec.decode(revision.snapshotJson) }
                val photo = source.snapshot.recipeAssets.firstOrNull { it.recipeRevisionId == revision.id }
                TurnInCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().toggleable(selected.contains(revision.id), enabled = !saving, role = Role.Checkbox) { checked -> selected = if (checked) selected + revision.id else selected - revision.id }.testTag("pack-select-${revision.id}"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        photo?.let { asset -> source.attachments[asset.id]?.let { data ->
                            val thumbnail = remember(asset.id) {
                                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                                var sample = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 384) sample *= 2
                                BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                            }
                            thumbnail?.let { Image(it, contentDescription = snapshot.name, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(MaterialTheme.shapes.small)) }
                        } }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(snapshot.name, style = PitTechTextStyles.bannerCard)
                            Text(listOf(snapshot.dish.foodType, snapshot.dish.cut).filter { it.isNotBlank() }.joinToString(" · "), style = PitTechTextStyles.monoMeta, color = pitTechTokens().inkMuted)
                            if (snapshot.description.isNotBlank()) Text(snapshot.description, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                            if (snapshot.observations.isNotEmpty()) Text("Recorded cook guide included", style = PitTechTextStyles.monoMeta, color = pitTechTokens().emberText)
                        }
                        Checkbox(selected.contains(revision.id), onCheckedChange = null)
                    }
                }
            }
            if (source.cookPack?.optString("kind") == "cook") {
                Text("Add all ${source.snapshot.dishes.size} recorded dishes in this file. Recipe selections above apply when saving recipes.", style = MaterialTheme.typography.bodySmall, color = pitTechTokens().inkMuted)
                if (historyAdded) {
                    // PL-19: the dialog stays open after adding history —
                    // the recipe selection above is still the user's to save.
                    OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().testTag("pack-add-history")) { Text("Added to history") }
                    Text("The recorded meal is in your history. Your recipe choices above are unchanged — save them whenever you're ready.", style = MaterialTheme.typography.bodySmall, color = pitTechTokens().inkMuted, modifier = Modifier.testTag("pack-history-added"))
                } else {
                    OutlinedButton(onClick = onAddHistory, enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("pack-add-history")) { Text("Add recorded meal to history") }
                }
            }
            Text("Guides stay separate from your own cooking history. Reminders are yours to choose when you cook.", style = MaterialTheme.typography.bodySmall, color = pitTechTokens().inkMuted)
            if (saving) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { CircularProgressIndicator(Modifier.size(24.dp)); Text(LoadingCopy.SAVING) }
            error?.let { TurnInStatusStrip(it, pitTechTokens().flag) }
        }
    }
}
