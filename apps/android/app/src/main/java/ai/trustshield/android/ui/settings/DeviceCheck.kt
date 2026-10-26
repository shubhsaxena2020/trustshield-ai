package ai.trustshield.android.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import ai.trustshield.android.input.speech.HindiLocaleSupportOutcome
import ai.trustshield.android.input.speech.HindiLocaleSupportRules
import ai.trustshield.android.input.speech.SpeechCapabilityProbe
import java.util.concurrent.Executor

internal enum class DeviceCheckLocale { SUPPORTED, UNSUPPORTED, NOT_CHECKED }

/**
 * The one place the hi-IN probe outcome becomes a Device-check row value.
 *
 * The distinction this object exists to protect: [HindiLocaleSupportOutcome.NotChecked] ("Android
 * could not answer: below API 33, a callback error, or a recogniser that could not be created") must
 * never be shown as [DeviceCheckLocale.UNSUPPORTED] ("this phone says no"). Pure Kotlin, so the
 * mapping is pinned on the JVM by `DeviceCheckFallbackTest` without a recogniser or a microphone.
 */
internal object DeviceCheckLocaleRules {

    fun statusFor(outcome: HindiLocaleSupportOutcome): DeviceCheckLocale = when (outcome) {
        HindiLocaleSupportOutcome.Supported -> DeviceCheckLocale.SUPPORTED
        HindiLocaleSupportOutcome.Unsupported -> DeviceCheckLocale.UNSUPPORTED
        HindiLocaleSupportOutcome.NotChecked -> DeviceCheckLocale.NOT_CHECKED
    }

    /** The row before any probe has run: nothing has been checked yet on this Android version. */
    fun initialStatus(): DeviceCheckLocale = DeviceCheckLocale.NOT_CHECKED

    /** True only when the probe may run at all, i.e. the row can stop saying "not checked". */
    fun canCheckOnThisApiLevel(apiLevel: Int): Boolean = HindiLocaleSupportRules.probeAllowed(apiLevel)
}

internal data class DeviceCheckSnapshot(
    val androidVersion: String,
    val apiLevel: Int,
    val abi: String,
    val recognizerAvailable: Boolean,
    val hindiLocale: DeviceCheckLocale,
    val cameraPermission: Boolean,
    val microphonePermission: Boolean,
)

internal object DeviceCheckCopy {
    fun localeStatus(locale: DeviceCheckLocale, english: Boolean): String = when (locale) {
        DeviceCheckLocale.SUPPORTED -> if (english) "Supported" else "Support mila"
        DeviceCheckLocale.UNSUPPORTED -> if (english) "Not supported" else "Support nahi mila"
        DeviceCheckLocale.NOT_CHECKED -> if (english) "Not checked on this Android version" else "Is Android par check nahi ho saka"
    }

    fun availability(available: Boolean, english: Boolean): String = when {
        available && english -> "Available"
        available -> "Mila"
        english -> "Unavailable"
        else -> "Nahi mila"
    }

    fun permission(granted: Boolean, english: Boolean): String = when {
        granted && english -> "Granted"
        granted -> "Mili"
        english -> "Not granted"
        else -> "Nahi mili"
    }
}

/** Platform snapshot for the user-visible, read-only Device check panel. */
internal object DeviceCheckProbe {
    fun snapshot(context: Context, hindiLocale: DeviceCheckLocale = DeviceCheckLocale.NOT_CHECKED): DeviceCheckSnapshot {
        val recognizerAvailable = runCatching {
            android.speech.SpeechRecognizer.isRecognitionAvailable(context.applicationContext ?: context)
        }.getOrDefault(false)
        return DeviceCheckSnapshot(
            androidVersion = Build.VERSION.RELEASE.orEmpty(),
            apiLevel = Build.VERSION.SDK_INT,
            abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "Unknown" },
            recognizerAvailable = recognizerAvailable,
            hindiLocale = hindiLocale,
            cameraPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
            microphonePermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    fun checkHindiLocale(context: Context, executor: Executor, onResult: (DeviceCheckLocale) -> Unit) {
        SpeechCapabilityProbe.probeHindiLocaleSupport(context, executor) { supported ->
            onResult(
                DeviceCheckLocaleRules.statusFor(
                    when (supported) {
                        true -> HindiLocaleSupportOutcome.Supported
                        false -> HindiLocaleSupportOutcome.Unsupported
                        null -> HindiLocaleSupportOutcome.NotChecked
                    },
                ),
            )
        }
    }
}
