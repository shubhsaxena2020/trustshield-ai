package ai.trustshield.android.core.rules

import ai.trustshield.android.core.model.Severity
import ai.trustshield.android.core.model.Strength

/**
 * Utility/service-disconnection **threat** (playbook stage 2) and the "personal number / unofficial
 * link" **asks** (stage 5) from report §3.1, §3.3 text signals and §3.4 row 4 (issue #118, lane D1).
 *
 * ## Why this file exists
 *
 * The senior-dev report's flagship electricity-bill scam -
 * `Dear customer, aapka bijli connection aaj raat 9:30 baje kat jayega. Turant is number par call
 * karein 9876543210` - scored 8/100 LOW_RISK. `ACCOUNT_NOUN` in `StageRules.kt` knows account, KYC,
 * SIM, card, PAN, Aadhaar, wallet and UPI, but no utility or service noun, and no ask rule knew that
 * "call this personal number" is the theft step. Two missing signals, one visible symptom.
 *
 * ## What these rules claim, and what they refuse to claim
 *
 *  1. **Disconnect threat** ([UTILITY_DISCONNECT_RULES], stage 2): a utility/service noun (bijli,
 *     electricity, gas, paani, broadband, landline, DTH, cable, meter, bill, connection) attached to
 *     an imminent-loss verb (kat jayega / kategi / band ho jayega / block ho jayega / suspend ho
 *     jayega / disconnect ho jayega). LOW/WEAK like every other stage-2 rule: a threat alone is
 *     never an accusation (BEHAVIOR_SPEC "Rule gates"). A routine bill with a due date carries no
 *     loss verb, so §3.4 row 4's legitimate twin stays unmatched.
 *  2. **Personal callback ask** (stage 5, STRONG, [AskType.CALLBACK]): an imperative callback verb
 *     aimed at a 10-digit **Indian personal mobile** (6-9 prefix, optional +91) - or at a number the
 *     sender itself calls a "personal/private number" - inside a message that also names a bank or a
 *     utility/service. Report §3.3 "personal number se bank banna - Moderate" plus §3.1 stage 5
 *     ("money/info via phone number"). Direction is what makes it an ask: a bare number, an official
 *     number, or "hum aapko call karenge" never qualifies.
 *  3. **KYC/detail update through an unofficial link** (stage 5, STRONG, [AskType.PERSONAL_INFO]):
 *     a KYC/identity noun, an update/verify/link verb and a shortener or throwaway-TLD link in one
 *     message. §3.3 lists a shortener as Weak-to-Moderate and a brand-subdomain trick as Strong;
 *     the *combination* with the request to submit KYC details is the personal-data ask (§3.4 row 3:
 *     "branch or net banking" is the legitimate twin and carries no link at all).
 *  4. **Personal mobile next to a bank/service name** (MODERATE): the same channel mismatch *without*
 *     an imperative. Corroboration only - fusion counts a stage once and only a **STRONG** ask can
 *     lift a band, so this item alone can never turn a message into an accusation.
 *
 * ## Guards (the false-alarm side matters as much as the catch)
 *
 *  * `guarded = true` on every ask: "is number par call mat karein" and "do not call this number" are
 *    protective, not requests (BEHAVIOR_SPEC "Negation ... scope around the action").
 *  * `skip = SKIP_OFFICIAL_NUMBER`: toll-free / helpline / short-code context (1800, 1860, 1912,
 *    1930) and official-channel advice (branch, net banking, official app). A number a real
 *    institution publishes is not a personal callback.
 *  * `requires = SERVICE_OR_BANK_BRAND`: a personal mobile is only suspicious *as a bank or service
 *    channel*. "Bhai call kar lena 9876543210" stays a personal message.
 *  * The mobile pattern demands 10 digits starting 6-9 with digit lookarounds, so account numbers,
 *    amounts, 5-digit short codes and 11/12-digit landlines cannot match.
 *  * The KYC-link rule needs both an unofficial link *and* an update/verify verb; a bank sending you
 *    to its own app or branch has neither.
 *
 * Deterministic and pure: no clock, no randomness, no I/O, no `android.*`, no new dependency. Every
 * quote still comes from the normalizer's raw-span map through [StageRules], so nothing here can
 * invent a quote that is not a normalised substring of the analysed text.
 */

// ---------------------------------------------------------------------------------------------
// Shared vocabulary
// ---------------------------------------------------------------------------------------------

/** Utility / service nouns a scammer threatens to cut off. Deliberately excludes account/KYC/SIM. */
internal const val UTILITY_SERVICE_NOUN =
    "(?:bijli|बिजली|electricity|power|connection|कनेक्शन|gas|गैस|lpg|water|पानी|paani|" +
        "broadband|internet|wifi|wi-fi|landline|dth|cable ?tv|cable|meter|मीटर|supply|" +
        "service|सेवा|bill)"

