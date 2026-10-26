package ai.trustshield.android.ui.home

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.trustshield.android.ui.theme.TrustShieldTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScanScreenEmptyStateTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun emptyTextNoticeAppearsOnlyAfterAnEmptyCheckAttempt() {
        val state = mutableStateOf(ScanInputState())
        compose.setContent {
            TrustShieldTheme {
                ScanScreen(
                    state = state.value,
                    onEvent = { state.value = reduce(state.value, it) },
                    onCheck = {},
                    onPickImage = {}
                )
            }
        }

        compose.onAllNodesWithText(ScanCopy.emptyHint.hinglish).assertCountEquals(0)
        compose.runOnIdle {
            state.value = reduce(state.value, ScanInputEvent.CheckRequested)
        }
        compose.onNodeWithText(ScanCopy.emptyHint.hinglish).performScrollTo().assertIsDisplayed()
    }
}
