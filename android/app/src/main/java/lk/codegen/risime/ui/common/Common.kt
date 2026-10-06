package lk.codegen.risime.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lk.codegen.risime.data.MessageStatus
import lk.codegen.risime.ui.theme.RisiShapes
import lk.codegen.risime.ui.theme.RisiTheme
import lk.codegen.risime.ui.theme.Sizes
import lk.codegen.risime.ui.theme.Spacing

/*
 * RisiMe component library (design pass 0.2). Screens compose these instead of styling Material
 * widgets ad hoc. Colours come from MaterialTheme / RisiTheme tokens only.
 */

// ---- Avatar and presence ----

/** Initials avatar, with a presence dot when [online]. Decorative for TalkBack except the dot. */
@Composable
fun InitialsAvatar(name: String, enabled: Boolean = true, size: Dp = Sizes.avatar, online: Boolean = false) {
    Box {
        val initials = name.split(' ', '-', '.').filter { it.isNotBlank() }.take(2)
            .joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
        val bg = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        val fg = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer else RisiTheme.colors.textMuted
        Box(
            Modifier.size(size).clip(CircleShape).background(bg).clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            Text(initials, color = fg, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.38f).sp)
        }
        if (online) PresenceDot(Modifier.align(Alignment.BottomEnd), size)
    }
}

/** Presence dot with a surface ring so it reads on any avatar; TalkBack: "online". */
@Composable
fun PresenceDot(modifier: Modifier = Modifier, avatarSize: Dp = Sizes.avatar) {
    val d = (avatarSize.value * 0.3f).coerceAtLeast(10f).dp
    Box(
        modifier.size(d).clip(CircleShape).background(MaterialTheme.colorScheme.surface).padding(Spacing.xxs)
            .clip(CircleShape).background(RisiTheme.colors.online)
            .semantics { contentDescription = "online" },
    )
}

// ---- Top bar ----

/**
 * App bar: optional back button, optional avatar, title and a one-line subtitle. [emphasis]
 * colours the subtitle (e.g. "typing…").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RisiTopBar(
    title: String,
    subtitle: String? = null,
    emphasis: Boolean = false,
    onBack: (() -> Unit)? = null,
    avatar: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                avatar?.let {
                    it()
                    Spacer(Modifier.width(Spacing.md))
                }
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() })
                    if (!subtitle.isNullOrEmpty()) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (emphasis) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

// ---- Lists ----

/** Standard row: leading slot, title + trailing meta, subtitle + trailing badge. ≥ 72 dp tall. */
@Composable
fun ListRow(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    meta: String? = null,
    strong: Boolean = false,
    enabled: Boolean = true,
    subtitleColor: Color? = null,
    badge: (@Composable () -> Unit)? = null,
    footer: String? = null,
    onClick: (() -> Unit)? = null,
    /** §15.7: the chat list's long-press (Clear chat / Delete chat). */
    onLongClick: (() -> Unit)? = null,
) {
    val titleColor = if (enabled) MaterialTheme.colorScheme.onSurface else RisiTheme.colors.textMuted
    Row(
        modifier.fillMaxWidth().heightIn(min = Sizes.listRowMin)
            .then(
                when {
                    onLongClick != null -> Modifier.combinedClickable(onClick = { if (enabled) onClick?.invoke() }, onLongClick = onLongClick, onLongClickLabel = "Chat options")
                    onClick != null -> Modifier.clickable(enabled = enabled, onClick = onClick)
                    else -> Modifier
                },
            )
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let {
            it()
            Spacer(Modifier.width(Spacing.md + Spacing.xxs))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = titleColor, fontWeight = if (strong) FontWeight.ExtraBold else FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                meta?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall,
                        color = if (strong) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (strong) FontWeight.Bold else null)
                }
            }
            if (subtitle != null || badge != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(subtitle.orEmpty(), style = MaterialTheme.typography.bodyMedium,
                        color = subtitleColor ?: when {
                            !enabled -> RisiTheme.colors.textMuted
                            strong -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = if (strong) FontWeight.SemiBold else null,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    badge?.invoke()
                }
            }
            footer?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
fun UnreadBadge(n: Int, modifier: Modifier = Modifier) {
    Badge(
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier.padding(start = Spacing.sm).clearAndSetSemantics { contentDescription = "$n unread" },
    ) { Text(if (n > 99) "99+" else n.toString()) }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

// ---- Empty and error states ----

@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = Spacing.xl, vertical = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge)
        if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

/** Inline error line with an optional retry; announced by TalkBack when it appears. */
@Composable
fun ErrorState(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Warning, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(Spacing.sm))
        Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        if (onRetry != null) TextButton(onClick = onRetry) { Text("Retry") }
    }
}

