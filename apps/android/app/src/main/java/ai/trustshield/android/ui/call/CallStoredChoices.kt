package ai.trustshield.android.ui.call

/**
 * Re-applies the user's **stored** settings choices onto a [CallUiState].
 *
 * ## Why this exists (issue #138, defect measured on `emulator-5554`)
 *
 * `CallShieldRouteHost` keeps a `CallShieldHost` alongside a local `state`. The host owns the call
 * session, but it is constructed before any screen sees the user's preferences, so `host.state`
 * carries [CallUiState]'s field defaults: `language = CallLanguage.HINGLISH` and
 * `hapticsEnabled = true`.
 *
 * The host therefore must not be treated as a source of *presentation* choices. Every path that
 * copied it verbatim (`state = host.state` on start / stop / pause / resume / an ASR utterance / the
 * activity lifecycle observer, and the capability probe in `LaunchedEffect`) silently reverted the
 * screen to Hinglish and to haptics-on, whatever the user had stored:
 *
 * ```kotlin
 * // observed on the emulator with "English" persisted and Home/Settings/QR Guard rendering English
 * state = host.state                      // → language back to HINGLISH, haptics back to true
 * ```
 *
 * Measured defect: with `settings.language = ENGLISH` persisted across a force-stop, the Call Shield
 * screen rendered Hinglish copy and its language button still offered "English" (i.e. it believed it
 * was in Hinglish). The same reset turned the haptics toggle back on inside the session state, which
 * is a second route to a Critical pulse with the preference off.
 *
 * ## What this function guarantees
 *
 * The three presentation choices ([language], [hapticsEnabled], [reducedMotion]) always come from
 * the caller's stored/derived settings, never from the host. Everything else — score, tone,
 * confidence, evidence, chips, transcript, `hapticPulses` — is passed through untouched, because
 * those *are* the session and must not be reconstructed from settings.
 *
 * Pure, total and idempotent: no `android.*`, no I/O, no clock, so `CallStoredChoicesTest` can prove
 * every rule on a plain JVM.
 */
fun CallUiState.withStoredChoices(
    language: CallLanguage,
    hapticsEnabled: Boolean,
    reducedMotion: Boolean
): CallUiState =
    if (this.language == language &&
        this.hapticsEnabled == hapticsEnabled &&
        this.reducedMotion == reducedMotion
    ) {
        // Identity when nothing changed: keeps the returned instance stable for Compose equality.
        this
    } else {
        copy(
            language = language,
            hapticsEnabled = hapticsEnabled,
            reducedMotion = reducedMotion
        )
    }
