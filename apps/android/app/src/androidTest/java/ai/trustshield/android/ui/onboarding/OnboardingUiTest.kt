package ai.trustshield.android.ui.onboarding

import ai.trustshield.android.MainActivity
import ai.trustshield.android.markOnboardingCompleteForTest
import ai.trustshield.android.setOnboardingCompleteForTest
import ai.trustshield.android.data.preferences.AppLanguage
import ai.trustshield.android.data.preferences.SharedPreferencesUserPreferencesStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class OnboardingUiTest {
    @get:Rule
    val compose: ComposeTestRule = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun startFromFirstRun() {
        setOnboardingCompleteForTest(false)
        val store = SharedPreferencesUserPreferencesStore
            .open(InstrumentationRegistry.getInstrumentation().targetContext)
        store.save(store.reset().copy(language = AppLanguage.ENGLISH))
    }

    @After
    fun restoreCompletedOnboarding() {
        scenario?.close()
        markOnboardingCompleteForTest()
    }

    @Test
    fun onboardingIsShownOnlyOnce() {
        scenario = ActivityScenario.launch(MainActivity::class.java)

        compose.onNodeWithText("Check before you trust").assertIsDisplayed()
        compose.onNodeWithText("Skip").performClick()
        compose.onNodeWithTag("app_shell").assertIsDisplayed()

        scenario?.recreate()

        compose.onNodeWithTag("app_shell").assertIsDisplayed()
        assertTrue(compose.onAllNodes(hasText("Check before you trust")).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodes(hasTestTag("bottom_navigation")).fetchSemanticsNodes().isNotEmpty())
    }
}
