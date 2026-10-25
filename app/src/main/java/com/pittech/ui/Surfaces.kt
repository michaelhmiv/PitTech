package com.pittech.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * #172 Slice 10 (PL-22, DD-6): the two containers Android surfaces use now
 * that the grammar table is enforced. `AlertDialog` remains ONLY for
 * destructive guards, single-value entry, and the show-once token (the
 * DialogGrammarTest pins the surviving sites). Everything that used to be
 * a dialog-shaped editor, list, or whole-task flow renders through one of
 * these instead — the same shapes the earlier slices established
 * (CookResultsSheet for sheets, RelayImportDialog for full screens):
 *
 * - [SheetSurface]: contextual capture and short editors (note, temp,
 *   target, check-in, report), choosers, and list/management surfaces.
 * - [FullScreenSurface]: multi-field editors (dish, recipe, step,
 *   playbook, serve plan, alert rule) and whole-task flows.
 *
 * Both keep the migrated surface's title, content, and action row — only
 * the container changes, so behaviour, test tags, and copy are untouched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SheetSurface(
    onDismiss: () -> Unit,
    title: String? = null,
    dismissEnabled: Boolean = true,
    fillHeightFraction: Float? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = { if (dismissEnabled) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (fillHeightFraction != null) Modifier.fillMaxHeight(fillHeightFraction) else Modifier)
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleLarge)
            content()
            if (actions != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    content = actions,
                )
            }
        }
    }
}

/** The full-screen container for multi-field editors and whole-task flows
 * (DD-6). [content] gets the bounded region between the pinned title and
 * the pinned action row; scrollable content keeps its own scroll modifier,
 * exactly as it did inside the retired dialogs. */
@Composable
internal fun FullScreenSurface(
    onDismiss: () -> Unit,
    title: String,
    dismissEnabled: Boolean = true,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = { if (dismissEnabled) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Column(modifier = Modifier.weight(1f)) { content() }
                if (actions != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        content = actions,
                    )
                }
            }
        }
    }
}