// ---- Messages ----

/** Day separator chip in a chat. */
@Composable
fun DaySeparator(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = Spacing.xs + Spacing.xxs), contentAlignment = Alignment.Center) {
        Surface(color = RisiTheme.colors.daySeparator, shape = RisiShapes.pill) {
            Text(label, Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs).semantics { heading() },
                style = MaterialTheme.typography.labelMedium, color = RisiTheme.colors.onDaySeparator)
        }
    }
}

/**
 * Chat bubble. Mine: right, bubbleMine; theirs: left, bubbleTheirs. TalkBack reads one merged
 * node ("You: hi, 14:05, Read"). Long-press (and tap when [tapOpensMenu]) opens [menu].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    body: String,
    time: String,
    mine: Boolean,
    status: MessageStatus?,
    note: String? = null,
    tapOpensMenu: Boolean = false,
    onMenu: () -> Unit,
    menu: @Composable () -> Unit = {},
    /** Under the bubble (reaction chips). */
    footer: @Composable () -> Unit = {},
    /** §12 groups: the sender's name above an incoming bubble (first of a run), in [senderColor]. */
    sender: String? = null,
    senderColor: Color = Color.Unspecified,
    /** §14: the photo above the caption; [onTap] (open, download, retry) with [tapLabel] for TalkBack. */
    image: (@Composable () -> Unit)? = null,
    onTap: (() -> Unit)? = null,
    tapLabel: String? = null,
    /** §15.7 select mode: this bubble is selected (row highlighted). */
    selected: Boolean = false,
    /** §15.6 a tombstone: italic, muted text. */
    muted: Boolean = false,
    /** A non-error note (e.g. "Couldn't verify a delete for this message"). */
    noteIsInfo: Boolean = false,
    /** §14: the photo's state line ("Uploading 42%", "Couldn't send photo"), read by TalkBack with the bubble. */
    imageStatus: String? = null,
    /** §14: the photo's tap target ("Retry"), also a TalkBack action of the merged bubble. */
    imageAction: Pair<String, () -> Unit>? = null,
) {
    val c = RisiTheme.colors
    val statusLabel = status?.let { tickLabel(it) }
    Box(
        Modifier.fillMaxWidth().then(if (selected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)) else Modifier),
        contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        menu()
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Surface(
            color = if (mine) c.bubbleMine else c.bubbleTheirs,
            contentColor = if (mine) c.onBubbleMine else c.onBubbleTheirs,
            shape = if (mine) RisiShapes.mine else RisiShapes.theirs,
            modifier = Modifier.widthIn(max = Sizes.bubbleMaxWidth)
                .minimumInteractiveComponentSize()
                .combinedClickable(
                    onClickLabel = if (tapOpensMenu) "Show options" else tapLabel,
                    onLongClickLabel = "Message options",
                    onClick = { if (tapOpensMenu) onMenu() else onTap?.invoke() },
                    onLongClick = onMenu,
                )
                .clearAndSetSemantics {
                    contentDescription = buildString {
                        append(if (mine) "You: " else sender?.let { "$it: " } ?: "")
                        append(if (image == null) body else if (body.isBlank()) "Photo" else "Photo, $body")
                        imageStatus?.let { append(", ").append(it) }
                        append(", ").append(time)
                        statusLabel?.let { append(", ").append(it) }
                        note?.let { append(". ").append(it) }
                        if (selected) append(", selected")
                    }
                    imageAction?.let { (label, run) ->
                        customActions = listOf(androidx.compose.ui.semantics.CustomAccessibilityAction(label) { run(); true })
                    }
                },
        ) {
            Column(Modifier.padding(horizontal = if (image != null) Spacing.xs else Spacing.md, vertical = if (image != null) Spacing.xs else Spacing.sm)) {
                sender?.let { Text(it, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = senderColor, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                image?.invoke()
                if (image == null || body.isNotBlank()) {
                    Text(
                        body,
                        style = if (muted) MaterialTheme.typography.bodyLarge.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic) else MaterialTheme.typography.bodyLarge,
                        color = if (muted) androidx.compose.material3.LocalContentColor.current.copy(alpha = 0.7f) else Color.Unspecified,
                        modifier = if (image != null) Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs) else Modifier,
                    )
                }
                note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = if (noteIsInfo) androidx.compose.material3.LocalContentColor.current.copy(alpha = 0.7f) else MaterialTheme.colorScheme.error) }
                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    Text(time, style = MaterialTheme.typography.labelSmall, color = if (mine) c.bubbleMineMeta else c.bubbleTheirsMeta)
                    if (status != null) {
                        Spacer(Modifier.size(Spacing.xs))
                        MessageTicks(status)
                    }
                }
            }
        }
        footer()
        }
    }
}

