package ai.trustshield.android.data.preferences

import ai.trustshield.android.core.model.AnalysisStatus
import ai.trustshield.android.core.model.RiskLabel
import ai.trustshield.android.core.model.TextNormalizer
import ai.trustshield.android.core.normalize.HinglishNormalizer
import ai.trustshield.android.core.score.LocalScorer
import ai.trustshield.android.core.score.ScoredResult

/**
 * Connects the persisted sensitivity preference to the local deterministic scorer (work package
 * P3-16, issue #95).
 *
 * ## Why this file exists
 *
 * C04 ([UserPreferences]) already persists `Sensitivity.LOW | BALANCED | HIGH` and already documents
 * what those presets are allowed to move ([SensitivityPolicy]). A05 ([LocalScorer]) already owns a
 * `Sensitivity` enum with the real optical parameters. Between the two there was nothing: a caller
 * could persist a choice and still analyse every message with the default engine preset, which makes
 * the Settings toggle decorative and would make "Settings really change behavior"
 * (`HEAD_ORDERS_PHASE3` definition of done item 6) untrue on the emulator.
 *
 * [scorer] is that missing seam. It is deliberately **one call**:
 *
 * ```kotlin
 * // The exact one-line integration seam for the scan pipeline (P3-02, separately owned):
 * val scorer = SensitivityScoring.scorer(preferences.sensitivity)
 * val scored: ScoredResult = scorer.analyzeScored(text)
 * val band: RiskLabel = SensitivityScoring.bandFor(preferences.sensitivity, scored)
 * ```
 *
 * Nothing here caches, stores or logs anything. No message text, transcript, quote, OTP, PIN,
 * phone number or UPI id can reach this file's output: it maps enums, thresholds and an integer
 * score. There is no I/O, no clock, no randomness and no network, so it is safe to call per scan and
 * pure enough for plain JVM unit tests.
 *
 * ## What sensitivity MAY do
 *
 *  - choose which of the three documented A05 presets the engine runs with ([localPreset]);
 *  - move the two presentation cut-offs inside C04's documented bounds ([SensitivityPolicy]
 *    table: `cautionAt`, `highRiskAt`) and the derived suspicious cut in [suspiciousAt];
 *  - make the app **more** alert-happy as the preset rises, never less (bounds table below).
 *
 * ## What sensitivity MAY NOT do (hard limits, enforced by the code below)
 *
 *  - It cannot change the verdict pipeline. The engine still fuses the same five stages with the same
 *    weights, the same verified-ask gate and the same negation rules; only numbers C04 documents as
 *    tunable are read from the preset. No sms/URL/QR/OCR path is added or removed.
 *  - It cannot suppress a verified strong ask. [bandFor] routes every score through
 *    [SensitivityPolicy.adjustScore], so an input with a verified OTP/PIN/CVV/money/remote-access ask
 *    is presented at no less than [SensitivityPolicy.STRONG_ASK_FLOOR_SCORE] (55) - above every
 *    preset's `cautionAt`. Such an input is therefore never [RiskLabel.LOW_RISK] / "safe" at any
 *    preset, and this is asserted exhaustively over `0..100` x three presets in
 *    `SensitivityPolicyTest`.
 *  - It cannot turn unreadable into safe. [bandFor] keeps the engine's
 *    [RiskLabel.UNABLE_TO_VERIFY] for unreadable or unsupported input; the numeric mapping never
 *    produces that label, so a preset cannot manufacture it either.
 *  - It cannot change reported confidence. Confidence is coverage-derived inside A05 (truncation,
 *    script support, ASR quality, evidence count) and is passed through untouched - score and
 *    confidence stay separate fields. A test pins "same confidence at all three presets" per fixture.
 *  - It cannot buy accuracy. This is deterministic rule behaviour, not a model metric: the sealed
 *    holdout baseline stays recall 82.1 / specificity 78.3 / precision 82.1 / F1 82.1 with the 90%
 *    target unmet (REQUIREMENTS N06). No preset claims better detection.
 *
 * ## The documented, bounded, monotonic table
 *
 * | Persisted preset | A05 engine preset | cautionAt | suspiciousAt | highRiskAt | Effect |
 * |---|---|---|---|---|---|
 * | `LOW`      | `LocalScorer.Sensitivity.LOW`      | 45 | 62 | 80 | Fewest alerts. Clear, multi-signal scams only. |
 * | `BALANCED` | `LocalScorer.Sensitivity.STANDARD` | 35 | 52 | 70 | Default: the calibrated band. |
 * | `HIGH`     | `LocalScorer.Sensitivity.HIGH`     | 25 | 41 | 58 | Most alerts; extra warnings are expected. |
 *
 * Cut-offs strictly decrease as sensitivity rises, and A05's presets are ordered the same way
 * (`LOW` needs three corroborating stages before the 70 floor while `BALANCED`/`HIGH` need two, and
 * the ask-alone / ask-plus-one bands rise monotonically), so for one fixed input
 * `score(LOW) <= score(BALANCED) <= score(HIGH)` and the presented band never gets *safer* as the
 * preset gets more sensitive. Both properties are tested against the real engine, not asserted here.
 *
 * ### Known gaps and honest limitations
 *
 *  - **Not every input moves.** A bare verified credential ask with no other stage scores 30 at all
 *    three presets (A05's ask-only noise floor), and a purely legitimate message scores 0 and stays
 *    [RiskLabel.LOW_RISK] everywhere. Sensitivity changes the *boundary* cases - ask + one
 *    corroborating stage, and how many corroborations are needed for the high-risk floor. The test
 *    file prints the observed table and marks the fixtures that do not move, instead of pretending.
 *  - **At `LOW`, a 70 is presented as [RiskLabel.SUSPICIOUS], not high risk.** `LOW.highRiskAt` is 80
 *    (C04's documented table), so a digital-arrest style message with the *minimum* corroboration
 *    (score 70) sits in the middle band at `LOW` while `BALANCED`/`HIGH` call it high risk. That is
 *    the user's "fewer alerts" choice working as documented; the result's `actions` still come from
 *    the contract's own [RiskLabel] and therefore still say "rukiye, OTP ya paise mat dein", so the
 *    calmer band never softens the advice. Bands are never *safer* than the preset asks for.
 *  - **The shown score can differ from the contract score.** When the verified-ask floor applies, the
 *    number the UI should print ([presentedScore]) is 55 while `result.risk.score` may be lower (30).
 *    The band is always computed from the shown score, so the number and the colour agree; the
 *    contract value stays untouched for anything that needs the raw engine output.
 *  - **This is deterministic rule behaviour, not model accuracy.** Nothing here improves or claims to
 *    improve detection quality.
 *
 * ### Why `suspiciousAt` is derived, not stored
 *
 * C04 owns two cut-offs per preset. The middle band is computed as
 * `cautionAt + (highRiskAt - cautionAt) / 2` so this file adds **no new tunable numbers** - it only
 * splits the preset's own warning band in half. At `BALANCED` that gives 52 where A01's fixed
 * [ai.trustshield.android.core.model.RiskAssessment.labelFor] uses 50; the 2-point delta is
 * presentation-only and the high-risk edge (70) is identical, so the "high risk" promise never
 * disagrees with the contract. The engine's own `risk.label` stays A01-banded and unchanged.
 */
