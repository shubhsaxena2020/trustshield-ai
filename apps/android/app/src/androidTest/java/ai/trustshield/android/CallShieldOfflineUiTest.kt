package ai.trustshield.android

import android.content.Intent
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
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CallShieldOfflineUiTest {

    @get:Rule
    val compose: ComposeTestRule = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun closeActivity() {
        scenario?.close()
    }

    @Test
    fun offlineControlHasPhoneSizedTargetAndSelectedStateChangesOnTap() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra(AppRoute.EXTRA_ROUTE, AppRoute.CALL_SHIELD.wireName)
        scenario = ActivityScenario.launch(intent)

        val chooseOffline = compose.onNode(hasText("Use offline mode", substring = true))
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasText("Use offline mode", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        chooseOffline.assertIsNotSelected()
        val heightDp = with(compose.density) { chooseOffline.fetchSemanticsNode().size.height.toDp().value }
        assertTrue("offline recognizer control must have a 48 dp target, was $heightDp dp", heightDp >= 48f)

        chooseOffline.performClick()
        compose.onNode(hasText("Offline mode selected", substring = true)).assertIsSelected()
    }
}
