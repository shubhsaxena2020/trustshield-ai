package ai.trustshield.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Type scale from `docs/android/UI_SPEC.md`: Material 3 typography at 14/16/20/28 sp using the
 * system font family. No bundled fonts and no `res/` usage — the platform default (Roboto / Noto on
 * Android, with fallback for Devanagari) keeps the APK small and makes Hinglish/Devanagari render
 * with the device's own glyph coverage.
 *
 * Line heights are set generously (1.4-1.5x) because the app must survive 200% font scale at 320 dp
 * without clipping.
 */
object TsTypeScale {

    /** Smallest body/support text, 14 sp. */
    const val SupportSp: Float = 14f

    /** Default body text, 16 sp. */
    const val BodySp: Float = 16f

    /** Section/panel titles, 20 sp. */
    const val TitleSp: Float = 20f

    /** Screen headline (risk panel headline), 28 sp. */
    const val HeadlineSp: Float = 28f

    /** The only sizes the app may use. Anything else is a spec violation. */
    val AllowedSp: List<Float> = listOf(SupportSp, BodySp, TitleSp, HeadlineSp)

    fun isAllowed(sizeSp: Float): Boolean = AllowedSp.any { it == sizeSp }
}

/**
 * Material 3 [Typography] for the app. Defaults for anything not overridden are Material 3's own
 * values at the system font family.
 */
val TsTypography: Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = TsTypeScale.HeadlineSp.sp, lineHeight = 36.sp
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = TsTypeScale.HeadlineSp.sp, lineHeight = 36.sp
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = TsTypeScale.TitleSp.sp, lineHeight = 28.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = TsTypeScale.HeadlineSp.sp, lineHeight = 36.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = TsTypeScale.TitleSp.sp, lineHeight = 28.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = TsTypeScale.TitleSp.sp, lineHeight = 28.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = TsTypeScale.TitleSp.sp, lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = TsTypeScale.BodySp.sp, lineHeight = 24.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = TsTypeScale.SupportSp.sp, lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = TsTypeScale.BodySp.sp, lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = TsTypeScale.SupportSp.sp, lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = TsTypeScale.SupportSp.sp, lineHeight = 20.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = TsTypeScale.BodySp.sp, lineHeight = 24.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = TsTypeScale.SupportSp.sp, lineHeight = 20.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = TsTypeScale.SupportSp.sp, lineHeight = 20.sp
    )
)
