package ai.trustshield.android.ui.voice

import ai.trustshield.android.data.preferences.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenVerdictTest {
    @Test
    fun speaksOnlyVerdictAdviceAndExactEvidenceTitlesInEnglish() {
        val tts = FakeTts(setOf("en-IN", "hi-IN"))
        val speaker = SpokenVerdictSpeaker(tts)

        val result = speaker.speak(AppLanguage.ENGLISH, content)

        assertEquals(SpokenVerdictResult.Spoken("en-IN"), result)
        assertEquals("High risk. Call the official number. Authority evidence. OTP request", tts.spoken.single().text)
        assertEquals("en-IN", tts.spoken.single().languageTag)
    }

    @Test
    fun HindiUsesHindiVoiceWhenInstalledAndEnglishVoiceAsFallback() {
        val hindi = FakeTts(setOf("hi-IN", "en-IN"))
        assertEquals(SpokenVerdictResult.Spoken("hi-IN"), SpokenVerdictSpeaker(hindi).speak(AppLanguage.HINDI, content))

        val englishFallback = FakeTts(setOf("en-IN"))
        assertEquals(SpokenVerdictResult.Spoken("en-IN"), SpokenVerdictSpeaker(englishFallback).speak(AppLanguage.HINDI, content))
    }

    @Test
    fun missingVoiceOrLanguageDataFallsBackToTextOnly() {
        val tts = FakeTts(emptySet())
        val result = SpokenVerdictSpeaker(tts).speak(AppLanguage.HINDI, content)
        assertEquals(SpokenVerdictResult.TextOnly(SpokenVerdictFailure.NO_VOICE), result)
        assertTrue(tts.spoken.isEmpty())

        val unavailableData = FakeTts(setOf("hi-IN"), speakSucceeds = false)
        assertEquals(
            SpokenVerdictResult.TextOnly(SpokenVerdictFailure.LANGUAGE_DATA),
            SpokenVerdictSpeaker(unavailableData).speak(AppLanguage.HINDI, content),
        )
    }

    @Test
    fun stopDelegatesToTheEngine() {
        val tts = FakeTts(setOf("en-IN"))
        SpokenVerdictSpeaker(tts).stop()
        assertEquals(1, tts.stopCount)
    }

    private val content = SpokenVerdictContent(
        riskLevel = "High risk",
        advice = "Call the official number",
        evidenceTitles = listOf("Authority evidence", "OTP request"),
    )

    private class FakeTts(private val voices: Set<String>, private val speakSucceeds: Boolean = true) : SpokenVerdictTts {
        data class Spoken(val text: String, val languageTag: String)
        val spoken = mutableListOf<Spoken>()
        var stopCount = 0

        override fun hasVoice(languageTag: String): Boolean = languageTag in voices
        override fun speak(text: String, languageTag: String): Boolean {
            spoken += Spoken(text, languageTag)
            return speakSucceeds
        }
        override fun stop() { stopCount++ }
    }
}
