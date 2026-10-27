package ai.trustshield.android.core.rules

import ai.trustshield.android.core.model.Severity
import ai.trustshield.android.core.model.Strength

/**
 * Advance-fee **offer** scams: the "you have been selected / approved / chosen, now pay a fee"
 * family. Job offers with a registration fee, loan apps with a processing fee, parcels stuck in
 * customs with a clearance fee, investment plans with a tax-to-withdraw fee, and prize claims with
 * a release fee.
 *
 * ## Why this file exists
 *
 * Every one of these reads as *good news* - a job, a loan, a parcel, a prize, a profit - and the
 * theft step is a small, plausible payment that arrives **before** anything is delivered. The
 * generic ask rule in `StageRules.kt` fires on a bare fee noun ("processing fee"), which is either
 * too much (a real loan sanction letter quotes a processing fee and is not a scam) or too little
 * (it never sees the frame that makes the fee suspicious: an employer asking for money, a lender
 * charging before sanction, a courier charging at a link). This module supplies the frames.
 *
 * ## What these rules claim, and what they refuse to claim
 *
 *  1. **Fake HR / recruiter persona** ([OFFER_AUTHORITY_RULES], stage 1, LOW/WEAK): a sender that
 *     presents itself as HR, a recruiter, a placement agency or a hiring team *and* a job noun.
 *     Stage 1 alone is never an accusation (BEHAVIOR_SPEC "Rule gates"), so a real recruiter's
 *     first message costs at most the 10-point authority weight.
 *  2. **Threats** ([OFFER_THREAT_RULES], stage 2, LOW/WEAK): loan-app recovery threats
 *     ("morphed photo bhej denge", "contact list viral kar denge"), a drugs/illegal parcel in the
 *     reader's name (the reverse order `drugs … parcel` that `StageRules` does not cover), and the
 *     outsized-return claim that the lottery rule misses (`paise double`, `risk free profit`).
 *     Nothing here is claimed twice: `guaranteed 30% monthly returns` and `guaranteed profit` are
 *     already stage 2 in `StageRules.kt` and are deliberately *not* repeated.
 *  3. **Offer urgency** ([OFFER_URGENCY_RULES], stage 3, LOW/WEAK): "limited seats", "sirf 3 slots
 *     bache", "first 100 applicants" - a scarcity clock that the generic urgency rule (turant /
 *     abhi / ghante) does not read.
 *  4. **The fee ask** ([OFFER_ASK_RULES], stage 5): each family gets its own STRONG, guarded,
 *     `AskType.MONEY_TRANSFER` rule, because each family needs a *different* frame to be believed.
 *     Secrecy ([OFFER_SECRECY_RULES]) is deliberately empty: `StageRules.kt` already owns
 *     "kisi ko mat batana" and a second copy would only double-count one stage.
 *
 * ## Guards (the false-alarm side matters as much as the catch)
 *
 *  * Every ask is `guarded = true`, so a protective "registration fee mat bhejo", "never pay a
 *    joining fee" and "do not pay any advance" stay advice instead of becoming evidence.
 *  * [SKIP_NO_FEE_ADVICE] removes the explicit anti-fee sentence from consideration: "no
 *    registration fee", "koi fee nahi lagti", "fee-free", "bina kisi charge". A real job
 *    advertisement that says it charges nothing must never score as extortion.
 *  * **Two facts, not one.** A fee noun alone is never an ask here. The pattern needs a *direction*
 *    (a payment verb, in either order) or an amount bound to the fee noun, **and** the rule's
 *    `requires` frame must be present somewhere in the message (a job frame, a loan frame, a parcel
 *    frame …). "Rs 500 late fee" on a broadband bill has direction and amount but no frame, so it
 *    stays unmatched.
 *  * The parcel link rule needs a **fee/clearance/update** word next to the link. An ordinary
 *    "your shipment is out for delivery, track here" notice carries a link and no fee, so it does
 *    not fire.
 *  * The investment "tax bharo" rule requires *both* a withdrawal word and a profit word in the
 *    message (see [OFFER_WITHDRAW_PROFIT]); a genuine tax payment is not a crypto exit fee.
 *  * Patterns are narrow and sentence-bounded (`[^.!?\n]`), so a fee in one sentence and an
 *    unrelated payment verb two sentences later cannot be welded into one ask.
 *
 * Deterministic and pure: no clock, no randomness, no I/O, no `android.*`, no new dependency. Every
 * quote still comes from the normalizer's raw-span map through `StageRules`, so nothing here can
 * invent a quote that is not a normalised substring of the analysed text.
 */

// ---------------------------------------------------------------------------------------------
// Shared vocabulary
// ---------------------------------------------------------------------------------------------

/**
 * Fee nouns that carry an advance-fee scam. First half is the qualifier (registration, joining,
 * security, clearance …), second half the money word it is attached to. The same noun list serves
 * every family: the *frame* in `requires` decides which family a message belongs to, so "clearance
 * fee" is a parcel fact in a customs message and a job fact in a visa-job message.
 */
internal const val OFFER_FEE_NOUN =
    "(?:registration|joining|security|processing|verification|activation|onboarding|training|" +
        "documentation|document|kit|uniform|id ?card|membership|file|admin(?:istration)?|" +
        "refundable|advance|initial|one[- ]?time|clearance|customs|re[- ]?delivery|delivery|" +
        "handling|convenience|maintenance|insurance|stamp|notary|legal|release|claim|" +
        "पंजीकरण|रजिस्ट्रेशन|जॉइनिंग|सिक्योरिटी|प्रोसेसिंग|वेरिफिकेशन|ट्रेनिंग|क्लीयरेंस|कस्टम्स|" +
        "डिलीवरी|सर्विस" +
        ") ?(?:fee|fees|charge|charges|deposit|amount|payment|duty|शुल्क|फीस|चार्ज|जमा|डिपॉजिट|राशि)"

