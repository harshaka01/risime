package lk.codegen.risime.ui.chat

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import lk.codegen.risime.data.MessageStatus
import lk.codegen.risime.data.db.MediaEntity
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.db.MediaState
import lk.codegen.risime.data.media.uploadFailureText
import lk.codegen.risime.ui.theme.Spacing

/** What tapping an image bubble does. */
enum class ImageTap { OPEN, DOWNLOAD, NONE }

/** The one-line state under/over an image (§14.7 texts); null when the photo just shows. */
fun imageStatusText(row: MediaEntity?): String? {
    val state = row?.state?.let { s -> MediaState.entries.firstOrNull { it.name == s } } ?: return "Photo"
    return when (state) {
        MediaState.ENCRYPTED, MediaState.UPLOADING -> "Sending photo…"
        MediaState.FAILED -> uploadFailureText(row.failReason)
        MediaState.NONE -> "Tap to download"
        MediaState.DOWNLOADING -> "Downloading…"
        MediaState.GONE -> "This photo is no longer available"
        MediaState.CORRUPT -> "Couldn't open this photo"
        MediaState.UPLOADED, MediaState.CACHED -> null
    }
}

fun imageTap(row: MediaEntity?): ImageTap = when (row?.state) {
    MediaState.CACHED.name, MediaState.UPLOADED.name, MediaState.ENCRYPTED.name, MediaState.UPLOADING.name ->
        if (row.fileName != null) ImageTap.OPEN else ImageTap.DOWNLOAD
    MediaState.NONE.name -> ImageTap.DOWNLOAD
    MediaState.FAILED.name -> if (row.fileName != null) ImageTap.OPEN else ImageTap.NONE
    else -> ImageTap.NONE
}

fun imageTapLabel(t: ImageTap): String? = when (t) {
    ImageTap.OPEN -> "Open photo"
    ImageTap.DOWNLOAD -> "Download photo"
    ImageTap.NONE -> null
}

/** The sender's side of a photo going out (§14.7 Sending 6–7), shown on its bubble. */
sealed interface PhotoSend {
    /** Encrypted and stored; the upload job hasn't started (or waits for a network). */
    data object Encrypted : PhotoSend

    /** The upload runs; [fraction] 0..1 from the request body (null before the first byte). */
    data class Uploading(val fraction: Float?) : PhotoSend

    /** An attempt failed on the network / 5xx / 429 / 507: retried automatically with backoff (Retry runs it now). */
    data object Waiting : PhotoSend

    /** Uploaded; the outbox sends the envelope. */
    data object Sending : PhotoSend

    /** A refusal no automatic retry fixes, or the send failed: Retry / Delete. */
    data class Failed(val text: String) : PhotoSend
}

const val PHOTO_SEND_FAILED = "Couldn't send photo"
const val PHOTO_RETRY = "Retry"

fun photoSendState(m: MessageEntity, row: MediaEntity?, progress: Float?): PhotoSend? {
    if (!m.outgoing || !m.image) return null
    val failed = m.status == MessageStatus.FAILED.name
    return when {
        row?.state == MediaState.FAILED.name -> PhotoSend.Failed(uploadFailureText(row.failReason))
        failed -> PhotoSend.Failed(uploadFailureText(m.failReason ?: row?.failReason))
        row?.state == MediaState.UPLOADING.name -> PhotoSend.Uploading(progress)
        row?.state == MediaState.ENCRYPTED.name -> when {
            progress != null -> PhotoSend.Uploading(progress)
            row.attempts > 0 -> PhotoSend.Waiting
            else -> PhotoSend.Encrypted
        }
        m.status == MessageStatus.PENDING.name && row != null -> PhotoSend.Sending
        else -> null
    }
}

fun photoSendText(s: PhotoSend): String = when (s) {
    PhotoSend.Encrypted -> "Encrypted · waiting to upload"
    is PhotoSend.Uploading -> s.fraction?.let { "Uploading ${(it * 100).toInt()}%" } ?: "Uploading…"
    PhotoSend.Waiting -> "Upload interrupted · retrying"
    PhotoSend.Sending -> "Sending…"
    is PhotoSend.Failed -> s.text
}

