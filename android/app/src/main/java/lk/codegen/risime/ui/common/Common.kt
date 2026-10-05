package lk.codegen.risime.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lk.codegen.risime.ui.theme.OnlineGreen

const val DEV_BANNER_TEXT = "Dev build — not end-to-end encrypted"

/** Mandatory until E2EE (MLS) ships in 0.3. Never remove before then. */
@Composable
fun DevEncryptionBanner(modifier: Modifier = Modifier) {
    Text(
        text = DEV_BANNER_TEXT,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        style = MaterialTheme.typography.labelMedium,
        textAlign = TextAlign.Center,
    )
}

/** Initials avatar for a contact, with a presence dot when [online]. */
@Composable
fun InitialsAvatar(name: String, enabled: Boolean = true, size: Dp = 44.dp, online: Boolean = false) {
    Box {
        InitialsCircle(name, enabled, size)
        if (online) PresenceDot(Modifier.align(Alignment.BottomEnd), size)
    }
}

/** Green-teal dot with a surface ring; TalkBack reads "online". */
@Composable
fun PresenceDot(modifier: Modifier = Modifier, avatarSize: Dp = 44.dp) {
    val d = (avatarSize.value * 0.3f).coerceAtLeast(10f).dp
    Box(
        modifier.size(d).clip(CircleShape).background(MaterialTheme.colorScheme.surface).padding(2.dp)
            .clip(CircleShape).background(OnlineGreen)
            .semantics { contentDescription = "online" },
    )
}

@Composable
private fun InitialsCircle(name: String, enabled: Boolean, size: Dp) {
    val initials = name.split(' ', '-', '.').filter { it.isNotBlank() }.take(2)
        .joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
    val bg = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.outline
    Box(
        Modifier.size(size).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, color = fg, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.38f).sp)
    }
}

/** Small clock glyph for `pending`. */
@Composable
fun PendingClock(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(13.dp).semantics { contentDescription = "Pending" }) {
        val r = size.minDimension / 2 - 1.dp.toPx()
        val c = center
        val w = 1.3.dp.toPx()
        drawCircle(color, radius = r, center = c, style = Stroke(width = w))
        drawLine(color, c, Offset(c.x, c.y - r * 0.6f), strokeWidth = w)
        drawLine(color, c, Offset(c.x + r * 0.45f, c.y), strokeWidth = w)
    }
}
