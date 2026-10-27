package ai.trustshield.android

import android.content.Intent
import ai.trustshield.android.data.preferences.AppLanguage
import ai.trustshield.android.data.preferences.SharedPreferencesUserPreferencesStore
import ai.trustshield.android.ui.navigation.AppRoute
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
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

@RunWith(AndroidJUnit4::class)
class CallShieldOfflineUiTest {

    @get:Rule
    val compose: ComposeTestRule = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun useEnglishCopy() {
        val store = SharedPreferencesUserPreferencesStore
            .open(InstrumentationRegistry.getInstrumentation().targetContext)
        store.save(store.reset().copy(language = AppLanguage.ENGLISH))
    }

    @After
    fun closeActivity() {
        scenario?.close()
    }

    @Test
    fun offlineControlHasPhoneSizedTargetAndShowsTheAutomaticSelection() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra(AppRoute.EXTRA_ROUTE, AppRoute.CALL_SHIELD.wireName)
        scenario = ActivityScenario.launch(intent)

        val offlineOption = compose.onNode(hasText("Use offline mode", substring = true))
        val selectedOffline = compose.onNode(hasText("Offline mode selected", substring = true))
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasText("Use offline mode", substring = true)).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodes(hasText("Offline mode selected", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        val startsOffline = compose.onAllNodes(hasText("Offline mode selected", substring = true))
            .fetchSemanticsNodes().isNotEmpty()
        val control = if (startsOffline) selectedOffline else offlineOption
        if (startsOffline) selectedOffline.assertIsSelected() else offlineOption.assertIsNotSelected()
        val heightDp = with(compose.density) { control.fetchSemanticsNode().size.height.toDp().value }
        assertTrue("offline recognizer control must have a 48 dp target, was $heightDp dp", heightDp >= 48f)

        control.performClick()
        selectedOffline.assertIsSelected()
    }
}
