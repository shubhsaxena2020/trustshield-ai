package ai.trustshield.android

import android.Manifest
import android.content.Intent
import ai.trustshield.android.data.preferences.AppLanguage
import ai.trustshield.android.data.preferences.SharedPreferencesUserPreferencesStore
import ai.trustshield.android.data.preferences.VoiceRecognitionChoice
import ai.trustshield.android.ui.call.CallShieldCopy
import ai.trustshield.android.ui.navigation.AppRoute
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs last because the emulator revokes runtime permissions by killing the target test process. */
@RunWith(AndroidJUnit4::class)
class ZZCallShieldLifecycleTest {

    @get:Rule
    val compose: ComposeTestRule = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun grantMicOnlyForFinalLifecycleJourney() {
        markOnboardingCompleteForTest()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${instrumentation.targetContext.packageName} ${Manifest.permission.RECORD_AUDIO}"
        ).close()
        val store = SharedPreferencesUserPreferencesStore.open(instrumentation.targetContext)
        store.save(store.reset().copy(
            language = AppLanguage.HINGLISH,
            voiceRecognition = VoiceRecognitionChoice.OFFLINE_ONLY,
        ))
    }

    @After
    fun closeActivity() { scenario?.close() }

    @Test
    fun rotationDuringListeningKeepsConsentAndReturnsToVisiblePausedSession() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra(AppRoute.EXTRA_ROUTE, AppRoute.CALL_SHIELD.wireName)
        scenario = ActivityScenario.launch(intent)

        compose.waitUntil(15_000) {
            compose.onAllNodes(hasText(CallShieldCopy.consentAction.hinglish)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText(CallShieldCopy.consentAction.hinglish)).performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasText(CallShieldCopy.start.hinglish)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText(CallShieldCopy.start.hinglish)).performClick()
        compose.waitUntil(60_000) {
            compose.onAllNodes(hasText(CallShieldCopy.stop.hinglish)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText("Offline voice.", substring = true)).assertExists()

        scenario?.recreate()

        compose.waitUntil(20_000) {
            compose.onAllNodes(hasText(CallShieldCopy.micPaused.hinglish)).fetchSemanticsNodes().isNotEmpty()
        }
        check(compose.onAllNodes(hasText(CallShieldCopy.consentAction.hinglish)).fetchSemanticsNodes().isEmpty()) {
            "consent prompt returned after Activity recreation"
        }
        compose.onNode(hasText(CallShieldCopy.stop.hinglish)).assertExists()
    }
}
