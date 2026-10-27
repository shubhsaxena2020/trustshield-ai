package ai.trustshield.android.core.rules

import ai.trustshield.android.core.model.Severity
import ai.trustshield.android.core.model.Strength

/**
 * UPI **collect request**, **PIN-to-receive**, **refund reversal** and **fake payment proof** rules:
 * the stage-5 asks of the `upi_collect_refund` and `otp_sharing` fixture families, plus the one
 * threat and one urgency rung those scams lean on (report §3.1 stage 5, §3.4 row 2).
 *
 * ## Why this file exists
 *
 * `StageRules.kt` already knows that "paise bhejo" is a money ask and that "PIN daalo" is a
 * credential ask. It does **not** know the four shapes that make UPI fraud work, because in each of
 * them the words alone look ordinary:
 *
 *  1. **A collect request is the theft step, not a payment.** A UPI *collect* request debits the
 *     reader's account the moment they approve it and enter their PIN; nothing is being received.
 *     So "UPI request bheji hai accept karo", "collect request approve karo", "request pending hai
 *     accept kar do", "payment request accept karein" and the pressure form "collect request decline
 *     mat karo" are all money asks with [AskType.MONEY_TRANSFER].
 *  2. **A PIN is never needed to *receive* money.** "Paise receive karne ke liye PIN daalo",
 *     "amount receive karne ke liye apna UPI PIN enter karo", "refund lene ke liye PIN daal do" are
 *     the exact inversion that empties an account, so they are [AskType.OTP_PIN] asks even though the
 *     sentence frames them as a credit.
 *  3. **"I sent it by mistake, send it back."** "Galti se Rs 5000 bhej diye wapas bhejo", "galat
 *     account mein paise chale gaye, return karo", "extra paisa credit hua wapas kar do" -
 *     [AskType.MONEY_TRANSFER]. The sender's own claim of a wrong transfer is not evidence that any
 *     money moved; only the reader's bank statement is.
 *  4. **A screenshot is not a settlement.** "Payment ho gaya screenshot dekh lo, ab bhej do", "UPI se
 *     bhej diya hai, aap bhi bhej do" - the proof is the sender's own picture, and the ask is a real
 *     transfer ([AskType.MONEY_TRANSFER]). BEHAVIOR_SPEC "QR and payment semantics" says the same
 *     thing in one line: no screenshot proves settlement.
 *
 * The threat rung ([UPI_THREAT_RULES]) and the urgency rung ([UPI_URGENCY_RULES]) exist because these
 * scams sell the same lie in two directions - "request expire ho jayegi to paisa kat jayega" (loss
 * from *not* acting) and "5 minute mein accept karo warna request cancel" (a clock). Both stay
 * `LOW`/`WEAK` like every other stage-2/stage-3 rule: a threat or a clock is never an accusation on
 * its own (BEHAVIOR_SPEC "Rule gates").
 *
 * [UPI_AUTHORITY_RULES] and [UPI_SECRECY_RULES] are empty on purpose. A collect-request scam needs no
 * claimed identity and no isolation; inventing an authority or secrecy rule here would only add
 * evidence a real message could trip.
 *
 * ## Guards (the false-alarm side matters as much as the catch)
 *
 *  * `guarded = true` on every ask except the one rule documented below: "accept mat karo",
 *    "decline karo", "is request ko accept na karein", "paise mat bhejo", "wapas mat bhejo" and
 *    "PIN mat daalo" are protective advice, and the engine's same-clause negation guard suppresses
 *    them (BEHAVIOR_SPEC "Negation and legitimate pairs"). "nahi to"/"warna" after a verb is a
 *    conditional threat, not a negation, and the guard already handles that distinction.
 *  * `skip` preconditions are narrow: an awareness/report frame ("1930", "cyber cell", "bank ko
 *    report karein", "se bachein") never becomes an ask, and a "PIN ki zaroorat nahi hoti" style
 *    protective clause suppresses the PIN trap.
 *  * `requires` preconditions gate the rules whose phrase alone is ambiguous: a bare "decline mat
 *    karo" only counts inside a UPI/payment frame, and a refund-reversal idiom only counts when the
 *    message also names money.
 *  * No rule here matches a bare "accept", a bare "PIN", a bare "screenshot" or a bare "request":
 *    every pattern needs the pair (request **and** approve-verb **and** imperative, or receive-frame
 *    **and** PIN, or mistake-frame **and** send-back verb **and** imperative). A routine "your
 *    request has been accepted" carries no imperative and stays unmatched.
 *  * Nothing here is an official-channel skip of the "the sender mentioned a branch" kind: naming a
 *    bank or an app is cheap for a scammer (the reasoning is spelled out in `UtilityServiceRules`).
 *
 * ## Known limits (stated, not hidden)
 *
 *  * The PIN trap needs the *receive* framing. A plain "apna PIN daal do" is already covered by the
 *    OTP/PIN rule in `StageRules`, and is deliberately not duplicated here.
 *  * A collect-request scam that never names the request ("bas yeh QR scan karo") is left to the
 *    existing QR/scan ask; this file does not widen that rule.
 *  * A fake proof that carries no claim ("screenshot bhej raha hoon") is not an ask and must not
 *    become one; only the claim **plus** a send imperative fires.
 *
 * Deterministic and pure: no clock, no randomness, no I/O, no `android.*`, no new dependency. Every
 * quote still comes from the normalizer's raw-span map through [StageRules], so nothing here can
 * invent a quote that is not a normalised substring of the analysed text.
 */

