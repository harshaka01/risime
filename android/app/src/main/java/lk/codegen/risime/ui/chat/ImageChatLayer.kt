package lk.codegen.risime.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lk.codegen.risime.data.db.MessageEntity

/**
 * The dialogs of a chat's photos (viewer, attach sheet) and their activity launchers (the system
 * Photo Picker; the Save dialog on API 26–28). Returns the "pick a photo" action for the composer.
 */
@Composable
fun rememberImageLayer(imgs: ImageActions, messages: List<MessageEntity>, groupNotice: String? = null): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewerId by imgs.viewer.collectAsStateWithLifecycle()
    val attach by imgs.attach.collectAsStateWithLifecycle()
    val media by imgs.media.collectAsStateWithLifecycle()
    val toast by imgs.toast.collectAsStateWithLifecycle()
    var pendingSave by remember { mutableStateOf<ByteArray?>(null) }

    // No storage permission anywhere: the Photo Picker (ACTION_OPEN_DOCUMENT fallback on old phones).
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> imgs.onPicked(uri) }
    val saveDialog = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        val bytes = pendingSave
        pendingSave = null
        if (uri != null && bytes != null) imgs.toast.value = if (GallerySaver.saveToUri(context, uri, bytes)) "Photo saved" else "Couldn't save the photo"
    }

    LaunchedEffect(toast) {
        if (toast != null) {
            delay(4_000)
            imgs.toast.value = null
        }
    }

    viewerId?.let { id ->
        val m = messages.firstOrNull { it.clientMsgId == id }
        var bmp by remember(id) { mutableStateOf<android.graphics.Bitmap?>(null) }
        var status by remember(id) { mutableStateOf<String?>(null) }
        LaunchedEffect(id) {
            when (val r = imgs.loader?.image(id, 2048)) {
                is ImageLoad.Ok -> bmp = r.bitmap
                ImageLoad.Failed -> status = "Couldn't open this photo"
                else -> status = imageStatusText(media[id]) ?: "Couldn't open this photo"
            }
        }
        ImageViewer(
            bitmap = bmp?.asImageBitmap(),
            caption = m?.body?.takeIf { it.isNotBlank() },
            status = status,
            canSave = true,
            onSave = {
                scope.launch {
                    val bytes = imgs.bytesForSave(id)
                    when {
                        bytes == null -> imgs.toast.value = "Couldn't save the photo"
                        GallerySaver.mediaStoreWithoutPermission ->
                            imgs.toast.value = if (GallerySaver.saveToMediaStore(context, bytes) != null) "Saved to Pictures/RisiMe" else "Couldn't save the photo"
                        else -> {
                            pendingSave = bytes
                            saveDialog.launch(GallerySaver.displayName())
                        }
                    }
                }
            },
            onClose = imgs::closeViewer,
        )
    }

    attach?.let { a ->
        AttachSheet(
            state = a.state,
            caption = a.caption,
            onCaption = imgs::onCaption,
            notice = groupNotice,
            onSend = imgs::sendAttach,
            onCancel = imgs::cancelAttach,
        )
    }

    return { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}
