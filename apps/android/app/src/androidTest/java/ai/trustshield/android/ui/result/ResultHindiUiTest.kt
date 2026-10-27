package ai.trustshield.android.ui.result

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.isText
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.fetchSemanticsNodes
import androidx.compose.ui.semantics.SemanticsProperties
import ai.trustshield.android.ui.theme.TrustShieldTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ResultHindiUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun HindiResultHasNoLatinOnlyParagraphOutsideExactQuotes() {
        val state = ResultFixtures.digitalArrest
        val exactQuotes = state.evidence.map { it.quote.text }
        compose.setContent {
            TrustShieldTheme {
                ResultScreen(state = state, language = UiLanguage.HINDI)
            }
        }

        val latinOnlyText = compose.onAllNodes(isText(), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .mapNotNull { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text } }
            .filter { text -> text.any(Char::isLetter) && text.none { it in '\u0900'..'\u097F' } }

        assertTrue(
            "Latin-only Result copy outside exact evidence quotes: $latinOnlyText",
            latinOnlyText.all { rendered -> exactQuotes.any { quote -> rendered.contains(quote) } },
        )
    }
}
