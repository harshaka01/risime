package lk.codegen.risime.ui.common

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lk.codegen.risime.data.media.AndroidBitmapOps
import lk.codegen.risime.data.media.ImageBytes
import lk.codegen.risime.data.profile.AvatarEncoder
import lk.codegen.risime.data.profile.CropSquare
import lk.codegen.risime.ui.theme.Spacing

/** v1.32 item 6: the photo sheet's rows (profile photo and group photo). */
const val PHOTO_SHEET_TAKE = "Take photo"
const val PHOTO_SHEET_GALLERY = "Gallery"
const val PHOTO_SHEET_REMOVE = "Remove photo"

/** The rows the sheet shows: Remove photo only when a photo is set. */
fun photoSheetRows(hasPhoto: Boolean): List<String> = listOfNotNull(PHOTO_SHEET_TAKE, PHOTO_SHEET_GALLERY, PHOTO_SHEET_REMOVE.takeIf { hasPhoto })

/** The bottom sheet: Take photo, Gallery, and Remove photo (only when [hasPhoto]). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PhotoSheet(hasPhoto: Boolean, onTake: () -> Unit, onGallery: () -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().testTag("photo_sheet")) {
            photoSheetRows(hasPhoto).forEach { label ->
                Text(
                    label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    color = if (label == PHOTO_SHEET_REMOVE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .clickable { when (label) { PHOTO_SHEET_TAKE -> onTake(); PHOTO_SHEET_GALLERY -> onGallery(); else -> onRemove() } }
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md).testTag("photo_sheet_" + label.substringBefore(' ').lowercase()),
                )
            }
        }
    }
}

/**
 * §18.6 + v1.32 item 6: the photo sheet (Take photo / Gallery / Remove photo), then a square crop the user
 * sees (pinch to zoom, drag to move, a circle shows what peers will see). Take photo is
 * `ActivityResultContracts.TakePicture` into our FileProvider cache; Gallery the Photo Picker. [onCropped] gets
 * the picked bytes and the square; the caller re-encodes them ([AvatarEncoder]) off the main thread.
 * Returns the "open the sheet" action.
 */
@Composable
fun rememberPhotoCropper(
    title: String,
    onCropped: (source: ByteArray, crop: CropSquare) -> Unit,
    onError: (String) -> Unit = {},
    hasPhoto: Boolean = false,
    onRemove: (() -> Unit)? = null,
): () -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<Pair<ByteArray, ImageBitmap>?>(null) }
    var sheet by remember { mutableStateOf(false) }
    fun show(read: () -> ByteArray?) {
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = read() ?: return@runCatching null
                    if (bytes.size > AvatarEncoder.MAX_SOURCE_BYTES) return@runCatching null
                    val d = AndroidBitmapOps.decode(bytes, PREVIEW_SIDE) ?: return@runCatching null
                    val o = if (d.orientationApplied) 1 else ImageBytes.exifOrientation(bytes)
                    val shown = AndroidBitmapOps.transform(d.bitmap, o, PREVIEW_SIDE)
                    d.bitmap.recycle()
                    bytes to shown.asImageBitmap()
                }.getOrNull()
            }
            if (r == null) onError("This photo format isn't supported on this phone") else picked = r
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) show { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
    }
    val shot = remember { java.io.File(java.io.File(ctx.cacheDir, "camera").also { it.mkdirs() }, "avatar.jpg") }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) show { runCatching { shot.readBytes() }.getOrNull().also { shot.delete() } } else shot.delete()
    }
    if (sheet) {
        PhotoSheet(
            hasPhoto,
            onTake = {
                sheet = false
                runCatching { camera.launch(androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", shot)) }
                    .onFailure { onError("No camera app can take a photo on this phone") }
            },
            onGallery = { sheet = false; launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onRemove = { sheet = false; onRemove?.invoke() },
            onDismiss = { sheet = false },
        )
    }
    picked?.let { (bytes, img) ->
        CropDialog(img, title, onCancel = { picked = null }, onUse = { crop ->
            picked = null
            onCropped(bytes, crop)
        })
    }
    return { sheet = true }
}

private const val PREVIEW_SIDE = 1440

/** The crop screen: a square viewport over the photo, pan and pinch, a circular guide. */
@Composable
fun CropDialog(image: ImageBitmap, title: String, onCancel: () -> Unit, onUse: (CropSquare) -> Unit) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
            Column(Modifier.fillMaxSize().safeDrawingPadding(), verticalArrangement = Arrangement.SpaceBetween) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(Spacing.lg))
                val w = image.width.toFloat()
                val h = image.height.toFloat()
                var zoom by remember { mutableFloatStateOf(1f) }
                var off by remember { mutableStateOf<Offset?>(null) }
                var view by remember { mutableFloatStateOf(1f) }
                BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                    val v = constraints.maxWidth.toFloat()
                    view = v
                    val base = v / minOf(w, h)
                    fun clamp(o: Offset, s: Float) = Offset(o.x.coerceIn(v - w * s, 0f), o.y.coerceIn(v - h * s, 0f))
                    if (off == null) off = Offset((v - w * base) / 2f, (v - h * base) / 2f)
                    Canvas(
                        Modifier.fillMaxSize().clipToBounds().pointerInput(image) {
                            detectTransformGestures { centroid, pan, z, _ ->
                                val old = base * zoom
                                val nz = (zoom * z).coerceIn(1f, MAX_ZOOM)
                                val ns = base * nz
                                val o = off ?: Offset.Zero
                                off = clamp(centroid - (centroid - o) * (ns / old) + pan, ns)
                                zoom = nz
                            }
                        },
                    ) {
                        val s = base * zoom
                        val o = off ?: Offset.Zero
                        drawImage(image, dstOffset = IntOffset(o.x.toInt(), o.y.toInt()), dstSize = IntSize((w * s).toInt(), (h * s).toInt()))
                        val p = Path().apply {
                            fillType = PathFillType.EvenOdd
                            addRect(Rect(0f, 0f, size.width, size.height))
                            addOval(Rect(0f, 0f, size.width, size.height))
                        }
                        drawPath(p, Color.Black.copy(alpha = 0.55f))
                        drawCircle(Color.White.copy(alpha = 0.8f), radius = size.width / 2f - 1f, style = Stroke(2f))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(Spacing.lg), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onCancel) { Text("Cancel", color = Color.White) }
                    Button(onClick = {
                        val s = (view / minOf(w, h)) * zoom
                        val o = off ?: Offset.Zero
                        onUse(CropSquare(left = (-o.x / s) / w, top = (-o.y / s) / h, side = (view / s) / minOf(w, h)))
                    }) { Text("Use photo", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

private const val MAX_ZOOM = 5f