fun tickLabel(status: MessageStatus): String = when (status) {
    MessageStatus.PENDING -> "Pending"
    MessageStatus.SENT -> "Sent"
    MessageStatus.DELIVERED -> "Delivered"
    MessageStatus.READ -> "Read"
    MessageStatus.FAILED -> "Not sent"
}

/** PROTOCOL.md §3: pending clock, ✓ sent, ✓✓ delivered, ✓✓ saffron for read. */
@Composable
fun MessageTicks(status: MessageStatus) {
    val muted = RisiTheme.colors.bubbleMineMeta
    val label = tickLabel(status)
    when (status) {
        MessageStatus.PENDING -> PendingClock(muted, label)
        MessageStatus.SENT -> Icon(Icons.Default.Check, label, Modifier.size(Sizes.tick), tint = muted)
        MessageStatus.DELIVERED -> DoubleCheck(muted, label)
        MessageStatus.READ -> DoubleCheck(RisiTheme.colors.readTick, label)
        MessageStatus.FAILED -> Icon(Icons.Default.Warning, label, Modifier.size(Sizes.tick), tint = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun DoubleCheck(tint: Color, label: String) {
    Box(Modifier.size(width = 20.dp, height = Sizes.tick).semantics { contentDescription = label }) {
        Icon(Icons.Default.Check, null, Modifier.size(Sizes.tick), tint = tint)
        Icon(Icons.Default.Check, null, Modifier.size(Sizes.tick).offset(x = 5.dp), tint = tint)
    }
}

/** Small clock glyph for `pending`. */
@Composable
fun PendingClock(color: Color, label: String = "Pending", modifier: Modifier = Modifier) {
    Canvas(modifier.size(13.dp).semantics { contentDescription = label }) {
        val r = size.minDimension / 2 - 1.dp.toPx()
        val c = center
        val w = 1.3.dp.toPx()
        drawCircle(color, radius = r, center = c, style = Stroke(width = w))
        drawLine(color, c, Offset(c.x, c.y - r * 0.6f), strokeWidth = w)
        drawLine(color, c, Offset(c.x + r * 0.45f, c.y), strokeWidth = w)
    }
}

/** §12 system line ("Kamal added Nimal"): centred, muted, no bubble, no actions. */
@Composable
fun SystemLineText(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = Spacing.xxs), contentAlignment = Alignment.Center) {
        Surface(color = RisiTheme.colors.daySeparator, contentColor = RisiTheme.colors.onDaySeparator, shape = MaterialTheme.shapes.small) {
            Text(text, style = MaterialTheme.typography.labelMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs))
        }
    }
}

/** A stable per-member name colour, readable on the incoming bubble in both themes. */
@Composable
fun memberColor(userId: String): Color {
    val dark = RisiTheme.colors.bubbleTheirs.luminance() < 0.5f
    val palette = if (dark) MEMBER_COLORS_DARK else MEMBER_COLORS_LIGHT
    return palette[(userId.lowercase().hashCode() and 0x7fffffff) % palette.size]
}

private val MEMBER_COLORS_LIGHT = listOf(Color(0xFF0B6E69), Color(0xFF9C4A00), Color(0xFF6A3FA0), Color(0xFF1F5FAD), Color(0xFFA0306A), Color(0xFF3C6E1F))
private val MEMBER_COLORS_DARK = listOf(Color(0xFF6FD6CF), Color(0xFFFFB873), Color(0xFFCDB0FF), Color(0xFF9CC3FF), Color(0xFFFF9CC9), Color(0xFFA9DB86))
