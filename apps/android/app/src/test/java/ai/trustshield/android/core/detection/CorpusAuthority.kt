package ai.trustshield.android.core.detection

/**
 * Authority / digital-arrest / KYC-bank / OTP-sharing fixture corpus (lane A, wave 1).
 *
 * Every row is fed to `LocalScanPipeline().analyzeText(text)` by the corpus gate. `minScore` on a
 * `SCAM` row is the score the row **must reach**; `maxScore` on a `CONTROL` row is the score it must
 * stay at or under. The numbers are expectations, not measurements — the lane lead measures them.
 *
 * `FixtureRow` and `FixtureKind` are declared in `DetectionCorpus.kt` in this same package, so this
 * file declares no types of its own and needs no import.
 *
 * ## Why the bands are what they are
 *
 * The engine's documented ladder (see `LocalScorer`): a verified directional ask plus two other
 * independent stages reaches the `>= 70` floor; ask plus one other stage is clamped to `35..49`; ask
 * alone to `25..45`; and a **no-ask** reading can only leave the watch band through the separate
 * `DigitalArrestSignals` marker set (`>= 2` other stages -> `25..49`, `>= 3` -> `50..69`). So:
 *
 *  * `70` only where the text carries a real ask **and** at least two corroborating stages
 *    (authority + threat, or threat + urgency, or authority + secrecy);
 *  * `50` for a digital-arrest marker set with three corroborating stages and no ask;
 *  * `25` for a marker set with two stages, and for an ask with nothing else behind it;
 *  * `35` for an ask with exactly one corroborating stage (the engine's ask-plus-one clamp);
 *  * `24`/`15` for controls, where the engine should stay quiet.
 *
 * ## Text discipline
 *
 * Every `text` is a raw string and is quoted verbatim by the engine, so nothing here may contain a
 * character that changes the words the user sees. The three mandated sentences appear verbatim:
 * `आपका ओटीपी ४५६७ है` (D-A23), `Aapka SBI खाता block हो गया hai` (D-A13), and a Devanagari
 * आधार/गिरफ्तार digital-arrest sentence (D-A08).
 *
 * ## Known engine gaps this corpus exposes (stated, not hidden)
 *
 *  * A KYC/bank threat with an OTP ask and **no** second corroborating stage (D-A17, D-A18) can only
 *    reach the ask-plus-one clamp (`35..49`), never `HIGH_RISK` — by design.
 *  * A digital-arrest call that has only reached the marker phrases (D-A07, D-A08) is capped at
 *    `49` because no money or credential has been asked for yet.
 *  * The bare `arrest\w*` threat pattern fires on advisory text that merely *names* "digital arrest"
 *    (D-A29); the no-ask ceiling hides it there, but any control that pairs the phrase with two other
 *    stages would land in `25..49` instead of the watch band.
 */
