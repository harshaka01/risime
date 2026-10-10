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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
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

/**
 * §18 photos for avatars: a user id (profile photo) or a `grp:` id (group icon) → a decoded,
 * display-sized bitmap held in memory only; null = initials. [revision] changes when any photo did.
 */
interface AvatarSource {
    val revision: kotlinx.coroutines.flow.StateFlow<Long>

    fun cached(key: String, sizePx: Int): androidx.compose.ui.graphics.ImageBitmap?

    suspend fun load(key: String, sizePx: Int): androidx.compose.ui.graphics.ImageBitmap?
}

/** Provided by the activities; null (tests, previews) = initials everywhere. */
val LocalAvatars = androidx.compose.runtime.staticCompositionLocalOf<AvatarSource?> { null }

/**
 * Initials avatar, with a presence dot when [online]. Decorative for TalkBack except the dot.
 * [photoKey] (a user id or a `grp:` id): the photo when this device has one (§18.2/§18.7), else initials.
 */
@Composable
fun InitialsAvatar(name: String, enabled: Boolean = true, size: Dp = Sizes.avatar, online: Boolean = false, photoKey: String? = null) {
    Box {
        val src = LocalAvatars.current
        val px = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }
        var photo by androidx.compose.runtime.remember(photoKey, px) {
            androidx.compose.runtime.mutableStateOf(if (photoKey != null && enabled) src?.cached(photoKey, px) else null)
        }
        if (src != null && photoKey != null && enabled) {
            val rev by src.revision.collectAsState()
            androidx.compose.runtime.LaunchedEffect(photoKey, px, rev) { photo = src.load(photoKey, px) }
        }
        val initials = name.split(' ', '-', '.').filter { it.isNotBlank() }.take(2)
            .joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
        val (bg, fg) = if (enabled) avatarColors(name) else MaterialTheme.colorScheme.surfaceVariant to RisiTheme.colors.textMuted
        val p = photo
        if (p != null) {
            androidx.compose.foundation.Image(
                p, null,
                Modifier.size(size).clip(CircleShape).background(bg).clearAndSetSemantics { },
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        } else {
            Box(
                Modifier.size(size).clip(CircleShape).background(bg).clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                Text(initials, color = fg, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.38f).sp)
            }
        }
        if (online) PresenceDot(Modifier.align(Alignment.BottomEnd), size)
    }
}

/** A stable (container, text) pair per name, from the brand's hues; ≥ 7:1 in both themes (AvatarColorsTest). */
@Composable
fun avatarColors(name: String): Pair<Color, Color> {
    val dark = RisiTheme.colors.surface.luminance() < 0.5f
    val list = if (dark) AVATAR_DARK else AVATAR_LIGHT
    return list[(name.lowercase().hashCode() and 0x7fffffff) % list.size]
}