/**
 * The direction of the ask: verbs that move money **towards the sender**, plus the obligation forms
 * a scam uses instead of an imperative ("joining fee compulsory hai"). Longer forms come first so
 * "transfer karo" wins over "transfer". A bare English "send"/"pay" is included on purpose - the
 * fee noun in the same clause is what makes it a money ask, never the verb alone.
 */
internal const val OFFER_PAY_VERB =
    "(?:transfer karo|transfer kar do|transfer kijiye|transfer karna|pay karo|pay kar do|" +
        "pay kijiye|payment karo|payment kar do|jama karo|jama kar do|jama karwao|jama karayein|" +
        "deposit karo|deposit kar do|daal do|daalo|daal dena|send karo|send kar do|" +
        "bhugtan karo|bhugtan kijiye|clear karo|clear kar do|bhej do|bhej de|bhej dena|bhejiye|" +
        "bhejna|bhejo|bhar do|bharo|bharna|bharni|" +
        "pay|transfer|send|deposit|remit|paid|" +
        "भेजो|भेज दो|भेजिए|भेजें|ट्रांसफर करो|जमा करो|जमा करें|भुगतान करो|भुगतान करें|" +
        "भरो|भरना|भर दो|डालो|डाल दो)"

/** The obligation twin of [OFFER_PAY_VERB]: "fee chahiye", "joining fee zaroori hai". */
internal const val OFFER_FEE_DEMAND =
    "(?:chahiye|chaiye|zaroori hai|jaruri hai|jaroori hai|compulsory hai|mandatory hai|" +
        "required hai|lazmi hai|dena hoga|deni hogi|dena hai|deni hai|" +
        "जरूरी है|देना होगा|देनी होगी|देना है)"

/**
 * An Indian money amount. Written with an explicit lookbehind instead of `\b` because `₹` is not a
 * word character and a `\b` before it can never match. The lookbehind stops "cursors 5" from
 * reading as "rs 5".
 */
internal const val OFFER_AMOUNT =
    "(?<![\\p{L}\\p{N}])(?:₹|rs\\.?|inr|rupees|rupaye|रुपये|रु\\.?)\\s?\\d[\\d,]*"

/**
 * The same amount with the currency token optional - "job ke liye 999 bhejo" quotes no "Rs".
 *
 * Only ever paired with [OFFER_PAY_VERB_STRICT] and a `requires` frame: a bare number is ordinary
 * text ("delivery in 3 days"), so this token must never be able to carry an ask on its own.
 */
internal const val OFFER_AMOUNT_ANY =
    "(?<![\\p{L}\\p{N}])(?:₹|rs\\.?|inr|rupees|rupaye|रुपये|रु\\.?)?\\s?\\d[\\d,]{1,9}(?![\\p{Nd}])"

/**
 * The imperative-only subset of [OFFER_PAY_VERB]: no bare English "send"/"pay"/"transfer".
 *
 * Used wherever the amount, not a fee noun, is what proves the ask. Without this split, "Salary
 * Rs 25,000, send your CV to hr@company.com" would read as a money request because "send" follows
 * an amount inside one sentence.
 */
internal const val OFFER_PAY_VERB_STRICT =
    "(?:bhej do|bhej de|bhej dena|bhejiye|bhejna|bhejo|transfer karo|transfer kar do|" +
        "transfer kijiye|pay karo|pay kar do|pay kijiye|payment karo|payment kar do|" +
        "jama karo|jama kar do|jama karwao|deposit karo|deposit kar do|daal do|daalo|" +
        "send karo|send kar do|bhugtan karo|bhugtan kijiye|clear karo|clear kar do|" +
        "भेजो|भेज दो|भेजिए|भेजें|ट्रांसफर करो|जमा करो|जमा करें|भुगतान करो|भुगतान करें|डालो|डाल दो)"

/**
 * The explicit anti-fee sentence. Applied as `skip` on every ask in this file: a message that tells
 * the reader there is nothing to pay is advice, not a request, and "no" / "bina" / "fee-free" are
 * not in the engine's negation vocabulary.
 */
internal val SKIP_NO_FEE_ADVICE: Regex = rx(
    """(?:\bno (?:registration |joining |processing |security |advance )?(?:fee|fees|charge|charges|deposit)\b""" +
        """|\bnever (?:pay|send|transfer)\b[^.!?\n]{0,24}?\b(?:fee|fees|money|amount|paise|deposit)\b""" +
        """|\bdo not (?:pay|send|transfer)\b[^.!?\n]{0,24}?\b(?:fee|fees|money|amount|paise|deposit)\b""" +
        """|\b(?:koi|koe|koyi) (?:bhi )?(?:fee|fees|charge|charges|paise|paisa|payment) (?:nahi|nahin)\b""" +
        """|\b(?:fee|fees|charge|charges|payment|paise) (?:nahi|nahin) (?:lagti|lagegi|lagta|hogi|hota|hai|dena)\b""" +
        """|\b(?:bina|without) (?:kisi |any )?(?:fee|fees|charge|charges|payment|paise)\b""" +
        """|\bfee[- ]?free\b|\bfree of (?:cost|charge)\b|\bno hidden (?:charge|charges|fee|fees)\b""" +
        """|\bकिसी भी शुल्क\b|\bशुल्क नहीं\b|\bफीस नहीं\b|\bपैसे मत भेजें\b|\bपैसे न भेजें\b)""",
)

