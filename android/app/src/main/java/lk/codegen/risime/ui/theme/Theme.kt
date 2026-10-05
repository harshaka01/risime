package lk.codegen.risime.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** A5: seed colours. Primary deep teal, accent saffron (also the read-tick colour). Dynamic colour off. */
val Teal = Color(0xFF0B6E69)
val Saffron = Color(0xFFF2A93B)

/** Presence dot (non-text; ringed by the surface colour so it reads on any avatar). */
val OnlineGreen = Color(0xFF1FA463)

private val Light = lightColorScheme(
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
)

private val Dark = darkColorScheme(
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
)

@Composable
fun RisiMeTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