val AVATAR_LIGHT = listOf(
    Color(0xFFD9E2FF) to Color(0xFF001849), Color(0xFFB8F5EE) to Color(0xFF00201D), Color(0xFFE9DDFF) to Color(0xFF22005D),
    Color(0xFFFFD8E8) to Color(0xFF3E001D), Color(0xFFFFDDB5) to Color(0xFF2B1700), Color(0xFFC4EFCB) to Color(0xFF00210B),
)
val AVATAR_DARK = listOf(
    Color(0xFF0040A3) to Color(0xFFD9E2FF), Color(0xFF00504A) to Color(0xFFB8F5EE), Color(0xFF4F2B9E) to Color(0xFFE9DDFF),
    Color(0xFF7A1F4C) to Color(0xFFFFD8E8), Color(0xFF6A4300) to Color(0xFFFFDDB5), Color(0xFF155226) to Color(0xFFC4EFCB),
)

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
    /** The app's home bar: the brand title (larger, in the primary colour). */
    brand: Boolean = false,
    /** Tapping the avatar + title opens chat/group info (WhatsApp-style), announced with [titleClickLabel]. */
    onTitleClick: (() -> Unit)? = null,
    titleClickLabel: String? = null,
    /** Always-visible tail of the title (e.g. " · Risi"): [title] ellipsizes first, the suffix never does. */
    titleSuffix: String? = null,
) {
    // P0 2026-10-10 (nightly.48 gate, "🔒 Risⁱ"): no Material TopAppBar any more. It clips its content to a fixed height,
    // and the 🔒 emoji's fallback-font metrics measured narrower and taller than they drew. This bar is a Surface + Row that
    // grows with its content (min 64 dp), the lock is an Icon, and the title uses the theme's normal line height.
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().semantics { testTagsAsResourceId = true }.testTag("risi_top_bar")) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(TopAppBarDefaults.windowInsets).heightIn(min = 64.dp).padding(horizontal = Spacing.xxs, vertical = Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } else Spacer(Modifier.width(Spacing.md - Spacing.xxs))
            Row(
                Modifier.weight(1f).then(
                    if (onTitleClick != null) {
                        Modifier.clip(RisiShapes.pill).clickable(onClickLabel = titleClickLabel, onClick = onTitleClick)
                    } else Modifier,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                avatar?.let {
                    it()
                    Spacer(Modifier.width(if (onBack != null) Spacing.sm + Spacing.xxs else Spacing.md))
                }
                Column(Modifier.padding(vertical = Spacing.xxs)) {
                    val titleStyle = if (brand) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium
                    val titleColor = if (brand) MaterialTheme.colorScheme.primary else Color.Unspecified
                    if (titleSuffix == null) {
                        Text(
                            title, style = titleStyle, color = titleColor, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { heading() }.testTag("risi_header_title"),
                        )
                    } else {
                        // The name ellipsizes first; the suffix (" · Risi") never clips.
                        Row(Modifier.semantics(mergeDescendants = true) { heading() }) {
                            Text(title, style = titleStyle, color = titleColor, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).alignByBaseline().testTag("risi_header_title"))
                            Text(titleSuffix, style = titleStyle, color = titleColor, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible, modifier = Modifier.alignByBaseline().padding(end = 2.dp).testTag("risi_header_suffix"))
                        }
                    }
                    if (!subtitle.isNullOrEmpty()) HeaderSubtitle(subtitle, emphasis)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

/** A header subtitle starting with "🔒 " is drawn as a lock Icon + the text (never the emoji). */
const val HEADER_LOCK_PREFIX = "🔒 "

/** (locked, text) of a header subtitle. */
fun headerSubtitleParts(subtitle: String): Pair<Boolean, String> =
    if (subtitle.startsWith(HEADER_LOCK_PREFIX)) true to subtitle.removePrefix(HEADER_LOCK_PREFIX) else false to subtitle

@Composable
private fun HeaderSubtitle(subtitle: String, emphasis: Boolean) {
    val (locked, text) = headerSubtitleParts(subtitle)
    val color = if (emphasis) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.testTag("risi_header_subtitle"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (locked) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = color, modifier = Modifier.size(13.dp).testTag("risi_header_lock"))
            Spacer(Modifier.width(3.dp))
        }
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("risi_header_subtitle_text"))
    }
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
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs + 1.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = titleColor, style = MaterialTheme.typography.titleMedium, fontWeight = if (strong) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                meta?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = Spacing.sm),
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
    Box(
        modifier.padding(start = Spacing.sm).heightIn(min = 22.dp).widthIn(min = 22.dp).clip(RisiShapes.pill)
            .background(MaterialTheme.colorScheme.primary).padding(horizontal = 6.dp)
            .clearAndSetSemantics { contentDescription = "$n unread" },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (n > 99) "99+" else n.toString(), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        if (onRetry != null) TextButton(onClick = onRetry) { Text("Retry", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
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
 * Chat bubble (WhatsApp-style). Mine: right, bubbleMine; theirs: left, bubbleTheirs; at most
 * [Sizes.bubbleMaxFraction] of the chat's width. The first bubble of a run has a [tail] at its top
 * corner. The time and ticks sit inside the bubble, bottom-right, on the last text line when they
 * fit. TalkBack reads one merged node ("You: hi, 14:05, Read"). Long-press (and tap when
 * [tapOpensMenu]) opens [menu].
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
    /** The first bubble of a run (a new sender or side): the tail and a little more space above. */
    tail: Boolean = true,
) {
    val c = RisiTheme.colors
    val statusLabel = status?.let { tickLabel(it) }
    val dark = c.bubbleTheirs.luminance() < 0.5f
    Box(
        Modifier.fillMaxWidth()
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)) else Modifier)
            .padding(top = if (tail) Spacing.xs else 0.dp),
        contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        menu()
        Column(Modifier.fillMaxWidth(Sizes.bubbleMaxFraction), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Surface(
            color = if (mine) c.bubbleMine else c.bubbleTheirs,
            contentColor = if (mine) c.onBubbleMine else c.onBubbleTheirs,
            shape = BubbleShape(mine, tail),
            shadowElevation = if (dark) 0.dp else 0.5.dp,
            modifier = Modifier
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
            val tailPad = RisiShapes.tailWidth
            val inner = if (image != null) Spacing.xs else Spacing.sm + Spacing.xxs
            Column(
                Modifier.padding(
                    start = inner + if (mine) 0.dp else tailPad,
                    end = inner + if (mine) tailPad else 0.dp,
                    top = if (image != null) Spacing.xs else Spacing.xs + Spacing.xxs,
                    bottom = if (image != null) Spacing.xs else Spacing.xs,
                ),
            ) {
                sender?.let {
                    Text(
                        it, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = senderColor,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = if (image != null) Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.xxs) else Modifier,
                    )
                }
                image?.invoke()
                val meta: @Composable () -> Unit = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(time, style = MaterialTheme.typography.labelSmall, color = if (mine) c.bubbleMineMeta else c.bubbleTheirsMeta)
                        if (status != null) {
                            Spacer(Modifier.size(Spacing.xs))
                            MessageTicks(status)
                        }
                    }
                }
                if (image == null || body.isNotBlank()) {
                    TextWithMeta(
                        text = {
                            Text(
                                body,
                                style = if (muted) MaterialTheme.typography.bodyLarge.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic) else MaterialTheme.typography.bodyLarge,
                                color = if (muted) androidx.compose.material3.LocalContentColor.current.copy(alpha = 0.7f) else Color.Unspecified,
                                onTextLayout = it,
                            )
                        },
                        meta = if (note == null) meta else null,
                        modifier = if (image != null) Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs) else Modifier,
                    )
                }
                note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = if (noteIsInfo) androidx.compose.material3.LocalContentColor.current.copy(alpha = 0.7f) else MaterialTheme.colorScheme.error) }
                if (note != null || (image != null && body.isBlank())) {
                    Box(Modifier.align(Alignment.End).padding(horizontal = if (image != null) Spacing.xs else 0.dp, vertical = if (image != null) Spacing.xxs else 0.dp)) { meta() }
                }
            }
        }
        footer()
        }
    }
}

