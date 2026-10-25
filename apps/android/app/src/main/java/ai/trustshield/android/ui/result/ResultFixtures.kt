package ai.trustshield.android.ui.result

import ai.trustshield.android.ui.theme.RiskTone
import ai.trustshield.android.ui.theme.ToneCopy

/**
 * Hand-written demo fixtures for the Result screen.
 *
 * **These are fixtures, not model output.** Every string below was typed by hand to exercise the
 * screen's states; nothing here was produced by the score engine, and no number in this file is a
 * measured metric. They exist so the screen and the demo journey (C08) have representative Hinglish
 * content before A01/A05 land.
 *
 * `// SEAM:` when A05 exists, these inputs are replaced by real deterministic output; the presenter
 * and the screen stay exactly as they are.
 *
 * Each fixture is built **through [ResultPresenter]**, so even the demo path exercises the real
 * quote verification, truncation and Unable-to-Verify rules instead of bypassing them.
 */
object ResultFixtures {

    /** Fixture label to make accidental reuse as "real output" obvious in any screenshot/log. */
    const val FIXTURE_LABEL: String = "hand-written fixture, not model output"

    // ---------------------------------------------------------------------------------------
    // A reported "digital arrest" style message: authority + threat + secrecy + a direct OTP ask.
    // Scam-shaped by construction (four independent stages, one explicit credential ask).
    // ---------------------------------------------------------------------------------------

    const val DIGITAL_ARREST_SOURCE: String =
        "CBI officer bol raha hoon. Aapke naam par parcel mila hai jisme drugs hain. " +
            "Aapko 2 ghante mein arrest hoga. Kisi ko mat batana, warna case kharab ho jayega. " +
            "Abhi turant apna OTP batao aur 50,000 is account par transfer karo. Call cut mat karna."

    val digitalArrestInput: ResultInput = ResultInput(
        sourceText = DIGITAL_ARREST_SOURCE,
        coverage = InputCoverage.READABLE_SUPPORTED,
        riskTone = RiskTone.RED,
        confidence = ConfidenceLevel.MEDIUM,
        evidence = listOf(
            ResultEvidenceInput(
                id = "fx-auth-1",
                module = "rules/authority",
                titleHinglish = "Official hone ka dawa",
                titleEnglish = "Claim of an official role",
                detailHinglish = "Bhejne wale ne khud ko CBI officer bataya hai. Sirf dawa se kuch pakka nahi hota, par yeh ek stage hai.",
                detailEnglish = "The sender claims to be a CBI officer. A claim alone proves nothing, but it is one stage.",
                severity = EvidenceSeverity.WATCH,
                quote = "CBI officer bol raha hoon"
            ),
            ResultEvidenceInput(
                id = "fx-threat-1",
                module = "rules/threat",
                titleHinglish = "Arrest ki dhamki",
                titleEnglish = "Threat of arrest",
                detailHinglish = "Do ghante mein arrest ka dawa pressure banane ke liye use hota hai; asli agency phone par arrest ki deadline nahi deti.",
                detailEnglish = "A two-hour arrest deadline is pressure tactics; a real agency does not set arrest deadlines by phone.",
                severity = EvidenceSeverity.STRONG,
                quote = "Aapko 2 ghante mein arrest hoga"
            ),
            ResultEvidenceInput(
                id = "fx-secrecy-1",
                module = "rules/secrecy",
                titleHinglish = "Chup rehne ka dabaav",
                titleEnglish = "Pressure to stay silent",
                detailHinglish = "Kisi ko na batane ka kehna isolation stage hai — yahi cheez asli officers kabhi nahi maangte.",
                detailEnglish = "Asking you to tell nobody is an isolation stage — something real officers never require.",
                severity = EvidenceSeverity.WATCH,
                quote = "Kisi ko mat batana"
            ),
            ResultEvidenceInput(
                id = "fx-ask-1",
                module = "rules/ask_otp",
                titleHinglish = "OTP maanga gaya",
                titleEnglish = "OTP was requested",
                detailHinglish = "Yahan seedha OTP maanga gaya hai. Bank, police ya koi bhi asli sanstha OTP nahi maangti.",
                detailEnglish = "An OTP is requested directly here. No bank, police or real institution asks for an OTP.",
                severity = EvidenceSeverity.STRONG,
                quote = "Abhi turant apna OTP batao"
            )
        ),
        actions = listOf(
            ResultActionInput(
                id = "fx-act-stop",
                labelHinglish = "Call kaat dein, koi OTP na batayein",
                labelEnglish = "End the call, share no OTP",
                detailHinglish = "Agar call chal rahi hai to abhi kaat dein. Jo OTP aa chuka hai use kisi ko na batayein.",
                detailEnglish = "If the call is still running, end it now. Share no OTP, even one you already received.",
                priority = ActionPriority.PRIMARY
            ),
            ResultActionInput(
                id = "fx-act-verify",
                labelHinglish = "Bank/police ke official number par khud call karein",
                labelEnglish = "Call the bank/police on their official number",
                detailHinglish = "Jo number call par bataya gaya, us par bharosa na karein. Card ke peeche ya official site ka number use karein.",
                detailEnglish = "Do not trust the number given on the call. Use the number on your card or the official site.",
                priority = ActionPriority.SECONDARY
            ),
            ResultActionInput(
                id = "fx-act-1930",
                labelHinglish = "Paise ya OTP diye hain to 1930 par report karein",
                labelEnglish = "If you shared money or an OTP, report on 1930",
                detailHinglish = "Cyber fraud helpline 1930 par jaldi report karne se transaction rokne ka mauka badhta hai.",
                detailEnglish = "Reporting to the cyber fraud helpline 1930 quickly improves the chance of stopping the transaction.",
                priority = ActionPriority.SECONDARY
            )
        ),
        limitations = listOf(
            copy(
                "Yeh sirf is message par based hai; sender ka asli naam ya number verify nahi hua.",
                "This is based on this message only; the sender's real name or number was not verified."
            )
        )
    )

