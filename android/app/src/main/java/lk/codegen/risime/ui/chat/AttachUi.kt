package lk.codegen.risime.ui.chat

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lk.codegen.risime.ui.common.RisiIcons
import lk.codegen.risime.ui.theme.Spacing
import java.io.File

/**
 * What the "+" sheet offers (WhatsApp order). Gallery and Camera work; the rest answer "Coming soon".
 * Photos only go in end-to-end encrypted chats (the button that opens the sheet is gated like before).
 */
enum class AttachOption(val label: String, val wired: Boolean) {
    GALLERY("Gallery", true),
    CAMERA("Camera", true),
    DOCUMENT("Document", false),
    LOCATION("Location", false),
    CONTACT("Contact", false),
    ;

    companion object {
        /** The tiles shown: all five; Camera only where a camera app exists. */
        fun visible(canCamera: Boolean): List<AttachOption> = entries.filter { it != CAMERA || canCamera }
    }
}

const val ATTACH_COMING_SOON = "Coming soon"

/** The "+" attachment sheet (WhatsApp-style round tiles in a grid). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachOptionsSheet(options: List<AttachOption>, onPick: (AttachOption) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) { AttachOptionsContent(options, onPick) }
}

@Composable
fun AttachOptionsContent(options: List<AttachOption>, onPick: (AttachOption) -> Unit) {
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = Spacing.xl, end = Spacing.xl, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        options.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { o ->
                    val (icon, tint) = when (o) {
                        AttachOption.GALLERY -> RisiIcons.Photo to Color(0xFF6D4AE0)
                        AttachOption.CAMERA -> RisiIcons.Camera to Color(0xFFD6336C)
                        AttachOption.DOCUMENT -> RisiIcons.Document to Color(0xFF3F6FD9)
                        AttachOption.LOCATION -> RisiIcons.Location to Color(0xFF1E9E6A)
                        AttachOption.CONTACT -> RisiIcons.Contact to Color(0xFF1C8FB5)
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        AttachTile(o.label, icon, tint, dimmed = !o.wired) { onPick(o) }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun AttachTile(label: String, icon: ImageVector, tint: Color, dimmed: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).semantics(mergeDescendants = true) { role = Role.Button }
            .padding(Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(if (dimmed) tint.copy(alpha = 0.55f) else tint), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(28.dp), tint = Color.White)
        }
        Spacer(Modifier.size(Spacing.xs + Spacing.xxs))
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** The camera capture: a cache file shared with the camera app through our FileProvider (no CAMERA permission). */
object CameraCapture {
    fun available(context: Context): Boolean =
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).resolveActivity(context.packageManager) != null

    fun newUri(context: Context): Uri {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        // A capture is deleted once sent or dropped; anything older than a day was orphaned by a kill.
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() }
        val file = File(dir, "capture-${java.util.UUID.randomUUID()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }
}

/** A downsampled preview of a picked photo (display only; what's sent is re-encoded by the pipeline). */
@Composable
fun rememberPreviewBitmap(uri: Uri, maxPx: Int = 1600): ImageBitmap? {
    val context = LocalContext.current
    val state = produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { runCatching { decodePreview(context, uri, maxPx) }.getOrNull()?.asImageBitmap() }
    }
    return state.value
}

private fun decodePreview(context: Context, uri: Uri, maxPx: Int): Bitmap? {
    val cr = context.contentResolver
    if (Build.VERSION.SDK_INT >= 28) {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, uri)) { decoder, info, _ ->
            val s = info.size
            val scale = maxOf(s.width, s.height).toFloat() / maxPx
            if (scale > 1f) decoder.setTargetSize((s.width / scale).toInt().coerceAtLeast(1), (s.height / scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
    return cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
}

/** The full-screen preview of the photos to send (a pager, a caption per photo, remove, Send). */
@Composable
fun PhotoPreview(
    photos: List<PickedPhoto>,
    notice: String?,
    onCaption: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        PhotoPreviewContent(photos, { rememberPreviewBitmap(it.uri) }, notice, onCaption, onRemove, onSend, onCancel)
    }
}

@Composable
fun PhotoPreviewContent(
    photos: List<PickedPhoto>,
    bitmap: @Composable (PickedPhoto) -> ImageBitmap?,
    notice: String?,
    onCaption: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    if (photos.isEmpty()) return
    val pager = rememberPagerState { photos.size }
    val scope = rememberCoroutineScope()
    val index = pager.currentPage.coerceIn(0, photos.lastIndex)
    val current = photos[index]
    // A removed photo shrinks the list: keep the pager on a valid page.
    LaunchedEffect(photos.size) { if (pager.currentPage > photos.lastIndex) pager.scrollToPage(photos.lastIndex) }
    val tooLong = photos.any { lk.codegen.risime.data.BodyLimits.of(it.caption, { s -> s.length }).tooLong }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { photos.getOrNull(it)?.id ?: it }) { page ->
            val p = photos.getOrNull(page) ?: return@HorizontalPager
            val bmp = bitmap(p)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (bmp != null) {
                    Image(bmp, "Photo ${page + 1} of ${photos.size}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                } else {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).statusBarsPadding().padding(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "Cancel", tint = Color.White) }
            Text(
                if (photos.size == 1) "Send photo" else "${index + 1} of ${photos.size}",
                Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.titleMedium,
            )
            IconButton(onClick = { onRemove(current.id) }) { Icon(Icons.Default.Delete, "Remove this photo", tint = Color.White) }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.45f))
                .navigationBarsPadding().imePadding().padding(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            notice?.let { Text(it, color = Color.White, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
            if (photos.size > 1) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs + Spacing.xxs)) {
                    itemsIndexed(photos, key = { _, p -> p.id }) { i, p ->
                        val b = bitmap(p)
                        val sel = i == index
                        Box(
                            Modifier.size(56.dp).clip(RoundedCornerShape(10.dp))
                                .then(if (sel) Modifier.border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), RoundedCornerShape(10.dp)) else Modifier)
                                .background(Color.DarkGray)
                                .clickable(onClickLabel = "Show") { scope.launch { pager.animateScrollToPage(i) } }
                                .clearAndSetSemantics { contentDescription = "Photo ${i + 1}"; selected = sel; role = Role.Tab },
                        ) {
                            if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(24.dp), color = Color(0xFF2A2B30), modifier = Modifier.weight(1f)) {
                    TextField(
                        value = current.caption,
                        onValueChange = { onCaption(current.id, it) },
                        placeholder = { Text("Add a caption…", color = Color.White.copy(alpha = 0.7f)) },
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Color.White,
                        ),
                    )
                }
                Spacer(Modifier.width(Spacing.sm))
                FilledIconButton(
                    onClick = onSend,
                    enabled = !tooLong,
                    shape = CircleShape,
                    modifier = Modifier.size(56.dp).semantics {
                        contentDescription = if (photos.size == 1) "Send photo" else "Send ${photos.size} photos"
                    },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, null)
                }
            }
            if (tooLong) Text("A caption is too long", color = Color(0xFFFFB4AB), style = MaterialTheme.typography.bodySmall)
        }
    }
}