/**
 * The message text with its time + ticks: on the last line when there's room (WhatsApp-style),
 * else on a line of its own, right-aligned. [meta] null: the text alone.
 */
@Composable
fun TextWithMeta(
    text: @Composable (onLayout: (androidx.compose.ui.text.TextLayoutResult) -> Unit) -> Unit,
    meta: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val holder = androidx.compose.runtime.remember { arrayOfNulls<androidx.compose.ui.text.TextLayoutResult>(1) }
    androidx.compose.ui.layout.Layout(
        contents = listOf({ text { holder[0] = it } }, { meta?.invoke() }),
        modifier = modifier,
    ) { (textM, metaM), constraints ->
        val tp = textM.first().measure(constraints.copy(minWidth = 0))
        val mp = metaM.firstOrNull()?.measure(androidx.compose.ui.unit.Constraints())
        if (mp == null) return@Layout layout(tp.width, tp.height) { tp.place(0, 0) }
        val gap = Spacing.sm.roundToPx()
        val lay = holder[0]
        val lastLine = lay?.let { it.lineCount - 1 } ?: 0
        val rtl = lay?.let { it.getParagraphDirection(it.getLineStart(lastLine)) == androidx.compose.ui.text.style.ResolvedTextDirection.Rtl } ?: false
        val lastWidth = lay?.let { kotlin.math.ceil(it.getLineRight(lastLine) - it.getLineLeft(lastLine)).toInt() } ?: tp.width
        val inline = !rtl && lastWidth + gap + mp.width <= constraints.maxWidth
        if (inline) {
            val w = maxOf(tp.width, lastWidth + gap + mp.width).coerceAtMost(constraints.maxWidth)
            val h = maxOf(tp.height, mp.height)
            layout(w, h) {
                tp.place(0, 0)
                mp.place(w - mp.width, h - mp.height + (Spacing.xxs.roundToPx() / 2))
            }
        } else {
            val w = maxOf(tp.width, mp.width).coerceAtMost(constraints.maxWidth)
            layout(w, tp.height + mp.height) {
                tp.place(0, 0)
                mp.place(w - mp.width, tp.height)
            }
        }
    }
}