/** The bubble's aspect ratio from the envelope size, clamped so a panorama or a strip stays tappable. */
fun bubbleAspect(w: Int, h: Int): Float = (w.toFloat() / h.coerceAtLeast(1)).coerceIn(0.5f, 2.5f)

/**
 * The photo inside a bubble: the (blurred) thumbnail first, the decrypted image once it's here,
 * a spinner while sending or downloading and the state text over the bottom edge. Stateless:
 * [ImageBubbleContent] loads.
 */
@Composable
fun ImageBox(
    w: Int,
    h: Int,
    thumb: ImageBitmap?,
    full: ImageBitmap?,
    status: String?,
    busy: Boolean,
    modifier: Modifier = Modifier,
    /** Determinate upload progress 0..1 (a ring that fills) instead of the spinner. */
    progress: Float? = null,
    /** A tap target on the state line ("Couldn't send photo · Retry"). */
    action: Pair<String, () -> Unit>? = null,
) {
    Box(
        modifier.widthIn(max = 260.dp).fillMaxWidth().aspectRatio(bubbleAspect(w, h)).heightIn(min = 96.dp)
            .clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when {
            full != null -> Image(full, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            thumb != null -> Image(
                thumb, null,
                Modifier.fillMaxSize().then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(6.dp) else Modifier),
                contentScale = ContentScale.Crop,
            )
        }
        if (progress != null) {
            CircularProgressIndicator(
                progress = { progress }, modifier = Modifier.size(36.dp), color = Color.White, trackColor = Color.Black.copy(alpha = 0.3f),
            )
        } else if (busy) {
            CircularProgressIndicator(Modifier.size(36.dp), color = Color.White, trackColor = Color.Black.copy(alpha = 0.3f))
        }
        if (status != null) {
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.55f))
                    .padding(start = Spacing.sm, end = if (action != null) Spacing.xxs else Spacing.sm, top = Spacing.xxs, bottom = Spacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    status,
                    Modifier.weight(1f),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                action?.let { (label, onClick) ->
                    TextButton(onClick = onClick) { Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
    }
}

/**
 * Loads the thumbnail and (when cached) the full image for one bubble; asks for a download when
 * visible. [send] is the sender's progress / failure ([photoSendState]); [onRetry] the Retry tap
 * target of a failed or waiting photo (null: not offered).
 */
@Composable
fun ImageBubbleContent(
    row: MediaEntity?,
    loader: ImageLoader?,
    onVisible: (String) -> Unit,
    send: PhotoSend? = null,
    onRetry: (() -> Unit)? = null,
) {
    val slotPx = with(LocalDensity.current) { 260.dp.roundToPx() }
    var thumb by remember(row?.clientMsgId) { mutableStateOf(row?.clientMsgId?.let { loader?.cachedThumb(it) }) }
    var full by remember(row?.clientMsgId) { mutableStateOf(row?.clientMsgId?.let { loader?.cached(it, slotPx) }) }
    LaunchedEffect(row?.clientMsgId, row?.state, row?.fileName) {
        if (row == null || loader == null) return@LaunchedEffect
        if (thumb == null) thumb = loader.thumb(row)
        if (row.fileName != null && full == null) {
            (loader.image(row.clientMsgId, slotPx) as? ImageLoad.Ok)?.let { full = it.bitmap }
        } else if (row.fileName == null && (row.state == MediaState.NONE.name || row.state == MediaState.DOWNLOADING.name)) {
            onVisible(row.clientMsgId)
        }
    }
    val status = send?.let(::photoSendText) ?: imageStatusText(row)
    ImageBox(
        w = row?.w ?: 4, h = row?.h ?: 3,
        thumb = thumb?.asImageBitmap(), full = full?.asImageBitmap(),
        status = status,
        busy = when (send) {
            null -> row?.state in setOf(MediaState.ENCRYPTED.name, MediaState.UPLOADING.name, MediaState.DOWNLOADING.name)
            PhotoSend.Encrypted, PhotoSend.Sending, is PhotoSend.Uploading -> true
            PhotoSend.Waiting, is PhotoSend.Failed -> false
        },
        progress = (send as? PhotoSend.Uploading)?.fraction,
        action = onRetry?.takeIf { send is PhotoSend.Failed || send == PhotoSend.Waiting }?.let { PHOTO_RETRY to it },
    )
}

/**
 * Full screen (§14.7): pinch to zoom (1×–5×), drag when zoomed, double-tap toggles 2×; the
 * caption under it; Save to gallery (a user action only).
 */
@Composable
fun ImageViewer(
    bitmap: ImageBitmap?,
    caption: String?,
    status: String?,
    canSave: Boolean,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ImageViewerContent(bitmap, caption, status, canSave, onSave, onClose)
    }
}

@Composable
fun ImageViewerContent(
    bitmap: ImageBitmap?,
    caption: String?,
    status: String?,
    canSave: Boolean,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (bitmap != null) {
            Image(
                bitmap,
                caption?.let { "Photo: $it" } ?: "Photo",
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            scale = if (scale > 1f) 1f else 2f
                            offset = Offset.Zero
                        })
                    }
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(status ?: "Loading…", Modifier.align(Alignment.Center).padding(Spacing.lg), color = Color.White)
        }
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().background(Color.Black.copy(alpha = 0.4f)).padding(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
            Spacer(Modifier.weight(1f))
            if (canSave && bitmap != null) TextButton(onClick = onSave) { Text("Save", color = Color.White) }
        }
        if (!caption.isNullOrBlank()) {
            Text(
                caption,
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.55f))
                    .navigationBarsPadding().padding(Spacing.md),
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/** Attach flow states (§14.7 Sending): preparing (re-encode + encrypt in-process), ready to send, or refused. */
sealed interface AttachState {
    /** Re-encoding (metadata stripped, ≤ 2048 px). */
    data object Preparing : AttachState