/** A job/employment frame: without this, a fee + verb pair is just a bill. */
internal val OFFER_JOB_FRAME: Regex = rx(
    """(?:\bjob\b|\bjobs\b|\bnaukri\b|\bनौकरी\b|\bजॉब\b|\bvacancy\b|\bvacancies\b|\bopening\b|\bopenings\b""" +
        """|\bhiring\b|\brecruit\w*\b|\btelecaller\b|\bdata entry\b|\bwork from home\b|\bghar se kaam\b""" +
        """|\bpart[- ]?time\b|\bfull[- ]?time\b|\btyping job\b|\bcopy paste\b|\btask\b|\btasks\b""" +
        """|\bassignment\b|\bकाम\b|\bभर्ती\b|\bटास्क\b)""",
)

/** A lending frame: a fee is only an advance-fee loan scam when a loan is the thing on offer. */
internal val OFFER_LOAN_FRAME: Regex = rx(
    """(?:\bloan\b|\bloans\b|\bलोन\b|\bऋण\b|\bpre[- ]?approved\b|\bpre[- ]?qualified\b|\bemi\b""" +
        """|\binstallment\b|\bकिस्त\b|\bcibil\b|\bcredit score\b|\bcredit report\b|\bpersonal loan\b""" +
        """|\binstant loan\b|\bloan app\b|\bnbfc\b|\bcredit line\b|\bsanction\w*\b|\bमंजूर\b)""",
)

/** A parcel/shipping frame: the customs-clearance and re-delivery families. */
internal val OFFER_PARCEL_FRAME: Regex = rx(
    """(?:\bparcel\b|\bपार्सल\b|\bpackage\b|\bpackages\b|\bcourier\b|\bshipment\b|\bconsignment\b""" +
        """|\bdelivery\b|\bडिलीवरी\b|\bcustoms\b|\bकस्टम्स\b|\btracking\b|\bspeed post\b|\bindia post\b""" +
        """|\bdtdc\b|\bbluedart\b|\bdelhivery\b|\bekart\b|\bparcel\b)""",
)

/** An investment/trading frame: the "deposit, then withdraw after tax" family. */
internal val OFFER_INVEST_FRAME: Regex = rx(
    """(?:\binvest\w*\b|\bनिवेश\b|\btrading\b|\bforex\b|\bcrypto\b|\bcryptocurrency\b|\bbitcoin\b""" +
        """|\bbtc\b|\busdt\b|\bbinance\b|\btoken\b|\bmutual fund\b|\bsip\b|\bportfolio\b|\bprofit\b""" +
        """|\breturns?\b|\bमुनाफ़ा\b|\btelegram group\b|\bsignal\b|\bbot\b|\bstock market\b)""",
)

/** A prize/lottery frame: KBC, lucky draw, jackpot, "aap jeet gaye". */
internal val OFFER_LOTTERY_FRAME: Regex = rx(
    """(?:\bkbc\b|\bkaun banega crorepati\b|\blottery\b|\bलॉटरी\b|\blucky draw\b|\bjackpot\b""" +
        """|\bprize\b|\bइनाम\b|\binam\b|\binaam\b|\bwinner\b|\bwon\b|\bjeet\w*\b|\bjeeta\b|\bjeeti\b""" +
        """|\bshahrukh\b|\bsrk\b|\bamitabh\b|\bscratch card\b)""",
)

/**
 * The crypto exit-fee shape needs **both** halves: something is being withdrawn *and* a profit is
 * being released. One lookahead each, over the whole message, so the two facts may sit in different
 * sentences (as they do in the real script) without letting a plain tax notice through.
 */
internal val OFFER_WITHDRAW_PROFIT: Regex = rx(
    """(?=[\s\S]*\b(?:withdraw\w*|withdrawal|nikal\w*|निकाल\w*|nikas\w*)\b)""" +
        """(?=[\s\S]*\b(?:profit|profits|return|returns|earning|earnings|मुनाफ़ा|लाभ)\b)""",
)

// ---------------------------------------------------------------------------------------------
// Stage 1 - the fake employer persona. Alone: LOW/WEAK, like every other stage-1 rule.
// ---------------------------------------------------------------------------------------------

/**
 * `StageRules.kt` knows a fake bank officer, a fake police/CBI/customs officer and a fake
 * customer-care executive. It does not know a fake **employer** - and "HR se baat kar rahe hain" is
 * how every job-fee scam opens. Stage 1 only, so this never accuses a message by itself.
 */
internal val OFFER_AUTHORITY_RULES: List<Rule> = listOf(
    Rule(
        titleHinglish = "Fake HR / recruiter ka dawa",
        detail = "Message khud ko HR, recruiter ya placement agency batata hai aur job ki baat karta " +
            "hai. Asli employer bharti ke liye paise nahi maangta; yeh sirf stage 1 ka dawa hai, " +
            "akele scam ka saboot nahi.",
        severity = Severity.LOW,
        strength = Strength.WEAK,
        patterns = listOf(
            // "this is HR from Talent Bridge", "main HR se baat kar rahi hoon"
            rx(
                """\b(?:this is|i am|i'm|main|mein|hum)\b[^.!?\n]{0,26}?""" +
                    """\b(?:hr|h\.r\.|human resource|recruiter|hiring manager|talent acquisition|""" +
                    """placement (?:agency|consultant|cell))\b""",
            ),
            // "HR team se", "recruitment team here", "placement agency ki taraf se"
            rx(
                """\b(?:hr|recruiter|recruitment (?:team|cell|agency|consultancy)|""" +
                    """placement (?:agency|consultant|cell)|hiring (?:team|manager))\b""" +
                    """[^.!?\n]{0,34}?\b(?:se|from|here|team|department|bol rah\w*|bat rah\w*)\b""",
            ),
            // "HR ne shortlist kiya hai", "recruiter ke paas aapki job"
            rx(
                """\b(?:hr|recruiter|placement (?:agency|consultant))\b[^.!?\n]{0,26}?""" +
                    """\b(?:job|naukri|vacancy|opening|hiring|selected|shortlist\w*)\b""",
            ),
            // "एचआर टीम से", "भर्ती विभाग"
            rx("""\b(?:एचआर|भर्ती|नियुक्ति|रिक्रूटर)\b[^.!?\n]{0,24}?\b(?:टीम|विभाग|से|ने)\b"""),
        ),
    ),
)

