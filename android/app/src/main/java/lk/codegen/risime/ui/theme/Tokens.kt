package lk.codegen.risime.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * RisiMe design tokens (GO UX). Derived from Harsha's logo (design/brand/risime-logo.png): seed
 * blue #1565E8 → Material 3 tonal palettes (primary, neutral, neutral variant), the logo's cyan
 * #18EBDE as the tertiary/accent (read ticks) and its violet for the call gradient. Every
 * text/background pair is listed in [RisiPalette.textPairs] and checked for WCAG AA (≥ 4.5:1) by
 * DesignTokensTest; non-text marks (ticks, presence dot) ≥ 3:1.
 */

/** The logo's three hues (gradient top-left → bottom-right). */
val BrandCyan = Color(0xFF18EBDE)
val BrandBlue = Color(0xFF1565E8)
val BrandViolet = Color(0xFF7B4DF5)

/** All colours of one theme (light or dark). Material roles plus RisiMe-specific ones. */
data class RisiPalette(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val outlineVariant: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val inverseSurface: Color,
    val inverseOnSurface: Color,
    val inversePrimary: Color,
    /** Material 3 surface containers (sheets, menus, dialogs, the input bar). */
    val surfaceContainerLowest: Color,
    val surfaceContainerLow: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val surfaceDim: Color,
    val surfaceBright: Color,
    // RisiMe roles
    /** Text for inactive rows (contacts not on RisiMe yet); still AA. */
    val textMuted: Color,
    /** The chat's wallpaper behind the bubbles (WhatsApp-style: incoming bubbles stand out on it). */
    val chatBackground: Color,
    val bubbleMine: Color,
    val onBubbleMine: Color,
    /** Time and ticks inside my bubble. */
    val bubbleMineMeta: Color,
    val bubbleTheirs: Color,
    val onBubbleTheirs: Color,
    val bubbleTheirsMeta: Color,
    /** Read ticks: the logo's cyan, toned so it stays ≥ 3:1 on my bubble and apart from delivered. */
    val readTick: Color,
    val online: Color,
    val banner: Color,
    val onBanner: Color,
    val daySeparator: Color,
    val onDaySeparator: Color,
    /** The call screen's gradient (top → bottom); white text on it. */
    val callTop: Color,
    val callBottom: Color,
) {
    /** (name, foreground, background) for every text pairing in the UI. */
    val textPairs: List<Triple<String, Color, Color>>
        get() = listOf(
            Triple("onPrimary/primary", onPrimary, primary),
            Triple("onPrimaryContainer/primaryContainer", onPrimaryContainer, primaryContainer),
            Triple("onSecondaryContainer/secondaryContainer", onSecondaryContainer, secondaryContainer),
            Triple("onTertiaryContainer/tertiaryContainer", onTertiaryContainer, tertiaryContainer),
            Triple("onBackground/background", onBackground, background),
            Triple("onSurface/surface", onSurface, surface),
            Triple("onSurfaceVariant/surface", onSurfaceVariant, surface),
            Triple("onSurfaceVariant/surfaceVariant", onSurfaceVariant, surfaceVariant),
            Triple("onSurface/surfaceContainer", onSurface, surfaceContainer),
            Triple("onSurfaceVariant/surfaceContainerHigh", onSurfaceVariant, surfaceContainerHigh),
            Triple("onSurfaceVariant/surfaceContainerHighest", onSurfaceVariant, surfaceContainerHighest),
            Triple("primary/surface (links, typing…, section titles)", primary, surface),
            Triple("error/surface", error, surface),
            Triple("onError/error", onError, error),
            Triple("onErrorContainer/errorContainer", onErrorContainer, errorContainer),
            Triple("inverseOnSurface/inverseSurface", inverseOnSurface, inverseSurface),
            Triple("textMuted/surface", textMuted, surface),
            Triple("onBubbleMine/bubbleMine", onBubbleMine, bubbleMine),
            Triple("bubbleMineMeta/bubbleMine", bubbleMineMeta, bubbleMine),
            Triple("error/bubbleMine (not sent)", error, bubbleMine),
            Triple("onBubbleTheirs/bubbleTheirs", onBubbleTheirs, bubbleTheirs),
            Triple("bubbleTheirsMeta/bubbleTheirs", bubbleTheirsMeta, bubbleTheirs),
            Triple("onBanner/banner", onBanner, banner),
            Triple("onDaySeparator/daySeparator", onDaySeparator, daySeparator),
            Triple("onSurfaceVariant/chatBackground (call lines)", onSurfaceVariant, chatBackground),
            Triple("white/callTop", Color.White, callTop),
            Triple("white/callBottom", Color.White, callBottom),
        )

    /** (name, mark, background) for meaningful non-text marks (WCAG 1.4.11: ≥ 3:1). */
    val markPairs: List<Triple<String, Color, Color>>
        get() = listOf(
            Triple("readTick/bubbleMine", readTick, bubbleMine),
            Triple("bubbleMineMeta tick/bubbleMine", bubbleMineMeta, bubbleMine),
            Triple("online/surface", online, surface),
            Triple("primary send button/surfaceContainer", primary, surfaceContainer),
        )
}

