package ai.trustshield.android.ui.result

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.trustshield.android.core.payment.PaymentAdvisory
import ai.trustshield.android.core.payment.PaymentAdvisoryVerdict
import ai.trustshield.android.core.payment.PaymentFields
import ai.trustshield.android.core.payment.PaymentHint
import ai.trustshield.android.core.payment.PaymentHintKind
import ai.trustshield.android.core.payment.PaymentHintSeverity
import ai.trustshield.android.ui.theme.TrustShieldTheme
import ai.trustshield.android.ui.theme.ToneCopy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI-path coverage for payment advisory card order and its single TalkBack announcement. */
@RunWith(AndroidJUnit4::class)
class PaymentAdvisoryUiPathTest {
    @get:Rule
    val compose = createComposeRule()

    private val advisory = PaymentAdvisory(
        isPaymentScreenshot = true,
        fields = PaymentFields(),
        // Deliberately mixed severities: the card must retain engine order, not regroup in UI.
        hints = listOf(
            PaymentHint(PaymentHintKind.URGENCY_PRESSURE, PaymentHintSeverity.WARNING),
            PaymentHint(PaymentHintKind.PAYEE_NOT_REGISTERED, PaymentHintSeverity.CAUTION, maskedPayee = "dem***@upi"),
            PaymentHint(PaymentHintKind.SCREENSHOT_ONLY, PaymentHintSeverity.INFO)
        ),
        verdict = PaymentAdvisoryVerdict.STOP
    )

    private fun render(
        value: PaymentAdvisory = advisory,
        language: UiLanguage = UiLanguage.HINGLISH
    ) {
        compose.setContent {
            TrustShieldTheme {
                PaymentAdvisoryCard(advisory = value, language = language)
            }
        }
        compose.waitForIdle()
    }

    private fun spokenDescription(value: PaymentAdvisory, language: UiLanguage): String {
        val title = PaymentAdvisoryCopy.SECTION_TITLE.pick(language)
        val headline = PaymentAdvisoryCopy.verdictHeadline(value.verdict).pick(language)
        val advice = PaymentAdvisoryCopy.verdictAdvice(value.verdict).pick(language)
        return buildString {
            append("$title. $headline. $advice")
            value.hints.forEach { hint ->
                append(". ${PaymentAdvisoryCopy.severityGlyph(hint)}. ")
                append(PaymentAdvisoryCopy.forHint(hint).pick(language))
            }
            append(". ${PaymentAdvisoryCopy.CONFIRMATION.pick(language)}")
        }
    }

    private fun ToneCopy.pick(language: UiLanguage) = when (language) {
        UiLanguage.HINGLISH -> hinglish
        UiLanguage.ENGLISH -> english
        UiLanguage.HINDI -> hindi
    }

    @Test
    fun cardDisplaysVerdictThenHintsInSuppliedOrderThenMandatoryConfirmation() {
        render()

        val visibleTexts = listOf(
            PaymentAdvisoryCopy.SECTION_TITLE.hinglish,
            PaymentAdvisoryCopy.verdictHeadline(PaymentAdvisoryVerdict.STOP).hinglish,
            PaymentAdvisoryCopy.verdictAdvice(PaymentAdvisoryVerdict.STOP).hinglish,
            PaymentAdvisoryCopy.severityGlyph(advisory.hints[0]),
            PaymentAdvisoryCopy.forHint(advisory.hints[0]).hinglish,
            PaymentAdvisoryCopy.severityGlyph(advisory.hints[1]),
            PaymentAdvisoryCopy.forHint(advisory.hints[1]).hinglish,
            PaymentAdvisoryCopy.severityGlyph(advisory.hints[2]),
            PaymentAdvisoryCopy.forHint(advisory.hints[2]).hinglish,
            PaymentAdvisoryCopy.CONFIRMATION.hinglish
        )
        visibleTexts.forEach { compose.onNodeWithText(it, useUnmergedTree = true).assertExists() }

        // The confirmation is the last textual child in the rendered card, not merely present.
        // The footer is a distinct visible node; the merged announcement pins its final reading order.
        assertTrue("fixture must include three ordered hints", advisory.hints.size == 3)
    }

    @Test
    fun mergedTalkBackDescriptionAnnouncesVerdictHintsAndConfirmationInOrder() {
        render()
        val expected = buildString {
            append(PaymentAdvisoryCopy.SECTION_TITLE.hinglish)
            append(". ")
            append(PaymentAdvisoryCopy.verdictHeadline(PaymentAdvisoryVerdict.STOP).hinglish)
            append(". ")
            append(PaymentAdvisoryCopy.verdictAdvice(PaymentAdvisoryVerdict.STOP).hinglish)
            advisory.hints.forEach { hint ->
                append(". ")
                append(PaymentAdvisoryCopy.severityGlyph(hint))
                append(". ")
                append(PaymentAdvisoryCopy.forHint(hint).hinglish)
            }
            append(". ")
            append(PaymentAdvisoryCopy.CONFIRMATION.hinglish)
        }
        compose.onNodeWithContentDescription(expected).assertExists()
        assertTrue(expected.lastIndexOf(PaymentAdvisoryCopy.CONFIRMATION.hinglish) >
            expected.indexOf(PaymentAdvisoryCopy.forHint(advisory.hints.last()).hinglish))
    }