/**
 * Imminent-loss forms only: "kat jayega", "kategi", "band ho jayega", "block ho jayega",
 * "suspend ho jayega", "disconnect ho jayega", "cut ho jayega" and their past perfect twins.
 * A bare "band" or "kat" is *not* here on purpose - "wifi band kar do" is an instruction, not a
 * threat, and must not become stage 2.
 */
internal const val SERVICE_DISCONNECT =
    "(?:" +
        // "kaat diya jayega" (issue #147: the owner's own shared fixture) and the plain
        // "kat jayega" family. Accepting both spellings of the vowel, and the optional "diya",
        // covers the compound future ("kaat diya jayega", "kat diya jayega") that the owner's phone
        // fixture used and the earlier rules missed.
        "(?:kat|kaat)(?:a|na|ne)? (?:diya )?(?:ja(?:yega|yegi|yenge|ega|egi|enge)|gaya|gayi)" +
        "|(?:kat|kaat)(?:ega|egi|enge|engi)" +
        "|band (?:ho )?(?:ja(?:yega|yegi|yenge|ega|egi|enge)|gaya|gayi)" +
        "|band kar (?:diya jayega|diya jaega|denge|diya gaya)" +
        "|block ho (?:ja(?:yega|yegi|ega|egi))" +
        "|suspend(?:ed)? (?:ho )?(?:ja(?:yega|yegi|ega|egi))" +
        "|suspend kar (?:diya|denge)" +
        "|disconnect(?:ed)? (?:ho )?(?:ja(?:yega|yegi|ega|egi)|kar diya|karna|kar denge)" +
        "|cut ho (?:ja(?:yega|yegi|ega|egi))" +
        "|(?:कट|काट) (?:दिया )?(?:जाएगा|जाएगी)" +
        "|बंद (?:हो जाएगा|हो जाएगी)|रुक (?:जाएगा|जाएगी)" +
        ")"

/**
 * The **completed**-loss twins of [SERVICE_DISCONNECT]: "kat gaya", "band ho gaya", "suspend ho gaya".
 *
 * Kept separate because a finished outage is also how an ordinary local notice reads ("Power cut ho
 * gaya hai, turant bijli office 9812345678 par call karein"). The threat rule only accepts a
 * completed form when the same sentence carries a reader possessive ("aapka connection kat gaya"), so
 * the loss is about *this* reader's service rather than a locality outage. The disconnection-scam
 * shape ("aapka connection kat gaya hai, reactivate karne ke liye call karein") therefore stays
 * caught, and the outage notice no longer becomes a red accusation.
 */
internal const val SERVICE_DISCONNECT_PAST =
    "(?:" +
        "kat(?:a|na|ne)? (?:gaya|gayi)" +
        "|band (?:ho )?(?:gaya|gayi)" +
        "|block ho (?:gaya|gayi)" +
        "|suspend(?:ed)? (?:ho )?(?:gaya|gayi)" +
        "|disconnect(?:ed)? (?:ho )?(?:gaya|gayi)" +
        "|cut ho (?:gaya|gayi)" +
        "|कट (?:गया|गई)|बंद (?:हो गया|हो गई)" +
        ")"

/** "aapka / apni / your / mera" - the reader's own service, not a locality-wide outage. */
internal const val READER_POSSESSIVE =
    "(?:aapka|aapki|aapke|apna|apni|apne|your|mera|meri|mere)"

/**
 * A 10-digit Indian personal mobile: 6-9 prefix, optional `+91`/`91` country code, never part of a
 * longer digit run (account numbers, 11-digit landlines, amounts) and never a 5-digit short code.
 * A single space is tolerated inside the number (`98765 43210`) because senders write it that way.
 */
internal const val INDIAN_PERSONAL_MOBILE =
    "(?<!\\p{Nd})(?:\\+?91[ -]?)?[6-9](?:\\p{Nd}{9}|\\p{Nd}{4}[ -]\\p{Nd}{5})(?!\\p{Nd})"

/** The command form of a callback verb. "call karenge" (we will call you) is deliberately absent. */
internal const val CALLBACK_IMPERATIVE =
    "(?:call|phone|dial|contact|ring|sampark|संपर्क) " +
        "(?:karein|karo|kar|kijiye|kijiyega|karke|kar do|kar dena|karna)"

/** KYC/identity nouns whose submission over a link is the personal-data ask. */
internal const val KYC_DETAIL_NOUN =
    "(?:kyc|re-?kyc|केवायसी|aadhaar|aadhar|adhaar|आधार|pan card|pan number|card details|" +
        "account details|identity|verification|document)"

/** The update/verify/link verb that turns a KYC notice into a request. */
internal const val KYC_UPDATE_VERB =
    "(?:update|verify|complete|submit|activate|link|click|khol|khole|kholo|bharein|bharna|" +
        "upload|karwa|karwaein)"