// ---------------------------------------------------------------------------------------------
// Stage 2 - the pressure behind the offer. Alone: LOW/WEAK.
// ---------------------------------------------------------------------------------------------

/**
 * Three pressures that `StageRules.kt` does not already cover: loan-app recovery blackmail, a
 * drugs/illegal parcel in the reader's name in the `drugs ... parcel` order, and the "double your
 * money" claim in Roman-Hindi.
 *
 * Deliberately absent: `guaranteed 30% monthly returns` and `guaranteed profit` are already stage 2
 * in the lottery/laalach rule, and `parcel ... customs` in that order is already the arrest rule's
 * second pattern. A second copy would only inflate one stage's evidence list.
 */
internal val OFFER_THREAT_RULES: List<Rule> = listOf(
    Rule(
        titleHinglish = "Loan app ki vasooli aur photo ki dhamki",
        detail = "Message loan recover karne ke liye photo, morphed photo ya contact list viral karne " +
            "ki dhamki de raha hai. Yeh darr stage 2 hai; asli lender blackmail nahi karta.",
        severity = Severity.LOW,
        strength = Strength.WEAK,
        patterns = listOf(
            // "loan app se recover karenge ... photo bhej denge"
            rx(
                """\b(?:recover\w* (?:karenge|karega|kar lenge|kar lega|kar rahe hain|hoga)|""" +
                    """vasool\w*|वसूल\w*)\b[^.!?\n]{0,44}?\b(?:photo|photos|pic|picture|""" +
                    """screenshot|morphed|contact list|contacts|mms|video|family|dost|""" +
                    """तस्वीर|फोटो)\b""",
            ),
            // "morphed photo bhej denge", "अश्लील फोटो वायरल कर देंगे"
            rx(
                """\b(?:morphed|morph|edited|fake|अश्लील)\b[^.!?\n]{0,16}?""" +
                    """\b(?:photo|photos|pic|picture|video|clip|तस्वीर|फोटो)\b""" +
                    """[^.!?\n]{0,30}?\b(?:bhej denge|bhejenge|bhej doonga|bhej dunga|""" +
                    """viral kar denge|share kar denge|send kar denge|भेज देंगे)\b""",
            ),
            // "contact list viral kar denge", "photo sabko bhej dunga"
            rx(
                """\b(?:photo|photos|तस्वीर|फोटो|contact list|contacts)\b[^.!?\n]{0,30}?""" +
                    """\b(?:viral|share|bhej)\w*\b[^.!?\n]{0,22}?\b(?:denge|doonga|dunga|""" +
                    """kar denge|कर देंगे|देंगे)\b""",
            ),
        ),
    ),
    Rule(
        titleHinglish = "Aapke naam par drugs/illegal parcel",
        detail = "Message kehta hai ki aapke naam par drugs ya illegal saman ka parcel aaya hai. Yeh " +
            "digital-arrest aur customs scam ka darr hai; koi asli agency message par case clear " +
            "karne ke liye paise nahi maangti.",
        severity = Severity.LOW,
        strength = Strength.WEAK,
        patterns = listOf(
            // "drugs wala parcel mila hai" - the reverse order StageRules does not match.
            rx(
                """\b(?:drugs?|narcotics|mdma|ganja|charas|contraband|""" +
                    """illegal (?:item|items|goods|material|substance)|ड्रग्स|नशीला|अवैध)\b""" +
                    """[^.!?\n]{0,34}?\b(?:parcel|package|courier|shipment|consignment|पार्सल)\b""",
            ),
            // "aapke naam se parcel aaya hai"
            rx(
                """\b(?:aapke|aapka|tumhare|tumhara|your)\s+(?:naam|name)\b[^.!?\n]{0,34}?""" +
                    """\b(?:parcel|package|courier|shipment|पार्सल)\b""",
            ),
            // "parcel mila hai ... customs/case"
            rx(
                """\b(?:parcel|package|पार्सल)\b[^.!?\n]{0,30}?\b(?:mila hai|mila|received|""" +
                    """aaya hai|aa gaya)\b[^.!?\n]{0,30}?\b(?:customs|police|drugs|illegal|court|""" +
                    """case|कस्टम्स)\b""",
            ),
        ),
    ),
    Rule(
        titleHinglish = "Paise double karne ka laalach",
        detail = "Message paise double/triple hone ka dawa karta hai ya 'risk free profit' ka laalach " +
            "deta hai. Aisa koi bhi investment guarantee nahi hota; yeh stage 2 laalach hai.",
        severity = Severity.LOW,
        strength = Strength.WEAK,
        patterns = listOf(
            // "crypto double karne ka plan", "paise double ho jayenge"
            rx(
                """\b(?:paise|paisa|money|amount|investment|crypto|bitcoin|capital|rakam|""" +
                    """रकम|पैसा|पैसे|निवेश)\b[^.!?\n]{0,26}?""" +
                    """\b(?:double|triple|duguna|duggna|दोगुना|चौगुना)\b""",
            ),
            // "double ... din mein / month mein"
            rx(
                """\b(?:double|triple|duguna|दोगुना)\b[^.!?\n]{0,26}?\b(?:paise|paisa|money|""" +
                    """investment|crypto|bitcoin|capital|din mein|days? mein|month mein|""" +
                    """mahine mein|दिन में|महीने में)\b""",
            ),
            // "risk free profit", "pakka profit"
            rx(
                """\b(?:risk[- ]?free|no risk|zero risk|pakka profit|पक्का मुनाफ़ा|""" +
                    """guaranteed income)\b[^.!?\n]{0,30}?\b(?:profit|return|returns|earning|""" +
                    """income|मुनाफ़ा|लाभ)\b""",
            ),
        ),
    ),
)