internal val CORPUS_AUTHORITY: List<FixtureRow> = listOf(

    // ---------------------------------------------------------------------------------------------
    // digital_arrest — fake CBI/ED/customs/TRAI officer, arrest or parcel threat, isolation, ask
    // ---------------------------------------------------------------------------------------------

    FixtureRow(
        id = "D-A01",
        category = "digital_arrest",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """Main CBI officer Sharma bol raha hoon. Aapke Aadhaar se bheje gaye parcel mein drugs mile hain. Aap digital arrest mein hain. Video call par raho aur jo OTP aaya hai woh batao.""",
        minScore = 70,
        expectAsk = true,
        note = "Authority + drugs-parcel threat + video-call hold + OTP request: four stages with a verified OTP_PIN ask.",
    ),
    FixtureRow(
        id = "D-A02",
        category = "digital_arrest",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """ED officer bol raha hoon, aapke naam par money laundering ka case darj hai. Turant 50,000 Rs is account mein transfer karo, warna arrest ho jayega. Yeh baat kisi ko mat batana.""",
        minScore = 70,
        expectAsk = true,
        note = "ED + money-laundering case + urgency + secrecy + Rs transfer: five stages, MONEY_TRANSFER ask.",
    ),
    FixtureRow(
        id = "D-A03",
        category = "digital_arrest",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """Customs department se bol raha hoon. Aapke naam ka parcel customs mein ruka hai, usme illegal items mile hain. Video call par raho aur ghar walon ko mat batana.""",
        minScore = 50,
        expectAsk = false,
        note = "Customs + held-parcel threat + video-call hold + 'ghar walon ko mat batana' marker, no ask: strong signal with three stages.",
    ),
    FixtureRow(
        id = "D-A04",
        category = "digital_arrest",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """TRAI officer bol raha hoon. Aapki SIM se illegal calls hue hain, isliye aapki SIM 2 ghante mein band ho jayegi. Aap digital arrest mein hain.""",
        minScore = 50,
        expectAsk = false,
        note = "TRAI + SIM-block threat + 2-hour deadline + 'aap digital arrest mein hain' marker, no ask: three stages with the strong signal.",
    ),
    FixtureRow(
        id = "D-A05",
        category = "digital_arrest",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """Mumbai police se Inspector bol raha hoon. Aapka arrest warrant jaari ho chuka hai. Camera band mat karna, video call par raho. Turant AnyDesk download karo verification ke liye.""",
        minScore = 70,
        expectAsk = true,
        note = "Police + arrest warrant + camera/video-call hold + urgency + AnyDesk: four stages, REMOTE_APP ask.",
    ),
    FixtureRow(
        id = "D-A06",
        category = "digital_arrest",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """Aap digital arrest mein hain, yeh CBI ka confidential case hai. Apni FD tod kar Rs 2,00,000 is account mein bhejo, warna giraftari warrant nikal jayega.""",
        minScore = 70,
        expectAsk = true,
        note = "CBI + confidential case + FD-break marker + Rs 2,00,000 transfer + warrant threat: MONEY_TRANSFER ask with three stages.",
    ),
    FixtureRow(
        id = "D-A07",
        category = "digital_arrest",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """Aap digital arrest mein hain. Main CBI se bol raha hoon. Kamre mein akele raho, kisi ko mat batana.""",
        minScore = 25,
        expectAsk = false,
        note = "Marker + CBI claim + isolation, nothing demanded yet: no-ask ladder caps this at 25..49.",
    ),
    FixtureRow(
        id = "D-A08",
        category = "digital_arrest",
        language = "devanagari",
        kind = FixtureKind.SCAM,
        text = """मैं सीबीआई अधिकारी बोल रहा हूँ। आपके आधार से भेजे गए पार्सल में ड्रग्स मिले हैं। आपका गिरफ्तारी वारंट जारी हो चुका है।""",
        minScore = 25,
        expectAsk = false,
        note = "Devanagari CBI + Aadhaar parcel + गिरफ्तारी वारंट marker, no ask: two stages with the strong signal.",
    ),
    FixtureRow(
        id = "D-A09",
        category = "digital_arrest",
        language = "devanagari",
        kind = FixtureKind.SCAM,
        text = """मैं सीबीआई अधिकारी हूँ। आप गिरफ्तार हो सकते हैं, आपका आधार ब्लॉक हो जाएगा। तुरंत ओटीपी बताइए।""",
        minScore = 70,
        expectAsk = true,
        note = "Devanagari CBI + गिरफ्तार threat + तुरंत + ओटीपी बताइए: OTP_PIN ask with three stages.",
    ),
    FixtureRow(
        id = "D-A10",
        category = "digital_arrest",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """Main CBI se bol raha hoon. Aap digital arrest mein hain. Turant apna Aadhaar card aur bank passbook ki photo WhatsApp par bhejo, warna arrest warrant nikal jayega.""",
        minScore = 70,
        expectAsk = true,
        note = "CBI + digital-arrest marker + urgency + Aadhaar/passbook photo to WhatsApp: PERSONAL_INFO ask.",
    ),
    FixtureRow(
        id = "D-A11",
        category = "digital_arrest",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """Main CBI se bol raha hoon. Aap digital arrest mein hain. Turant yeh app download karo cbi-verify.apk, warna arrest warrant nikal jayega.""",
        minScore = 70,
        expectAsk = true,
        note = "Sideloaded .apk in a digital-arrest frame: APK_INSTALL ask with authority, threat and urgency.",
    ),

    // ---------------------------------------------------------------------------------------------
    // kyc_bank — account/card/SIM block, KYC via short link, OTP/CVV to unblock
    // ---------------------------------------------------------------------------------------------

    FixtureRow(
        id = "D-A12",
        category = "kyc_bank",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """Dear customer, aapka SBI khata 24 ghante mein block ho jayega. KYC update karne ke liye bit.ly/sbi-kyc par click karo aur OTP batao.""",
        minScore = 70,
        expectAsk = true,
        note = "Khata-block threat + 24-ghante urgency + bit.ly KYC link + OTP request: two asks, three stages.",
    ),
    FixtureRow(
        id = "D-A13",
        category = "kyc_bank",
        language = "mixed",
        kind = FixtureKind.SCAM,
        text = """Aapka SBI खाता block हो गया hai. Turant unblock karne ke liye jo OTP aaya hai woh batao.""",
        minScore = 70,
        expectAsk = true,
        note = "Mandated sentence. Roman+Devanagari account-block threat, turant, OTP request: threat + urgency + ask.",
    ),
    FixtureRow(
        id = "D-A14",
        category = "kyc_bank",
        language = "english",
        kind = FixtureKind.SCAM,
        text = """Dear customer, your HDFC debit card will be blocked in 2 hours. To reactivate, share the 6 digit code sent to your mobile with our executive.""",
        minScore = 70,
        expectAsk = true,
        note = "Card-block threat + 2-hour deadline + '6 digit code' share request: OTP_PIN ask, no authority claim needed.",
    ),
    FixtureRow(
        id = "D-A15",
        category = "kyc_bank",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """SBI KYC 24 ghante mein band ho jayega. Is link par KYC update karo aur apna Aadhaar number batao: bit.ly/kyc-sbi""",
        minScore = 70,
        expectAsk = true,
        note = "KYC-block threat + urgency + Aadhaar number request behind a shortener: PERSONAL_INFO ask.",
    ),
    FixtureRow(
        id = "D-A16",
        category = "kyc_bank",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """Aapki SIM TRAI ke record mein illegal dikh rahi hai. 2 ghante mein SIM block ho jayegi. Turant 9876543210 par call karein.""",
        minScore = 70,
        expectAsk = true,
        note = "TRAI + SIM-block threat + urgency + personal mobile callback: CALLBACK ask (stage 5, but no full-screen escalation).",
    ),
    FixtureRow(
        id = "D-A17",
        category = "kyc_bank",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """Sir main SBI customer care se bol raha hoon, aapka account update nahi hua. Jo OTP aaya hai woh bata do.""",
        minScore = 35,
        expectAsk = true,
        note = "Bank customer-care claim + OTP request, no threat and no urgency: ask-plus-one clamp, 35..49.",
    ),
    FixtureRow(
        id = "D-A18",
        category = "kyc_bank",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """Aapka HDFC credit card block ho gaya hai. Card chalu karne ke liye CVV batao.""",
        minScore = 35,
        expectAsk = true,
        note = "Card-block threat + CVV request only: one corroborating stage, so 35..49 by the documented clamp.",
    ),
    FixtureRow(
        id = "D-A19",
        category = "kyc_bank",
        language = "mixed",
        kind = FixtureKind.SCAM,
        text = """Dear Customer, your account will be suspended within 24 hours. KYC update karne ke liye http://sbi-kyc-verify.xyz/upd par jaayein.""",
        minScore = 70,
        expectAsk = true,
        note = "Account-suspension threat + 24 hours + KYC update on a throwaway .xyz host: PERSONAL_INFO ask.",
    ),

    // ---------------------------------------------------------------------------------------------
    // otp_sharing — 'jo OTP aaya woh batao', 'OTP bhej do', '6 digit code confirm karo'
    // ---------------------------------------------------------------------------------------------

    FixtureRow(
        id = "D-A20",
        category = "otp_sharing",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """Sir jo OTP aaya woh batao, warna refund ruk jayega.""",
        minScore = 25,
        expectAsk = true,
        note = "OTP told to the sender with nothing corroborating it: ask-alone band 25..45.",
    ),
    FixtureRow(
        id = "D-A21",
        category = "otp_sharing",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """OTP bhej do, 6 digit code confirm karo.""",
        minScore = 25,
        expectAsk = true,
        note = "Two phrasings of the same credential ask, no other stage: ask-alone band 25..45.",
    ),
    FixtureRow(
        id = "D-A22",
        category = "otp_sharing",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """Aapka account band ho jayega agar aapne OTP share nahi kiya. Turant OTP batao.""",
        minScore = 70,
        expectAsk = true,
        note = "Account-block threat + turant + OTP request: threat and urgency corroborate the ask.",
    ),
    FixtureRow(
        id = "D-A23",
        category = "otp_sharing",
        language = "devanagari",
        kind = FixtureKind.SCAM,
        text = """आपका ओटीपी ४५६७ है। इसे तुरंत मुझे बताइए, वरना खाता बंद हो जाएगा।""",
        minScore = 70,
        expectAsk = true,
        note = "Mandated sentence with Devanagari digits. खाता-बंद threat + तुरंत + बताइए: OTP_PIN ask.",
    ),
    FixtureRow(
        id = "D-A24",
        category = "otp_sharing",
        language = "english",
        kind = FixtureKind.SCAM,
        text = """Please tell me the verification code you just received on your phone, otherwise the payment will fail.""",
        minScore = 25,
        expectAsk = true,
        note = "English 'verification code' read-out request with no corroborating stage: ask-alone band.",
    ),
    FixtureRow(
        id = "D-A25",
        category = "otp_sharing",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """SBI se bol raha hoon. Yeh case confidential hai, kisi ko mat batana, warna account block ho jayega. Jo OTP aaya hai woh mujhe batao.""",
        minScore = 70,
        expectAsk = true,
        note = "Bank claim + confidential 'kisi ko mat batana' + account-block threat + OTP request: three stages corroborate the ask.",
    ),

    // ---------------------------------------------------------------------------------------------
    // legit_control — real bank/utility/police wording; the engine must stay quiet
    // ---------------------------------------------------------------------------------------------

    FixtureRow(
        id = "D-A26",
        category = "legit_control",
        language = "english",
        kind = FixtureKind.CONTROL,
        text = """123456 is your OTP for txn of Rs 500. Do not share it with anyone. -SBI""",
        maxScore = 24,
        expectAsk = false,
        note = "Real OTP notice. 'Do not share' is protective, never stage 5; the credential-scoped skip keeps it out of stage 4 too.",
    ),
    FixtureRow(
        id = "D-A27",
        category = "legit_control",
        language = "english",
        kind = FixtureKind.CONTROL,
        text = """Your KYC is due. Visit your branch or update via net banking.""",
        maxScore = 24,
        expectAsk = false,
        note = "Official-channel KYC advice with no link, no deadline and no request for data.",
    ),
    FixtureRow(
        id = "D-A28",
        category = "legit_control",
        language = "english",
        kind = FixtureKind.CONTROL,
        text = """Rs 5,000 credited to A/c XX1234. Avl bal Rs 12,340.""",
        maxScore = 15,
        expectAsk = false,
        note = "Credit-only balance alert: factual, no ask, no threat. Existing suite measures this shape at 0.",
    ),
    FixtureRow(
        id = "D-A29",
        category = "legit_control",
        language = "hinglish",
        kind = FixtureKind.CONTROL,
        text = """Cyber Crime police ki salah: digital arrest scam se bachein. Paise ya OTP kisi ko na dein. Report karne ke liye 1930 par call karein.""",
        maxScore = 24,
        expectAsk = false,
        note = "Genuine police advisory naming the scam. Advisory cue suppresses the digital-arrest marker; the bare 'arrest' threat pattern still fires, but the no-ask ceiling holds it at <= 24.",
    ),
    FixtureRow(
        id = "D-A30",
        category = "legit_control",
        language = "hinglish",
        kind = FixtureKind.CONTROL,
        text = """Aapka KYC update pending hai. 30 Nov tak apni branch mein Aadhaar ke saath jaakar update karwa lein. Net banking se bhi ho sakta hai.""",
        maxScore = 15,
        expectAsk = false,
        note = "Branch/net-banking KYC reminder: no link, no loss verb, no request for the Aadhaar number itself.",
    ),
)
