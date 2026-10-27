package ai.trustshield.android.ui.theme

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DevanagariTextLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun systemFontRendersHindiWithoutMissingGlyphsAtLargeFontScale() {
        val hindi = "\u091c\u094b\u0916\u093f\u092e \u0915\u093e \u0938\u094d\u0924\u0930"
        val devanagariTypeface = Typeface.createFromFile("/system/fonts/NotoSansDevanagari-VF.ttf")
        val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = devanagariTypeface }
        val hindiLetters = listOf("\u091c", "\u0916", "\u092e", "\u0915", "\u0938", "\u0924", "\u0930")
        assertTrue("system font must contain the Devanagari letters used by the sample", hindiLetters.all(glyphPaint::hasGlyph))

        var lineHeightPx = 0f
        var expectedLineHeightPx = 0f
        var layoutHeightPx = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(compose.density.density, fontScale = 1.3f)) {
                expectedLineHeightPx = with(LocalDensity.current) { 36.sp.toPx() }
                MaterialTheme {
                    Box {
                        Text(
                            hindi,
                            modifier = Modifier,
                            fontFamily = FontFamily.Default,
                            fontSize = 20.sp,
                            lineHeight = 36.sp,
                            onTextLayout = {
                                lineHeightPx = it.getLineBottom(0) - it.getLineTop(0)
                                layoutHeightPx = it.size.height
                            },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        assertTrue("scaled line height should retain Hindi marks: $lineHeightPx < $expectedLineHeightPx", lineHeightPx >= expectedLineHeightPx - 1f)
        assertTrue("text layout must contain the full line: $layoutHeightPx", layoutHeightPx >= lineHeightPx)
    }
}