// ---------------------------------------------------------------------------------------------
// Shared vocabulary
// ---------------------------------------------------------------------------------------------

/**
 * The noun a collect request is called by. `request`/`req` are in the list because the shortest real
 * form is "request pending hai accept kar do"; every pattern still demands an approve-verb **and** an
 * imperative next to it, so a bare "request" can never fire on its own.
 */
private const val UPI_COLLECT_NOUN =
    "(?:collect ?request|collect ?req|upi ?request|payment ?request|pay ?request|" +
        "money ?request|refund ?request|request|req)"

/** The approve family. `decline`/`reject`/`cancel` are deliberately absent - they are the opposite. */
private const val UPI_ACCEPT_VERB = "(?:accept|approve|allow|authori[sz]e|confirm|swipe|tap)"

/**
 * The imperative tail that makes an approve-verb a *request to the reader*. "accept ho gaya" /
 * "accept kar diya gaya" (a completed, passive state) matches none of these, which is what keeps a
 * routine "your request has been accepted" out of stage 5.
 */
private const val UPI_IMPERATIVE =
    "(?:karo|karein|kar do|kar dena|kar dein|kar le|kar lo|kar lena|kijiye|kijiyega|" +
        "karna hai|karna hoga|please|now|turant|jaldi|abhi)"

/** Verbs that move money towards the sender. Devanagari twins are in `DevanagariRules`. */
private const val UPI_MONEY_IMPERATIVE =
    "(?:bhejo|bhej do|bhej dena|bhej dein|karo|kar do|kar dena|kar dein|kijiye|send|transfer|" +
        "credit|daal do|de do|dedo|jama karo)"

/** The "this transfer was a mistake / not mine" frame that precedes a refund-reversal demand. */
private const val UPI_MISTAKE_FRAME =
    "(?:galti se|galti se hi|by mistake|mistakenly|accidentally|galat account|galat number|" +
        "galat upi|galat upi id|wrong account|wrong number|wrong upi|extra paisa|extra paise|" +
        "extra amount|double payment|duplicate payment|do baar kat|do baar cut)"

/** Words that ask for the value back. A completed "wapas mil gaya" is not in this list. */
private const val UPI_SEND_BACK =
    "(?:wapas|vapas|wapis|return|refund|send back|transfer back|reverse|revert)"

/**
 * A UPI / payment frame. Whole-text precondition for the rules whose phrase alone is ambiguous, so a
 * private message that says "isse decline mat karo" about an invitation never becomes a money ask.
 */
