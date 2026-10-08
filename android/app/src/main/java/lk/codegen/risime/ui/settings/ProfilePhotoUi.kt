package lk.codegen.risime.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lk.codegen.risime.RisiMeApp
import lk.codegen.risime.data.media.AndroidBitmapOps
import lk.codegen.risime.data.media.ImageRejected
import lk.codegen.risime.data.profile.AvatarEncoder
import lk.codegen.risime.data.profile.PhotoChange
import lk.codegen.risime.data.profile.ProfilePhotos
import lk.codegen.risime.ui.common.rememberPhotoCropper
import lk.codegen.risime.ui.theme.Spacing

/** §18.0 / §18.6 the small print under the photo controls. */
const val PROFILE_PHOTO_AUDIENCE = "Your photo is visible to your friends and people in your groups."

/**
 * §18.6 Settings → Profile: Set / Change / Remove photo (system Photo Picker, a square crop the
 * user sees, re-encoded without metadata, encrypted, then sent to every e2ee chat). Shown only with
 * the photo store (an app with the MLS core); otherwise nothing.
 */
@Composable
fun ProfilePhotoControls(userId: String) {
    val ctx = LocalContext.current
    val c = (ctx.applicationContext as? RisiMeApp)?.container ?: return
    if (c.mediaCrypto == null) return
    val photos: ProfilePhotos = c.profilePhotos
    val scope = rememberCoroutineScope()
    val rev by photos.revision.collectAsState()
    var has by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(rev, userId) { has = photos.hasPhoto(userId) }
    val pick = rememberPhotoCropper("Move and scale", onCropped = { source, crop ->
        message = null
        busy = "Setting your photo…"
        scope.launch {
            val r = withContext(Dispatchers.Default) {
                try {
                    val jpeg = AvatarEncoder(AndroidBitmapOps).encode(source, crop)
                    photos.setOwnPhoto(jpeg.bytes, jpeg.side)
                } catch (e: ImageRejected) {
                    PhotoChange.Refused(e.message ?: "Couldn't use this photo")
                } catch (e: Exception) {
                    PhotoChange.Refused(ProfilePhotos.GENERIC)
                }
            }
            busy = null
            message = (r as? PhotoChange.Refused)?.text
        }
    }, onError = { message = it })
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedButton(onClick = pick, enabled = busy == null) { Text(if (has) "Change photo" else "Set photo") }
            if (has) {
                TextButton(onClick = {
                    message = null
                    busy = "Removing your photo…"
                    scope.launch {
                        val r = photos.removeOwnPhoto()
                        busy = null
                        message = (r as? PhotoChange.Refused)?.text
                    }
                }, enabled = busy == null) { Text("Remove photo") }
            }
        }
        (busy ?: message)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (busy == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(PROFILE_PHOTO_AUDIENCE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
