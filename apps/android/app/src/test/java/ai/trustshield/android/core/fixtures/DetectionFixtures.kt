package ai.trustshield.android.core.fixtures

import ai.trustshield.android.core.model.RiskLabel

/**
 * Which side of report §3.4 / BEHAVIOR_SPEC "Negation and legitimate pairs" a row belongs to.
 *
 * [LEGITIMATE] rows are the real-world twin of the same family: a bank credit notice, an OTP warning
 * that says *do not share*, a due-date bill, a delivery OTP for the agent at the door, an official
 * KYC branch notice. They must stay in the watch band; a rule that catches more scams by making one
 * of these look suspicious is a regression, not a win.
 */
enum class FixtureKind { SCAM, LEGITIMATE }

/**
 * One synthetic rule fixture.
 *
 * @property id stable row id quoted in `docs/android/DETECTION_FIXTURES.md`.
 * @property family the report §3.1-3.3 scam family (or the legitimate twin of one).
 * @property language English / Hinglish / Hindi (Devanagari) / Mixed, for the coverage table.
 * @property kind scam row or the legitimate twin.
 * @property expected the band BEHAVIOR_SPEC mandates for this shape of message - written from the
 *   spec **before** the row was measured, never read back off the engine. See [specBasis].
 * @property specBasis the sentence of BEHAVIOR_SPEC / report §3.x the expectation comes from.
 * @property text the synthetic message. Deliberately short, invented, and free of any real person's
 *   data. No link in any row is ever opened, resolved or fetched by the test: the engine parses URLs
 *   as inert strings and this fixture file only ever hands over characters.
 */
data class Fixture(
    val id: String,
    val family: String,
    val language: String,
    val kind: FixtureKind,
    val expected: RiskLabel,
    val specBasis: String,
    val text: String,
)

/**
 * The Phase 4 item 4 + 5 fixture inventory (issue #137, lane "rules/fixtures").
 *
 * ## What this is, and what it is not
 *
 * These are **rule fixtures**: hand-written synthetic messages, one per family per language shape,
 * run through the real on-device engine ([ai.trustshield.android.core.analysis.LocalScanPipeline] ->
 * `LocalScorer` -> `StageRules`/`LegitimateIndicators`/`DigitalArrestSignals`). They show whether a
 * *rule* fires and lands the message in the band the behaviour spec asks for. They are **not** an
 * accuracy measurement: there is no holdout, no sampling frame, no labelled corpus behind them, and
 * nothing here may be quoted as precision/recall. The repository's honest model numbers stay
 * untouched (holdout recall 82.1 / specificity 78.3 / precision 82.1 / F1 82.1).
 *
 * ## Why the expectations are written down first
 *
 * Every [Fixture.expected] value comes from a rule the behaviour spec already states, recorded in
 * [Fixture.specBasis]:
 *
 *  * `ASK + >= 2 other independent stages => HIGH_RISK` (BEHAVIOR_SPEC "Rule gates");
 *  * `ASK + exactly 1 other stage => CAUTION` (LocalScorer rule 5, band `35..49`);
 *  * `ASK alone => CAUTION` (rule 5, band `25..45`; a friend or a hacked account may be asking);
 *  * no verified ask, no strong signal => `LOW_RISK` watch (rules 1/6);
 *  * no verified ask but a validated [ai.trustshield.android.core.rules.DigitalArrestSignals] marker
 *    plus `>= 2` other stages => `CAUTION` (`25..49`), and plus `>= 3` other stages => `SUSPICIOUS`
 *    (`50..69`) - the two rungs of rule 6's no-ask ladder;
 *  * every legitimate twin => `LOW_RISK` (`<= 24`) and no verified ask (report §3.4).
 *
 * A row that misses is therefore a statement about the engine, not about the fixture.
 */
object DetectionFixtures {