/**
 * A shortener or a throwaway-TLD host. A bank's own domain is *not* here, and a bare `https://` link
 * is not proof of anything either, so "update via net banking", an official deep link and an
 * ordinary `https://sbi.co.in/kyc` all stay unmatched. Only the two shapes §3.3 actually calls out -
 * "shortener" and "brand subdomain trick" - can fire.
 */
internal const val UNOFFICIAL_LINK =
    "(?:(?:https?://)?(?:bit\\.ly|tinyurl\\.com|t\\.co|is\\.gd|rb\\.gy|cutt\\.ly|shorturl\\.at|" +
        "rebrand\\.ly|ow\\.ly|tiny\\.cc|wa\\.me|t\\.me|linktr\\.ee)/[\\p{L}\\p{N}_-]{1,40}" +
        "|(?:[a-z0-9][a-z0-9-]{0,40}\\.)+(?:xyz|top|online|site|club|icu|live|shop|link|click|" +
        "buzz|website|store|cfd|sbs|cyou)(?:/[\\p{L}\\p{N}_-]{0,40})?)"

/**
 * Toll-free / short-code context: the numbers a real bank or department publishes.
 *
 * Deliberately *numbers only*. The advice words "branch", "net banking" and "official app" used to be
 * in here (report §3.4 row 3), but naming a branch is cheap for a scammer: "Aapka account block ho
 * jayega. Apni branch mein ya 9876543210 par call karein" was skipped down to LOW because of the word
 * "branch". A personal 10-digit mobile is never rescued by the sender mentioning a branch.
 */
internal val SKIP_OFFICIAL_NUMBER: Regex = rx(
    """(?:toll ?free|helpline|short ?code|\b(?:1800|1860|1912|1930|1947|14416)\b|\b1[89]\p{Nd}{2}\b)""",
)

/**
 * A **bank / card / KYC** brand only - the shape report §3.3 actually calls out ("Personal number se
 * bank banna - Moderate").
 *
 * The utility nouns are deliberately absent: a locality notice from a bijli/paani/LPG office that
 * prints a mobile number is ordinary, and turning it into an amber evidence card is the same
 * contradiction issue #114 was filed for.
 */
internal val BANK_OR_CARD_BRAND: Regex = rx(
    """(?:\bbank\b|बैंक|sbi|hdfc|icici|axis|kotak|pnb|bank of baroda|canara|union bank|yes bank|idfc|""" +
        """credit card|debit card|account|a/c|khata|खाता|kyc|केवायसी|loan|emi|insurance|policy|""" +
        """trai|telecom|bsnl|airtel|jio|vodafone|department|vibhag|विभाग|nigam|board)""",
)

/** A bank or a utility/service brand: the channel a personal mobile number is *not*. */
internal val SERVICE_OR_BANK_BRAND: Regex = rx(
    """(?:\bbank\b|बैंक|sbi|hdfc|icici|axis|kotak|pnb|bank of baroda|canara|union bank|yes bank|idfc|""" +
        """credit card|debit card|account|a/c|khata|खाता|kyc|केवायसी|loan|emi|insurance|policy|""" +
        """trai|telecom|bsnl|airtel|jio|vodafone|department|vibhag|विभाग|nigam|board|""" +
        """$UTILITY_SERVICE_NOUN)""",
)

/** [INDIAN_PERSONAL_MOBILE] compiled once for the corroboration rule. */
private val INDIAN_PERSONAL_MOBILE_RX: Regex = rx(INDIAN_PERSONAL_MOBILE)

// ---------------------------------------------------------------------------------------------
// Stage 2 - utility/service disconnection threat. Alone: LOW/WEAK, like every other stage-2 rule.
// ---------------------------------------------------------------------------------------------

internal val UTILITY_DISCONNECT_RULES: List<Rule> = listOf(
    Rule(
        titleHinglish = "Bijli/connection katne ki dhamki",
        detail = "Message bijli, gas, paani, broadband ya kisi service ka connection katne, band hone " +
            "ya block hone ki dhamki de raha hai. Asli department due date ka notice bhejta hai, " +
            "raat mein connection katne ki dhamki nahi. Yeh darr (stage 2) hai; akela CRITICAL nahi " +
            "banta.",
        severity = Severity.LOW,
        strength = Strength.WEAK,
        patterns = listOf(
            rx("""\b$UTILITY_SERVICE_NOUN\b[^.!?\n]{0,48}?\b(?:$SERVICE_DISCONNECT)\b"""),
            rx("""\b(?:$SERVICE_DISCONNECT)\b[^.!?\n]{0,40}?\b$UTILITY_SERVICE_NOUN\b"""),
            // A completed loss only counts when it is about the reader's own service (see
            // SERVICE_DISCONNECT_PAST): a locality-wide "power cut ho gaya" notice is not a threat.
            rx("""\b$READER_POSSESSIVE\b[^.!?\n]{0,50}?\b(?:$SERVICE_DISCONNECT_PAST)\b"""),
            rx("""\b(?:$SERVICE_DISCONNECT_PAST)\b[^.!?\n]{0,40}?$READER_POSSESSIVE\b"""),
        ),
    ),
)

