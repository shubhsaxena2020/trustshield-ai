package ai.trustshield.android.core.analysis

import ai.trustshield.android.core.model.Confidence
import ai.trustshield.android.core.model.RiskLabel
import ai.trustshield.android.core.model.StageId

/**
 * One hand-written Hinglish demo message and the band the deterministic engine is *expected* to put
 * it in (package P3-03, issue #82).
 *
 * @property id stable id; tests, the demo script and the miss report all key off it, so renaming one
 *   is a deliberate, reviewed change and never a silent one.
 * @property titleHinglish the one-line label used in the demo and in the miss report.
 * @property text the synthetic message. No real personal data, no real phone number, UPI id or OTP.
 * @property expectation the honest band plus the reasoning behind it.
 */
data class DemoMessage(
    val id: String,
    val titleHinglish: String,
    val text: String,
    val expectation: DemoExpectation,
)

/**
 * What a fixture is allowed to produce, and why.
 *
 * @property allowedLabels every risk label this message is honestly allowed to produce. Usually a
 *   single label; a band (e.g. `LOW_RISK..CAUTION`) is only used where BEHAVIOR_SPEC explicitly
 *   permits a range.
 * @property requiredStages stages the deterministic rules must find. An absent required stage means
 *   the rules missed something the message plainly contains.
 * @property forbiddenStages stages that must NOT appear. For a legitimate message the presence of
 *   [StageId.ASK] is the interesting failure: the app would be asking the user to distrust a real
 *   bank or delivery notice.
 * @property note why the band is what it is, quoting the BEHAVIOR_SPEC rule. Written before the run,
 *   never edited to match a result.
 */
data class DemoExpectation(
    val allowedLabels: Set<RiskLabel>,
    val requiredStages: Set<StageId>,
    val forbiddenStages: Set<StageId>,
    val note: String,
)

/**
 * One line of the honest miss table: expected versus what the engine actually did.
 *
 * @property expected human-readable expected band, e.g. `HIGH_RISK / ASK,AUTHORITY,...`.
 * @property actualLabel / [actualScore] / [actualConfidence] reported as three separate values on
 *   purpose - risk and confidence are independent fields of the contract ([ai.trustshield.android.core.model.RiskAssessment]).
 * @property stages the stages the pipeline actually found, in playbook order.
 * @property missed true when any part of the expectation was not met.
 */
data class DemoResultRow(
    val id: String,
    val expected: String,
    val actualLabel: RiskLabel,
    val actualScore: Int,
    val actualConfidence: Confidence,
    val stages: List<StageId>,
    val missed: Boolean,
)

/**
 * The seven P3-03 Hinglish scan regression fixtures (issue #82).
 *
 * These are **rule and normaliser** fixtures, not a quality measurement. They pin what the
 * deterministic local engine does with seven synthetic messages so a regression names itself
 * instead of hiding inside a green build. They say nothing about model performance, they are not
 * sampled from any evaluation set, and they change no reported metric.
 *
 * Four fixtures are scam-shaped (1-4) and three are legitimate (5-7). The legitimate three exist to
 * prove the app does not cry wolf: a protective OTP notice, a credit alert and a delivery OTP must
 * never be accused.
 *
 * Texts are synthetic. The only digit runs longer than three characters are obviously fake demo
 * tokens (`123456` as a sample OTP, `4521` as a sample delivery OTP, `1234` as the tail of a masked
 * account, `5000` as an advertised daily earning), which `DemoMessagesTest` pins so nobody can paste
 * a believable phone number, card number or customer id into this file later.
 *
 * ## Known miss, recorded not hidden (measured 2026-10-26, P3-03)
 *
 * Fixture 7 (`delivery-otp`) is expected to stay `LOW_RISK` with no ask, and the engine puts it in
 * `CAUTION` (score 30, confidence HIGH) with an `ASK` stage. Cause: `StageRules` matches the OTP ask
 * pattern `OTP … share` on "share only with the agent at your door", and its negation guard only
 * looks for `na/nahi/mat/not/never`, so an in-person share destination is indistinguishable from a
 * remote request. `LegitimateIndicators` already proves the two rules disagree: the same run reports
 * the `DELIVERY_AT_DOOR` indicator *and* a strong ask. The expectation below is left exactly as the
 * spec states it, so `DemoMessagesTest` fails and names this fixture. The fix belongs to A03
 * (`core/rules/StageRules.kt`), not to this package.
 */
object DemoMessages {

