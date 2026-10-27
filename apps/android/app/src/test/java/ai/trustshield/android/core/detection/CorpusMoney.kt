package ai.trustshield.android.core.detection

/**
 * Money-movement scam fixtures (lane A, wave 1, T3).
 *
 * Four families, all of them the moment where value actually leaves the user's account:
 *
 *  * `upi_collect_refund` - collect requests and "refund" pivots. Receiving a UPI payment never
 *    needs a PIN, so "PIN daal ke paisa receive karo" / "enter your UPI PIN to receive" is the
 *    theft step even though the wording sounds like a credit.
 *  * `investment_crypto` - guaranteed-return, Telegram-group, app-deposit, doubling and forex-bot
 *    offers. The reward claim is stage 2; the deposit is stage 5.
 *  * `lottery_kbc` - KBC / lucky-draw "wins" that only release after a processing, courier or GST
 *    "fee".
 *  * `remote_access` - AnyDesk / QuickSupport / TeamViewer / screen-share installs on a bank
 *    "verification" pretext.
 *
 * Plus five `legit_control` rows: the genuine twins the same rules must not accuse (an order
 * shipment notice, a UPI payment-received alert from the user's own app, a mutual-fund statement,
 * a delivery OTP handed to the agent at the door, a real bank credit alert).
 *
 * ## How the floors were set
 *
 * Every `minScore` is the score this row actually produces against the rules in this worktree at
 * authoring time, rounded *down* to the band the arithmetic allows - the numbers were read off a
 * faithful port of `StageRules` + `LocalScorer` (same patterns, same negation guard, same skip
 * windows, same noisy-OR and band clamps), never guessed, and the port was first checked against
 * rows whose scores are already pinned in `LegitScamPairsTest` and `DemoMessages`. Nothing here is
 * an accuracy claim and nothing is asserted about a model.
 *
 * With the default `Sensitivity.STANDARD` a verified ask lands in one of three places, so a floor
 * of 70 versus 35 says something real about the message:
 *
 *  * ask + **two or more** other independent stages -> `>= 70` (`HIGH_RISK`);
 *  * ask + **one** other stage -> clamped to `35..49` (`SUSPICIOUS`);
 *  * ask alone -> clamped to `25..45` (`CAUTION`).
 *
 * A row pinned at `35` therefore means "this is a real ask, but the text carries only one
 * corroboration" (a `bhejo` with a threat and no time pressure, for example). Pinning it at 70
 * would be a wish, not a measurement. A row pinned at `70` carries an ask plus two independent
 * stages, which is the BEHAVIOR_SPEC rule gate for `HIGH_RISK`.
 *
 * No scam row pins `maxScore`: a better rule module may lift a row, and a fixture must not turn
 * red because detection improved. Control rows pin `maxScore = 24`, the documented `LOW_RISK`
 * ceiling - a legitimate message that crosses it is the false alarm this corpus exists to catch.
 *
 * ## Text hygiene
 *
 * Every `text` is a single line with no quotation marks and no apostrophes, so no legitimate
 * "quoted region" can suppress an evidence item and no multi-sentence quoting artefact can appear.
 * Names, UPI handles, amounts and OTP-like numbers are synthetic.
 *
 * ## Known engine property these rows work around
 *
 * `StageRules`' negation guard treats a bare Devanagari "न" as a negation marker, and Devanagari
 * matras are not letters, so an isolated "न" inside an ordinary word (लेने, पाने, अपना, मुनाफा)
 * can swallow a nearby ask. Every Devanagari row here is written so the ask ends its clause and no
 * word with a standalone "न" sits between the clause start and the ask; where an ask follows an
 * amount, the separator inside the amount resets the clause anyway. D-M06 is the row that shows the
 * raw failure: an earlier draft opened with
 * "पैसे लेने के लिए QR code scan karo" and the "न" in लेने negated the ask outright, dropping the
 * whole row to 24 with no ask at all. That is a fixture constraint, not a fix, and the underlying
 * over-negation is reported to the lane lead rather than hidden here.
 */
