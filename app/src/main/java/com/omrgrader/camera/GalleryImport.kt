package com.omrgrader.camera

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import com.omrgrader.platform.AndroidImages

/** Gallery/SAF image import: reads the selected document into bytes for the OMR engine. */
@Composable
fun rememberGalleryImagePicker(
    context: Context,
    onImage: (ByteArray) -> Unit,
    onError: (String) -> Unit
): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            onImage(AndroidImages.readUri(context, uri))
        } catch (e: Exception) {
            onError(e.javaClass.simpleName)
        }
    }
    return { launcher.launch("image/*") }
}
