package ai.trustshield.android.ui.voice

import ai.trustshield.android.data.preferences.AppLanguage
import ai.trustshield.android.data.preferences.resolveLanguage
import ai.trustshield.android.input.speech.SpeechLanguage

/** Content approved for speech. User-entered message text is deliberately not represented here. */
data class SpokenVerdictContent(
    val riskLevel: String,
    val advice: String,
    val evidenceTitles: List<String>,
)

interface SpokenVerdictTts {
    fun hasVoice(languageTag: String): Boolean
    fun speak(text: String, languageTag: String): Boolean
    fun setSpeechRate(rate: Float) = Unit
    fun stop()
}

enum class SpokenVerdictFailure { NO_VOICE, LANGUAGE_DATA }

sealed interface SpokenVerdictResult {
    data class Spoken(val languageTag: String) : SpokenVerdictResult
    data class TextOnly(val reason: SpokenVerdictFailure) : SpokenVerdictResult
}

class SpokenVerdictSpeaker(private val tts: SpokenVerdictTts) {
    fun speak(
        language: AppLanguage,
        content: SpokenVerdictContent,
        systemLanguageTag: String? = null,
    ): SpokenVerdictResult {
        val resolved = resolveLanguage(language, systemLanguageTag)
        val primaryTag = if (resolved == AppLanguage.ENGLISH) SpeechLanguage.EN_IN else SpeechLanguage.HI_IN
        val candidates = if (resolved == AppLanguage.ENGLISH) {
            listOf(SpeechLanguage.EN_IN, SpeechLanguage.EN_US)
        } else if (primaryTag == SpeechLanguage.HI_IN) {
            // Hinglish verdicts contain Hindi advice and labels, so the local hi-IN voice
            // preserves the dominant pronunciation; en-IN remains a tested fallback.
            listOf(SpeechLanguage.HI_IN, SpeechLanguage.EN_IN)
        } else {
            listOf(SpeechLanguage.EN_IN)
        }
        val text = buildList {
            add(content.riskLevel)
            add(content.advice)
            addAll(content.evidenceTitles)
        }.filter(String::isNotBlank).joinToString(". ")
        var voiceFound = false
        for (tag in candidates) {
            if (!tts.hasVoice(tag)) continue
            voiceFound = true
            if (tts.speak(text, tag)) return SpokenVerdictResult.Spoken(tag)
        }
        return SpokenVerdictResult.TextOnly(
            if (voiceFound) SpokenVerdictFailure.LANGUAGE_DATA else SpokenVerdictFailure.NO_VOICE,
        )
    }

    fun stop() = tts.stop()
}