    // Report §3.4's own legitimate-vs-scam sentences, reused verbatim as row text so the table and
    // the spec cannot drift apart.
    private const val OTP_NOTICE =
        "123456 is your OTP for txn of Rs 500. Do not share it with anyone. -SBI"
    private const val CREDIT_NOTICE = "Rs 5,000 credited to A/c XX1234. Avl bal Rs 12,340"
    private const val KYC_BRANCH = "Your KYC is due. Visit your branch or update via net banking."
    private const val BILL_DUE = "Your electricity bill of Rs 1,240 is due on 30 Oct."
    private const val DELIVERY_DOOR = "Delivery OTP 4521, share only with the agent at your door"

    // The three exact probes PHASE4 item 5 names, byte for byte (UTF-8: Devanagari + Devanagari
    // digits + one mixed Roman/Devanagari sentence).
    const val PROBE_OTP_DEVANAGARI = "आपका ओटीपी ४५६७ है"
    const val PROBE_MIXED_SBI_BLOCK = "Aapka SBI खाता block हो गया hai"
    const val PROBE_DIGITAL_ARREST_DEVANAGARI =
        "आपका आधार गिरफ़्तारी के लिए इस्तेमाल हुआ है, सुप्रीम कोर्ट का आदेश है। " +
            "किसी को मत बताना, कमरे में अकेले रहो।"

    private const val HIGH = "ASK + >=2 other stages => HIGH_RISK (BEHAVIOR_SPEC rule gates)"
    private const val CAUTION_ONE = "ASK + 1 other stage => CAUTION 35..49 (LocalScorer rule 5)"
    private const val CAUTION_ALONE = "ASK alone => CAUTION 25..45 (LocalScorer rule 5)"
    private const val LOW_WATCH = "no ask, no strong signal => LOW_RISK watch (LocalScorer rule 6)"
    private const val SUSPICIOUS_LADDER =
        "no ask, digital-arrest marker + >=3 stages => SUSPICIOUS 50..69 (LocalScorer rule 6)"
    private const val LEGIT_TWIN =
        "legitimate twin stays LOW_RISK <=24 with no verified ask (report §3.4)"

    private fun scam(
        id: String,
        family: String,
        language: String,
        expected: RiskLabel,
        specBasis: String,
        text: String,
    ) = Fixture(id, family, language, FixtureKind.SCAM, expected, specBasis, text)

    private fun legit(
        id: String,
        family: String,
        language: String,
        text: String,
    ) = Fixture(id, family, language, FixtureKind.LEGITIMATE, RiskLabel.LOW_RISK, LEGIT_TWIN, text)