    /** Stable ids. Tests and the demo script key off these, never off list position. */
    const val ID_SBI_KYC_OTP: String = "sbi-kyc-otp"
    const val ID_DIGITAL_ARREST: String = "digital-arrest"
    const val ID_ELECTRICITY_BILL: String = "electricity-bill"
    const val ID_JOB_FEE: String = "job-fee"
    const val ID_LEGIT_OTP: String = "legit-otp"
    const val ID_CREDIT_ALERT: String = "credit-alert"
    const val ID_DELIVERY_OTP: String = "delivery-otp"

    /** How many fixtures this package ships. Tests assert this so a deletion cannot pass silently. */
    const val FIXTURE_COUNT: Int = 7

    /**
     * The claim this package is allowed to make. Deliberately free of the words a quality claim
     * needs (`accuracy`, `holdout`) and free of percentages, so `DemoMessagesTest` can fail if
     * someone turns a fixture table into a performance boast.
     */
    const val CLAIM_DISCLAIMER: String =
        "Seven synthetic Hinglish regression fixtures. They pin the deterministic rule and " +
            "normaliser behaviour of the local pipeline; they are not a measurement of model " +
            "quality and they modify no reported number. Current honest reporting stays " +
            "82.1 recall, 78.3 specificity, 82.1 precision, 82.1 F1 on the sealed evaluation set, " +
            "90 target unmet. Passing these fixtures must never be read as a new performance figure."

    /** Fixtures 1-4: scam-shaped messages, the only ones allowed to produce an ask stage. */
    val scamIds: Set<String> =
        setOf(ID_SBI_KYC_OTP, ID_DIGITAL_ARREST, ID_ELECTRICITY_BILL, ID_JOB_FEE)

    /** Fixtures 5-7: legitimate messages that must not be accused. */
    val legitimateIds: Set<String> = setOf(ID_LEGIT_OTP, ID_CREDIT_ALERT, ID_DELIVERY_OTP)

    /** Fixture 3: the false-alarm gate. Threat plus urgency plus a personal number, but no ask. */
    val noAskGuardIds: Set<String> = setOf(ID_ELECTRICITY_BILL)

