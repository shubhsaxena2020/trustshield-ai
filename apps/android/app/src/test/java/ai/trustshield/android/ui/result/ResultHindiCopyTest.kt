package ai.trustshield.android.ui.result

import ai.trustshield.android.ui.theme.RiskTone
import ai.trustshield.android.ui.theme.ToneCopy
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ResultHindiCopyTest {
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
}
