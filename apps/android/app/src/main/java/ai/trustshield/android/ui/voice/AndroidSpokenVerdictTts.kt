package ai.trustshield.android.ui.voice

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/** Thin Android adapter; it selects an installed local voice and never requests a network voice. */
class AndroidSpokenVerdictTts(context: Context) : SpokenVerdictTts {
    @Volatile
    private var ready = false
    private val engine = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
    }

    init {
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
    }

    override fun hasVoice(languageTag: String): Boolean = findLocalVoice(languageTag) != null

    override fun speak(text: String, languageTag: String): Boolean {
        if (!ready) return false
        val voice = findLocalVoice(languageTag) ?: return false
        if (engine.setVoice(voice) != TextToSpeech.SUCCESS) return false
        return engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "trustshield-${System.nanoTime()}") == TextToSpeech.SUCCESS
    }

    override fun stop() {
        if (ready) engine.stop()
    }

    fun shutdown() {
        ready = false
        engine.stop()
        engine.shutdown()
    }

    private fun findLocalVoice(languageTag: String): Voice? {
        if (!ready) return null
        val desired = Locale.forLanguageTag(languageTag)
        return runCatching {
            engine.voices.firstOrNull { voice ->
                voice.locale.language.equals(desired.language, ignoreCase = true) &&
                    voice.locale.country.equals(desired.country, ignoreCase = true) &&
                    !voice.isNetworkConnectionRequired
            }
        }.getOrNull()
    }
}
