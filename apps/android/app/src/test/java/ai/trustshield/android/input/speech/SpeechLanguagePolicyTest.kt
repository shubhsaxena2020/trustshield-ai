package ai.trustshield.android.input.speech

import ai.trustshield.android.data.preferences.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechLanguagePolicyTest {
    @Test
    fun selectedAppLanguageUsesHindiForHindiAndHinglishAndEnglishForEnglish() {
        assertEquals(SpeechLanguage.HI_IN, SpeechLanguagePolicy.selectedTag(AppLanguage.HINDI))
        assertEquals(SpeechLanguage.HI_IN, SpeechLanguagePolicy.selectedTag(AppLanguage.HINGLISH))
        assertEquals(SpeechLanguage.EN_IN, SpeechLanguagePolicy.selectedTag(AppLanguage.ENGLISH))
        assertEquals(SpeechLanguage.HI_IN, SpeechLanguagePolicy.selectedTag(AppLanguage.SYSTEM, "hi-IN"))
        assertEquals(SpeechLanguage.EN_IN, SpeechLanguagePolicy.selectedTag(AppLanguage.SYSTEM, "en-IN"))
    }

    @Test
    fun scriptSwitchNeedsTwoConsecutiveOtherScriptFinalResults() {
        val detector = SpeechScriptLanguageDetector(SpeechLanguage.HI_IN)
        assertNull(detector.observeFinal("The caller says send money"))
        assertEquals(SpeechLanguage.EN_IN, detector.observeFinal("Your bank account is blocked"))
        assertEquals(SpeechLanguage.EN_IN, detector.languageTag)
    }

    @Test
    fun sameScriptAndMixedAmbiguousResultsResetTheCandidate() {
        val detector = SpeechScriptLanguageDetector(SpeechLanguage.HI_IN)
        assertNull(detector.observeFinal("The caller says send money"))
        assertNull(detector.observeFinal("क A"))
        assertNull(detector.observeFinal("The caller says send money"))
        assertEquals(SpeechLanguage.EN_IN, detector.observeFinal("The caller says send money"))
        assertEquals(SpeechLanguage.EN_IN, detector.languageTag)
    }

    @Test
    fun hindiScriptCanSwitchBackAfterTwoFinalResults() {
        val detector = SpeechScriptLanguageDetector(SpeechLanguage.EN_IN)
        detector.observeFinal("कृपया अपना खाता सत्यापित करें")
        assertEquals(SpeechLanguage.HI_IN, detector.observeFinal("अभी पैसे न भेजें"))
    }
}
