package ai.trustshield.android.core.detection

/**
 * Service-side scam corpus: utility disconnection, parcel/customs, fake job offers and instant-loan
 * apps — the four message families a user forwards most often (report §3.1, §3.4).
 *
 * ## What a row is
 *
 * One [FixtureRow] is one raw message plus what the deterministic engine is *expected* to do with
 * it. [FixtureRow.minScore] is a floor the row must reach, never an accuracy claim: the corpus
 * exists so a regression shows up as a number, not as an opinion. Rows are grouped by
 * [FixtureRow.category] in the brief's exact spellings (`utility_bill`, `parcel_customs`,
 * `job_offer`, `loan_app`) and end with `legit_control` rows that must stay at or under
 * [FixtureRow.maxScore] = 24 (the `LOW_RISK` ceiling).
 *
 * ## How the numbers were chosen
 *
 * The score is not aesthetic. It follows the documented ladder in
 * `ai.trustshield.android.core.score.LocalScorer`:
 *
 *  * a **verified directional ask** (OTP/PIN, money, remote app, APK, personal data, personal
 *    callback number) plus `>= 2` other independent stages reaches the `70` floor — those rows carry
 *    `minScore = 70`;
 *  * the same ask with only **one** other stage is clamped into `35..49` — those rows carry `35`;
 *  * ask + threat with **no** urgency lands mid-band — `40`;
 *  * a **no-ask** message is capped at `24` unless a `DigitalArrestSignals` marker fires; with a
 *    marker and `>= 3` other stages the band is `50..69` (`minScore = 50`), with a marker and two
 *    stages `25..49` (`minScore = 40`), and with no marker at all `24` (`minScore = 22`).
 *
 * Every row states its own mechanism in [FixtureRow.note] so a row that later moves can be
 * re-derived instead of re-guessed. Two rows are deliberately below their family's usual band: the
 * engine honestly cannot do better on them today, and pinning the honest number is what makes the
 * next improvement visible.
 *
 * ## What these rows refuse to do
 *
 *  * No row relies on a loosened guard. Every scam row's ask is a real, non-negated direction
 *    ("bhejo", "call karein", "daalein", "install karo"), which is why the protective twins in
 *    `legit_control` can sit in the same file without contradiction.
 *  * The `legit_control` rows are the false-alarm side of the same families: a utility bill with a
 *    due date and no loss verb, a courier "out for delivery" notice, a genuine offer letter with no
 *    fee, a real EMI statement, and a debit alert whose only credential wording is the bank's own
 *    "never share OTP" line.
 *  * Devanagari rows keep their ask in the Roman/English form the current rules can read (scammers
 *    mix scripts in exactly this way). A purely Devanagari *ask* is still out of reach for A03 and is
 *    a fair target for a dedicated rule module, not something to fake here.
 *
 * These expectations are read off the rule text, not measured: the lane lead compiles and runs them.
 */