// ---------------------------------------------------------------------------------------------
// Stage 3 - the scarcity clock these offers run on. Alone: LOW/WEAK.
// ---------------------------------------------------------------------------------------------

/**
 * `StageRules.kt` reads a time clock ("turant", "abhi", "2 ghante"). Offer scams use a *seat*
 * clock instead - "limited seats", "sirf 3 slots bache", "first 100 applicants" - which is a
 * different way of saying "decide before you can check". Stage 3 only, and it never fires on a
 * plain job advertisement that lists a number of openings without a closing claim.
 */
internal val OFFER_URGENCY_RULES: List<Rule> = listOf(
    Rule(
        titleHinglish = "Seats/slots khatam hone ka dabaav",
        detail = "Message seat, slot ya vacancy jaldi bharne ka dabaav bana raha hai. Akele yeh scam " +
            "ka saboot nahi hai, lekin jaldbaazi mein faisla karana scam ka tareeka hai.",
        severity = Severity.LOW,
        strength = Strength.WEAK,
        patterns = listOf(
            rx(
                """\b(?:limited|sirf|only|few|kam) (?:seats?|slots?|positions?|vacanc(?:y|ies)|""" +
                    """openings?)\b""",
            ),
            rx("""\b(?:sirf|only)\s?\d{1,3}\s?(?:seats?|slots?|positions?|logon|log)\b"""),
            rx(
                """\b(?:seats?|slots?|vacanc(?:y|ies)|positions?|offer)\b[^.!?\n]{0,20}?""" +
                    """\b(?:bhar rahe|bhar rahi|bhar gaye|bhar chuki|full ho rahe|close ho raha|""" +
                    """closing)\b""",
            ),
            rx("""\b(?:first|pehle)\s?\d{1,3}\s?(?:applicants?|users?|members?|log)\b"""),
            rx("""\b(?:सीमित सीटें|सीटें भर रही|स्लॉट सीमित|सीमित स्लॉट|जल्दी भर जाएंगी)\b"""),
        ),
        // A scarcity line is only this module's business inside a job/prize/investment pitch. An
        // event notice that says "limited seats" about a workshop or a loan mela names no such
        // frame, so it stays out.
        requires = rx(
            """\b(?:job|jobs|naukri|नौकरी|जॉब|vacancy|opening|hiring|recruit\w*|part[- ]?time|""" +
                """work from home|internship|prize|इनाम|lottery|lucky draw|kbc|invest\w*|trading|""" +
                """forex|crypto)\b""",
        ),
    ),
)

// ---------------------------------------------------------------------------------------------
// Stage 4 - deliberately empty.
// ---------------------------------------------------------------------------------------------

/**
 * No secrecy rule here. `StageRules.SECRECY_RULES` already owns "kisi ko mat batana", the
 * confidential-case framing and the stay-on-the-line pressure, and a second copy of the same
 * vocabulary would put two cards on one stage while fusion counts the stage once. Offer scams do
 * use "yeh offer kisi ko mat batana", and that sentence is caught by the existing rule.
 */
internal val OFFER_SECRECY_RULES: List<Rule> = emptyList()

// ---------------------------------------------------------------------------------------------
// Stage 5 - the fee ask. Every rule: HIGH/STRONG, guarded, MONEY_TRANSFER, narrow patterns.
// ---------------------------------------------------------------------------------------------

/**
 * Job-fee asks.
 *
 * A genuine offer letter says "we are pleased to offer you the position" and never asks the
 * candidate for money, so the ask pattern needs three things at once: a **fee noun**, a **payment
 * direction** in the same sentence (verb in either order, or an amount bound to the fee noun, or an
 * obligation "fee compulsory hai"), and the **job frame** in [Rule.requires]. "Rs 500 late fee" on
 * a broadband bill has a fee and an amount but no job frame; a job advertisement that says it
 * charges nothing is removed by [SKIP_NO_FEE_ADVICE].
 */