    // ---------------------------------------------------------------------------------------
    // A legitimate bank credit notice: informative, with a protective "do not share OTP" line.
    // Used to prove the screen does not shout at a harmless message (and never says "safe").
    // ---------------------------------------------------------------------------------------

    const val BANK_CREDIT_SOURCE: String =
        "Rs 5,000 credited to a/c XX1234 on 25-10-26. Avl bal Rs 12,340. " +
            "Never share your OTP with anyone, including bank staff."

    val bankCreditInput: ResultInput = ResultInput(
        sourceText = BANK_CREDIT_SOURCE,
        coverage = InputCoverage.READABLE_SUPPORTED,
        riskTone = RiskTone.GREEN,
        confidence = ConfidenceLevel.MEDIUM,
        evidence = listOf(
            ResultEvidenceInput(
                id = "fx-info-credit",
                module = "rules/info_credit",
                titleHinglish = "Sirf credit ki jaankari",
                titleEnglish = "Credit notice only",
                detailHinglish = "Yeh ek normal credit message lagta hai: paisa aaya hai, koi request nahi.",
                detailEnglish = "This reads as a normal credit message: money came in, nothing is being requested.",
                severity = EvidenceSeverity.INFO,
                quote = "Rs 5,000 credited to a/c XX1234"
            ),
            ResultEvidenceInput(
                id = "fx-info-protect",
                module = "rules/negation",
                titleHinglish = "OTP na batane ki salah",
                titleEnglish = "Advice not to share an OTP",
                detailHinglish = "Yahan OTP maanga nahi gaya, OTP na batane ko kaha gaya hai. Yeh protective line hai, ask nahi.",
                detailEnglish = "This does not request an OTP; it tells you not to share one. That is a protective line, not an ask.",
                severity = EvidenceSeverity.INFO,
                quote = "Never share your OTP with anyone"
            )
        ),
        actions = listOf(
            ResultActionInput(
                id = "fx-act-check-balance",
                labelHinglish = "Apne bank app mein balance khud check karein",
                labelEnglish = "Check the balance yourself in your bank app",
                detailHinglish = "Message mein diye link par na jayein; app ya passbook se confirm karein.",
                detailEnglish = "Do not use a link from the message; confirm in the app or passbook.",
                priority = ActionPriority.PRIMARY
            )
        )
    )

    // ---------------------------------------------------------------------------------------
    // Unsupported language: must degrade to Unable to Verify with low confidence, never "safe".
    // The Tamil text says an account will be closed and to contact immediately — the kind of
    // pressure a scam uses, which is exactly why guessing would be dangerous.
    // ---------------------------------------------------------------------------------------

    const val UNSUPPORTED_LANGUAGE_SOURCE: String =
        "உங்கள் கணக்கு மூடப்படும். உடனே தொடர்பு கொள்ளுங்கள்."

    val unsupportedLanguageInput: ResultInput = ResultInput(
        sourceText = UNSUPPORTED_LANGUAGE_SOURCE,
        coverage = InputCoverage.UNSUPPORTED_LANGUAGE,
        riskTone = RiskTone.NEUTRAL,
        confidence = ConfidenceLevel.LOW,
        evidence = emptyList(),
        actions = listOf(
            ResultActionInput(
                id = "fx-act-unable",
                labelHinglish = "Kisi link par click na karein",
                labelEnglish = "Do not tap any link",
                detailHinglish = "Jab tak verify na ho, koi link, OTP ya paisa involved na karein.",
                detailEnglish = "Until this is verified, do not involve any link, OTP or money.",
                priority = ActionPriority.PRIMARY
            )
        )
    )

    // ---------------------------------------------------------------------------------------
    // Unreadable input (OCR/ASR produced nothing usable).
    // ---------------------------------------------------------------------------------------

    val unreadableInput: ResultInput = ResultInput(
        sourceText = "",
        coverage = InputCoverage.UNREADABLE,
        riskTone = RiskTone.NEUTRAL,
        confidence = ConfidenceLevel.LOW,
        evidence = emptyList()
    )

    val digitalArrest: ResultUiState get() = ResultPresenter.build(digitalArrestInput)
    val bankCredit: ResultUiState get() = ResultPresenter.build(bankCreditInput)
    val unsupportedLanguage: ResultUiState get() = ResultPresenter.build(unsupportedLanguageInput)
    val unreadable: ResultUiState get() = ResultPresenter.build(unreadableInput)

    /** All fixtures, for exhaustive safety sweeps in tests. */
    val all: List<ResultUiState> get() = listOf(digitalArrest, bankCredit, unsupportedLanguage, unreadable)

    /** All fixture inputs, for tests that need the source text alongside the built state. */
    val allInputs: List<ResultInput>
        get() = listOf(digitalArrestInput, bankCreditInput, unsupportedLanguageInput, unreadableInput)

    /** Convenience for a demo harness that wants a bilingual caption for a fixture. */
    fun label(): ToneCopy = copy(
        "Demo data ($FIXTURE_LABEL), asli scan nahi.",
        "Demo data ($FIXTURE_LABEL), not a real scan."
    )
}
