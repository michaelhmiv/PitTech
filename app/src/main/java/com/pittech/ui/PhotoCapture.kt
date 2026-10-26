package com.pittech.ui

import android.content.ActivityNotFoundException
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.pittech.data.CameraPhotoFiles
import java.io.File

@Composable
internal fun PhotoSourceDialog(
    onDismiss: () -> Unit,
    onTakePhoto: () -> Unit,
    onChooseFromLibrary: () -> Unit,
) {
    // PL-22 (DD-6): picking a photo source is contextual capture — a sheet.
    // TURN-IN gap disposition (§5.12.1): no frame exists for this chooser;
    // it is styled from the §4 placard language under its parent frames —
    // two large tappable rows, camera first. Flagged in the Slice 1 PR.
    SheetSurface(
        onDismiss = onDismiss,
        title = "Add a photo",
        actions = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("photo-source-cancel")) {
                Text("Cancel")
            }
        },
    ) {
        Text("Take a new photo or choose one from your library.")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onTakePhoto, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("photo-source-camera")) {
                Text("Take a photo")
            }
            OutlinedButton(onClick = onChooseFromLibrary, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("photo-source-library")) {
                Text("Choose from library")
            }
        }
    }
}

/** Opens the system camera and returns the full-sized image saved to a temporary content URI. */
@Composable
internal fun rememberCameraPhotoCapture(onPhotoCaptured: (Uri) -> Unit, onCaptureCancelled: () -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val currentOnPhotoCaptured by rememberUpdatedState(onPhotoCaptured)
    val currentOnCaptureCancelled by rememberUpdatedState(onCaptureCancelled)
    var pendingOutputPath by rememberSaveable { mutableStateOf<String?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val file = pendingOutputPath?.let(::File)
        pendingOutputPath = null
        if (captured && file != null && file.isFile && file.length() > 0L) {
            currentOnPhotoCaptured(CameraPhotoFiles.uri(context, file))
        } else {
            file?.delete()
            currentOnCaptureCancelled()
        }
    }

    return {
        val output = CameraPhotoFiles.create(context)
        pendingOutputPath = output.absolutePath
        try {
            cameraLauncher.launch(CameraPhotoFiles.uri(context, output))
        } catch (unavailable: ActivityNotFoundException) {
            pendingOutputPath = null
            output.delete()
            Toast.makeText(context, "No camera app is available.", Toast.LENGTH_SHORT).show()
            currentOnCaptureCancelled()
        }
    }
}