internal val OFFER_ASK_RULES: List<Rule> = listOf(
    Rule(
        titleHinglish = "Job ke naam par registration/joining fee",
        detail = "Message job dene ke badle pehle registration, joining ya security fee maang raha " +
            "hai. Koi asli employer bharti ke liye candidate se paise nahi leta - yeh advance-fee " +
            "job scam ka paisa maangne wala step hai.",
        severity = Severity.HIGH,
        strength = Strength.STRONG,
        patterns = listOf(
            // "pay Rs 1500 registration fee"
            rx("""\b$OFFER_PAY_VERB\b[^.!?\n]{0,44}?\b$OFFER_FEE_NOUN\b"""),
            // "registration fee bhejo", "security deposit transfer karo"
            rx("""\b$OFFER_FEE_NOUN\b[^.!?\n]{0,44}?\b$OFFER_PAY_VERB\b"""),
            // "Rs 1500 registration fee" - the price of the job, no verb needed
            rx("""$OFFER_AMOUNT[^.!?\n]{0,34}?\b$OFFER_FEE_NOUN\b"""),
            // "joining fee compulsory hai", "registration charge zaroori hai"
            rx("""\b$OFFER_FEE_NOUN\b[^.!?\n]{0,30}?\b$OFFER_FEE_DEMAND\b"""),
            // "pehle deposit karo" - the prepay instruction with no fee noun at all
            rx(
                """\b(?:pehle|pahle|पहले)\b[^.!?\n]{0,14}?\b(?:deposit|payment|fee|fees|charge|""" +
                    """charges|paise|शुल्क|फीस|चार्ज)\b[^.!?\n]{0,14}?\b(?:karo|karein|kijiye|""" +
                    """bhejo|bhej do|dena|करो|दो)\b""",
            ),
            // "work from home task, per task Rs 50, pehle deposit karo"
            rx(
                """\b(?:per (?:task|day|click|like|referral)|har (?:task|din)|प्रति टास्क)\b""" +
                    """[^.!?\n]{0,44}?\b$OFFER_PAY_VERB\b""",
            ),
            // "part time job ke liye 999 bhejo" - no fee noun, the price of the job is the number
            rx(
                """\b(?:ke liye|for)\b[^.!?\n]{0,20}?$OFFER_AMOUNT_ANY[^.!?\n]{0,14}?""" +
                    """\b$OFFER_PAY_VERB_STRICT\b""",
            ),
        ),
        askType = AskType.MONEY_TRANSFER,
        guarded = true,
        skip = SKIP_NO_FEE_ADVICE,
        requires = OFFER_JOB_FRAME,
    ),
    Rule(
        titleHinglish = "Telegram/WhatsApp recruiter ne paise maange",
        detail = "Message Telegram ya WhatsApp par job dilane ke liye pehle paise bhejne ko keh raha " +
            "hai. Asli bharti kisi chat app par fee nahi maangti; yeh job scam ka paisa maangne " +
            "wala step hai.",
        severity = Severity.HIGH,
        strength = Strength.STRONG,
        patterns = listOf(
            // "telegram par message karo, job ke liye 999 bhejo"
            rx(
                """\b(?:telegram|whatsapp|wa\.me|t\.me|signal)\b[^.!?\n]{0,50}?""" +
                    """$OFFER_AMOUNT_ANY[^.!?\n]{0,20}?\b$OFFER_PAY_VERB_STRICT\b""",
            ),
            // "999 bhejo ... whatsapp par"
            rx(
                """$OFFER_AMOUNT_ANY[^.!?\n]{0,30}?\b$OFFER_PAY_VERB_STRICT\b[^.!?\n]{0,40}?""" +
                    """\b(?:telegram|whatsapp|wa\.me|t\.me)\b""",
            ),
        ),
        askType = AskType.MONEY_TRANSFER,
        guarded = true,
        skip = SKIP_NO_FEE_ADVICE,
        requires = rx(
            """(?=[\s\S]*\b(?:telegram|whatsapp|wa\.me|t\.me)\b)""" +
                """(?=[\s\S]*\b(?:job|naukri|नौकरी|vacancy|hiring|part[- ]?time|work from home|""" +
                """task|recruit\w*)\b)""",
        ),
    ),
    Rule(
        titleHinglish = "Loan approve hone par processing fee",
        detail = "Message loan approve hone ke baad pehle processing/verification fee bhejne ko keh " +
            "raha hai. Asli lender fee sanction ke baad account se katta hai, pehle UPI par nahi " +
            "maangta - yeh loan-app scam ka paisa maangne wala step hai.",
        severity = Severity.HIGH,
        strength = Strength.STRONG,
        patterns = listOf(
            // "pay Rs 2000 processing fee"
            rx("""\b$OFFER_PAY_VERB\b[^.!?\n]{0,44}?\b$OFFER_FEE_NOUN\b"""),
            // "processing fee bhejo", "2000 processing fee transfer karo"
            rx("""\b$OFFER_FEE_NOUN\b[^.!?\n]{0,44}?\b$OFFER_PAY_VERB\b"""),
            // "pre-approved loan Rs 2,00,000, processing fee"
            rx(
                """\b(?:pre[- ]?approved|pre[- ]?qualified|approved|sanctioned|approve ho gaya|""" +
                    """approve ho gya|मंजूर)\b[^.!?\n]{0,50}?\b$OFFER_FEE_NOUN\b""",
            ),
            // "instant loan bina documents, pehle fee"
            rx(
                """\b(?:bina|without)\s+(?:any\s+)?(?:document|documents|doc|docs|paperwork|cibil|""" +
                    """income proof|कागज)\b[^.!?\n]{0,44}?\b(?:fee|fees|charge|charges|फीस|शुल्क)\b""",
            ),
        ),
        askType = AskType.MONEY_TRANSFER,
        guarded = true,
        skip = SKIP_NO_FEE_ADVICE,
        requires = OFFER_LOAN_FRAME,
    ),
    Rule(
        titleHinglish = "CIBIL score sudharne ki fee",
        detail = "Message CIBIL/credit score sudharne ke liye paise maang raha hai. Koi bhi agent " +
            "score badal nahi sakta - CIBIL khud is tarah paise nahi maangta.",
        severity = Severity.HIGH,
        strength = Strength.STRONG,
        patterns = listOf(
            // "CIBIL score improve karne ke liye fee"
            rx(
                """\b(?:cibil|credit score|credit report|क्रेडिट स्कोर)\b[^.!?\n]{0,44}?""" +
                    """\b(?:ke liye|for|sudhar\w*|improve\w*|badha\w*|badhane|बढ़ाने)\b""" +
                    """[^.!?\n]{0,20}?\b(?:fee|fees|charge|charges|payment|paise|फीस|शुल्क|पैसे)\b""",
            ),
            // "fee ... CIBIL ... sudhar"
            rx(
                """\b(?:fee|fees|charge|charges|payment|फीस|शुल्क)\b[^.!?\n]{0,26}?""" +
                    """\b(?:cibil|credit score)\b[^.!?\n]{0,20}?\b(?:sudhar|improve|badha|badhane|""" +
                    """बढ़ाने)\b""",
            ),
            // "Rs 2000 CIBIL score"
            rx("""$OFFER_AMOUNT[^.!?\n]{0,30}?\b(?:cibil|credit score)\b"""),
        ),
        askType = AskType.MONEY_TRANSFER,
        guarded = true,
        skip = SKIP_NO_FEE_ADVICE,
        requires = OFFER_LOAN_FRAME,
    ),
    Rule(
        titleHinglish = "Parcel customs mein ruka - clearance fee",
        detail = "Message kehta hai ki parcel customs mein ruka hai aur clearance/duty fee bhejne ko " +
            "keh raha hai. Asli customs duty parcel deliver karte waqt official channel se leta " +
            "hai, message par UPI se nahi - yeh parcel scam ka paisa maangne wala step hai.",
        severity = Severity.HIGH,
        strength = Strength.STRONG,
        patterns = listOf(
            // "clearance fee Rs 2500 bhejo"
            rx("""\b$OFFER_FEE_NOUN\b[^.!?\n]{0,44}?\b$OFFER_PAY_VERB\b"""),
            // "pay the clearance fee"
            rx("""\b$OFFER_PAY_VERB\b[^.!?\n]{0,44}?\b$OFFER_FEE_NOUN\b"""),
            // "parcel customs mein ruka hai ... clearance fee"
            rx(
                """\b(?:parcel|package|courier|shipment|consignment|delivery|पार्सल)\b""" +
                    """[^.!?\n]{0,50}?\b(?:ruka|ruki|held|on hold|stuck|seized|roka|pending|""" +
                    """customs|कस्टम्स|रुका|फंसा)\b[^.!?\n]{0,50}?\b$OFFER_FEE_NOUN\b""",
            ),
            // "customs duty ke liye pay karo"
            rx(
                """\b(?:customs|कस्टम्स)\b[^.!?\n]{0,30}?\b(?:duty|fee|fees|charge|charges|""" +
                    """clearance|शुल्क|फीस|चार्ज)\b[^.!?\n]{0,34}?\b$OFFER_PAY_VERB\b""",
            ),
        ),
        askType = AskType.MONEY_TRANSFER,
        guarded = true,
        skip = SKIP_NO_FEE_ADVICE,
        requires = OFFER_PARCEL_FRAME,
    ),
    Rule(
        titleHinglish = "Parcel link par delivery/clearance charge",
        detail = "Message delivery ya clearance charge ke liye ek short ya anjaan link par bhej raha " +
            "hai. Asli courier apni official app/site par tracking deta hai, short link par " +
            "payment nahi maangta - yeh paisa maangne wala step hai.",
        severity = Severity.HIGH,
        strength = Strength.STRONG,
        patterns = listOf(
            // "delivery failed, Rs 25 re-delivery charge is link par"
            rx(
                """\b(?:fee|fees|charge|charges|clearance|duty|payment|pay|re[- ]?delivery|""" +
                    """शुल्क|फीस|चार्ज)\b[^.!?\n]{0,26}?\b(?:is|iss|this)\s?link\b""",
            ),
            // "is link par Rs 25 pay karo"
            rx(
                """\b(?:is|iss|this)\s?(?:link|लिंक)\b[^.!?\n]{0,26}?\b(?:fee|fees|charge|""" +
                    """charges|clearance|duty|pay|payment|re[- ]?delivery)\b""",
            ),
            // a shortener / throwaway host next to a fee word, in either order. "pay" only counts
            // as an imperative here: "pay on delivery ... track here <link>" is a normal courier
            // line and must not read as a payment request.
            rx(
                """$UNOFFICIAL_LINK[^.!?\n]{0,44}?\b(?:fee|fees|charge|charges|clearance|duty|""" +
                    """payment|pay (?:karo|karein|kijiye|now|here)|re[- ]?delivery|शुल्क|फीस|चार्ज)\b""",
            ),
            rx(
                """\b(?:fee|fees|charge|charges|clearance|duty|payment|""" +
                    """pay (?:karo|karein|kijiye|now|here)|re[- ]?delivery|""" +
                    """शुल्क|फीस|चार्ज)\b[^.!?\n]{0,44}?$UNOFFICIAL_LINK""",
            ),
        ),
        askType = AskType.MONEY_TRANSFER,
        guarded = true,
        skip = SKIP_NO_FEE_ADVICE,
        requires = OFFER_PARCEL_FRAME,
    ),
    Rule(
        titleHinglish = "Parcel ka naam/address unofficial link par update",
        detail = "Message parcel ka naam, address ya detail ek short ya anjaan link par update/verify " +
            "karne ko keh raha hai. Asli courier ka tracking link official domain par hota hai; " +
            "short link par detail dena identity chori ka rasta hai.",
        severity = Severity.HIGH,
        strength = Strength.STRONG,
        patterns = listOf(
            // "address update karein https://delivery-update.xyz/abc" - the imperative is required,
            // so a courier's "Track updates here: <short link>" notice stays out.
            rx(
                """$UNOFFICIAL_LINK[^.!?\n]{0,40}?\b(?:update|verify|confirm|submit)\b""" +
                    """[^.!?\n]{0,10}?\b(?:karein|karo|kar|kijiye|karne|ke liye|here|now)\b""",
            ),
            rx(
                """\b(?:update|verify|confirm|submit)\b[^.!?\n]{0,14}?""" +
                    """\b(?:karein|karo|kijiye|karne|ke liye)\b[^.!?\n]{0,40}?$UNOFFICIAL_LINK""",
            ),
        ),
        askType = AskType.PERSONAL_INFO,
        guarded = true,
        requires = OFFER_PARCEL_FRAME,
    ),
    Rule(
        titleHinglish = "Trading app par paisa daalne ko kaha",
        detail = "Message trading/forex/crypto app ya Telegram group join karke paisa daalne ko keh " +
            "raha hai, aksar minimum deposit ke saath. Aisa paisa wapas nahi aata - yeh " +
            "investment scam ka paisa maangne wala step hai.",
        severity = Severity.HIGH,
        strength = Strength.STRONG,
        patterns = listOf(
            // "forex bot, minimum deposit 5000" - the scheme word must sit in the same sentence,
            // so an ordinary "minimum deposit Rs 500 to open an SIP" notice stays out.
            rx(
                """\b(?:bot|trading|forex|crypto|bitcoin|scheme|plan|signal|account|app|telegram|""" +
                    """group|channel)\b[^.!?\n]{0,44}?\b(?:minimum|min|initial|first|starting)\s+""" +
                    """(?:deposit|investment|amount|capital|पूंजी)\b[^.!?\n]{0,26}?""" +
                    """(?:$OFFER_AMOUNT|\d[\d,]{2,})""",
            ),
            // "trading app par Rs 5000 daalo"
            rx(
                """\b(?:trading app|forex|binance|crypto|exchange|app|account|wallet|portfolio)\b""" +
                    """[^.!?\n]{0,30}?\b(?:par|pe|mein|me|में)\b[^.!?\n]{0,24}?$OFFER_AMOUNT""" +
                    """[^.!?\n]{0,14}?\b(?:daalo|daal do|dalein|invest karo|lagao|lagayein|""" +
                    """डालो|लगाओ)\b""",
            ),
            // "telegram group join karo ... Rs 5000 bhejo"
            rx(
                """\b(?:telegram group|telegram channel|group|channel)\b[^.!?\n]{0,44}?""" +
                    """$OFFER_AMOUNT_ANY[^.!?\n]{0,24}?\b$OFFER_PAY_VERB_STRICT\b""",
            ),
        ),
        askType = AskType.MONEY_TRANSFER,
        guarded = true,
        skip = SKIP_NO_FEE_ADVICE,
        requires = OFFER_INVEST_FRAME,
    ),
    Rule(
        titleHinglish = "Profit withdraw karne ke liye tax",
        detail = "Message profit ya paisa withdraw karne se pehle tax/fee bhejne ko keh raha hai. " +
            "Asli tax account se katta hai, withdrawal se pehle UPI par nahi maanga jata - yeh " +
            "investment scam ka aakhri paisa maangne wala step hai.",
        severity = Severity.HIGH,
        strength = Strength.STRONG,
        patterns = listOf(
            // "profit withdraw karne ke liye tax bharo"
            rx(
                """\b(?:tax|gst|tds|charges?|fee|fees|commission|कर|टैक्स|कमीशन)\b""" +
                    """[^.!?\n]{0,34}?\b(?:bharo|bhar do|bharna|pay karo|pay kar do|jama karo|""" +
                    """transfer karo|भरो|भरना)\b""",
            ),
            // "withdraw karne ke liye tax"
            rx(
                """\b(?:withdraw\w*|withdrawal|nikal\w*|निकाल\w*)\b[^.!?\n]{0,34}?""" +
                    """\b(?:tax|gst|tds|fee|fees|charges?|कर|टैक्स)\b""",
            ),
        ),
        askType = AskType.MONEY_TRANSFER,
        guarded = true,
        skip = SKIP_NO_FEE_ADVICE,
        requires = OFFER_WITHDRAW_PROFIT,
    ),
    Rule(
        titleHinglish = "Prize/claim ke liye pehle fee",
        detail = "Message lottery, KBC ya lucky-draw ka inam dene ke badle pehle fee bhejne ko keh " +
            "raha hai. Koi asli lucky draw jeetne se pehle paise nahi maangta - yeh prize scam ka " +
            "paisa maangne wala step hai.",
        severity = Severity.HIGH,
        strength = Strength.STRONG,
        patterns = listOf(
            // "KBC 25 lakh winner, processing fee bhejo"
            rx("""\b$OFFER_FEE_NOUN\b[^.!?\n]{0,40}?\b$OFFER_PAY_VERB\b"""),
            // "Rs 5,000 processing fee"
            rx("""$OFFER_AMOUNT[^.!?\n]{0,34}?\b$OFFER_FEE_NOUN\b"""),
            // "lucky draw winner, claim karne ke liye Rs 5,000"
            rx(
                """\b(?:kbc|kaun banega crorepati|lucky draw|lottery|jackpot|prize|inam|inaam|""" +
                    """इनाम|shahrukh|srk|amitabh)\b[^.!?\n]{0,60}?""" +
                    """\b(?:claim|release|collect|receive|paane|lene|nikalne)\b""" +
                    """[^.!?\n]{0,30}?$OFFER_AMOUNT""",
            ),
            // "claim karne ke liye Rs 5,000 bhejo"
            rx(
                """\b(?:claim|release|collect)\b[^.!?\n]{0,26}?\b(?:ke liye|keliye)\b""" +
                    """[^.!?\n]{0,20}?$OFFER_AMOUNT[^.!?\n]{0,16}?\b$OFFER_PAY_VERB\b""",
            ),
        ),
        askType = AskType.MONEY_TRANSFER,
        guarded = true,
        skip = SKIP_NO_FEE_ADVICE,
        requires = OFFER_LOTTERY_FRAME,
    ),
)
