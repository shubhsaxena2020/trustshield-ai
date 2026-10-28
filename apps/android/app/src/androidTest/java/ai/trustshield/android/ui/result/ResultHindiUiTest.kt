package ai.trustshield.android.ui.result

import ai.trustshield.android.ui.theme.TrustShieldTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class ResultHindiUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun resultTitleIsDisplayedInHindi() {
        compose.setContent {
            TrustShieldTheme {
                ResultScreen(state = ResultFixtures.digitalArrest, language = UiLanguage.HINDI)
            }
        }

        compose.onNodeWithText("\u0928\u0924\u0940\u091c\u093e").assertIsDisplayed()
    }
}