    /** Encrypting into the blob file (the key never leaves the device unencrypted). */
    data object Encrypting : AttachState

    class Ready(val thumb: Bitmap?, val w: Int, val h: Int) : AttachState

    data class Error(val message: String) : AttachState
}

/**
 * The attach sheet: the re-encoded thumbnail, a caption and Send / Cancel. [notice] is the group
 * "Some members need to update to see photos" line (§14.1).
 */
@Composable
fun AttachSheet(
    state: AttachState,
    caption: String,
    onCaption: (String) -> Unit,
    notice: String?,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    Dialog(onDismissRequest = onCancel) {
        AttachSheetContent(state, caption, onCaption, notice, onSend, onCancel)
    }
}

@Composable
fun AttachSheetContent(
    state: AttachState,
    caption: String,
    onCaption: (String) -> Unit,
    notice: String?,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
        Column(Modifier.padding(Spacing.lg).width(300.dp), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text("Send photo", style = MaterialTheme.typography.titleMedium)
            when (state) {
                AttachState.Preparing, AttachState.Encrypting -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.size(Spacing.sm))
                    Text(if (state == AttachState.Encrypting) "Encrypting photo…" else "Preparing photo…")
                }
                is AttachState.Ready -> ImageBox(state.w, state.h, state.thumb?.asImageBitmap(), null, null, busy = false)
                is AttachState.Error -> Text(state.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { contentDescription = state.message })
            }
            notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state is AttachState.Ready) {
                androidx.compose.material3.OutlinedTextField(
                    value = caption, onValueChange = onCaption, placeholder = { Text("Add a caption") },
                    modifier = Modifier.fillMaxWidth(), maxLines = 4,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                TextButton(onClick = onSend, enabled = state is AttachState.Ready && !lk.codegen.risime.data.BodyLimits.of(caption, { it.length }).tooLong) { Text("Send") }
            }
        }
    }
}

/** §14.1 DM attach gate text: null = photos can be sent. */
fun dmImagesBlockedText(encrypted: Boolean, imagesReady: Boolean?, missingIsMe: Boolean, peerName: String): String? = when {
    !encrypted -> "Couldn't send: this chat isn't end-to-end encrypted yet."
    imagesReady == false && missingIsMe -> "Your other phone needs to update to receive photos"
    imagesReady == false -> "$peerName needs to update the app to receive photos"
    else -> null
}

const val GROUP_IMAGES_NOTICE = "Some members need to update to see photos"
