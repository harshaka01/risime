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
 * The dialogs of a chat's photos (viewer, the "+" sheet, the send preview) and their activity
 * launchers (the system Photo Picker for up to 10 photos, the camera app, the Save dialog on API
 * 26–28). Returns the "+" action for the composer (opens the attachment sheet).
 */
@Composable
fun rememberImageLayer(imgs: ImageActions, messages: List<MessageEntity>, groupNotice: String? = null): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewerId by imgs.viewer.collectAsStateWithLifecycle()
    val selection by imgs.selection.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf(false) }
    var cameraUri by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val canCamera = remember { runCatching { CameraCapture.available(context) }.getOrDefault(false) }
    val media by imgs.media.collectAsStateWithLifecycle()
    val toast by imgs.toast.collectAsStateWithLifecycle()
    var pendingSave by remember { mutableStateOf<ByteArray?>(null) }

    // No storage permission anywhere: the Photo Picker (ACTION_OPEN_DOCUMENT fallback on old phones).
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(ImageActions.MAX_PHOTOS)) { uris ->
        if (uris.size > ImageActions.MAX_PHOTOS) imgs.toast.value = "You can send up to ${ImageActions.MAX_PHOTOS} photos at a time"
        imgs.onPicked(uris)
    }
    // No CAMERA permission: the camera app writes into our FileProvider cache file.
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri?.let(android.net.Uri::parse)
        cameraUri = null
        if (uri != null) {
            if (ok) imgs.onPicked(listOf(uri), temp = true) else runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }
    fun capture() {
        runCatching {
            val uri = CameraCapture.newUri(context)
            cameraUri = uri.toString()
            camera.launch(uri)
        }.onFailure { imgs.toast.value = "Couldn't open the camera" }
    }
    // v1.18 declares CAMERA (video calls): Android then requires it for the camera app intent too.
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) capture() else imgs.toast.value = "Allow the camera to take photos"
    }
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

    if (sheet) {
        AttachOptionsSheet(
            options = AttachOption.visible(canCamera),
            onPick = { o ->
                sheet = false
                when (o) {
                    AttachOption.DOCUMENT, AttachOption.LOCATION, AttachOption.CONTACT -> imgs.toast.value = ATTACH_COMING_SOON
                    AttachOption.GALLERY -> picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    AttachOption.CAMERA -> if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        capture()
                    } else {
                        cameraPermission.launch(android.Manifest.permission.CAMERA)
                    }
                }
            },
            onDismiss = { sheet = false },
        )
    }

    selection?.let { photos ->
        PhotoPreview(
            photos = photos,
            notice = groupNotice,
            onCaption = imgs::onCaption,
            onRemove = imgs::remove,
            onSend = imgs::sendSelection,
            onCancel = imgs::cancelSelection,
        )
    }

    return { sheet = true }
}
