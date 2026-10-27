package ai.trustshield.android.ui.onboarding

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import ai.trustshield.android.data.preferences.AppLanguage
import ai.trustshield.android.ui.theme.TrustShieldTheme
import org.junit.Rule
import org.junit.Test

class OnboardingUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun onboardingLeavesTheAppAfterCompletion() {
        var complete by mutableStateOf(false)
        compose.setContent {
            TrustShieldTheme {
                if (complete) Text("home")
                else OnboardingScreen(
                    language = AppLanguage.ENGLISH,
                    onLanguageChange = {},
                    onComplete = { complete = true }
                )
            }
        }

        compose.onNodeWithText("Check before you trust").assertIsDisplayed()
        compose.onNodeWithText("Skip").performClick()
        compose.onNodeWithText("home").assertIsDisplayed()
        compose.onNodeWithText("Check before you trust").assertDoesNotExist()
    }
}