    /** Every row, in family order. 47 total: 28 scam and 19 legitimate, including probes and controls. */
    val rows: List<Fixture> = listOf(

        // ---------------------------------------------------------------------------------------
        // 1. KYC link + OTP
        // ---------------------------------------------------------------------------------------
        scam(
            id = "F01",
            family = "KYC link + OTP",
            language = "Hinglish",
            expected = RiskLabel.HIGH_RISK,
            specBasis = HIGH,
            text = "Dear customer, aapka SBI KYC 24 ghante mein band ho jayega. Turant is link par " +
                "KYC update karein: bit.ly/3xYz9Q",
        ),
        scam(
            id = "F02",
            family = "KYC link + OTP",
            language = "English",
            expected = RiskLabel.HIGH_RISK,
            specBasis = HIGH,
            text = "SBI ALERT: Your account will be suspended today. Complete KYC verification at " +
                "http://sbi-kyc-verify.xyz/update within 2 hours or the account will be closed.",
        ),
        legit(
            id = "F03",
            family = "KYC link + OTP",
            language = "English",
            text = KYC_BRANCH,
        ),

        // ---------------------------------------------------------------------------------------
        // 2. Digital arrest (text form)
        // ---------------------------------------------------------------------------------------
        scam(
            id = "F04",
            family = "Digital arrest text",
            language = "Hinglish",
            expected = RiskLabel.HIGH_RISK,
            specBasis = HIGH,
            text = "Main CBI officer bol raha hoon. Aapke naam par money laundering ka case darj hai, " +
                "aap digital arrest mein hain. Video call par rahiye. Kisi ko mat batayein. Turant 2 " +
                "ghante mein Rs 50,000 verification account mein bhejo.",
        ),
        scam(
            id = "F05",
            family = "Digital arrest text",
            language = "Hindi (Devanagari)",
            expected = RiskLabel.HIGH_RISK,
            specBasis = HIGH,
            text = "मैं पुलिस अधिकारी बोल रहा हूँ। आपके पार्सल में ड्रग्स मिला है और आपका " +
                "गिरफ़्तारी वारंट जारी है। किसी को मत बताना। अभी ₹25,000 भेजो।",
        ),
        legit(
            id = "F06",
            family = "Digital arrest text",
            language = "English",
            text = "RBI advisory: no agency makes arrests over a video call and no one will ask you " +
                "for money to close a case. Report such calls on 1930.",
        ),

        // ---------------------------------------------------------------------------------------
        // 3. Electricity / gas / SIM disconnect
        // ---------------------------------------------------------------------------------------
        scam(
            id = "F07",
            family = "Electricity/gas/SIM disconnect",
            language = "Hinglish",
            expected = RiskLabel.HIGH_RISK,
            specBasis = HIGH,
            text = "Dear customer, aapka bijli connection aaj raat 9:30 baje kat jayega. Turant is " +
                "number par call karein 9876543210",
        ),
        scam(
            id = "F08",
            family = "Electricity/gas/SIM disconnect",
            language = "English",
            expected = RiskLabel.HIGH_RISK,
            specBasis = HIGH,
            text = "TRAI notice: your SIM card will be deactivated in 24 hours. Call 8899776655 now " +
                "to verify your number or it will be blocked permanently.",
        ),
        legit(
            id = "F09",
            family = "Electricity/gas/SIM disconnect",
            language = "English",
            text = "Your electricity bill of Rs 1,240 is due on 30 Oct. Pay through the official app " +
                "or at any payment centre.",
        ),

        // ---------------------------------------------------------------------------------------
        // 4. Job registration fee
        // ---------------------------------------------------------------------------------------
        scam(
            id = "F10",
            family = "Job registration fee",
            language = "Hinglish",
            expected = RiskLabel.HIGH_RISK,
            specBasis = HIGH,
            text = "Congratulations! Aap work from home job ke liye select ho gaye hain, salary " +
                "Rs 25,000 monthly. Registration fee Rs 499 abhi is UPI par bhejo: " +
                "jobpay@ybl",
        ),
        scam(
            id = "F11",
            family = "Job registration fee",
            language = "Hindi (Devanagari)",
            expected = RiskLabel.HIGH_RISK,
            specBasis = HIGH,
            text = "आपको घर बैठे नौकरी मिलेगी, रोज ₹2000 कमाओ। रजिस्ट्रेशन फीस ₹300 अभी भेजो।",
        ),
        legit(
            id = "F12",
            family = "Job registration fee",
            language = "English",
            text = "Interview scheduled for 28 Oct, 11:00 am at our Andheri office. Please carry a " +
                "photo ID. Registration is free.",
        ),

        // ---------------------------------------------------------------------------------------
        // 5. Lottery / KBC
        // ---------------------------------------------------------------------------------------
        scam(
            id = "F13",
            family = "Lottery/KBC",
            language = "Hinglish",
            expected = RiskLabel.HIGH_RISK,
            specBasis = HIGH,
            text = "KBC lucky draw mein aapka number select hua hai, aapne 25 lakh jeete hain! " +
                "Prize paane ke liye Rs 6,500 processing fee abhi bhejo. Kisi ko mat batana.",
        ),
        scam(
            id = "F14",
            family = "Lottery/KBC",
            language = "English",
            expected = RiskLabel.CAUTION,
            specBasis = CAUTION_ONE,
            text = "Congratulations! You won a prize of Rs 9,00,000 in the Amazon lucky draw. Pay " +
                "Rs 4,999 clearance charges by UPI to receive your prize.",
        ),
        legit(
            id = "F15",
            family = "Lottery/KBC",
            language = "Hinglish",
            text = "SBI alert: lottery ya prize ke naam par koi paisa maange to kabhi mat bhejo. " +
                "Bank kabhi prize ke liye fee nahi maangta.",
        ),

        // ---------------------------------------------------------------------------------------
        // 6. Courier / customs OTP
        // ---------------------------------------------------------------------------------------
        scam(
            id = "F16",
            family = "Courier/customs OTP",
            language = "Hinglish",
            expected = RiskLabel.HIGH_RISK,
            specBasis = HIGH,
            text = "Courier se bol rahe hain, aapke parcel mein illegal saman mila hai. Customs " +
                "clearance ke liye Rs 2,500 abhi bhejo aur OTP phone par bata do.",
        ),
        scam(
            id = "F17",
            family = "Courier/customs OTP",
            language = "English",
            expected = RiskLabel.HIGH_RISK,
            // Corrected spec reading (recorded honestly): THREAT_RULES' authority pattern matches a
            // bare agency noun, so "on hold at customs" is a third independent stage next to the
            // parcel threat and the OTP ask. Three corroborations => the 70 floor, not ask+1.
            specBasis = HIGH,
            text = "Your parcel is on hold at customs. Pay Rs 1,499 duty now or it will be returned. " +
                "Share the OTP you receive with our delivery agent.",
        ),
        legit(
            id = "F18",
            family = "Courier/customs OTP",
            language = "English",
            text = DELIVERY_DOOR,
        ),

        // ---------------------------------------------------------------------------------------
        // 7. APK install (.apk link)
        // ---------------------------------------------------------------------------------------
        scam(
            id = "F19",
            family = "APK install",
            language = "Hinglish",
            expected = RiskLabel.HIGH_RISK,
            specBasis = HIGH,
            text = "SBI KYC pending hai. Turant yeh app install karo: sbi-kyc-update.apk , warna " +
                "account band ho jayega.",
        ),
        scam(
            id = "F20",
            family = "APK install",
            language = "Hindi (Devanagari)",
            expected = RiskLabel.HIGH_RISK,
            specBasis = HIGH,
            text = "आपका खाता बंद हो जाएगा। तुरंत यह ऐप डाउनलोड करें: sbi-kyc.apk",
        ),
        legit(
            id = "F21",
            family = "APK install",
            language = "English",
            text = "Install the BHIM app from Google Play to check your balance. Never install APK " +
                "files that someone sends you on WhatsApp.",
        ),

        // ---------------------------------------------------------------------------------------
        // 8. AnyDesk / TeamViewer remote access
        // ---------------------------------------------------------------------------------------
        scam(
            id = "F22",
            family = "Remote access app",
            language = "Hinglish",
            expected = RiskLabel.HIGH_RISK,
            specBasis = HIGH,
            text = "Sir main SBI customer care se bol raha hoon. AnyDesk download karke screen share " +
                "karein, main abhi account theek kar deta hoon.",
        ),
        scam(
            id = "F23",
            family = "Remote access app",
            language = "English",
            expected = RiskLabel.CAUTION,
            specBasis = CAUTION_ONE,
            text = "This is HDFC bank support. Install QuickSupport so we can verify your account " +
                "remotely.",
        ),
        legit(
            id = "F24",
            family = "Remote access app",
            language = "Hinglish",
            text = "Kisi ke kehne par AnyDesk ya TeamViewer install mat karo. Hamari bank kabhi " +
                "screen share nahi maangti.",
        ),

        // ---------------------------------------------------------------------------------------
        // 9. UPI "receive" QR / PIN-to-receive
        // ---------------------------------------------------------------------------------------
        scam(
            id = "F25",
            family = "UPI receive QR / PIN-to-receive",
            language = "Hinglish",
            expected = RiskLabel.HIGH_RISK,
            specBasis = HIGH,
            text = "Rs 2,000 cashback milega. Turant is QR ko scan karein aur UPI PIN daalein, paisa " +
                "turant aa jayega.",
        ),
        scam(
            id = "F26",
            family = "UPI receive QR / PIN-to-receive",
            language = "English",
            expected = RiskLabel.CAUTION,
            specBasis = CAUTION_ALONE,
            text = "Scan this QR code to receive your refund of Rs 4,999 and enter your UPI PIN.",
        ),
        legit(
            id = "F27",
            family = "UPI receive QR / PIN-to-receive",
            language = "Hinglish",
            text = "Rs 500 received from Rahul Kumar via UPI. Yaad rahe: paise receive karne ke liye " +
                "UPI PIN kabhi nahi lagta.",
        ),

        // ---------------------------------------------------------------------------------------
        // 10. Refund-me-back
        // ---------------------------------------------------------------------------------------
        scam(
            id = "F28",
            family = "Refund-me-back",
            language = "Hinglish",
            expected = RiskLabel.CAUTION,
            // The report's §3.4 row 2 shape: one directional money ask plus one intimidation stage.
            // LocalScorer rule 5 caps "ask + exactly one other stage" at the 35..49 caution band on
            // purpose - a friend, or a hacked friend, can also ask for money back.
            specBasis = CAUTION_ONE,
            text = "Sir galti se aapke account mein Rs 4,000 bhej diye. Please wapas bhej do isi " +
                "number par, warna police complaint kar dunga.",
        ),
        scam(
            id = "F29",
            family = "Refund-me-back",
            language = "Hindi (Devanagari)",
            expected = RiskLabel.CAUTION,
            specBasis = CAUTION_ALONE,
            text = "गलती से आपके खाते में ₹5,000 भेज दिए हैं, कृपया वापस भेज दो।",
        ),
        legit(
            id = "F30",
            family = "Refund-me-back",
            language = "English",
            text = CREDIT_NOTICE,
        ),

        // ---------------------------------------------------------------------------------------
        // 11. Crypto / investment hook
        // ---------------------------------------------------------------------------------------
        scam(
            id = "F31",
            family = "Crypto/investment hook",
            language = "Hinglish",
            expected = RiskLabel.HIGH_RISK,
            specBasis = HIGH,
            text = "Crypto trading mein 30% monthly return guaranteed hai. Sirf aaj ke liye turant " +
                "Rs 5,000 is app par deposit karein.",
        ),
        scam(
            id = "F32",
            family = "Crypto/investment hook",
            language = "Hindi (Devanagari)",
            expected = RiskLabel.HIGH_RISK,
            specBasis = HIGH,
            text = "क्रिप्टो में निवेश करो, 40% मुनाफा पक्का है। आज ही ₹10,000 भेजो।",
        ),
        legit(
            id = "F33",
            family = "Crypto/investment hook",
            language = "English",
            text = "Market update: bitcoin is down 4% today. Invest only through registered advisors " +
                "and remember that no return is guaranteed.",
        ),

        // ---------------------------------------------------------------------------------------
        // 12. Aadhaar / PAN update
        // ---------------------------------------------------------------------------------------
        scam(
            id = "F34",
            family = "Aadhaar/PAN update",
            language = "Hinglish",
            expected = RiskLabel.HIGH_RISK,
            specBasis = HIGH,
            text = "Income Tax Department alert: aapka Aadhaar PAN se link nahi hai, isliye account " +
                "block ho jayega. Aaj hi apna Aadhaar number aur OTP WhatsApp par bhejo.",
        ),
        scam(
            id = "F35",
            family = "Aadhaar/PAN update",
            language = "Hindi (Devanagari)",
            expected = RiskLabel.HIGH_RISK,
            specBasis = HIGH,
            text = "आधार कार्ड अपडेट करने के लिए तुरंत अपना आधार नंबर और ओटीपी भेजें। " +
                "वरना खाता बंद हो जाएगा।",
        ),
        legit(
            id = "F36",
            family = "Aadhaar/PAN update",
            language = "English",
            text = "UIDAI: Aadhaar update is free at any Aadhaar centre. Book a slot at uidai.gov.in " +
                "and carry one proof of address.",
        ),

        // ---------------------------------------------------------------------------------------
        // Extra legitimate guards for the two rule shapes this lane touched: Devanagari
        // anti-download advice and an ordinary logistics SMS that carries the agent's own mobile.
        // ---------------------------------------------------------------------------------------
        legit(
            id = "F37",
            family = "Legit guard: Devanagari anti-APK advisory",
            language = "Hindi (Devanagari)",
            text = "सावधान: किसी भी अनजान APK फाइल को डाउनलोड करने से बचें।",
        ),
        legit(
            id = "F38",
            family = "Legit guard: delivery SMS with the agent's mobile",
            language = "English",
            text = "Your parcel is out for delivery today between 2-5 pm. Call 9876543210 if the " +
                "address is wrong.",
        ),
        legit(
            id = "F39",
            family = "Legit guard: Hindi delivery OTP at the door",
            language = "Hindi (Devanagari)",
            text = "डिलीवरी ओटीपी 4521, दरवाज़े पर एजेंट को बताएं।",
        ),
        legit(
            id = "F40",
            family = "Legit guard: routine electricity bill support number",
            language = "English",
            text = "Your electricity bill is due tomorrow. Call 9876543210 if you have questions.",
        ),

        // ---------------------------------------------------------------------------------------
        // PHASE4 item 5 probes - the three exact strings from the head orders.
        // ---------------------------------------------------------------------------------------
        legit(
            id = "P01",
            family = "Probe: Devanagari OTP notice",
            language = "Hindi (Devanagari)",
            text = PROBE_OTP_DEVANAGARI,
        ),
        legit(
            id = "P02",
            family = "Probe: mixed-script SBI account block",
            language = "Mixed (Roman + Devanagari)",
            text = PROBE_MIXED_SBI_BLOCK,
        ),
        scam(
            id = "P03",
            family = "Probe: Devanagari Aadhaar/digital-arrest",
            language = "Hindi (Devanagari)",
            expected = RiskLabel.SUSPICIOUS,
            specBasis = SUSPICIOUS_LADDER,
            text = PROBE_DIGITAL_ARREST_DEVANAGARI,
        ),

        // ---------------------------------------------------------------------------------------
        // Band-ladder controls: the four rule-gate rows that pin the ladder itself.
        // ---------------------------------------------------------------------------------------
        scam(
            id = "C01",
            family = "Control: bare credential ask",
            language = "Hinglish",
            expected = RiskLabel.CAUTION,
            specBasis = CAUTION_ALONE,
            text = "Bhai jo code aaya woh bhej de.",
        ),
        scam(
            id = "C02",
            family = "Control: threat only, no ask",
            language = "Hinglish",
            expected = RiskLabel.LOW_RISK,
            specBasis = LOW_WATCH,
            text = "Aapka account band ho jayega.",
        ),
        scam(
            id = "C03",
            family = "Control: authority only, no ask",
            language = "English",
            expected = RiskLabel.LOW_RISK,
            specBasis = LOW_WATCH,
            text = "This is TRAI. Your number will be disconnected.",
        ),
        legit(
            id = "C04",
            family = "Control: OTP warning (report §3.4 row 1)",
            language = "English",
            text = OTP_NOTICE,
        ),
    )
}