internal val CORPUS_SERVICE: List<FixtureRow> = listOf(

    // -----------------------------------------------------------------------------------------
    // utility_bill — disconnection threats and "update your bill here" redirects
    // -----------------------------------------------------------------------------------------

    FixtureRow(
        id = "D-S01",
        category = "utility_bill",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """Dear customer, aapka bijli connection aaj raat 9:30 baje kat jayega. Turant is number par call karein 9876543210 aur bill update karein.""",
        minScore = 70,
        expectAsk = true,
        note = "Flagship shape: disconnect threat + urgency + callback ask, and the bill-update rule " +
            "sees utility noun, loss verb and personal mobile in one message.",
    ),
    FixtureRow(
        id = "D-S02",
        category = "utility_bill",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """Dear customer, aapka LPG gas connection aaj shaam band kar diya jayega kyunki bill pending hai. Turant 9988776655 par call karein aur payment karein.""",
        minScore = 70,
        expectAsk = true,
        note = "Same ladder on gas: 'band kar diya jayega' is the imminent-loss form, urgency from " +
            "'turant', ask is the personal mobile in a callback imperative.",
    ),
    FixtureRow(
        id = "D-S03",
        category = "utility_bill",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """Jal Board notice: aapke water connection ki last date aaj hai, kal se paani band ho jayega. Bill jama karne ke liye is link par apna account number daalein: http://bit.ly/jal-bill-pay""",
        minScore = 70,
        expectAsk = true,
        note = "No phone number: the theft step is the shortener plus 'account number daalein' " +
            "(personal-data ask). Threat + 'last date aaj' corroborate.",
    ),
    FixtureRow(
        id = "D-S04",
        category = "utility_bill",
        language = "mixed",
        kind = FixtureKind.SCAM,
        text = """प्रिय ग्राहक, आपका बिजली कनेक्शन आज रात कट जाएगा। तुरंत 9123456780 par call karein aur bill update karein.""",
        minScore = 70,
        expectAsk = true,
        note = "Devanagari threat body with the Roman ask real senders actually type. " +
            "'कट जाएगा' + 'आज रात' + 'तुरंत' carry the two corroborating stages.",
    ),
    FixtureRow(
        id = "D-S05",
        category = "utility_bill",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """Aapka LPG connection band ho jayega aaj shaam agar bill aaj hi jama nahi hua. Gas bill update karein aur 8877665544 par call karein.""",
        minScore = 70,
        expectAsk = true,
        note = "The negation sits in the first sentence ('jama nahi hua'), the ask in the second — " +
            "a same-clause guard must keep the ask and the row must still climb.",
    ),
    FixtureRow(
        id = "D-S06",
        category = "utility_bill",
        language = "devanagari",
        kind = FixtureKind.SCAM,
        text = """सूचना: आपका बिजली कनेक्शन आज रात कट जाएगा। मीटर खराब है, कृपया सुबह दफ्तर आएं।""",
        minScore = 22,
        expectAsk = false,
        note = "Deliberately ask-free and fully Devanagari: a threat plus urgency with no theft " +
            "step is capped at 24 by the no-ask band, and no current ask rule reads a Devanagari verb.",
    ),
    FixtureRow(
        id = "D-S07",
        category = "utility_bill",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """Electricity bill notice: aapka power connection cut ho jayega tonight. Bill update karein before midnight, warna connection restore karne mein 2 din lagenge. Call 9765432100 for update.""",
        minScore = 70,
        expectAsk = true,
        note = "English 'before midnight' urgency token plus the Hinglish loss verb; ask is the " +
            "bill-update request that names the personal mobile later in the message.",
    ),

    // -----------------------------------------------------------------------------------------
    // parcel_customs — held parcels, clearance fees, fake tracking pages, drug-parcel pressure
    // -----------------------------------------------------------------------------------------

    FixtureRow(
        id = "D-S08",
        category = "parcel_customs",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """India Post: aapka parcel customs mein held hai. Clearance fee Rs 799 bhejna hoga warna parcel wapas chala jayega. UPI 9845012345 par bhejein.""",
        minScore = 70,
        expectAsk = true,
        note = "Clearance-fee money ask to a UPI id; authority ('customs') + held-parcel threat " +
            "are the two corroborating stages.",
    ),
    FixtureRow(
        id = "D-S09",
        category = "parcel_customs",
        language = "mixed",
        kind = FixtureKind.SCAM,
        text = """Your parcel delivery failed. Redelivery ke liye Rs 25 pay karo is UPI par: 7021234567@ybl, warna parcel cancel ho jayega. 24 hours.""",
        minScore = 70,
        expectAsk = true,
        note = "The 'delivery failed, pay Rs 25' micro-fee shape. Money ask + cancel threat + a " +
            "'24 hours' countdown.",
    ),
    FixtureRow(
        id = "D-S10",
        category = "parcel_customs",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """BlueDart courier: aapka parcel address issue ki wajah se blocked hai. Aaj hi address confirm karne ke liye apna aadhaar number batao aur is link par daalein: http://bluedart-track.xyz/confirm""",
        minScore = 70,
        expectAsk = true,
        note = "Fake tracking page: Aadhaar number asked back (personal-data ask) with blocked " +
            "parcel + 'aaj hi' as corroboration.",
    ),
    FixtureRow(
        id = "D-S11",
        category = "parcel_customs",
        language = "mixed",
        kind = FixtureKind.SCAM,
        text = """Customs Department notice: aapke naam se bheja gaya parcel mein drugs mile hain. Aap digital arrest mein hain. Kamre mein akele raho aur video call par rahiye. Kisi ko mat batana.""",
        minScore = 50,
        expectAsk = false,
        note = "Drugs-in-parcel pressure with no theft step yet: authority + threat + secrecy plus " +
            "three digital-arrest markers, so the no-ask ladder reaches 50..69 and never HIGH_RISK.",
    ),
    FixtureRow(
        id = "D-S12",
        category = "parcel_customs",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """Yeh Customs department se bol rahe hain. Aapka parcel customs clearance mein ruka hai, clearance fee Rs 1,250 jama karo, 8012345678 par call karein warna parcel destroy ho jayega.""",
        minScore = 70,
        expectAsk = true,
        note = "Officer claim + held parcel + clearance-fee transfer to a personal mobile: money " +
            "ask and callback ask both fire, two other stages corroborate.",
    ),
    FixtureRow(
        id = "D-S13",
        category = "parcel_customs",
        language = "mixed",
        kind = FixtureKind.SCAM,
        text = """कस्टम्स विभाग सूचना: आपका पार्सल कस्टम में रोका गया है। क्लीयरेंस फीस Rs 550 bhejo, warna पार्सल नीलाम हो जाएगा।""",
        minScore = 70,
        expectAsk = true,
        note = "Devanagari customs notice with a Roman 'bhejo' — the money ask the rules can read, " +
            "with 'पार्सल … रोका' as the threat and 'कस्टम्स' as the authority claim.",
    ),
    FixtureRow(
        id = "D-S14",
        category = "parcel_customs",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """Delivery failed: aapka parcel blocked hai. Aaj hi naya courier app APK download karein: http://bit.ly/cr-app.apk aur address confirm karein.""",
        minScore = 70,
        expectAsk = true,
        note = "Sideloaded 'courier app' behind a shortener — the strongest ask type after an OTP, " +
            "with blocked parcel + 'aaj hi' behind it. The words 'APK download' have to be adjacent: " +
            "the rule's .apk-URL branch never matches (see the hand-off note on rx()).",
    ),

    // -----------------------------------------------------------------------------------------
    // job_offer — registration/security fees, WFH task deposits, joining fees
    // -----------------------------------------------------------------------------------------

    FixtureRow(
        id = "D-S15",
        category = "job_offer",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """Congratulations aap job ke liye select ho gaye. Aaj hi registration fee Rs 1,500 bhejo is UPI par: hr.amazon@ybl, warna seat kisi aur ko de di jayegi.""",
        minScore = 70,
        expectAsk = true,
        note = "Selection reward + registration-fee transfer to a UPI handle; 'aaj hi' supplies the " +
            "second corroborating stage.",
    ),
    FixtureRow(
        id = "D-S16",
        category = "job_offer",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """Work from home part time job: daily 3 ghante kaam, Rs 2,000 per day. Task complete karne ke liye pehle Rs 500 deposit karo is link par: http://taskpay.online/join""",
        minScore = 35,
        expectAsk = true,
        note = "Work-from-home task deposit. Money ask + a '3 ghante' urgency token is one " +
            "corroborating stage, so the ask band clamps to 35..49 — honest 35, not 70.",
    ),
    FixtureRow(
        id = "D-S17",
        category = "job_offer",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """Part time data entry job, ghar se Rs 15,000 monthly kamayein. Security deposit Rs 750 pay karein account activate karne ke liye. Aaj hi WhatsApp karein 9432108765.""",
        minScore = 35,
        expectAsk = true,
        note = "Data-entry 'security deposit'. The fee phrase alone is the money ask (no transfer " +
            "verb needed by that pattern); one urgency stage keeps it in 35..49.",
    ),
    FixtureRow(
        id = "D-S18",
        category = "job_offer",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """Congratulations aap 25,000 per day job ke liye select ho gaye. Abhi joining fee Rs 1,999 bhejo aur apna aadhaar card photo bhejo is number par: 8765432109.""",
        minScore = 70,
        expectAsk = true,
        note = "The '25,000/day job, pay joining fee' shape: money ask and Aadhaar-photo ask, with " +
            "reward + 'abhi' behind them.",
    ),
    FixtureRow(
        id = "D-S19",
        category = "job_offer",
        language = "mixed",
        kind = FixtureKind.SCAM,
        text = """Badhai ho, आपको data entry job मिल गई है। Foran registration fee Rs 900 bhejo is UPI par: jobhelp@okaxis, warna seat cancel ho jayegi.""",
        minScore = 70,
        expectAsk = true,
        note = "Devanagari body, Roman reward word and Roman ask — registration fee to a UPI id " +
            "with 'foran' urgency.",
    ),
    FixtureRow(
        id = "D-S20",
        category = "job_offer",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """HR officer bol raha hoon, Infosys se. Aapko work from home job mili hai, daily 5 task aur Rs 300 per task. Aaj hi Rs 1,000 deposit karke VIP group join karein, WhatsApp 9123450987.""",
        minScore = 70,
        expectAsk = true,
        note = "Recruiter claim ('HR officer bol raha hoon') + task-app deposit: authority and " +
            "urgency corroborate the money ask.",
    ),

    // -----------------------------------------------------------------------------------------
    // loan_app — pre-approved offers, release/processing fees, recovery threats, CIBIL fees
    // -----------------------------------------------------------------------------------------

    FixtureRow(
        id = "D-S21",
        category = "loan_app",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """RBI approved NBFC: aapka instant loan Rs 50,000 pre-approved hai, 10 minute mein account mein. Processing fee Rs 499 bhejo is UPI par: loanfast@ybl.""",
        minScore = 70,
        expectAsk = true,
        note = "Pre-approved instant loan, processing fee to a UPI id. Deliberately 'RBI approved', " +
            "not 'RBI registered' — the helpline skip swallows 'register'.",
    ),
    FixtureRow(
        id = "D-S22",
        category = "loan_app",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """HDFC loan department se: aapka loan approved hai but on hold. Aaj hi release fee Rs 1,200 pay karo. App APK install karo: http://bit.ly/ln-app.apk""",
        minScore = 70,
        expectAsk = true,
        note = "Release fee to unlock an already-approved loan plus a sideloaded APK; bank " +
            "department claim + 'aaj hi' corroborate. 'APK install' is adjacent on purpose (the " +
            "rule cannot match a bare .apk URL).",
    ),
    FixtureRow(
        id = "D-S23",
        category = "loan_app",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """Loan app recovery: aapne Rs 8,000 ka loan liya tha. Aaj hi payment karo warna aapki photo contacts ko bhej di jayegi aur FIR ho jayegi. Is number par call karein 7654321098.""",
        minScore = 70,
        expectAsk = true,
        note = "Recovery extortion: contact-list + FIR threat with a personal callback number. A " +
            "callback ask earns the 70 floor here but must not release the full-screen alert alone.",
    ),
    FixtureRow(
        id = "D-S24",
        category = "loan_app",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """CIBIL score sudharne ke liye fee Rs 2,499 pay karein, warna aapka credit card block ho jayega. Apna PAN number aur date of birth share karein: http://cibil-fix.online/form""",
        minScore = 40,
        expectAsk = true,
        note = "CIBIL-repair fee plus card-block threat and a PAN/DOB form. Ask + one other stage " +
            "only, so the honest band is 35..49 (expected ~45).",
    ),
    FixtureRow(
        id = "D-S25",
        category = "loan_app",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """Loan disbursement ke liye aapke account ka OTP verify karna hoga. OTP 482913 abhi batao warna aapka account block ho jayega. Helpline 7012398765.""",
        minScore = 70,
        expectAsk = true,
        note = "OTP read back to release a loan: credential ask + block threat + 'abhi'. Should " +
            "escalate, unlike the callback rows.",
    ),
    FixtureRow(
        id = "D-S26",
        category = "loan_app",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """Loan recovery department: aapka account block ho jayega aur photo contacts ko bhej di jayegi. Video call par rahiye aur kisi ko mat batana.""",
        minScore = 40,
        expectAsk = false,
        note = "No ask yet: block threat + secrecy, with the video-call marker. Two corroborating " +
            "stages put it in 25..49 (expected ~46) — CAUTION, not SUSPICIOUS.",
    ),
    FixtureRow(
        id = "D-S27",
        category = "loan_app",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """Loan app final notice: aapka account seize ho jayega. Video call par rahiye, camera band mat karna, aur kisi ko mat batana. Aaj raat tak.""",
        minScore = 50,
        expectAsk = false,
        note = "Seize threat + secrecy + 'aaj raat' plus two markers (video call, camera off): the " +
            "no-ask ladder reaches 50..69 and must stop below 70.",
    ),
    FixtureRow(
        id = "D-S28",
        category = "loan_app",
        language = "mixed",
        kind = FixtureKind.SCAM,
        text = """आपका लोन अप्रूव हो गया है, लेकिन आपका account block है. प्रोसेसिंग फीस Rs 350 bhejo is UPI par: loanhelp@okhdfcbank, फिर लोन तुरंत मिलेगा.""",
        minScore = 70,
        expectAsk = true,
        note = "Devanagari loan notice with a Roman fee ask: block threat + 'तुरंत' corroborate the " +
            "money transfer to a UPI handle.",
    ),

    // -----------------------------------------------------------------------------------------
    // legit_control — the legitimate twin of each family. Must stay at or under 24 (LOW_RISK).
    // -----------------------------------------------------------------------------------------

    FixtureRow(
        id = "D-S29",
        category = "legit_control",
        language = "hinglish",
        kind = FixtureKind.CONTROL,
        text = """BESCOM: Aapka bijli bill Rs 1,240 due on 12 Nov 2026 hai. Payment net banking ya official app se karein. Bill copy bescom.co.in par available hai.""",
        maxScore = 24,
        expectAsk = false,
        note = "Genuine bill: a due date, an official channel and no loss verb. The utility+harm " +
            "pair must not fire, so nothing is claimed.",
    ),
    FixtureRow(
        id = "D-S30",
        category = "legit_control",
        language = "hinglish",
        kind = FixtureKind.CONTROL,
        text = """Delhivery: your parcel (AWB 4471920) is out for delivery. Delivery agent aaj shaam 5 baje pahunchega. Koi payment nahi karni hai.""",
        maxScore = 24,
        expectAsk = false,
        note = "Real 'out for delivery' notice: parcel noun present, no held/seized/cancelled word, " +
            "and the payment sentence is a negative.",
    ),
    FixtureRow(
        id = "D-S31",
        category = "legit_control",
        language = "english",
        kind = FixtureKind.CONTROL,
        text = """Dear Candidate, we are pleased to offer you the position of Junior Analyst at Nexa Systems. The offer letter is attached. Please confirm your acceptance by 20 Nov 2026. For queries write to careers@nexasystems.in""",
        maxScore = 24,
        expectAsk = false,
        note = "Genuine offer letter: no selection-reward wording, no fee, no link and no personal " +
            "number — the job scam family has nothing to hold on to.",
    ),
    FixtureRow(
        id = "D-S32",
        category = "legit_control",
        language = "english",
        kind = FixtureKind.CONTROL,
        text = """HDFC Bank: EMI of Rs 8,432 for loan account ending 7781 is due on 05 Nov 2026. The amount will be auto-debited from your registered account. Ignore if already paid.""",
        maxScore = 24,
        expectAsk = false,
        note = "Real EMI/auto-debit statement: an amount and a date, but no transfer verb, no " +
            "personal number and no harm word.",
    ),
    FixtureRow(
        id = "D-S33",
        category = "legit_control",
        language = "english",
        kind = FixtureKind.CONTROL,
        text = """HDFC Bank: Rs 1,499 debited from A/c XX4412 for electricity bill payment. Avl bal Rs 8,920. Never share OTP or PIN with anyone.""",
        maxScore = 24,
        expectAsk = false,
        note = "Debit alert carrying the bank's own 'never share OTP' line. The credential-share " +
            "skip must hold: protective wording is not an ask and not isolation pressure.",
    ),
)