object SensitivityScoring {

    /**
     * The persisted preference mapped onto the merged A05 engine preset.
     *
     * `BALANCED` maps to `STANDARD` because "balanced" is the shipping default, and A05 named its
     * default `STANDARD`; there is no fourth mapping on purpose (see the bijection test).
     */
    fun localPreset(sensitivity: Sensitivity): LocalScorer.Sensitivity = when (sensitivity) {
        Sensitivity.LOW -> LocalScorer.Sensitivity.LOW
        Sensitivity.BALANCED -> LocalScorer.Sensitivity.STANDARD
        Sensitivity.HIGH -> LocalScorer.Sensitivity.HIGH
    }

    /**
     * A [LocalScorer] configured from the stored preference - the seam that makes the setting real.
     *
     * @param normalizer A02's normalizer. The default is the real [HinglishNormalizer] (no-arg
     *   constructor), so a caller with only a `UserPreferences` needs no extra wiring; tests and the
     *   Call Shield transcript path can inject their own.
     */
    fun scorer(
        sensitivity: Sensitivity,
        normalizer: TextNormalizer = HinglishNormalizer(),
    ): LocalScorer = LocalScorer(normalizer = normalizer, sensitivity = localPreset(sensitivity))

    /**
     * The middle cut: scores at or above this are [RiskLabel.SUSPICIOUS] rather than
     * [RiskLabel.CAUTION]. Derived from the preset's own two documented cut-offs (see the class KDoc)
     * so no extra tunable exists here.
     */
    fun suspiciousAt(sensitivity: Sensitivity): Int {
        val thresholds = SensitivityPolicy.thresholds(sensitivity)
        return thresholds.cautionAt + (thresholds.highRiskAt - thresholds.cautionAt) / 2
    }