private val UPI_FRAME: Regex = rx(
    """(?:₹|\b(?:upi|gpay|google pay|phonepe|paytm|bhim|collect|payment|request|qr|scan|""" +
        """transfer|refund|vpa|beneficiary|paise|paisa|amount|rs|inr)\b)""",
)

/** Money actually named. Whole-text precondition for the refund-reversal rule. */
private val UPI_MONEY_CUE: Regex = rx(
    """(?:₹|\b(?:rs|inr|paise|paisa|amount|money|payment|transfer|credit|refund|upi|neft|imps)\b|""" +
        """\p{Nd}{2,})""",
)

/**
 * Awareness / report framing. A message that tells the reader to report the request, or that names
 * the cyber helpline, is warning *about* the scam rather than pushing the reader to approve it.
 */
private val SKIP_UPI_AWARENESS: Regex = rx(
    """(?:1930|cyber ?(?:cell|crime)|bank ko (?:report|inform|batayein|batana)|""" +
        """report kar (?:dein|do|dena)|se bachein|se bachaye|savdhan|सावधान|धोखाधड़ी|ठगी|""" +
        """fraud hai|scam hai|dhokha hai)""",
)

/**
 * The protective half of the PIN trap: the sentence says a PIN is *not* needed, or warns against
 * entering one. "Refund lene ke liye PIN ki zaroorat nahi hoti" is the bank's own line and must never
 * become the ask it warns about.
 */
private val SKIP_PIN_PROTECTIVE: Regex = rx(
    """(?:zaroorat nahi|jarurat nahi|nahi maangta|never (?:ask|need)|do not (?:enter|share)|""" +
        """don'?t (?:enter|share)|mat daal\w*|na daalein|की जरूरत नहीं|नहीं मांगता|मत डालो)""",
)

// ---------------------------------------------------------------------------------------------
// Stage 1 / stage 4 - not touched by this module.
// ---------------------------------------------------------------------------------------------

/** A collect-request scam claims no identity; stage 1 stays with `StageRules`. */
internal val UPI_AUTHORITY_RULES: List<Rule> = emptyList()

/** A collect-request scam needs no isolation; stage 4 stays with `StageRules`. */
internal val UPI_SECRECY_RULES: List<Rule> = emptyList()

// ---------------------------------------------------------------------------------------------
// Stage 2 - the "request will expire and you lose money" threat. Alone: LOW/WEAK.
// ---------------------------------------------------------------------------------------------

internal val UPI_THREAT_RULES: List<Rule> = listOf(
    Rule(
        titleHinglish = "Request expire hone par paisa katne ki dhamki",
        detail = "Message keh raha hai ki collect/payment request expire hone par paisa kat " +
            "jayega. UPI mein request expire hone se paisa nahi katta - yeh darr (stage 2) " +
            "hai, akela CRITICAL nahi banta.",
        severity = Severity.LOW,
        strength = Strength.WEAK,
        patterns = listOf(
            // "request expire ho jayegi to paisa kat jayega"
            rx(
                """\b(?:collect ?request|upi ?request|payment ?request|request|req)\b""" +
                    """[^.!?\n]{0,40}?\b(?:expire|expiry|cancel|reject|decline|timeout|time out)\w*\b""" +
                    """[^.!?\n]{0,40}?\b(?:paise|paisa|amount|money|rakam)\b""" +
                    """[^.!?\n]{0,24}?\b(?:kat|cut|deduct|nikal|debit|ja(?:yega|yegi|ega|egi))\w*\b""",
            ),
            // "accept nahi kiya to paisa kat jayega" - the loss is conditioned on NOT approving
            rx(
                """\b(?:accept|approve|confirm)\s+(?:nahi|na|mat)\s+(?:kiya|kiye|karne|karen)\b""" +
                    """[^.!?\n]{0,40}?\b(?:paise|paisa|amount|money|rakam)\b""" +
                    """[^.!?\n]{0,24}?\b(?:kat|cut|deduct|nikal|debit|ja(?:yega|yegi))\w*\b""",
            ),
            // "paise kat jayenge, request accept kar lo" (loss stated first)
            rx(
                """\b(?:paise|paisa|amount|money|rakam)\b[^.!?\n]{0,20}?\b(?:kat|cut|deduct)\w*\b""" +
                    """[^.!?\n]{0,30}?\b(?:accept|approve|request)\b""",
            ),
        ),
    ),
)