/**
 * The bubble's outline: rounded, with a WhatsApp-style tail at the top corner on [mine]'s side
 * (end for mine, start for theirs; mirrored in RTL). The tail side always reserves
 * [RisiShapes.tailWidth], so bubbles of a run line up with or without a tail.
 */
class BubbleShape(private val mine: Boolean, private val tail: Boolean) : androidx.compose.ui.graphics.Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density,
    ): androidx.compose.ui.graphics.Outline {
        val t = with(density) { RisiShapes.tailWidth.toPx() }
        val r = with(density) { RisiShapes.bubbleRadius.toPx() }.coerceAtMost(minOf(size.width - t, size.height) / 2)
        val right = mine == (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Ltr)
        val w = size.width
        val h = size.height
        val path = androidx.compose.ui.graphics.Path()
        // Draw as if the tail is on the right; mirror for the left.
        fun x(v: Float) = if (right) v else w - v
        val bodyR = w - t
        if (tail) {
            path.moveTo(x(r), 0f)
            path.lineTo(x(w - 2f), 0f)
            path.quadraticTo(x(w), 0f, x(w - 1.5f), 2f)
            path.lineTo(x(bodyR), minOf(t * 1.6f, h / 2))
        } else {
            path.moveTo(x(r), 0f)
            path.lineTo(x(bodyR - r), 0f)
            arc(path, right, x(bodyR - r), r, r, -90f)
        }
        path.lineTo(x(bodyR), h - r)
        arc(path, right, x(bodyR - r), h - r, r, 0f)
        path.lineTo(x(r), h)
        arc(path, right, x(r), h - r, r, 90f)
        path.lineTo(x(0f), r)
        arc(path, right, x(r), r, r, 180f)
        path.close()
        return androidx.compose.ui.graphics.Outline.Generic(path)
    }

    /** A 90° corner arc around (cx, cy) starting at [start] degrees (right-tail frame), mirrored when needed. */
    private fun arc(path: androidx.compose.ui.graphics.Path, right: Boolean, cx: Float, cy: Float, r: Float, start: Float) {
        val rect = androidx.compose.ui.geometry.Rect(cx - r, cy - r, cx + r, cy + r)
        if (right) path.arcTo(rect, start, 90f, false) else path.arcTo(rect, 180f - start, -90f, false)
    }

    override fun equals(other: Any?) = other is BubbleShape && other.mine == mine && other.tail == tail
    override fun hashCode() = (if (mine) 1 else 0) * 2 + if (tail) 1 else 0
}

/** True when the message at [index] starts a run (WhatsApp tail): the row before it is another side, sender or kind. */
fun startsRun(items: List<lk.codegen.risime.ui.chat.ChatItem>, index: Int): Boolean {
    val m = (items[index] as? lk.codegen.risime.ui.chat.ChatItem.Msg)?.m ?: return true
    val prev = (items.getOrNull(index - 1) as? lk.codegen.risime.ui.chat.ChatItem.Msg)?.m ?: return true
    return prev.system || prev.call || prev.outgoing != m.outgoing || !prev.from.equals(m.from, true)
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
