package ai.trustshield.android.ui.bomb

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.trustshield.android.core.flood.BombShieldRuntime
import ai.trustshield.android.data.preferences.AppLanguage
import ai.trustshield.android.ui.theme.TrustShieldTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BombShieldJourneyTest {
    @get:Rule val compose = createComposeRule()

    @After fun resetRuntime() {
        BombShieldRuntime.reset()
        BombShieldRuntime.setEnabled(InstrumentationRegistry.getInstrumentation().targetContext, false)
    }

    @Test fun smsAndCallDemosUseDetectorAndRenderFloodAlert() {
        BombShieldRuntime.reset()
        compose.setContent { TrustShieldTheme { BombShieldScreen(AppLanguage.ENGLISH) } }

        compose.onNodeWithText("Simulate SMS bomb").performScrollTo().performClick()
        compose.onNodeWithText("Message flood: 8 messages in this window").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Mute for 30 min", substring = true).performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("Simulate call bomb").performScrollTo().performClick()
        compose.onNodeWithText("Call flood: 4 calls in this window").performScrollTo().assertIsDisplayed()
    }

    @Test fun enabledFeatureWithRevokedPermissionsShowsMissingState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // The emulator test environment starts with these ungranted; opting-in cannot claim protection.
        BombShieldRuntime.setEnabled(context, true)
        compose.setContent { TrustShieldTheme { BombShieldScreen(AppLanguage.ENGLISH) } }

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Permission missing").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Permission missing").assertIsDisplayed()
        compose.onNodeWithText("Required SMS or call permissions are missing. Review Bomb Shield permissions in system settings.")
            .performScrollTo().assertIsDisplayed()
        assertTrue("permission denial path must leave collection disabled", !BombShieldRuntime.isEnabled(context))
    }
}