// ---------------------------------------------------------------------------------------------
// Stage 3 - the accept-by clock. Alone: LOW/WEAK.
// ---------------------------------------------------------------------------------------------

internal val UPI_URGENCY_RULES: List<Rule> = listOf(
    Rule(
        titleHinglish = "Request accept karne ki jaldbaazi",
        detail = "Message accept/approve karne ke liye ek chhoti si deadline ya 'warna request " +
            "cancel' ka dabaav banata hai. Akele urgency scam ka saboot nahi hai; yeh sirf " +
            "jaldbaazi ka signal hai.",
        severity = Severity.LOW,
        strength = Strength.WEAK,
        patterns = listOf(
            // "5 minute mein accept karo warna request cancel"
            rx(
                """\b(?:accept|approve|confirm|pay|bhejo|transfer)\w*\b[^.!?\n]{0,30}?""" +
                    """\b(?:warna|varna|nahi to|nahin to|na to)\b[^.!?\n]{0,30}?""" +
                    """\b(?:cancel|expire|reject|decline|kat|band|block|freeze|suspend|loss)\w*\b""",
            ),
            // "request cancel ho jayegi agar aap accept nahi karenge"
            rx(
                """\b(?:cancel|expire|reject|decline)\w*\b[^.!?\n]{0,24}?""" +
                    """\b(?:ho jayegi|ho jayega|ho jaega|ho jayengi)\b[^.!?\n]{0,20}?\b(?:agar|if)\b""" +
                    """[^.!?\n]{0,24}?\b(?:accept|approve|confirm)\s+(?:nahi|na|mat)\b""",
            ),
            // English twin
            rx(
                """\bif you (?:do not|don'?t) (?:accept|approve|pay|confirm)\b[^.!?\n]{0,30}?""" +
                    """\b(?:will be|will get|gets?)\b[^.!?\n]{0,20}?""" +
                    """\b(?:cancelled|canceled|reversed|deducted|expired)\b""",
            ),
        ),
    ),
)

// ---------------------------------------------------------------------------------------------
// Stage 5 - the asks this file adds. STRONG + directional, each one negation-guarded.
// ---------------------------------------------------------------------------------------------

