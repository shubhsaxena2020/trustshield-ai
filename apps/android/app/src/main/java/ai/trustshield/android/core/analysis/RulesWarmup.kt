package ai.trustshield.android.core.analysis

import kotlin.concurrent.thread

/**
 * Compiling the scam-rule patterns costs several seconds of CPU on older phones. Doing it on first use
 * froze the app on the main thread right after onboarding (measured ~13 s on a Redmi Note 5 Pro). This
 * runs one throwaway analysis on a background thread at process start so the patterns are already
 * compiled when a screen first needs them. It only reads a constant string; nothing is stored or sent.
 */
object RulesWarmup {
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        thread(name = "ts-rules-warmup", isDaemon = true, priority = Thread.NORM_PRIORITY - 1) {
            runCatching { LocalScanPipeline().analyzeText("Your account will be blocked today. Verify now.") }
        }
    }
}