val LightPalette = RisiPalette(
    primary = Color(0xFF0056D3),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E2FF),
    onPrimaryContainer = Color(0xFF001849),
    secondary = Color(0xFF585E71),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE2F9),
    onSecondaryContainer = Color(0xFF151B2C),
    tertiary = Color(0xFF006A63),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFAEFFF8),
    onTertiaryContainer = Color(0xFF00201D),
    background = Color(0xFFFDFBFF),
    onBackground = Color(0xFF1B1B1E),
    surface = Color(0xFFFDFBFF),
    onSurface = Color(0xFF1B1B1E),
    surfaceVariant = Color(0xFFE2E2EC),
    onSurfaceVariant = Color(0xFF44464E),
    outline = Color(0xFF757780),
    outlineVariant = Color(0xFFC5C6D0),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    inverseSurface = Color(0xFF303033),
    inverseOnSurface = Color(0xFFF2F0F5),
    inversePrimary = Color(0xFFB0C6FF),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F5FA),
    surfaceContainer = Color(0xFFF1F0F6),
    surfaceContainerHigh = Color(0xFFEBEAF0),
    surfaceContainerHighest = Color(0xFFE5E4EA),
    surfaceDim = Color(0xFFDBD9DD),
    surfaceBright = Color(0xFFFDFBFF),
    textMuted = Color(0xFF5C5E67),
    chatBackground = Color(0xFFEEF1F8),
    bubbleMine = Color(0xFFD9E2FF),
    onBubbleMine = Color(0xFF001849),
    bubbleMineMeta = Color(0xFF44464E),
    bubbleTheirs = Color.White,
    onBubbleTheirs = Color(0xFF1B1B1E),
    bubbleTheirsMeta = Color(0xFF5C5E67),
    readTick = Color(0xFF007C8C),
    online = Color(0xFF15834F),
    banner = Color(0xFFFFDDB5),
    onBanner = Color(0xFF2B1700),
    daySeparator = Color(0xFFDCE2F9),
    onDaySeparator = Color(0xFF151B2C),
    callTop = Color(0xFF00507A),
    callBottom = Color(0xFF2A1C8C),
)

val DarkPalette = RisiPalette(
    primary = Color(0xFFB0C6FF),
    onPrimary = Color(0xFF002B74),
    primaryContainer = Color(0xFF0040A3),
    onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Color(0xFFC0C6DD),
    onSecondary = Color(0xFF2A3041),
    secondaryContainer = Color(0xFF404658),
    onSecondaryContainer = Color(0xFFDCE2F9),
    tertiary = Color(0xFF00DED1),
    onTertiary = Color(0xFF003733),
    tertiaryContainer = Color(0xFF00504A),
    onTertiaryContainer = Color(0xFFAEFFF8),
    background = Color(0xFF121316),
    onBackground = Color(0xFFE4E2E6),
    surface = Color(0xFF121316),
    onSurface = Color(0xFFE4E2E6),
    surfaceVariant = Color(0xFF44464E),
    onSurfaceVariant = Color(0xFFC5C6D0),
    outline = Color(0xFF8F909A),
    outlineVariant = Color(0xFF44464E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE4E2E6),
    inverseOnSurface = Color(0xFF303033),
    inversePrimary = Color(0xFF0056D3),
    surfaceContainerLowest = Color(0xFF0D0E11),
    surfaceContainerLow = Color(0xFF1B1B1E),
    surfaceContainer = Color(0xFF1F1F23),
    surfaceContainerHigh = Color(0xFF292A2D),
    surfaceContainerHighest = Color(0xFF343438),
    surfaceDim = Color(0xFF121316),
    surfaceBright = Color(0xFF39393C),
    textMuted = Color(0xFFA9AAB4),
    chatBackground = Color(0xFF0C0E15),
    bubbleMine = Color(0xFF0B3D91),
    onBubbleMine = Color(0xFFE6ECFF),
    bubbleMineMeta = Color(0xFFA9B8E0),
    bubbleTheirs = Color(0xFF262830),
    onBubbleTheirs = Color(0xFFE4E2E6),
    bubbleTheirsMeta = Color(0xFFADAFBA),
    readTick = Color(0xFF8AF7FF),
    online = Color(0xFF4CC38A),
    banner = Color(0xFF6A4300),
    onBanner = Color(0xFFFFDDB5),
    daySeparator = Color(0xFF282A32),
    onDaySeparator = Color(0xFFC5C6D0),
    callTop = Color(0xFF003A5C),
    callBottom = Color(0xFF1E1466),
)

/** 4-point spacing scale. */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** Sizes that carry meaning (accessibility, list rhythm). */
object Sizes {
    /** Minimum touch target (Material / WCAG 2.5.8). */
    val minTouch = 48.dp
    val avatar = 50.dp
    val avatarSmall = 36.dp
    val avatarLarge = 56.dp
    val listRowMin = 72.dp
    /** Bubbles take at most this share of the chat's width (WhatsApp-style). */
    const val bubbleMaxFraction = 0.8f
    val tick = 15.dp
}

object RisiShapes {
    val bubbleRadius = 16.dp
    val bubbleTail = 4.dp
    /** The bubble tail's width (reserved on the tail side of every bubble). */
    val tailWidth = 7.dp
    val mine = RoundedCornerShape(topStart = bubbleRadius, topEnd = bubbleRadius, bottomStart = bubbleRadius, bottomEnd = bubbleTail)
    val theirs = RoundedCornerShape(topStart = bubbleRadius, topEnd = bubbleRadius, bottomStart = bubbleTail, bottomEnd = bubbleRadius)
    val pill = RoundedCornerShape(50)
    val input = RoundedCornerShape(24.dp)

    val material = Shapes(
        extraSmall = RoundedCornerShape(4.dp),
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(16.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )
}

/** Type scale: Material 3 defaults, with RisiMe weights for titles and the chat body. */
val RisiTypography: Typography = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = t.bodyLarge.copy(lineHeight = 22.sp),
        labelSmall = t.labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.3.sp),
    )
}

/** Wordmark on the login screens. */
val WordmarkStyle = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold)