internal val UPI_ASK_RULES: List<Rule> = listOf(
    Rule(
        titleHinglish = "UPI collect request accept karne ko kaha",
        detail = "Message UPI collect/payment request approve karne ko keh raha hai. Collect " +
            "request approve karne par paisa aapke account se KAT ta hai, aata nahi - isliye " +
            "anjaan request kabhi accept nahi karni chahiye.",
        severity = Severity.HIGH,
        strength = Strength.STRONG,
        patterns = listOf(
            // "upi request bheji hai accept karo" / "request pending hai accept kar do"
            rx(
                """\b$UPI_COLLECT_NOUN\b[^.!?\n]{0,34}?\b$UPI_ACCEPT_VERB\w*\b""" +
                    """[^.!?\n]{0,18}?\b$UPI_IMPERATIVE\b""",
            ),
            // "approve karo is request ko"
            rx(
                """\b$UPI_ACCEPT_VERB\w*\b[^.!?\n]{0,22}?\b$UPI_COLLECT_NOUN\b""" +
                    """[^.!?\n]{0,16}?\b$UPI_IMPERATIVE\b""",
            ),
            // "accept the collect request"
            rx("""\b(?:accept|approve)\s+(?:the|this|my)\s+(?:collect\s+|payment\s+|upi\s+)?request\b"""),
        ),
        askType = AskType.MONEY_TRANSFER,
        guarded = true,
        skip = SKIP_UPI_AWARENESS,
        requires = UPI_FRAME,
    ),
    Rule(
        titleHinglish = "Collect request decline karne se roka",
        detail = "Message keh raha hai ki request decline/reject MAT karo - yaani accept karne " +
            "par majboor kar raha hai. Koi bhi asli bank ya app 'decline mat karo' nahi kehta; " +
            "decline karna hi aapki suraksha hai.",
        severity = Severity.HIGH,
        strength = Strength.STRONG,
        patterns = listOf(
            // "collect request decline mat karo"
            rx(
                """\b(?:decline|reject|cancel|deny|ignore)\s+(?:mat|na)\s+""" +
                    """(?:karo|karein|karna|kijiye|kar do|kar dena|kar dein)\b""",
            ),
            // "decline ... mat karo" with a word or two in between
            rx(
                """\b(?:decline|reject|cancel)\b[^.!?\n]{0,12}?\b(?:mat|na)\b""" +
                    """[^.!?\n]{0,10}?\b(?:karo|karein|karna|kijiye|kar do)\b""",
            ),
            // "do not decline this request"
            rx(
                """\b(?:do not|don'?t|never)\s+(?:decline|reject|cancel)\b[^.!?\n]{0,24}?""" +
                    """\b(?:request|collect|payment)\b""",
            ),
            // "request ko decline mat karein"
            rx("""\b$UPI_COLLECT_NOUN\b[^.!?\n]{0,20}?\b(?:decline|reject|cancel)\s+(?:mat|na)\b"""),
        ),
        askType = AskType.MONEY_TRANSFER,
        // NOT guarded, and that is deliberate: the scam idiom *is* a negation ("decline MAT karo"),
        // so the engine's same-clause negation guard would suppress the exact phrase this rule
        // exists for. Safety comes from the pattern instead - it can only match when "decline" and
        // "mat/na" are adjacent, so the protective forms ("decline karo", "accept mat karo") cannot
        // reach it. See the file KDoc.
        guarded = false,
        requires = UPI_FRAME,
    ),
    Rule(
        titleHinglish = "Paisa receive karne ke liye PIN maanga",
        detail = "Message keh raha hai ki paisa RECEIVE karne ya refund lene ke liye PIN daalna " +
            "hoga. UPI PIN sirf paisa bhejne par lagta hai - receive karne ke liye kabhi nahi. " +
            "Yeh PIN chori ka sabse seedha tareeka hai.",
        severity = Severity.HIGH,
        strength = Strength.STRONG,
        patterns = listOf(
            // "paise receive karne ke liye PIN daalo" / "amount receive karne ke liye UPI PIN enter karo"
            rx(
                """\b(?:paise|paisa|amount|payment|refund|rakam|money|credit)\b[^.!?\n]{0,34}?""" +
                    """\b(?:receive|recieve|get|lene|vasool|credit)\w*\b[^.!?\n]{0,20}?""" +
                    """\b(?:ke liye|keliye|liye)\b[^.!?\n]{0,26}?\b(?:pin|mpin|upi pin)\b""",
            ),
            // "PIN daal do, refund aa jayega"
            rx(
                """\b(?:pin|mpin|upi pin)\b[^.!?\n]{0,26}?""" +
                    """\b(?:daal|daalo|enter|type|put|likho|share)\w*\b[^.!?\n]{0,34}?""" +
                    """\b(?:receive|refund|credit|lene)\b""",
            ),
            // Devanagari twin: "पैसे receive करने के लिए पिन डालो"
            rx(
                """\b(?:पैसे|रुपये|रकम|पेमेंट|रिफंड|अमाउंट)\b[^।.!?\n]{0,30}?""" +
                    """\b(?:receive|प्राप्त|पाने|लेन)\w*\b[^।.!?\n]{0,20}?""" +
                    """\b(?:के लिए|ke liye|liye)\b[^।.!?\n]{0,20}?\b(?:pin|पिन)\b""",
            ),
        ),
        askType = AskType.OTP_PIN,
        guarded = true,
        skip = SKIP_PIN_PROTECTIVE,
    ),
    Rule(
        titleHinglish = "Galti se bheja, wapas bhejo",
        detail = "Message dawa karta hai ki galti se ya galat account mein paisa chala gaya aur " +
            "wapas karne ko keh raha hai. Bhejne wale ka screenshot ya dawa proof nahi hota - " +
            "asli refund bank ke through aata hai, aapse transfer maang kar nahi.",
        severity = Severity.HIGH,
        strength = Strength.STRONG,
        patterns = listOf(
            // "galti se Rs 5000 bhej diye wapas bhejo" / "extra paisa credit hua wapas kar do"
            rx(
                """\b$UPI_MISTAKE_FRAME\b[^.!?\n]{0,60}?\b$UPI_SEND_BACK\b""" +
                    """[^.!?\n]{0,26}?\b$UPI_MONEY_IMPERATIVE\b""",
            ),
            // "I sent it by mistake, send it back"
            rx(
                """\b(?:sent|transferred|credited)\b[^.!?\n]{0,30}?""" +
                    """\b(?:by mistake|wrongly|accidentally)\b[^.!?\n]{0,50}?""" +
                    """\b(?:send|transfer|return|refund|pay)\b[^.!?\n]{0,16}?\bback\b""",
            ),
            // "send it back, I transferred by mistake"
            rx(
                """\b(?:send|transfer|return|refund|pay)\b[^.!?\n]{0,14}?\bback\b[^.!?\n]{0,40}?""" +
                    """\b(?:by mistake|wrongly|accidentally|galti se)\b""",
            ),
            // "wrongly transferred" / "credited to the wrong account"
            rx(
                """\b(?:wrong(?:ly)? (?:transferred|sent|credited)|credited (?:to )?(?:the )?wrong)\b""" +
                    """[^.!?\n]{0,40}?\b(?:return|refund|send back|reverse)\b""",
            ),
        ),
        askType = AskType.MONEY_TRANSFER,
        guarded = true,
        requires = UPI_MONEY_CUE,
    ),
    Rule(
        titleHinglish = "Screenshot dikhakar paise maange",
        detail = "Bhejne wale ka dawa hai ki payment ho gaya aur screenshot dekhne ko keh raha " +
            "hai, phir aapse paise bhejne ko keh raha hai. Screenshot kabhi settlement ka proof " +
            "nahi hota - pakka confirmation sirf aapke bank SMS ya UPI app se aata hai.",
        severity = Severity.HIGH,
        strength = Strength.STRONG,
        patterns = listOf(
            // "payment ho gaya screenshot dekh lo, ab bhej do"
            rx(
                """\b(?:screenshot|screen shot|receipt|slip|payment proof|proof|photo)\b""" +
                    """[^.!?\n]{0,28}?\b(?:dekh|dekho|dekh lo|dekh lena|dekhein|dekh lijiye|check|""" +
                    """verify|confirm)\w*\b[^.!?\n]{0,40}?\b$UPI_MONEY_IMPERATIVE\b""",
            ),
            // "dekh lo screenshot, phir bhej do"
            rx(
                """\b(?:dekh|dekho|check)\w*\b[^.!?\n]{0,20}?""" +
                    """\b(?:screenshot|receipt|slip|proof)\b[^.!?\n]{0,40}?\b$UPI_MONEY_IMPERATIVE\b""",
            ),
            // "UPI se bhej diya hai, aap bhi bhej do" - the second person is what makes it an ask
            rx(
                """\b(?:upi|gpay|google pay|phonepe|paytm|bhim|neft|imps|payment|paise|amount)\b""" +
                    """[^.!?\n]{0,26}?\b(?:bhej diya|bhej diya hai|bhej di|send kar diya|""" +
                    """transfer kar diya|kar diya|kar di|ho gaya|ho gaya hai|complete ho gaya)\b""" +
                    """[^.!?\n]{0,36}?\b(?:aap bhi|tum bhi|you also|you too|ab aap|ab tum)\b""" +
                    """[^.!?\n]{0,20}?\b$UPI_MONEY_IMPERATIVE\b""",
            ),
        ),
        askType = AskType.MONEY_TRANSFER,
        guarded = true,
    ),
)
