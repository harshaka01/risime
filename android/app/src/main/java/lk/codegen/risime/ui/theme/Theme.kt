package lk.codegen.risime.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

private fun RisiPalette.light() = lightColorScheme(
    primary = primary, onPrimary = onPrimary, primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
    secondary = secondary, onSecondary = onSecondary, secondaryContainer = secondaryContainer,
    onSecondaryContainer = onSecondaryContainer, tertiary = tertiary, onTertiary = onTertiary,
    tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer, background = background,
    onBackground = onBackground, surface = surface, onSurface = onSurface, surfaceVariant = surfaceVariant,
    onSurfaceVariant = onSurfaceVariant, outline = outline, error = error, onError = onError,
)

private fun RisiPalette.dark() = darkColorScheme(
    primary = primary, onPrimary = onPrimary, primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
    secondary = secondary, onSecondary = onSecondary, secondaryContainer = secondaryContainer,
    onSecondaryContainer = onSecondaryContainer, tertiary = tertiary, onTertiary = onTertiary,
    tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer, background = background,
    onBackground = onBackground, surface = surface, onSurface = onSurface, surfaceVariant = surfaceVariant,
    onSurfaceVariant = onSurfaceVariant, outline = outline, error = error, onError = onError,
)

private val LocalRisiPalette = staticCompositionLocalOf { LightPalette }

/** RisiMe-specific colour roles (bubbles, ticks, presence, banner); Material roles via MaterialTheme. */
object RisiTheme {
    val colors: RisiPalette
        @Composable @ReadOnlyComposable get() = LocalRisiPalette.current
}

/** Material 3, light + dark from the token palettes; dynamic colour stays off (brand colours). */
@Composable
fun RisiMeTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val palette = if (dark) DarkPalette else LightPalette
    CompositionLocalProvider(LocalRisiPalette provides palette) {
        MaterialTheme(
            colorScheme = if (dark) palette.dark() else palette.light(),
            typography = RisiTypography,
            shapes = RisiShapes.material,
            content = content,
        )
    }
}
