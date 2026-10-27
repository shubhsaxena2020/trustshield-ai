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
        val hindi = "जोखिम का स्तर"
        assertTrue("system font must contain Devanagari glyphs", Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT
        }.hasGlyph(hindi))

        var lineHeightPx = 0f
        var layoutHeightPx = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(compose.density, fontScale = 1.3f)) {
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

        assertTrue("scaled line height should retain Hindi marks: $lineHeightPx", lineHeightPx >= compose.density * 36f * 1.3f - 1f)
        assertTrue("text layout must contain the full line: $layoutHeightPx", layoutHeightPx >= lineHeightPx)
    }
}
