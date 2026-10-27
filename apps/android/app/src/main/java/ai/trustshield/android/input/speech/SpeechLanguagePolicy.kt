package ai.trustshield.android.input.speech

import ai.trustshield.android.data.preferences.AppLanguage
import ai.trustshield.android.data.preferences.resolveLanguage

/** Maps the app language choice to the Android locale used by speech recognition. */
object SpeechLanguagePolicy {
    fun selectedTag(language: AppLanguage, systemLanguageTag: String? = null): String =
        when (resolveLanguage(language, systemLanguageTag)) {
            AppLanguage.ENGLISH -> SpeechLanguage.EN_IN
            AppLanguage.SYSTEM, AppLanguage.HINGLISH, AppLanguage.HINDI -> SpeechLanguage.HI_IN
        }

    /** Hinglish starts with Hindi recognition and falls back to English if Android lacks hi-IN. */
    fun fallbackTag(language: AppLanguage, currentTag: String): String? =
        SpeechLanguage.EN_IN.takeIf {
            language == AppLanguage.HINGLISH && currentTag == SpeechLanguage.HI_IN
        }
}

/** Changes locale after two consecutive final results are predominantly in the other script. */
class SpeechScriptLanguageDetector(initialLanguageTag: String) {
    var languageTag: String = initialLanguageTag
        private set

    private var candidateTag: String? = null
    private var candidateCount = 0

    init {
        require(SpeechLanguage.isSelectable(initialLanguageTag))
    }

    /** Returns the changed tag only when a second consecutive final result confirms it. */
    fun observeFinal(text: String): String? {
        val devanagari = text.count { it in '\u0900'..'\u097F' }
        val latin = text.count { it in 'A'..'Z' || it in 'a'..'z' }
        val observedTag = when {
            devanagari > latin -> SpeechLanguage.HI_IN
            latin > devanagari -> SpeechLanguage.EN_IN
            else -> null
        }
        if (observedTag == null || observedTag == languageTag) {
            candidateTag = null
            candidateCount = 0
            return null
        }
        if (candidateTag == observedTag) candidateCount++ else {
            candidateTag = observedTag
            candidateCount = 1
        }
        if (candidateCount < 2) return null

        languageTag = observedTag
        candidateTag = null
        candidateCount = 0
        return languageTag
    }
}
