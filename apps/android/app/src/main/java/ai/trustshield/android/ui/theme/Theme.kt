package ai.trustshield.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import ai.trustshield.android.data.preferences.ThemeMode

/**
 * The two Material 3 schemes, built only from [TsTokens] / [TsPalette].
 *
 * **Dynamic colour is intentionally OFF.** Material You would repaint `primary`/`surface` from the
 * user's wallpaper, which would (a) break the brand accent, (b) make the risk tones drift into
 * whatever hue the wallpaper happened to have, and (c) make contrast unpredictable. We never call
 * `dynamicLightColorScheme` / `dynamicDarkColorScheme`, so the brand and risk tokens stay stable on
 * every device.
 */
object TsColorSchemes {

    val Light: ColorScheme = lightColorScheme(
        primary = TsPalette.OnSurfaceLight,
        onPrimary = TsPalette.BackgroundLight,
        primaryContainer = TsPalette.AccentContainerLight,
        onPrimaryContainer = TsPalette.OnAccentContainerLight,
        inversePrimary = TsPalette.OnSurfaceDark,
        secondary = TsPalette.OnSurfaceVariantLight,
        onSecondary = TsPalette.SurfaceLight,
        secondaryContainer = TsPalette.SurfaceVariantLight,
        onSecondaryContainer = TsPalette.OnSurfaceLight,
        tertiary = TsTokens.AccentTextLight,
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = TsPalette.AccentContainerLight,
        onTertiaryContainer = TsPalette.OnAccentContainerLight,
        background = TsPalette.BackgroundLight,
        onBackground = TsPalette.OnSurfaceLight,
        surface = TsPalette.SurfaceLight,
        onSurface = TsPalette.OnSurfaceLight,
        surfaceVariant = TsPalette.SurfaceVariantLight,
        onSurfaceVariant = TsPalette.OnSurfaceVariantLight,
        surfaceTint = TsTokens.Accent,
        inverseSurface = Color(0xFF242424),
        inverseOnSurface = Color(0xFFFFFFFF),
        error = TsTokens.RiskRed,
        onError = Color(0xFFFFFFFF),
        errorContainer = TsPalette.RedContainerLight,
        onErrorContainer = TsPalette.OnRedContainerLight,
        outline = TsPalette.OutlineLight,
        outlineVariant = TsPalette.OutlineVariantLight,
        scrim = Color(0xFF000000)
    )

    val Dark: ColorScheme = darkColorScheme(
        primary = TsPalette.OnSurfaceDark,
        onPrimary = TsPalette.BackgroundDark,
        primaryContainer = TsPalette.AccentContainerDark,
        onPrimaryContainer = TsPalette.OnAccentContainerDark,
        inversePrimary = TsTokens.Accent,
        secondary = TsPalette.OnSurfaceVariantDark,
        onSecondary = TsPalette.SurfaceDark,
        secondaryContainer = TsPalette.SurfaceVariantDark,
        onSecondaryContainer = TsPalette.OnSurfaceDark,
        tertiary = TsTokens.AccentDark,
        onTertiary = TsPalette.OnAccentFillDark,
        tertiaryContainer = TsPalette.AccentContainerDark,
        onTertiaryContainer = TsPalette.OnAccentContainerDark,
        background = TsPalette.BackgroundDark,
        onBackground = TsPalette.OnSurfaceDark,
        surface = TsPalette.SurfaceDark,
        onSurface = TsPalette.OnSurfaceDark,
        surfaceVariant = TsPalette.SurfaceVariantDark,
        onSurfaceVariant = TsPalette.OnSurfaceVariantDark,
        surfaceTint = TsTokens.AccentDark,
        inverseSurface = Color(0xFFE5E5E5),
        inverseOnSurface = Color(0xFF000000),
        error = TsTokens.RiskRedDark,
        onError = Color(0xFF3A0A0E),
        errorContainer = TsPalette.RedContainerDark,
        onErrorContainer = TsPalette.OnRedContainerDark,
        outline = TsPalette.OutlineDark,
        outlineVariant = TsPalette.OutlineVariantDark,
        scrim = Color(0xFF000000)
    )

    fun of(dark: Boolean): ColorScheme = if (dark) Dark else Light
}

/** Resolve the app's theme preference against the current system setting. */
fun resolveDarkTheme(mode: ThemeMode, systemIsDark: Boolean): Boolean = when (mode) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> systemIsDark
}

/**
 * Whether the composition is currently dark. Screens need this outside `@Composable` context too,
 * hence a CompositionLocal rather than only `MaterialTheme.colorScheme` inspection.
 */
val LocalTsDarkTheme = staticCompositionLocalOf { false }

/** Risk colours for the current theme, without every call site threading a `dark` flag. */
@Composable
fun RiskTone.container(): Color = container(LocalTsDarkTheme.current)

@Composable
fun RiskTone.onContainer(): Color = onContainer(LocalTsDarkTheme.current)

@Composable
fun RiskTone.accent(): Color = accent(LocalTsDarkTheme.current)

@Composable
fun RiskTone.onSurface(): Color = onSurface(LocalTsDarkTheme.current)

/**
 * App theme. Dynamic colour is off (see [TsColorSchemes]); typography comes from [TsTypography].
 *
 * `darkTheme` is a parameter rather than being read internally so Settings ("theme" preference,
 * C04) can force light/dark; it defaults to the system setting.
 */
@Composable
fun TrustShieldTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalTsDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = TsColorSchemes.of(darkTheme),
            typography = TsTypography,
            content = content
        )
    }
}
