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

@Composable internal fun CookPackImportDialog(source: PitTechDataTransfer.ImportDraft, saving: Boolean, error: String?, onCancel: () -> Unit, onSave: (Set<String>) -> Unit) {
    var selected by remember(source) { mutableStateOf(source.snapshot.recipes.map { it.currentRevisionId }.toSet()) }
    AlertDialog(onDismissRequest = { if (!saving) onCancel() }, title = { Text("Cook Pack") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Choose what to save", style = MaterialTheme.typography.titleMedium)
            Text("Save either dish or the whole collection. Your copies can be edited and cooked offline.")
            source.snapshot.recipes.forEach { recipe ->
                val revision = source.snapshot.recipeRevisions.first { it.id == recipe.currentRevisionId }
                val snapshot = remember(revision.id) { RecipeSnapshotCodec.decode(revision.snapshotJson) }
                val photo = source.snapshot.recipeAssets.firstOrNull { it.recipeRevisionId == revision.id }
                Card {
                    Row(Modifier.fillMaxWidth().toggleable(selected.contains(revision.id), enabled = !saving, role = Role.Checkbox) { checked -> selected = if (checked) selected + revision.id else selected - revision.id }.testTag("pack-select-${revision.id}").padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        photo?.let { asset -> source.attachments[asset.id]?.let { data ->
                            val thumbnail = remember(asset.id) {
                                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                                var sample = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 384) sample *= 2
                                BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                            }
                            thumbnail?.let { Image(it, contentDescription = snapshot.name, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(MaterialTheme.shapes.small)) }
                        } }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(snapshot.name, style = MaterialTheme.typography.titleSmall)
                            Text(listOf(snapshot.dish.foodType, snapshot.dish.cut).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                            if (snapshot.description.isNotBlank()) Text(snapshot.description, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                            if (snapshot.observations.isNotEmpty()) Text("Recorded cook guide included", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Checkbox(selected.contains(revision.id), onCheckedChange = null)
                    }
                }
            }
            Text("Guides stay separate from your own cooking history. Reminders are yours to choose when you cook.", style = MaterialTheme.typography.bodySmall)
            if (saving) CircularProgressIndicator(Modifier.size(24.dp))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { onSave(selected) }, enabled = selected.isNotEmpty() && !saving, modifier = Modifier.testTag("pack-save-recipes")) { Text("Save ${selected.size} ${if (selected.size == 1) "recipe" else "recipes"}") } }, dismissButton = { TextButton(onClick = onCancel, enabled = !saving) { Text("Cancel") } })
}