    /**
     * The score the UI should print next to a band: C04's clamp plus the verified-ask floor.
     *
     * Kept in one place so the number and the band can never disagree. It is *not* a new score - a
     * score that already clears the floor comes back unchanged, and the clamp only defends against a
     * caller handing in something outside `0..100`.
     */
    fun presentedScore(rawScore: Int, hasVerifiedStrongAsk: Boolean): Int =
        SensitivityPolicy.adjustScore(rawScore, hasVerifiedStrongAsk)

    /**
     * Presentation band for a raw score under [sensitivity].
     *
     * Order of operations matters and is the whole safety argument:
     *  1. clamp + verified-ask floor ([SensitivityPolicy.adjustScore]);
     *  2. compare against the preset's three cut-offs.
     *
     * Consequently a verified strong ask is at least [SensitivityPolicy.STRONG_ASK_FLOOR_SCORE], which
     * clears every preset's `cautionAt`, so it can never be [RiskLabel.LOW_RISK] here.
     *
     * This mapping never returns [RiskLabel.UNABLE_TO_VERIFY]: "we could not judge this" is the
     * engine's status, not a threshold. Use the [ScoredResult] overload when you have a real result.
     *
     * @param hasVerifiedStrongAsk the engine's `verifiedAsk` for *this* input. Passing `false` for an
     *   input that does contain a verified ask defeats the floor - the only way to get a safe band for
     *   a real ask - so callers must forward it, not guess it.
     */
    fun bandFor(sensitivity: Sensitivity, score: Int, hasVerifiedStrongAsk: Boolean): RiskLabel {
        val presented = presentedScore(score, hasVerifiedStrongAsk)
        val thresholds = SensitivityPolicy.thresholds(sensitivity)
        return when {
            presented >= thresholds.highRiskAt -> RiskLabel.HIGH_RISK
            presented >= suspiciousAt(sensitivity) -> RiskLabel.SUSPICIOUS
            presented >= thresholds.cautionAt -> RiskLabel.CAUTION
            else -> RiskLabel.LOW_RISK
        }
    }

    /**
     * Presentation band for a finished local analysis - the overload the UI and the scan pipeline
     * should call, because it carries the one outcome a score cannot express.
     *
     * [RiskLabel.UNABLE_TO_VERIFY] (and A01's `FAILED`/`PARTIAL` statuses, which A05 already labels
     * unable-to-verify) wins over every threshold: empty, emoji-only, too-short or unsupported-script
     * input stays unable-to-verify at every preset, so no sensitivity setting can talk a user into
     * "safe" for text the app could not read (REQUIREMENTS F03, UI_SPEC "unsupported language").
     *
     * Confidence is not read, not modified and not returned: it belongs to the result's
     * `risk.confidence` and travels with it.
     */
    fun bandFor(sensitivity: Sensitivity, scored: ScoredResult): RiskLabel {
        val result = scored.result
        val unverifiable = result.risk.label == RiskLabel.UNABLE_TO_VERIFY ||
            result.status == AnalysisStatus.UNABLE_TO_VERIFY ||
            result.status == AnalysisStatus.FAILED ||
            result.status == AnalysisStatus.PARTIAL
        if (unverifiable) return RiskLabel.UNABLE_TO_VERIFY
        return bandFor(sensitivity, result.risk.score, scored.verifiedAsk)
    }
}
