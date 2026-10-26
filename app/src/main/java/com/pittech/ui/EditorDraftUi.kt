package com.pittech.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pittech.domain.EditorDraftStore
import com.pittech.domain.SharedPreferencesEditorDraftStore

/**
 * PL-24 (#172): every multi-field editor autosaves its working state to
 * the device-local draft store and restores it on open. The store is
 * obtained the same way everywhere so the pattern stays one pattern.
 */
@Composable
internal fun rememberEditorDraftStore(): EditorDraftStore {
    val context = LocalContext.current
    return remember(context) { SharedPreferencesEditorDraftStore(context) }
}

/**
 * The visible "Resume …" affordance (PL-24): shown inside an editor
 * whose fields were restored from a kept draft, so the restore is never
 * silent. Discarding drops the stored draft and resets the editor to
 * the values it was opened with.
 */
@Composable
internal fun ResumeDraftBanner(onDiscard: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Resume draft", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Your unsaved changes were kept on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDiscard, modifier = Modifier.testTag("editor-draft-discard")) {
                    Text("Discard draft")
                }
            }
        }
    }
}