    /**
     * The seven fixtures, stable order and stable ids.
     *
     * Notes quote BEHAVIOR_SPEC ("Rule gates" / "Negation and legitimate pairs") because that is the
     * document the bands are derived from.
     */
    val all: List<DemoMessage> = listOf(
        DemoMessage(
            id = ID_SBI_KYC_OTP,
            titleHinglish = "SBI KYC band hone ka darr + OTP maang",
            text = "Main SBI head office se bol raha hoon. Aapka KYC 24 ghante mein band ho " +
                "jayega. Abhi isi number par OTP batao warna account band ho jayega.",
            expectation = DemoExpectation(
                allowedLabels = setOf(RiskLabel.HIGH_RISK),
                requiredStages = setOf(
                    StageId.AUTHORITY,
                    StageId.THREAT_OR_REWARD,
                    StageId.URGENCY,
                    StageId.ASK,
                ),
                forbiddenStages = emptySet(),
                note = "BEHAVIOR_SPEC rule gate: a validated ask plus any two other independent " +
                    "stages qualifies for the high-risk floor. This message carries authority " +
                    "('main SBI head office se'), threat ('KYC band ho jayega'), urgency " +
                    "('24 ghante', 'abhi') and a credential ask ('OTP batao'), so HIGH_RISK is " +
                    "the only honest band.",
            ),
        ),
        DemoMessage(
            id = ID_DIGITAL_ARREST,
            titleHinglish = "Digital arrest: CBI + drug parcel + kisi ko mat batana",
            text = "Main CBI cyber crime se Inspector Sharma bol raha hoon. Aapke Aadhaar se bheje " +
                "gaye parcel mein drugs mile hain. Yeh confidential case hai, kisi ko mat batana. " +
                "Turant Rs 50,000 transfer karo warna arrest ho jayega.",
            expectation = DemoExpectation(
                allowedLabels = setOf(RiskLabel.HIGH_RISK),
                requiredStages = setOf(
                    StageId.AUTHORITY,
                    StageId.THREAT_OR_REWARD,
                    StageId.URGENCY,
                    StageId.SECRECY,
                    StageId.ASK,
                ),
                forbiddenStages = emptySet(),
                note = "The full report section 6 ladder. BEHAVIOR_SPEC: a validated ask plus two " +
                    "independent stages reaches the floor, and 'Critical' is the UI escalation for " +
                    "a high-risk money ask with multiple corroborations - this fixture must also " +
                    "set escalateCritical. Secrecy ('kisi ko mat batana', 'confidential case') is " +
                    "pressure, not protective advice.",
            ),
        ),
        DemoMessage(
            id = ID_ELECTRICITY_BILL,
            titleHinglish = "Bijli bill: aaj raat kat jayegi + personal number",
            text = "Aapka bijli bill due date 30 Oct hai. Aaj raat bijli kat jayegi, turant " +
                "98XXXXXX12 par call karein.",
            expectation = DemoExpectation(
                allowedLabels = setOf(RiskLabel.LOW_RISK, RiskLabel.CAUTION),
                requiredStages = setOf(StageId.URGENCY),
                forbiddenStages = setOf(StageId.ASK),
                note = "BEHAVIOR_SPEC 'Rule gates': authority or reward with no ask stays low/watch, " +
                    "and a validated ask is what moves the needle. This message has urgency and a " +
                    "veiled threat with a personal callback number but no credential, money or " +
                    "remote-access ask, so it must stay at watch level. It exists to prove the app " +
                    "does not cry wolf: reaching HIGH_RISK or SUSPICIOUS here would be a false alarm.",
            ),
        ),
        DemoMessage(
            id = ID_JOB_FEE,
            titleHinglish = "Job offer + registration fee bhejo",
            text = "Congratulations aap job ke liye select ho gaye hain. Roz 5000 kamao ghar baithe. " +
                "Registration fee 500 bhejo is UPI par.",
            expectation = DemoExpectation(
                allowedLabels = setOf(RiskLabel.CAUTION, RiskLabel.SUSPICIOUS, RiskLabel.HIGH_RISK),
                requiredStages = setOf(StageId.THREAT_OR_REWARD, StageId.ASK),
                forbiddenStages = emptySet(),
                note = "BEHAVIOR_SPEC 'Rule gates': a validated ask alone is at most caution/medium " +
                    "until another corroborating signal lands, and here the reward stage is that one " +
                    "corroboration - so the ask-plus-one band applies and the score lands at or above " +
                    "CAUTION. At least CAUTION is the honest floor; a real employer never charges " +
                    "a registration fee to be paid by the candidate.",
            ),
        ),
        DemoMessage(
            id = ID_LEGIT_OTP,
            titleHinglish = "Asli bank OTP notice (share na karein)",
            text = "123456 is your OTP for txn of Rs 500. Do not share it with anyone. -SBI",
            expectation = DemoExpectation(
                allowedLabels = setOf(RiskLabel.LOW_RISK),
                requiredStages = emptySet(),
                forbiddenStages = setOf(StageId.ASK),
                note = "BEHAVIOR_SPEC 'Negation and legitimate pairs': the protective twin of the " +
                    "OTP scam. 'Do not share it with anyone' is a warning, never an ask, so this " +
                    "must be LOW_RISK with the protective legitimate indicator visible as evidence. " +
                    "A warning that lowers risk may never be read as the scam it warns about.",
            ),
        ),
        DemoMessage(
            id = ID_CREDIT_ALERT,
            titleHinglish = "Credit alert (koi maang nahi)",
            text = "Rs 5,000 credited to A/c XX1234. Avl bal Rs 12,340.",
            expectation = DemoExpectation(
                allowedLabels = setOf(RiskLabel.LOW_RISK),
                requiredStages = emptySet(),
                forbiddenStages = setOf(StageId.ASK),
                note = "BEHAVIOR_SPEC: 'Credit notice alone is informational; transfer request adds " +
                    "ask.' There is no transfer request here, so no ask stage and no accusation. " +
                    "The scam variant that pairs a fake credit alert with 'galti se paise bheje, " +
                    "wapas bhejo' is a different message and would add the ask.",
            ),
        ),
        DemoMessage(
            id = ID_DELIVERY_OTP,
            titleHinglish = "Delivery OTP (darwaze par agent ko)",
            text = "Delivery OTP 4521, share only with the agent at your door.",
            expectation = DemoExpectation(
                allowedLabels = setOf(RiskLabel.LOW_RISK),
                requiredStages = emptySet(),
                forbiddenStages = setOf(StageId.ASK),
                note = "BEHAVIOR_SPEC 'Negation and legitimate pairs': 'Context-specific in-person " +
                    "delivery differs from remote request.' Handing a code to a person standing at " +
                    "your door is the normal delivery flow, so this must stay LOW_RISK and must not " +
                    "produce an ask stage.",
            ),
        ),
    )

    /** Look a fixture up by its stable id. Null (not an exception) so a caller can report a miss. */
    fun byId(id: String): DemoMessage? = all.firstOrNull { it.id == id }
}
