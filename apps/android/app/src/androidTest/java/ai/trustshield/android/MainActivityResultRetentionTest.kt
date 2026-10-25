package ai.trustshield.android

import android.content.Intent
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityResultRetentionTest {

    @get:Rule
    val compose: ComposeTestRule = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun closeActivity() {
        scenario?.close()
    }

    @Test
    fun verifiedPasteResultSurvivesActivityRecreationWithoutSavingTheInput() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
        scenario = ActivityScenario.launch(intent)

        waitForText("Message yahan paste karein")
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput(
            "SBI: Aapka KYC 24 ghante mein band ho jayega. Turant link se update karein aur OTP batao."
        )
        compose.onAllNodes(hasText("Check karein", substring = true)).onFirst().performClick()
        waitForText("Bharosa")

        scenario?.recreate()

        waitForText("Bharosa")
    }

    private fun waitForText(text: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
