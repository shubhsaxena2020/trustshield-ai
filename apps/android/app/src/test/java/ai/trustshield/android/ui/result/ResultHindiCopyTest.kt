package ai.trustshield.android.ui.result

import ai.trustshield.android.ui.theme.RiskTone
import ai.trustshield.android.ui.theme.ToneCopy
import ai.trustshield.android.ui.theme.HindiCopy
import ai.trustshield.android.ui.navigation.LocalEvidenceAdapter
import ai.trustshield.android.core.model.Evidence
import ai.trustshield.android.core.model.Severity
import ai.trustshield.android.core.model.Strength
import ai.trustshield.android.core.score.LocalScorer
import java.io.File
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultHindiCopyTest {
    @Test
    fun everyCoreRuleHasSpecificHindiTitleAndDescription() {
        val rules = listOf(
            File("src/main/java/ai/trustshield/android/core/rules"),
            File("apps/android/app/src/main/java/ai/trustshield/android/core/rules"),
        ).firstOrNull(File::isDirectory) ?: error("Cannot locate deterministic core rules")
        val titlePattern = Regex("titleHinglish\\s*=\\s*\"([^\"]+)\"")
        val titles = rules.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> titlePattern.findAll(file.readText()).map { it.groupValues[1] } }
            .toList()

        assertTrue("No rule titles found", titles.isNotEmpty())
        titles.forEach { title ->
            val result = LocalEvidenceAdapter.evidence(
                Evidence.unquoted(
                    module = "stage_rules",
                    titleHinglish = title,
                    detail = "Rule description",
                    severity = Severity.LOW,
                    strength = Strength.WEAK,
                )
            )
            assertNotEquals("Generic result title for rule: $title", "Signal detected", result.titleEnglish)
            assertHindiTranslation(title, result.titleEnglish)
            assertHindiTranslation(title, result.detailEnglish)
        }
    }

    @Test
    fun presenterActionsAndLimitationsHaveHindiCopy() {
        listOf(
            LocalScorer.ACTION_HIGH_STOP,
            LocalScorer.ACTION_HIGH_VERIFY,
            LocalScorer.ACTION_HIGH_REPORT_MONEY,
            LocalScorer.ACTION_CAUTION_ASK,
            LocalScorer.ACTION_LOW_WATCH,
            LocalScorer.ACTION_CAUTION_WATCH,
            LocalScorer.ACTION_UNABLE_HOLD,
            LocalScorer.ACTION_UNABLE_RETRY,
        ).forEachIndexed { index, line ->
            val action = LocalEvidenceAdapter.action(line, index)
            assertHindiTranslation("action $line", action.labelEnglish)
            assertHindiTranslation("action detail $line", action.detailEnglish)
        }

        listOf(
            LocalScorer.LIMIT_UNSUPPORTED_SCRIPT_PREFIX + "Tamil",
            LocalScorer.LIMIT_TRUNCATED,
            LocalScorer.LIMIT_NO_TEXT,
            LocalScorer.LIMIT_TOO_LITTLE,
            LocalScorer.LIMIT_LOW_ASR,
            LocalScorer.LIMIT_NO_EVIDENCE,
            LocalScorer.LIMIT_WINDOW_DROPPED,
        ).forEach { limitation ->
            assertHindiTranslation(limitation, LocalEvidenceAdapter.limitation(limitation))
        }
    }

    @Test
    fun everyStaticResultMessageHasHindiCopy() {
        val messages = buildList {
            add(ToneCopy(ResultCopy.TITLE_HINGLISH, ResultCopy.TITLE_ENGLISH))
            addAll(
                listOf(
                    ResultCopy.EVIDENCE_SECTION, ResultCopy.ACTIONS_SECTION, ResultCopy.LIMITS_SECTION,
                    ResultCopy.QUOTE_LABEL, ResultCopy.QUOTE_TRUNCATED, ResultCopy.CONFIDENCE_TITLE,
                    ResultCopy.RISK_TITLE, ResultCopy.LANGUAGE_TOGGLE_DESCRIPTION,
                    ResultCopy.ABSOLUTE_CLAIM_REPLACEMENT, ResultCopy.VERDICT_NOTE, ResultCopy.NOT_PROOF,
                    ResultCopy.DROPPED_REASON, ResultCopy.DROPPED_REASON_EMPTY, ResultCopy.UNABLE_LABEL,
                    ResultCopy.UNABLE_SUMMARY, ResultCopy.UNABLE_CONTENT_DESCRIPTION,
                    ResultCopy.UNSUPPORTED_LANGUAGE_FAILED, ResultCopy.UNREADABLE_FAILED,
                    ResultCopy.NO_EVIDENCE_FAILED, ResultCopy.SAFEST_NEXT_STEP, ResultCopy.BACKEND_OFF_LABEL,
                    ResultCopy.BACKEND_OFF_DETAIL, ResultCopy.BACKEND_ON_LABEL, ResultCopy.BACKEND_ON_DETAIL,
                    ResultCopy.DEFAULT_LIMITATION_SOURCE_SCOPE, ResultCopy.DEFAULT_LIMITATION_NOT_PROOF,
                    ResultCopy.DEFAULT_LIMITATION_OFFLINE, ResultCopy.DEFAULT_LIMITATION_LANGUAGE,
                    ResultCopy.ANALYSED_CHARS
                )
            )
            RiskTone.entries.forEach { add(ResultCopy.bandSummary(it)) }
            ConfidenceLevel.entries.forEach {
                add(ResultCopy.confidenceLabel(it))
                add(ResultCopy.confidenceReason(it))
            }
        }

        messages.forEach { message ->
            assertNotEquals("Missing Hindi Result copy for: ${message.english}", message.english, message.hindi)
        }
    }

    private fun assertHindiTranslation(context: String, english: String) {
        val hindi = HindiCopy.forEnglish(english)
        assertTrue("Missing Hindi copy for $context: $english", hindi != english && hindi.any { it in '\u0900'..'\u097F' })
    }
}
