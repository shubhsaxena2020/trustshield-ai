package ai.trustshield.android.ui.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import ai.trustshield.android.ui.theme.TrustShieldTheme
import org.junit.Rule
import org.junit.Test

class ScanScreenLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun checkActionStaysVisibleAtOnePointFiveFontScale() {
        compose.setContent {
            TrustShieldTheme {
                CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.5f)) {
                    ScanScreen(
                        state = ScanInputState(),
                        onEvent = {},
                        onCheck = {},
                        onPickImage = {}
                    )
                }
            }
        }
        compose.onNodeWithTag("check_button").assertIsDisplayed()
    }
}