internal val CORPUS_MONEY: List<FixtureRow> = listOf(

    // ---------------------------------------------------------------------------------------
    // UPI collect / refund
    // ---------------------------------------------------------------------------------------

    FixtureRow(
        id = "D-M01",
        category = "upi_collect_refund",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = "Aapko Rahul Sharma se Rs 4,999 ka UPI collect request aaya hai. Accept karo aur " +
            "PIN daal ke paisa receive karo. Aapki UPI request 30 minute mein expire ho jayegi.",
        minScore = 70,
        expectAsk = true,
        note = "Collect request dressed as an incoming payment: 'receive' never needs a PIN. Ask " +
            "(PIN daal) + threat (request expire) + urgency (30 minute) = 70.",
    ),
    FixtureRow(
        id = "D-M02",
        category = "upi_collect_refund",
        language = "english",
        kind = FixtureKind.SCAM,
        text = "You have a UPI collect request of \u20b94,999 from Rahul Sharma. Approve it and " +
            "enter your UPI PIN to receive the money. Your UPI request will expire in 15 minutes.",
        minScore = 70,
        expectAsk = true,
        note = "English twin of D-M01. The PIN entry is the ask; the expiry line is the threat.",
    ),
    FixtureRow(
        id = "D-M03",
        category = "upi_collect_refund",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = "Sir, galti se aapke account mein Rs 5,000 bhej diye hain. Please paisa wapas bhej " +
            "do warna main account block karwa dunga.",
        minScore = 35,
        expectAsk = true,
        note = "The 'galti se bheje, wapas bhejo' pivot with a threat and no deadline: ask + one " +
            "stage, so the honest floor is 35, not 70.",
    ),
    FixtureRow(
        id = "D-M04",
        category = "upi_collect_refund",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = "Payment ho gaya hai, screenshot check karo. Galti se Rs 2,000 zyada bhej diye, " +
            "abhi wapas bhej do warna account block karwa dunga.",
        minScore = 70,
        expectAsk = true,
        note = "Fake 'payment ho gaya, check karo' screenshot followed by the overpayment refund " +
            "ask. Ask + threat + urgency (abhi) = 70.",
    ),
    FixtureRow(
        id = "D-M05",
        category = "upi_collect_refund",
        language = "english",
        kind = FixtureKind.SCAM,
        text = "We have transferred \u20b92,000 extra to your account by mistake. Please send the " +
            "amount back to UPI ID refund@okaxis. If not returned in 1 hour, your UPI will be " +
            "blocked.",
        minScore = 70,
        expectAsk = true,
        note = "Refund-of-overpayment with a lookalike handle and a one-hour block threat. No real " +
            "bank refunds money by asking the customer to send it to a UPI ID.",
    ),
    FixtureRow(
        id = "D-M06",
        category = "upi_collect_refund",
        language = "mixed",
        kind = FixtureKind.SCAM,
        text = "QR code scan karo aur UPI PIN \u0921\u093e\u0932\u094b. Aapki UPI request 10 " +
            "\u092e\u093f\u0928\u091f \u092e\u0947\u0902 expire \u0939\u094b " +
            "\u091c\u093e\u090f\u0917\u0940, paise turant milenge.",
        minScore = 70,
        expectAsk = true,
        note = "Scan-to-receive: a QR only moves money *out*. Ask (QR scan) + threat (request " +
            "expire) + urgency (10 minute / turant) = 70.",
    ),
    FixtureRow(
        id = "D-M07",
        category = "upi_collect_refund",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = "UPI request expire ho jayegi, 10 minute bache hain. Jaldi accept karo aur PIN daal " +
            "do.",
        minScore = 70,
        expectAsk = true,
        note = "Short 'UPI request expire ho jayegi' countdown with a PIN ask in the same breath.",
    ),
    FixtureRow(
        id = "D-M08",
        category = "upi_collect_refund",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = "Aapka Rs 3,000 cashback pending hai. Is QR ko scan karo aur PIN daal do, cashback " +
            "turant milega.",
        minScore = 70,
        expectAsk = true,
        note = "Pending-cashback lure released by a QR scan plus a PIN. Carries both a money ask " +
            "(scan) and a credential ask (PIN).",
    ),

    // ---------------------------------------------------------------------------------------
    // Investment / crypto
    // ---------------------------------------------------------------------------------------

    FixtureRow(
        id = "D-M09",
        category = "investment_crypto",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = "Hamari VIP Telegram trading group join karo. 30% monthly return guaranteed hai. " +
            "Sirf 10 seats bachi hain, abhi Rs 5,000 is app par deposit karo.",
        minScore = 70,
        expectAsk = true,
        note = "The '30% monthly guaranteed returns' shape. Reward claim + deposit ask + abhi.",
    ),
    FixtureRow(
        id = "D-M10",
        category = "investment_crypto",
        language = "english",
        kind = FixtureKind.SCAM,
        text = "Join our VIP Telegram trading group for 30% monthly returns with zero risk. Pay " +
            "the joining fee of \u20b95,000 to our UPI ID today. Offer closes in 24 hours.",
        minScore = 70,
        expectAsk = true,
        note = "Zero-risk 30% claim plus a joining fee to a UPI ID - a fee to be *paid* is the tell, " +
            "a real fund collects from your own bank account.",
    ),
    FixtureRow(
        id = "D-M11",
        category = "investment_crypto",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = "Sirf Rs 5000 is app par deposit karo aur 30% monthly guaranteed return kamao. " +
            "Telegram group join karo, seats limited hain, aaj hi join karo.",
        minScore = 70,
        expectAsk = true,
        note = "'Rs 5000 is app par deposit karo' - money moved into an app the reader cannot " +
            "withdraw from, plus a closing-day nudge.",
    ),
    FixtureRow(
        id = "D-M12",
        category = "investment_crypto",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = "Paisa double karne ka mauka: 200% return guaranteed, sirf 10 minute mein. " +
            "\u20b95,000 is wallet par bhejo aur turant \u20b910,000 wapas pao.",
        minScore = 70,
        expectAsk = true,
        note = "Crypto-doubling shape: send to a wallet, get double back in ten minutes.",
    ),
    FixtureRow(
        id = "D-M13",
        category = "investment_crypto",
        language = "english",
        kind = FixtureKind.SCAM,
        text = "Our AI forex bot gives 12% daily profit. Pay the activation fee of \u20b93,000 to " +
            "start. Offer closes in 6 hours.",
        minScore = 70,
        expectAsk = true,
        note = "Forex-bot '12% daily' with an activation fee - a guaranteed daily return does not " +
            "exist, and the fee is the first loss.",
    ),
    FixtureRow(
        id = "D-M14",
        category = "investment_crypto",
        language = "devanagari",
        kind = FixtureKind.SCAM,
        text = "\u0939\u0930 \u092e\u0939\u0940\u0928\u0947 30% \u092e\u0941\u0928\u093e\u092b\u093e " +
            "\u0917\u093e\u0930\u0902\u091f\u0940\u0964 \u091f\u0947\u0932\u0940\u0917\u094d\u0930\u093e\u092e " +
            "\u0917\u094d\u0930\u0941\u092a \u091c\u0949\u0907\u0928 \u0915\u0930\u094b \u0914\u0930 5,000 " +
            "\u0930\u0941\u092a\u092f\u0947 \u091c\u092e\u093e \u0915\u0930\u094b\u0964 \u0906\u091c " +
            "\u0939\u0940 \u0906\u0916\u093f\u0930\u0940 \u092e\u094c\u0915\u093e, \u0938\u0940\u091f\u0947\u0902 " +
            "\u0938\u0940\u092e\u093f\u0924 \u0939\u0948\u0902\u0964",
        minScore = 35,
        expectAsk = true,
        note = "Devanagari guaranteed-return offer with a jama karo ask. The 30% claim is written in " +
            "Devanagari, so only the ask and 'aaj hi' fire today: ask + one stage = 35.",
    ),

    // ---------------------------------------------------------------------------------------
    // Lottery / KBC
    // ---------------------------------------------------------------------------------------

    FixtureRow(
        id = "D-M15",
        category = "lottery_kbc",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = "Congratulations! Aap KBC 25 lakh ke winner hain. Prize claim karne ke liye " +
            "processing fee Rs 6,500 is account mein jama karo, aaj hi.",
        minScore = 70,
        expectAsk = true,
        note = "KBC 25-lakh winner with a processing fee. No lottery in India collects a fee from " +
            "the winner, and never by transfer.",
    ),
    FixtureRow(
        id = "D-M16",
        category = "lottery_kbc",
        language = "english",
        kind = FixtureKind.SCAM,
        text = "Congratulations, your number has won the KBC lucky draw of \u20b925,00,000. To " +
            "release the prize, pay a processing fee of \u20b96,500 within 24 hours.",
        minScore = 70,
        expectAsk = true,
        note = "Lucky-draw twin of D-M15 with a 24-hour clock.",
    ),
    FixtureRow(
        id = "D-M17",
        category = "lottery_kbc",
        language = "devanagari",
        kind = FixtureKind.SCAM,
        text = "\u092c\u0927\u093e\u0908 \u0939\u094b! \u0906\u092a\u0915\u093e \u0928\u0902\u092c\u0930 " +
            "\u0932\u0915\u0940 \u0921\u094d\u0930\u0949 \u092e\u0947\u0902 25 \u0932\u093e\u0916 \u0915\u093e " +
            "\u0907\u0928\u093e\u092e \u091c\u0940\u0924\u093e \u0939\u0948\u0964 \u0907\u0928\u093e\u092e " +
            "\u092a\u093e\u0928\u0947 \u0915\u0947 \u0932\u093f\u090f 6,500 \u0930\u0941\u092a\u092f\u0947 " +
            "\u092d\u0947\u091c\u094b\u0964",
        minScore = 35,
        expectAsk = true,
        note = "Devanagari lucky-draw win with a bhejo ask and no deadline: ask + reward = 35.",
    ),
    FixtureRow(
        id = "D-M18",
        category = "lottery_kbc",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = "Aapka mobile number KBC lucky draw 2026 mein 9 lakh ka inaam jeeta hai. Inaam lene " +
            "ke liye 4,900 ka processing charge is UPI par bhejo, 2 ghante mein expire ho jayega.",
        minScore = 70,
        expectAsk = true,
        note = "KBC 2026 variant with a processing charge and a two-hour expiry. Reward + ask + " +
            "urgency + threat.",
    ),
    FixtureRow(
        id = "D-M19",
        category = "lottery_kbc",
        language = "english",
        kind = FixtureKind.SCAM,
        text = "You have won \u20b99,00,000 in the Amazon Lucky Draw. Pay courier and GST charges " +
            "of \u20b94,999 to receive it. Claim within 12 hours.",
        minScore = 70,
        expectAsk = true,
        note = "Retail-brand lucky draw using 'courier and GST charges' instead of the word fee - " +
            "the shape the report flags: a prize that only releases after a courier or tax charge.",
    ),

    // ---------------------------------------------------------------------------------------
    // Remote access
    // ---------------------------------------------------------------------------------------

    FixtureRow(
        id = "D-M20",
        category = "remote_access",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = "Main bank verification officer bol raha hoon. Aapka account 2 ghante mein band ho " +
            "jayega, isliye AnyDesk install karo aur screen share karo.",
        minScore = 70,
        expectAsk = true,
        note = "Officer claim + account-block threat + AnyDesk install + screen share. A real bank " +
            "never asks for remote access.",
    ),
    FixtureRow(
        id = "D-M21",
        category = "remote_access",
        language = "english",
        kind = FixtureKind.SCAM,
        text = "This is the HDFC verification department. Install QuickSupport and share your " +
            "screen with our agent. Your account will be suspended in 30 minutes if KYC is not " +
            "completed.",
        minScore = 70,
        expectAsk = true,
        note = "QuickSupport install on a KYC pretext with a 30-minute suspension threat.",
    ),
    FixtureRow(
        id = "D-M22",
        category = "remote_access",
        language = "devanagari",
        kind = FixtureKind.SCAM,
        text = "\u092c\u0948\u0902\u0915 \u0935\u0947\u0930\u093f\u092b\u093f\u0915\u0947\u0936\u0928 " +
            "\u0915\u0947 \u0932\u093f\u090f QuickSupport download karo aur screen share karo. " +
            "\u0906\u092a\u0915\u093e \u0916\u093e\u0924\u093e \u0924\u0941\u0930\u0902\u0924 " +
            "\u092c\u0902\u0926 \u0939\u094b \u091c\u093e\u090f\u0917\u093e\u0964",
        minScore = 70,
        expectAsk = true,
        note = "Devanagari screen-share request with a khata band hone ka threat. Remote-access ask " +
            "+ threat + urgency.",
    ),
    FixtureRow(
        id = "D-M23",
        category = "remote_access",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = "Sir, aapke account mein suspicious transaction aaya hai. Turant screen share karo " +
            "bank verification ke liye, warna account block ho jayega.",
        minScore = 70,
        expectAsk = true,
        note = "'Screen share karo bank verification ke liye' with a block threat - the exact " +
            "pretext Anydesk frauds open with.",
    ),
    FixtureRow(
        id = "D-M24",
        category = "remote_access",
        language = "english",
        kind = FixtureKind.SCAM,
        text = "Your cashback of \u20b91,500 is stuck. Install AnyDesk QuickSupport and share the " +
            "ID with me, otherwise the cashback will be cancelled forever.",
        minScore = 35,
        expectAsk = true,
        note = "Cashback-fix pretext for a remote install. Ask + reward only, so 35 is the honest " +
            "floor: nothing threatens the account and no deadline is given.",
    ),

    // ---------------------------------------------------------------------------------------
    // Controls - the legitimate twins. These must never be accused.
    // ---------------------------------------------------------------------------------------

    FixtureRow(
        id = "D-M25",
        category = "legit_control",
        language = "english",
        kind = FixtureKind.CONTROL,
        text = "Your Amazon order 402-1234567-1234567 has been shipped and will be delivered on " +
            "28 Oct between 10 am and 6 pm. Track it in the Amazon app.",
        maxScore = 24,
        expectAsk = false,
        note = "Ordinary order/shipment notice. 'Shipped' and 'delivered' must not be read as a " +
            "customs hold and the tracking line must not become an ask.",
    ),
    FixtureRow(
        id = "D-M26",
        category = "legit_control",
        language = "english",
        kind = FixtureKind.CONTROL,
        text = "Payment received: \u20b91,500 from Priya Sharma. UPI ref 402312345678. Money " +
            "credited to your account. Not you? Contact your bank.",
        maxScore = 24,
        expectAsk = false,
        note = "The user's own UPI app confirming a credit. 'Money credited' plus a support line is " +
            "not a refund request; the credit-only notice is the documented legitimate class.",
    ),
    FixtureRow(
        id = "D-M27",
        category = "legit_control",
        language = "english",
        kind = FixtureKind.CONTROL,
        text = "Your SIP of \u20b95,000 in Axis Bluechip Fund was processed on 01 Oct 2026. Units " +
            "allotted 12.345 at NAV \u20b9405.62. This is a system generated statement.",
        maxScore = 24,
        expectAsk = false,
        note = "Real SIP statement: an amount, a fund and a NAV. A guaranteed-returns rule must not " +
            "fire on an actual allotment.",
    ),
    FixtureRow(
        id = "D-M28",
        category = "legit_control",
        language = "english",
        kind = FixtureKind.CONTROL,
        text = "Your parcel will arrive today. Share the delivery OTP with the agent at your door. " +
            "Do not share it with anyone else.",
        maxScore = 24,
        expectAsk = false,
        note = "Delivery OTP handed to the agent at the door - correct behaviour per report 3.4 " +
            "row 5. The in-person destination must keep swallowing the OTP ask.",
    ),
    FixtureRow(
        id = "D-M29",
        category = "legit_control",
        language = "english",
        kind = FixtureKind.CONTROL,
        text = "HDFC Bank: Rs 5,000 credited to your A/c XX1234 on 27-10-26. Avl Bal Rs 45,210. " +
            "Never share OTP or PIN with anyone.",
        maxScore = 24,
        expectAsk = false,
        note = "Real credit alert with the bank's own protective line. 'Never share OTP' is advice, " +
            "never an ask, and must not be turned into one.",
    ),
)