// ---------------------------------------------------------------------------------------------
// Stage 5 - the asks this file adds. STRONG + directional, each one negation-guarded.
// ---------------------------------------------------------------------------------------------

internal val UTILITY_SERVICE_ASK_RULES: List<Rule> = listOf(
    Rule(
        titleHinglish = "Personal number par call karne ko kaha",
        detail = "Message bank ya bijli/paani jaisi service ke naam par ek personal number par call " +
            "karne ko keh raha hai. Asli bank ya department toll-free number ya short code se " +
            "message karta hai, personal mobile se nahi — aur phone par hi OTP ya paise maangta " +
            "hai. Report 3.3: personal number se bank banna.",
        severity = Severity.HIGH,
        strength = Strength.STRONG,
        patterns = listOf(
            // "turant is number par call karein 9876543210"
            rx("""\b$CALLBACK_IMPERATIVE\b[^.!?\n]{0,30}?\b$INDIAN_PERSONAL_MOBILE\b"""),
            // "9876543210 par call karein"
            rx(
                """\b$INDIAN_PERSONAL_MOBILE\b[^.!?\n]{0,18}?\b(?:par|pe|per)\b[^.!?\n]{0,10}?""" +
                    """\b(?:call|phone|dial|contact|ring) (?:karein|karo|kar|kijiye|karke)\b""",
            ),
            // "call this number 9876543210"
            rx(
                """\b(?:call|phone|dial|contact) (?:this|the|on|at|to|is)\b[^.!?\n]{0,26}?""" +
                    """\b$INDIAN_PERSONAL_MOBILE\b""",
            ),
            // "turant is personal number par call karke bill clear karein" (number never shown)
            rx(
                """\b(?:personal|private|niji|व्यक्तिगत) ?(?:mobile |phone )?(?:number|no)\b""" +
                    """[^.!?\n]{0,26}?\b(?:call|phone|dial|contact|ring)\b """ +
                    """(?:karein|karo|kar|karke|kijiye|kijiyega|kar do)\b""",
            ),
            // "call this personal number"
            rx(
                """\b(?:call|phone|dial|contact) (?:this|the|on|at|is)\b[^.!?\n]{0,8}?""" +
                    """\b(?:personal|private) ?(?:mobile )?(?:number|no)\b""",
            ),
        ),
        askType = AskType.CALLBACK,
        guarded = true,
        skip = SKIP_OFFICIAL_NUMBER,
        requires = SERVICE_OR_BANK_BRAND,
    ),
    Rule(
        titleHinglish = "Unofficial link par KYC/detail update karne ko kaha",
        detail = "Message KYC ya personal detail ek short link ya anjaan website par update/verify " +
            "karne ko keh raha hai. Asli bank aapko apni app, branch ya net banking par bhejta " +
            "hai. Report 3.3: shortener ka pata nahi hota, aur brand ke naam par bana anjaan " +
            "domain khatra hai.",
        severity = Severity.HIGH,
        strength = Strength.STRONG,
        patterns = listOf(
            rx(
                """\b$KYC_DETAIL_NOUN\b[^.!?\n]{0,72}?\b$KYC_UPDATE_VERB\w*\b[^.!?\n]{0,30}?""" +
                    """$UNOFFICIAL_LINK""",
            ),
            rx(
                """$UNOFFICIAL_LINK[^.!?\n]{0,40}?\b$KYC_UPDATE_VERB\w*\b[^.!?\n]{0,20}?""" +
                    """\b$KYC_DETAIL_NOUN\b""",
            ),
        ),
        askType = AskType.PERSONAL_INFO,
        guarded = true,
    ),
    Rule(
        titleHinglish = "Bank/service ke naam par personal mobile number",
        detail = "Message bank ya bijli jaisi service ka naam lekin ek personal 10-digit mobile " +
            "number deta hai. Report 3.3 ke hisaab se personal number se bank banna ek Moderate " +
            "signal hai — asli sanstha toll-free number ya short code se message karti hai. Yeh " +
            "akele saboot nahi hai, isliye yeh item score ko akele HIGH nahi karta.",
        severity = Severity.MEDIUM,
        strength = Strength.MODERATE,
        patterns = listOf(INDIAN_PERSONAL_MOBILE_RX),
        guarded = true,
        skip = SKIP_OFFICIAL_NUMBER,
        requires = BANK_OR_CARD_BRAND,
    ),
)
