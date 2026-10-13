package ai.risicloud.risime.ui.chat

import ai.risicloud.risime.net.LinkPreview
import ai.risicloud.risime.net.LinkUrls
import ai.risicloud.risime.ui.theme.Spacing
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/* v1.44 §39.4 the receiver's card and tappable links; PART 11 (d) swipe-to-reply. No agent: infrastructure. */

object LinkPreviewStrings {
    const val CARD = "Link preview"
    const val OPEN_LINK = "Open link"
    fun label(lp: LinkPreview) = "$CARD: ${lp.title}" + (lp.siteName?.let { ", $it" } ?: "")
}

/** §39.4 the body with its `http(s)://` and `www.` links tappable (opened by the system browser; nothing fetched). */
fun linkified(body: String, linkColor: Color): AnnotatedString {
    val spans = LinkUrls.find(body)
    if (spans.isEmpty()) return AnnotatedString(body)
    return buildAnnotatedString {
        var at = 0
        for (s in spans) {
            append(body.substring(at, s.start))
            withLink(LinkAnnotation.Url(s.target, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) { append(s.text) }
            at = s.end
        }
        append(body.substring(at))
    }
}

/** §39.4 the card, rendered from the message's `link_preview` only (a tap opens [LinkPreview.url]). */
@Composable
fun LinkPreviewCard(lp: LinkPreview, mine: Boolean) {
    val uri = LocalUriHandler.current
    val bmp = remember(lp.thumb) {
        lp.thumb?.let { t -> runCatching { android.graphics.BitmapFactory.decodeByteArray(t.data, 0, t.data.size)?.asImageBitmap() }.getOrNull() }
    }
    Surface(
        color = LocalContentColor.current.copy(alpha = if (mine) 0.08f else 0.06f),
        contentColor = LocalContentColor.current,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.xs).clip(RoundedCornerShape(8.dp))
            .clickable { runCatching { uri.openUri(lp.url) } }
            .testTag("link_preview_card"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            bmp?.let {
                Image(it, null, Modifier.size(72.dp), contentScale = ContentScale.Crop)
            }
            Column(Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs)) {
                lp.siteName?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.alpha(0.75f)) }
                Text(lp.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                lp.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.alpha(0.85f)) }
            }
        }
    }
}

/** PART 11 (d) how far a bubble may follow the finger, and where a release replies. */
object SwipeReply {
    val MAX = 96.dp
    val THRESHOLD = 64.dp

    /** A right-drag of [dx] (px) reaches the reply threshold [threshold] (px). */
    fun reached(dx: Float, threshold: Float): Boolean = dx >= threshold

    /** The bubble's offset while dragging: right only, capped (and a little resistance past the threshold). */
    fun follow(current: Float, delta: Float, threshold: Float, max: Float): Float {
        val d = if (current > threshold) delta * 0.4f else delta
        return (current + d).coerceIn(0f, max)
    }
}

/**
 * PART 11 (d) swipe right on a message to reply (§33.9 `reply_to`; no wire change): the bubble follows the finger,
 * a reply arrow fades in, one haptic tick at the threshold, release past it replies, then it snaps back.
 * [onReply] null: not replyable (the row is shown as is).
 */
@Composable
fun SwipeToReply(onReply: (() -> Unit)?, content: @Composable () -> Unit) {
    if (onReply == null) { content(); return }
    val density = LocalDensity.current
    val threshold = with(density) { SwipeReply.THRESHOLD.toPx() }
    val max = with(density) { SwipeReply.MAX.toPx() }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var armed by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxWidth()
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    val next = SwipeReply.follow(offset.value, delta, threshold, max)
                    scope.launch { offset.snapTo(next) }
                    val now = SwipeReply.reached(next, threshold)
                    if (now && !armed) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    armed = now
                },
                onDragStopped = {
                    if (armed) onReply()
                    armed = false
                    scope.launch { offset.animateTo(0f) }
                },
            )
            .testTag("swipe_reply"),
    ) {
        val progress = (offset.value / threshold).coerceIn(0f, 1f)
        if (progress > 0f) {
            Row(Modifier.align(Alignment.CenterStart).padding(start = Spacing.sm).alpha(progress)) {
                Icon(ai.risicloud.risime.ui.common.RisiIcons.Reply, MessagingStrings.REPLY, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(Spacing.xs))
            }
        }
        Box(Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }) { content() }
    }
}
