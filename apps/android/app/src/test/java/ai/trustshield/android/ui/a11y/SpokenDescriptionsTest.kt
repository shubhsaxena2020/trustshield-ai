package ai.trustshield.android.ui.a11y

import ai.trustshield.android.ui.call.AlertSeverity
import ai.trustshield.android.ui.call.CallLanguage
import ai.trustshield.android.ui.call.CallShieldCopy
import ai.trustshield.android.ui.call.ConfidenceLevel
import ai.trustshield.android.ui.call.forLanguage
import ai.trustshield.android.ui.result.ResultCopy
import ai.trustshield.android.ui.result.UiLanguage
import ai.trustshield.android.ui.result.pick
import ai.trustshield.android.ui.theme.RiskTone
import ai.trustshield.android.ui.theme.RiskToneCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #97 regression suite for the TalkBack announcements built by [SpokenDescriptions].
 *
 * The defect class this pins is *silent omission*: a card merges its children into one semantics
 * node and sets an explicit `contentDescription`, so any visible sentence the builder forgets is not
 * spoken at all. These tests assert against the real shipping copy objects (`CallShieldCopy`,
 * `ResultCopy`, `RiskToneCopy`), so a future copy change cannot quietly drop a sentence from an
 * announcement. Plain JVM, no device (`testDebugUnitTest`).
 */
class SpokenDescriptionsTest {

    private val callLanguages = listOf(CallLanguage.HINGLISH, CallLanguage.ENGLISH)
    private val resultLanguages = listOf(UiLanguage.HINGLISH, UiLanguage.ENGLISH)

    // ---------------------------------------------------------------- merge() mechanics

    @Test
    fun merge_skipsBlankAndNullParts() {
        assertEquals("Ek baat.", SpokenDescriptions.merge(null, "  ", "", "Ek baat"))
        assertEquals("", SpokenDescriptions.merge(null, "   "))
    }

    @Test
    fun merge_collapsesWhitespaceRuns() {
        assertEquals(
            "Do baat. Teen baat.",
            SpokenDescriptions.merge("Do    baat", "\n Teen\tbaat ")
        )
    }

    @Test
    fun merge_announcesAnIdenticalPartOnlyOnce() {
        assertEquals("Wahi baat.", SpokenDescriptions.merge("Wahi baat", "Wahi baat.", "Wahi baat"))
    }

    @Test
    fun merge_givesEveryPartTerminalPunctuation() {
        assertEquals("A. B? C! D\u2026", SpokenDescriptions.merge("A", "B?", "C!", "D\u2026"))
    }

    @Test
    fun merge_neverLeavesDoubleOrEdgeSpaces() {
        val merged = SpokenDescriptions.merge("  A  ", "", null, " B ")
        assertEquals("A. B.", merged)
        assertFalse(merged.contains("  "))
        assertEquals(merged.trim(), merged)
    }

    // ---------------------------------------------------------------- Call Shield warning banner

    @Test
    fun warningBanner_announcesTheRiskBandSoTheLevelIsHeard() {
        for (tone in RiskTone.entries) {
            val presentation = RiskToneCopy.of(tone)
            for (language in callLanguages) {
                val label = presentation.label.forLanguage(language)
                val body = CallShieldCopy.warning(AlertSeverity.CRITICAL).forLanguage(language)
                val noAccusation = CallShieldCopy.noAccusation.forLanguage(language)
                val spoken = SpokenDescriptions.warningBanner(label, body, noAccusation)
                println("  banner[$tone/$language] = $spoken")
                assertTrue("band label missing: $spoken", spoken.contains(label))
                assertTrue("warning body missing: $spoken", spoken.contains(body.trim()))
                assertTrue("no-accusation line missing: $spoken", spoken.contains(noAccusation.trim()))
                assertTrue("band label is not announced first: $spoken", spoken.startsWith(label))
            }
        }
    }

    // ---------------------------------------------------------------- Call Shield risk meter

    @Test
    fun riskMeter_keepsBothHonestyExplainersInTheAnnouncement() {
        for (tone in RiskTone.entries) {
            val presentation = RiskToneCopy.of(tone)
            for (language in callLanguages) {
                val confidenceLabel = CallShieldCopy.confidenceLevel(ConfidenceLevel.HIGH)
                    .forLanguage(language)
                val spoken = SpokenDescriptions.riskMeter(
                    toneLabel = presentation.label.forLanguage(language),
                    toneDescription = presentation.contentDescription.forLanguage(language),
                    confidenceHeading = CallShieldCopy.confidenceHeading.forLanguage(language),
                    confidenceLabel = confidenceLabel,
                    confidenceExplainer = CallShieldCopy.confidenceExplainer.forLanguage(language),
                    riskExplainer = CallShieldCopy.riskExplainer.forLanguage(language)
                )
                println("  meter[$tone/$language] = $spoken")
                assertTrue(
                    "confidence explainer dropped: $spoken",
                    spoken.contains(CallShieldCopy.confidenceExplainer.forLanguage(language).trim())
                )
                assertTrue(
                    "risk explainer dropped: $spoken",
                    spoken.contains(CallShieldCopy.riskExplainer.forLanguage(language).trim())
                )
                assertTrue(
                    "confidence reading dropped: $spoken",
                    spoken.contains(
                        CallShieldCopy.confidenceHeading.forLanguage(language) + ": " + confidenceLabel
                    )
                )
                assertTrue(
                    "band label dropped: $spoken",
                    spoken.contains(presentation.label.forLanguage(language))
                )
            }
        }
    }

    @Test
    fun riskMeter_saysUnableToVerifyOutLoudRatherThanOmittingTheLevel() {
        for (language in callLanguages) {
            val spoken = SpokenDescriptions.riskMeter(
                toneLabel = RiskToneCopy.of(RiskTone.NEUTRAL).label.forLanguage(language),
                toneDescription = RiskToneCopy.of(RiskTone.NEUTRAL).contentDescription
                    .forLanguage(language),
                confidenceHeading = CallShieldCopy.confidenceHeading.forLanguage(language),
                confidenceLabel = CallShieldCopy.confidenceLevel(ConfidenceLevel.UNABLE_TO_VERIFY)
                    .forLanguage(language),
                confidenceExplainer = CallShieldCopy.confidenceExplainer.forLanguage(language),
                riskExplainer = CallShieldCopy.riskExplainer.forLanguage(language)
            )
            assertTrue(spoken, spoken.contains(
                CallShieldCopy.confidenceLevel(ConfidenceLevel.UNABLE_TO_VERIFY)
                    .forLanguage(language)
            ))
        }
    }

    // ---------------------------------------------------------------- Result risk card

    @Test
    fun resultRiskCard_announcesTheBandSummaryCaveat() {
        for (tone in RiskTone.entries) {
            val summary = ResultCopy.bandSummary(tone)
            for (language in resultLanguages) {
                val spoken = SpokenDescriptions.resultRiskCard(
                    riskDescription = RiskToneCopy.of(tone).contentDescription.pick(language),
                    summary = summary.pick(language)
                )
                println("  card[$tone/$language] = $spoken")
                assertTrue(
                    "band summary (the caveat) dropped: $spoken",
                    spoken.contains(summary.pick(language).trim())
                )
                assertTrue(
                    "band description dropped: $spoken",
                    spoken.contains(RiskToneCopy.of(tone).contentDescription.pick(language))
                )
            }
        }
    }
}
