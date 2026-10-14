package ai.risicloud.risime.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ai.risicloud.risime.data.mls.E2EE_INFO_TEXT
import ai.risicloud.risime.ui.theme.Spacing

/*
 * Per-chat encryption state (decision 048, replaces the global dev banner): a lock in the header
 * and "Messages are end-to-end encrypted" in chat info for E2EE chats; for every other chat the
 * "Not end-to-end encrypted yet: <real reason>" strip under the header.
 */

const val E2EE_LOCK_DESCRIPTION = "End-to-end encrypted"
const val E2EE_STRIP_TAG = "e2ee_strip"

/** The lock in a chat header; shown only for an E2EE chat. [onClick] opens chat info. */
@Composable
fun E2eeHeaderLock(encrypted: Boolean, onClick: () -> Unit) {
    if (!encrypted) return
    IconButton(onClick = onClick) { Icon(Icons.Default.Lock, E2EE_LOCK_DESCRIPTION, Modifier.size(20.dp)) }
}

/** The "Not end-to-end encrypted yet: …" strip; nothing for an E2EE chat ([text] null). */
@Composable
fun E2eeStrip(text: String?) {
    if (text == null) return
    Text(
        text,
        Modifier.fillMaxWidth().testTag(E2EE_STRIP_TAG)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs)
            .semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The chat-info encryption line: the lock + "Messages are end-to-end encrypted", or the strip's reason. */
@Composable
fun E2eeInfoLine(encrypted: Boolean, notEncryptedText: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (encrypted) Icons.Default.Lock else Icons.Default.Info, null, Modifier.size(18.dp))
        Spacer(Modifier.width(Spacing.sm))
        Text(
            if (encrypted) E2EE_INFO_TEXT else notEncryptedText ?: "Checking encryption…",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** DM chat info (opened from the header): who, and the chat's real encryption state. */
@Composable
fun DmChatInfoDialog(name: String, encrypted: Boolean, notEncryptedText: String?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name) },
        text = { E2eeInfoLine(encrypted, notEncryptedText) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) } },
    )
}

/**
 * Item 6 (honesty): the Official marker, the lock with a small Risi badge (the Official accent dot). Official and
 * the Risi chat use it; Private keeps the plain lock. Never the plain lock for a conversation Risi can read.
 */
@Composable
fun OfficialLockIcon(size: Dp, tint: Color = androidx.compose.material3.LocalContentColor.current, modifier: Modifier = Modifier, contentDescription: String? = null) {
    val desc = contentDescription
    Box(modifier.size(size).then(if (desc != null) Modifier.semantics(mergeDescendants = true) { this.contentDescription = desc } else Modifier)) {
        Icon(Icons.Default.Lock, null, Modifier.size(size), tint = tint)
        Box(
            Modifier.align(Alignment.BottomEnd).size(size * 0.5f)
                .border(maxOf(size * 0.08f, 1.dp), MaterialTheme.colorScheme.surface, CircleShape)
                .background(ai.risicloud.risime.ui.tabs.OfficialAccent, CircleShape),
        )
    }
}

/** The icon of an [EncMarker] (nothing for [EncMarker.NONE]). */
@Composable
fun EncMarkerIcon(marker: EncMarker, size: Dp, tint: Color = androidx.compose.material3.LocalContentColor.current, modifier: Modifier = Modifier) {
    when (marker) {
        EncMarker.PRIVATE_LOCK -> Icon(Icons.Default.Lock, null, modifier.size(size), tint = tint)
        EncMarker.OFFICIAL_LOCK -> OfficialLockIcon(size, tint, modifier)
        EncMarker.NONE -> Unit
    }
}

const val OFFICIAL_ENCRYPTION_TAG = "official_encryption"

/**
 * Item 6: chat info's Official encryption explanation (both chat info and group info, in the Official section):
 * the Official lock, "Encrypted · Risi can read", and one or two plain sentences on what that means.
 */
@Composable
fun OfficialEncryptionInfo(dm: Boolean, modifier: Modifier = Modifier) {
    val label = encryptionLabel(if (dm) EncSurface.OFFICIAL_DM else EncSurface.OFFICIAL_GROUP, encrypted = true)
    Column(modifier.testTag(OFFICIAL_ENCRYPTION_TAG)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OfficialLockIcon(18.dp)
            Spacer(Modifier.width(Spacing.sm))
            Text(label.short.orEmpty(), style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            label.info.orEmpty(),
            Modifier.padding(top = Spacing.xxs),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
