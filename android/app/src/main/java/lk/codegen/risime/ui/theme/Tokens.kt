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
 * RisiMe design tokens (0.2 design pass). Seeds: teal #0B6E69 (primary) and saffron #F2A93B
 * (accent). Every text/background pair used by the app is listed in [RisiPalette.textPairs] and
 * checked for WCAG AA (≥ 4.5:1) by DesignTokensTest; non-text marks (ticks, presence dot) ≥ 3:1.
 */

val Teal = Color(0xFF0B6E69)
val Saffron = Color(0xFFF2A93B)

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
    val error: Color,
    val onError: Color,
    // RisiMe roles
    /** Text for inactive rows (contacts not on RisiMe yet); still AA. */
    val textMuted: Color,
    val bubbleMine: Color,
    val onBubbleMine: Color,
    /** Time and ticks inside my bubble. */
    val bubbleMineMeta: Color,
    val bubbleTheirs: Color,
    val onBubbleTheirs: Color,
    val bubbleTheirsMeta: Color,
    /** Read ticks: saffron family, darkened on light so it stays ≥ 3:1 on my bubble. */
    val readTick: Color,
    val online: Color,
    val banner: Color,
    val onBanner: Color,
    val daySeparator: Color,
    val onDaySeparator: Color,
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
            Triple("primary/surface (links, typing…, section titles)", primary, surface),
            Triple("error/surface", error, surface),
            Triple("onError/error", onError, error),
            Triple("textMuted/surface", textMuted, surface),
            Triple("onBubbleMine/bubbleMine", onBubbleMine, bubbleMine),
            Triple("bubbleMineMeta/bubbleMine", bubbleMineMeta, bubbleMine),
            Triple("error/bubbleMine (not sent)", error, bubbleMine),
            Triple("onBubbleTheirs/bubbleTheirs", onBubbleTheirs, bubbleTheirs),
            Triple("bubbleTheirsMeta/bubbleTheirs", bubbleTheirsMeta, bubbleTheirs),
            Triple("onBanner/banner", onBanner, banner),
            Triple("onDaySeparator/daySeparator", onDaySeparator, daySeparator),
        )

    /** (name, mark, background) for meaningful non-text marks (WCAG 1.4.11: ≥ 3:1). */
    val markPairs: List<Triple<String, Color, Color>>
        get() = listOf(
            Triple("readTick/bubbleMine", readTick, bubbleMine),
            Triple("bubbleMineMeta tick/bubbleMine", bubbleMineMeta, bubbleMine),
            Triple("online/surface", online, surface),
        )
}

val LightPalette = RisiPalette(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB4EFE8),
    onPrimaryContainer = Color(0xFF00201E),
    secondary = Color(0xFF4A6361),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE8E5),
    onSecondaryContainer = Color(0xFF051F1E),
    tertiary = Saffron,
    onTertiary = Color(0xFF2B1700),
    tertiaryContainer = Color(0xFFFFDDB5),
    onTertiaryContainer = Color(0xFF2B1700),
    background = Color(0xFFF7FAF9),
    onBackground = Color(0xFF171D1C),
    surface = Color(0xFFF7FAF9),
    onSurface = Color(0xFF171D1C),
    surfaceVariant = Color(0xFFDAE5E3),
    onSurfaceVariant = Color(0xFF3F4948),
    outline = Color(0xFF6F7978),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    textMuted = Color(0xFF5E6867),
    bubbleMine = Color(0xFFB4EFE8),
    onBubbleMine = Color(0xFF00201E),
    bubbleMineMeta = Color(0xFF3F4948),
    bubbleTheirs = Color(0xFFDAE5E3),
    onBubbleTheirs = Color(0xFF171D1C),
    bubbleTheirsMeta = Color(0xFF3F4948),
    readTick = Color(0xFF9C5F00),
    online = Color(0xFF15834F),
    banner = Color(0xFFFFDDB5),
    onBanner = Color(0xFF2B1700),
    daySeparator = Color(0xFFCCE8E5),
    onDaySeparator = Color(0xFF051F1E),
)

val DarkPalette = RisiPalette(
    primary = Color(0xFF84D5CD),
    onPrimary = Color(0xFF003734),
    primaryContainer = Color(0xFF00504C),
    onPrimaryContainer = Color(0xFFB4EFE8),
    secondary = Color(0xFFB0CCC9),
    onSecondary = Color(0xFF1C3533),
    secondaryContainer = Color(0xFF324B49),
    onSecondaryContainer = Color(0xFFCCE8E5),
    tertiary = Saffron,
    onTertiary = Color(0xFF2B1700),
    tertiaryContainer = Color(0xFF6A4300),
    onTertiaryContainer = Color(0xFFFFDDB5),
    background = Color(0xFF0F1514),
    onBackground = Color(0xFFDEE4E2),
    surface = Color(0xFF0F1514),
    onSurface = Color(0xFFDEE4E2),
    surfaceVariant = Color(0xFF3F4948),
    onSurfaceVariant = Color(0xFFBEC9C7),
    outline = Color(0xFF889391),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    textMuted = Color(0xFFA3AEAC),
    bubbleMine = Color(0xFF00504C),
    onBubbleMine = Color(0xFFB4EFE8),
    bubbleMineMeta = Color(0xFFB4EFE8),
    bubbleTheirs = Color(0xFF3F4948),
    onBubbleTheirs = Color(0xFFDEE4E2),
    bubbleTheirsMeta = Color(0xFFBEC9C7),
    readTick = Saffron,
    online = Color(0xFF4CC38A),
    banner = Color(0xFF6A4300),
    onBanner = Color(0xFFFFDDB5),
    daySeparator = Color(0xFF324B49),
    onDaySeparator = Color(0xFFCCE8E5),
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
    val avatar = 44.dp
    val avatarSmall = 36.dp
    val avatarLarge = 56.dp
    val listRowMin = 72.dp
    val bubbleMaxWidth = 300.dp
    val tick = 15.dp
}

object RisiShapes {
    val bubbleRadius = 18.dp
    val bubbleTail = 4.dp
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
