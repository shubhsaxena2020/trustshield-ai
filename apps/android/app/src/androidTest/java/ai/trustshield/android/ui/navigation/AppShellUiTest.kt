package ai.trustshield.android.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import ai.trustshield.android.data.preferences.AppLanguage
import ai.trustshield.android.data.preferences.UserPreferences
import ai.trustshield.android.ui.theme.TrustShieldTheme
import org.junit.Rule
import org.junit.Test

class AppShellUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun bottomTabsNavigateAcrossAllFiveDestinations() {
        compose.setContent {
            TrustShieldTheme {
                CompositionLocalProvider(
                    LocalUserPreferences provides UserPreferences.defaults(),
                    LocalPreferencesSink provides {}
                ) {
                    AppNavHost { route -> Text(route.wireName) }
                }
            }
        }

        val tabs = listOf(
            "nav_home" to "home",
            "nav_call_shield" to "call_shield",
            "nav_qr_guard" to "qr_guard",
            "nav_history" to "history",
            "nav_settings" to "settings"
        )
        tabs.forEach { (tag, routeName) ->
            compose.onNodeWithTag(tag).performClick()
            compose.onNodeWithTag("app_shell").assertIsDisplayed()
            compose.onNodeWithText(routeName).assertIsDisplayed()
        }
    }

    @Test
    fun languageMenuUpdatesCurrentLanguage() {
        var preferences by mutableStateOf(UserPreferences.defaults())
        compose.setContent {
            TrustShieldTheme {
                CompositionLocalProvider(
                    LocalUserPreferences provides preferences,
                    LocalPreferencesSink provides { preferences = it }
                ) {
                    AppNavHost { route -> Text(route.wireName) }
                }
            }
        }

        compose.onNodeWithTag("language_picker").performClick()
        compose.onNodeWithTag("language_${AppLanguage.HINDI.name.lowercase()}").performClick()
        compose.onNodeWithTag("language_picker").assertTextContains("हिन्दी")
    }
}