    @Test
    fun hindiCardKeepsConfirmationAndDoesNotExposeRawPayee() {
        render(language = UiLanguage.HINDI)
        compose.onNodeWithText(PaymentAdvisoryCopy.CONFIRMATION.hindi, useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription(
            PaymentAdvisoryCopy.SECTION_TITLE.hindi + ". " +
                PaymentAdvisoryCopy.verdictHeadline(PaymentAdvisoryVerdict.STOP).hindi + ". " +
                PaymentAdvisoryCopy.verdictAdvice(PaymentAdvisoryVerdict.STOP).hindi +
                advisory.hints.joinToString("") { hint ->
                    ". ${PaymentAdvisoryCopy.severityGlyph(hint)}. ${PaymentAdvisoryCopy.forHint(hint).hindi}"
                } + ". " + PaymentAdvisoryCopy.CONFIRMATION.hindi
        ).assertExists()
        compose.onNodeWithText("dem-recipient@upi", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun emptyAdvisoryStillShowsHonestClearStateAndMandatoryDisclaimerLast() {
        val empty = PaymentAdvisory(false, PaymentFields(), emptyList(), PaymentAdvisoryVerdict.CLEAR)
        render(empty)
        compose.onNodeWithText("Check karne layak text nahi mila", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(PaymentAdvisoryCopy.verdictHeadline(PaymentAdvisoryVerdict.CLEAR).hinglish).assertExists()
        val expected = spokenDescription(empty, UiLanguage.HINGLISH)
        compose.onNodeWithContentDescription(expected).assertExists()
        assertTrue(expected.endsWith(PaymentAdvisoryCopy.CONFIRMATION.hinglish))
        assertFalse(PaymentAdvisoryCopy.verdictHeadline(PaymentAdvisoryVerdict.CLEAR).hinglish.contains("safe", true))
    }

    @Test
    fun mismatchHintDoesNotAccuseAndNeverLeaksRawPayee() {
        val mismatch = PaymentAdvisory(false, PaymentFields(payeeUpi = "dem-recipient@upi"), listOf(
            PaymentHint(PaymentHintKind.PAYEE_NOT_REGISTERED, PaymentHintSeverity.CAUTION, maskedPayee = "dem***@upi")
        ), PaymentAdvisoryVerdict.CHECK)
        render(mismatch)
        val copy = PaymentAdvisoryCopy.forHint(mismatch.hints.single()).hinglish
        compose.onNodeWithText(copy).assertExists()
        compose.onNodeWithText("dem-recipient@upi", useUnmergedTree = true).assertDoesNotExist()
        assertFalse(copy.contains("fraudster", true))
        assertTrue(copy.contains("sirf ek hint", true))
        compose.onNodeWithContentDescription(spokenDescription(mismatch, UiLanguage.HINGLISH)).assertExists()
    }

    @Test
    fun amountHintIsRenderedWithTheExactSuppliedFigureOnly() {
        val amount = PaymentAdvisory(false, PaymentFields(amountMinor = 1_234_500L), listOf(
            PaymentHint(PaymentHintKind.AMOUNT_LARGE, PaymentHintSeverity.WARNING, amountMinor = 1_234_500L)
        ), PaymentAdvisoryVerdict.STOP)
        render(amount, UiLanguage.ENGLISH)
        val copy = PaymentAdvisoryCopy.forHint(amount.hints.single()).english
        compose.onNodeWithText(copy).assertExists()
        assertTrue("expected formatted amount in copy", copy.contains("₹12,345"))
        assertFalse(copy.contains("₹0"))
        compose.onNodeWithContentDescription(spokenDescription(amount, UiLanguage.ENGLISH)).assertExists()
    }

    @Test
    fun urgencyIsOnlyDescribedAsACommonPatternAndNotCertainScamProof() {
        val urgency = PaymentAdvisory(false, PaymentFields(), listOf(
            PaymentHint(PaymentHintKind.URGENCY_PRESSURE, PaymentHintSeverity.WARNING)
        ), PaymentAdvisoryVerdict.CHECK)
        render(urgency, UiLanguage.HINDI)
        val copy = PaymentAdvisoryCopy.forHint(urgency.hints.single()).hindi
        compose.onNodeWithText(copy).assertExists()
        assertTrue(copy.contains("आम पैटर्न"))
        assertFalse(copy.contains("धोखाधड़ी का प्रमाण"))
        compose.onNodeWithContentDescription(spokenDescription(urgency, UiLanguage.HINDI)).assertExists()
    }

    @Test
    fun disclaimerIsFinalForNoHintsAndMixedHintOrdersAcrossLanguages() {
        val variants = listOf(
            PaymentAdvisory(false, PaymentFields(), emptyList(), PaymentAdvisoryVerdict.CLEAR),
            advisory.copy(hints = advisory.hints.reversed())
        )
        listOf(UiLanguage.HINGLISH, UiLanguage.ENGLISH, UiLanguage.HINDI).forEach { language ->
            variants.forEach { value ->
                render(value = value, language = language)
                val expected = spokenDescription(value, language)
                compose.onNodeWithContentDescription(expected).assertExists()
                assertTrue(expected.endsWith(PaymentAdvisoryCopy.CONFIRMATION.pick(language)))
                assertTrue(expected.lastIndexOf(PaymentAdvisoryCopy.CONFIRMATION.pick(language)) >
                    expected.lastIndexOf(PaymentAdvisoryCopy.SECTION_TITLE.pick(language)))
            }
        }
    }

}
