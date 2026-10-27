package ai.trustshield.android

import android.content.Intent
import ai.trustshield.android.data.preferences.AppLanguage
import ai.trustshield.android.data.preferences.SharedPreferencesUserPreferencesStore
import ai.trustshield.android.ui.navigation.AppRoute
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real routed UI checks for the accessibility semantics added in package C. */
@RunWith(AndroidJUnit4::class)
class AccessibilityFlowsTest {

    @get:Rule
    val compose: ComposeTestRule = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun useHinglishCopy() {
        val store = SharedPreferencesUserPreferencesStore
            .open(InstrumentationRegistry.getInstrumentation().targetContext)
        store.save(store.reset().copy(language = AppLanguage.HINGLISH))
    }

    @After
    fun closeActivity() {
        scenario?.close()
    }

    @Test
    fun scanAndResultExposeOrderedRiskConfidenceAndEvidence() {
        launch()
        waitForText("Message yahan paste karein")
        compose.onAllNodes(hasSetTextAction()).onFirst().performScrollTo().performTextInput(
            "SBI: Aapka KYC 24 ghante mein band ho jayega. Link par update karo aur OTP batao."
        )
        val check = compose.onNode(hasContentDescription("Message ko isi device par check karein"))
        assertTouchTarget(check)
        check.performScrollTo().performClick()

        waitForText("Evidence")
        val risk = compose.onNode(hasContentDescription("Zyada khatra", substring = true))
        val confidence = compose.onNode(hasText("Bharosa", substring = true)).onFirst()
        val evidence = compose.onNode(hasText("Evidence"))
        val riskTop = risk.fetchSemanticsNode().boundsInRoot.top
        val confidenceTop = confidence.fetchSemanticsNode().boundsInRoot.top
        val evidenceTop = evidence.fetchSemanticsNode().boundsInRoot.top
        assertTrue("risk must precede confidence", riskTop <= confidenceTop)
        assertTrue("confidence must precede evidence", confidenceTop < evidenceTop)
        assertTouchTarget(compose.onNode(hasContentDescription("Bhasha badlein", substring = true)))
    }

    @Test
    fun callShieldAndQrGuardExposeNamedControlsAndReadableState() {
        launchRoute(AppRoute.CALL_SHIELD)
        waitForText("Call Shield")
        assertTouchTarget(compose.onNode(hasText("English")))
        assertTrue("Call Shield heading missing", compose.onAllNodes(hasText("Call Shield")).fetchSemanticsNodes().isNotEmpty())

        launchRoute(AppRoute.QR_GUARD)
        waitForText("QR Guard")
        assertTrue(
            "camera state must be named without relying on its colour",
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription))
                .fetchSemanticsNodes().isNotEmpty()
        )
        assertTrue(
            "camera and QR status should each expose a spoken state",
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription))
                .fetchSemanticsNodes().size >= 2
        )
    }

    @Test
    fun settingsSwitchesExposeTranslatedStateAndPhoneSizedTargets() {
        launchRoute(AppRoute.SETTINGS)
        waitForText("Settings")
        val haptics = compose.onNode(hasTestTag("settings_haptics_toggle"))
        haptics.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Chalu"))
        assertTouchTarget(haptics)
        assertTouchTarget(compose.onNode(hasTestTag("settings_reduced_motion_toggle")))
    }

    private fun launch() {
        scenario = ActivityScenario.launch(Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java))
    }

    private fun launchRoute(route: AppRoute) {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra(AppRoute.EXTRA_ROUTE, route.wireName)
        scenario?.close()
        scenario = ActivityScenario.launch(intent)
    }

    private fun waitForText(text: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertTouchTarget(node: androidx.compose.ui.test.SemanticsNodeInteraction) {
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val widthDp = with(compose.density) { bounds.width.toDp().value }
        val heightDp = with(compose.density) { bounds.height.toDp().value }
        assertTrue("target width must be at least 48 dp, was $widthDp dp", widthDp >= 48f)
        assertTrue("target height must be at least 48 dp, was $heightDp dp", heightDp >= 48f)
    }
}
