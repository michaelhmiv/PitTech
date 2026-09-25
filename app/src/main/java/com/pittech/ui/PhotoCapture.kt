package com.pittech.ui

import android.content.ActivityNotFoundException
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a photo") },
        text = { Text("Take a new photo or choose one from your library.") },
        confirmButton = {
            Column {
                TextButton(onClick = onTakePhoto, modifier = Modifier.testTag("photo-source-camera")) {
                    Text("Take a photo")
                }
                TextButton(onClick = onChooseFromLibrary, modifier = Modifier.testTag("photo-source-library")) {
                    Text("Choose from library")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("photo-source-cancel")) {
                Text("Cancel")
            }
        },
    )
}

/** Opens the system camera and returns the full-sized image saved to a temporary content URI. */
@Composable
internal fun rememberCameraPhotoCapture(onPhotoCaptured: (Uri) -> Unit): () -> Unit {
    val context = LocalContext.current
    val currentOnPhotoCaptured by rememberUpdatedState(onPhotoCaptured)
    var pendingOutputPath by rememberSaveable { mutableStateOf<String?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val file = pendingOutputPath?.let(::File)
        pendingOutputPath = null
        if (captured && file != null && file.isFile && file.length() > 0L) {
            currentOnPhotoCaptured(CameraPhotoFiles.uri(context, file))
        } else {
            file?.delete()
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
        }
    }
}