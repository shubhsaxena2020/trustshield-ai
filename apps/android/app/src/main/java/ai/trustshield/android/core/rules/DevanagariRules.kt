package ai.trustshield.android.core.rules

import ai.trustshield.android.core.model.Severity
import ai.trustshield.android.core.model.Strength

/**
 * **Devanagari-only** coverage: the Hindi-script sentences the Roman rules in `StageRules.kt` cannot
 * reach, on all five playbook stages.
 *
 * ## Why this file exists, and why it is small
 *
 * `StageRules.kt` is written in Roman-Hinglish and carries only a handful of Devanagari words. That
 * mix produces an asymmetric detector: a Hindi-script message that says exactly what the Roman rules
 * are built to catch can score `LOW_RISK`, because the *noun* is Devanagari and the *verb* the rules
 * look for is not. The worst example is stage 4 - "किसी को नहीं बताना" (tell no one) is the isolation
 * demand the playbook is built around, and `SECRECY_RULES` only knows the Roman negation and the
 * short "न" form, so the common "नहीं" spelling never matched.
 *
 * This module is deliberately **small and additive**. Every pattern here was checked against
 * `StageRules.kt`, `UtilityServiceRules.kt` and `DigitalArrestSignals.kt` first, and the ones already
 * covered were **not** re-added:
 *
 *  * "मैं सीबीआई अधिकारी …" - already stage 1 via `मैं` + `सीबीआई` in `AUTHORITY_RULES`;
 *  * "कस्टम्स" / "वारंट" / "गिरफ़्तार" / "गिरफ्तार" / "एफआईआर" - already stage 1/2 as bare nouns;
 *  * "खाता बंद हो जाएगा" - already stage 2 via `खाता` + `बंद`;
 *  * "गिरफ्तारी वारंट" - already `DigitalArrestSignals`' fake-paper marker;
 *  * "बिजली कट जाएगी", "बिजली बंद हो जाएगी" - already stage 2 via `बिजली` + `SERVICE_DISCONNECT`;
 *  * "ओटीपी बताइए", "ओटीपी भेज दो" - already stage 5, because the normalizer rewrites `ओटीपी` to `otp`
 *    and `TELL` already contains `बताइए` / `भेज दो`. The credential rule here therefore lists only the
 *    Devanagari **verbs `TELL` is missing** (`डालो`, `भेजिए`, `बताएं`, `दीजिए`, …), so the same
 *    sentence cannot produce two asks for one request;
 *  * "तुरंत", "आज रात", "जल्दी", "अंतिम मौका", "फौरन" - already stage 3; this file adds only the
 *    urgency words that are *not* there (`शीघ्र`, `तत्काल`, `आखिरी मौका`, `समय खत्म`);
 *  * "गोपनीय" - already stage 4.
 *
 * ## What it does add
 *
 *  1. [DEV_AUTHORITY_RULES] - the Devanagari **speaker claim**: "बैंक से बोल रहे हैं",
 *     "कस्टम्स विभाग से बोल रहे हैं", "मैं अधिकारी बोल रहा हूँ". The institution noun exists in
 *     `StageRules`; the Devanagari "से बोल रहे" / "बोल रहा" verb does not, so a claim that names the
 *     role without the Roman verb was invisible.
 *  2. [DEV_THREAT_RULES] - "पार्सल जब्त" (the parcel-seizure threat: `StageRules` has `पार्सल` but its
 *     seizure verbs are English), "बिजली काट दी जाएगी" (the `काट दी/दिया/देंगे` form, which
 *     `SERVICE_DISCONNECT` does not list - it only has `कट जाएगा`), and "खाता फ्रीज/ब्लॉक हो जाएगा"
 *     (`ACCOUNT_HARM` has only the Roman `freez`/`block` and the Devanagari `बंद`).
 *  3. [DEV_URGENCY_RULES] - the deadline words listed above. Stage 3 is weak alone, so this adds
 *     pressure vocabulary, never a verdict.
 *  4. [DEV_SECRECY_RULES] - "किसी को नहीं बताना" / "घर वालों को न बताइए" (the `नहीं` spelling), and
 *     the "फोन मत काटना" / "फोन पर रहिए" isolation pair, which `SECRECY_RULES` only has in Roman.
 *  5. [DEV_ASK_RULES] - the directional asks whose **verb** is Devanagari: "कोड बताओ",
 *     "आधार नंबर भेजो", "पैसे भेज दो", "इस नंबर पर कॉल करें" (with a personal mobile),
 *     "केवाईसी अपडेट करो" (with an unofficial link), and the credential + `डालो`/`भेजिए` family.
 *
 * ## Guards
 *
 *  * Every ask is `guarded = true`, so "पैसे मत भेजो", "कोड किसी को मत बताएं" and
 *    "ओटीपी नहीं देना" stay warnings: the engine's same-clause negation guard knows `मत` and `नहीं`.
 *  * The secrecy rule carries its own credential skip ([SKIP_CRED_ADVICE_DEV]) because
 *    `StageRules`' `SKIP_CREDENTIAL_SHARE_ADVICE` is file-private: without it,
 *    "ओटीपी किसी को न बताएं" would be filed as isolation pressure - the exact contradiction issue
 *    #114 was filed for.
 *  * The threat rule carries a protective skip ([SKIP_PROTECTIVE_DEV]) for the "नहीं होगा" /
 *    "सावधान" / "से बचें" awareness sentence a real bank sends, while still letting the conditional
 *    threat ("भुगतान नहीं किया तो खाता फ्रीज हो जाएगा") through - the skip needs the *completed*
 *    negative ("नहीं होगा"), not any negation.
 *  * `requires` gates the two asks whose phrase alone is not enough: a callback needs a real 10-digit
 *    personal mobile somewhere in the message (a bare "इस नंबर पर कॉल करें" is not a channel), and a
 *    KYC update needs an unofficial link (branch / net-banking advice carries none).
 *  * The two credential asks carry [SKIP_CRED_ASK_DEV], which suppresses the delivery-at-the-door
 *    shape (handing the delivery code to the courier at your own door is correct behaviour, report
 *    §3.4 row 5) **and** the "किसी को न/नहीं/मत" advice shape. The second half matters because the
 *    engine's `NEGATION_INSIDE` set has `मत` and `नहीं` but not the bare Devanagari `न`, so
 *    "ओटीपी किसी को न बताएं" would otherwise become an ask.
 *
 * ## Known limits (stated, not hidden)
 *
 *  * `StageRules` splits clauses on `.` `!` `?` `;` `,` and newlines but not on the danda `।`, so a
 *    negation on one side of a danda can still scope across it. Nothing here depends on that.
 *  * A Devanagari message that mixes its nouns in Roman ("KYC अपडेट करो") is caught by the existing
 *    Roman rules whenever the *verb* is Roman, and by this file only when the verb is Devanagari.
 *  * A KYC link on a plain `.com` host is still unmatched by [UNOFFICIAL_LINK] - the same conservative
 *    bound the existing KYC-link rule has, and the reason a real bank link never fires either.
 *
 * Deterministic and pure: no clock, no randomness, no I/O, no `android.*`, no new dependency. Every
 * quote still comes from the normalizer's raw-span map through [StageRules].
 */

// ---------------------------------------------------------------------------------------------
// Shared vocabulary
// ---------------------------------------------------------------------------------------------

/** Institutions a caller can claim to be. The nouns `StageRules` already knows, reused as a set. */
private const val DEV_INSTITUTION =
    "(?:बैंक|सीबीआई|पुलिस|कस्टम्स|विभाग|अधिकारी|आरबीआई|ट्राई|ईडी|नार्कोटिक्स|" +
        "प्रवर्तन निदेशालय|साइबर सेल|क्राइम ब्रांच|आयकर विभाग|मुख्यालय|हेड ऑफिस)"

/** Roles a caller can claim to hold. */
private const val DEV_ROLE =
    "(?:अधिकारी|इंस्पेक्टर|डीएसपी|कमिश्नर|मैनेजर|जांच अधिकारी|सब इंस्पेक्टर|अफसर)"

/**
 * The Devanagari **speaker** verb: "से बोल रहे हैं", "बोल रहा हूँ", "की तरफ से".
 * Deliberately not a bare "बोलना" - only the claim-of-channel forms.
 */
private const val DEV_SPEAKING_VERB =
    "(?:से बोल रहे|से बोल रहा|से बोल रही|बोल रहा|बोल रही|बोल रहे|से बात|की तरफ से|की ओर से|" +
        "की कस्टमर केयर|कस्टमर केयर से)"

/** "aapka / aapki / apna" - the reader's own thing, so a locality-wide notice is not a threat. */
private const val DEV_READER_POSSESSIVE = "(?:आपका|आपकी|आपके|आपके|अपना|अपनी|अपने)"

/** Credential nouns whose Devanagari *verb* is the part `StageRules` cannot see. */
private const val DEV_CRED_NOUN = "(?:कोड|सुरक्षा कोड|वेरिफिकेशन कोड|सत्यापन कोड|वन टाइम कोड|कोड नंबर)"

/**
 * Every Devanagari give/tell imperative. Used by the `कोड`, Aadhaar and money rules, whose nouns are
 * absent from `OTP_WORD` / the personal-detail rule, so no phrase can double-fire with `StageRules`.
 */
private const val DEV_TELL_VERB =
    "(?:बताओ|बताइए|बता दो|बता दीजिए|बताएं|बताइएगा|बताओगे|भेजो|भेज दो|भेजिए|भेज दीजिए|भेज दें|" +
        "डालो|डालिए|डाल दो|लिखो|लिखिए|बोल दो|बोलिए|दीजिए|दे दो|पढ़कर सुनाओ|कन्फर्म करो|" +
        "वेरिफाई करो|पुष्टि करो|शेयर कर दो|फॉरवर्ड करो|अपलोड करो)"

/**
 * Only the Devanagari imperatives `TELL` in `StageRules` does **not** already contain
 * (`TELL` has `बताओ`, `बता दो`, `बताइए`, `भेजो`, `भेज दो`, `शेयर करो`). Keeping this list separate
 * is what stops "otp डालो" from producing two ask cards for one request.
 */
private const val DEV_TELL_VERB_MISSING_EXTRA = "(?:डालें|डाल दीजें|भेजें)"
private const val DEV_TELL_VERB_MISSING =
    "(?:बताएं|बताइएगा|बताओगे|बता दीजिए|भेजिए|भेज दीजिए|भेज दें|भेज देना|डालो|डालिए|डाल दो|" +
        "डाल दीजिए|लिखो|लिखिए|बोल दो|बोलिए|दीजिए|दे दो|पढ़कर सुनाओ|कन्फर्म करो|वेरिफाई करो|" +
        "पुष्टि करो|शेयर कर दो|फॉरवर्ड करो)"

/** Identity nouns whose submission is the personal-data ask. */
private const val DEV_ID_NOUN =
    "(?:आधार|आधार कार्ड|पैन|पैन कार्ड|पैन नंबर|कार्ड नंबर|खाता संख्या|खाता नंबर|बैंक खाता|" +
        "जन्म तिथि|एटीएम कार्ड)"

/** Money nouns and the Devanagari verbs that move them towards the sender. */
private const val DEV_MONEY_NOUN = "(?:पैसे|पैसा|रुपये|रुपए|रकम|धन|भुगतान|पेमेंट|अमाउंट)"
private const val DEV_MONEY_NOUN_EXTRA = "(?:शुल्क|फीस|जुर्माना|₹\\s?\\d{3,})"
private const val DEV_SEND_MONEY =
    "(?:भेजो|भेज दो|भेजिए|भेज दें|भेज दीजिए|ट्रांसफर करो|ट्रांसफर कर दो|जमा करो|जमा कर दो|" +
        "दे दो|दीजिए|वापस करो|वापस भेजो|लौटाओ)"
private const val DEV_SEND_MONEY_EXTRA = "(?:भेजें|भेज दीजिए|जमा करें|जमा कर दें|डालें|भुगतान करें)"

/** The callback imperative in Devanagari: "कॉल करें", "फोन कीजिए", "संपर्क करें". */
private const val DEV_CALLBACK_VERB =
    "(?:कॉल|फोन|संपर्क|काल)\\s*(?:करें|करो|कीजिए|कर दें|कर दीजिए|करना)"

/** A 10-digit Indian personal mobile, compiled once for the callback precondition. */
private val DEV_PERSONAL_MOBILE_RX: Regex = rx(INDIAN_PERSONAL_MOBILE)

/**
 * The shortener / throwaway-TLD link from `UtilityServiceRules`, compiled once for the KYC
 * precondition. Reused rather than re-written so a link that the existing KYC rule treats as
 * unofficial is exactly the same set of links this rule demands.
 */
private val UNOFFICIAL_LINK_RX: Regex = rx(UNOFFICIAL_LINK)

/**
 * The protective half of a Devanagari threat: the sentence says the loss will **not** happen, or
 * warns the reader. Needs the completed negative (`नहीं होगा`) so the conditional threat
 * "भुगतान नहीं किया तो खाता फ्रीज हो जाएगा" is still a threat.
 */
private val SKIP_PROTECTIVE_DEV: Regex = rx(
    """(?:नहीं होगा|नहीं होगी|नहीं कटेगा|नहीं कटेगी|नहीं लगेगा|सावधान|सतर्क|से बचें|से बचिए|""" +
        """सुरक्षित रहेगा|सुरक्षित रहें)""",
)

/**
 * Credential/identity-scoped sharing advice in Devanagari: a secret or identity noun immediately
 * before "किसी को … न/नहीं/मत". `StageRules`' equivalent skip is file-private, so this is the local
 * twin - and it is needed here for a second reason: the engine's `NEGATION_INSIDE` set knows `मत` and
 * `नहीं` but **not** the bare Devanagari `न`, so "ओटीपी किसी को न बताएं" would otherwise be filed as
 * an ask. That shape is the bank's own safety line, not a request (issue #114).
 */
private const val DEV_CRED_ADVICE =
    """(?:\b(?:otp|pin|cvv|password|passcode|कोड|पिन|सीवीवी|पासवर्ड|आधार|पैन|कार्ड|खाता|यूपीआई)\b""" +
        """[^।.!?\n]{0,20}?\b(?:किसी को|किसी भी को|किसी के साथ|घर वालों को)\b\s*(?:नहीं|न|मत)\b)"""

/**
 * Delivery-at-the-door. Handing the delivery code to the courier standing at your door is correct
 * behaviour (report §3.4 row 5), so that shape is not an ask.
 */
private const val DEV_DELIVERY_DOOR =
    """(?:डिलीवरी\s*(?:एजेंट|वाले|वाला|बॉय|पार्टनर)|पार्सल\s*वाले|कूरियर\s*वाले|""" +
        """दरवाज़े पर|दरवाजे पर|डिलीवरी के समय)"""

/** The secrecy-stage skip: credential advice only, so isolation is never hidden by a delivery note. */
private val SKIP_CRED_ADVICE_DEV: Regex = rx(DEV_CRED_ADVICE)

/** The ask-side skip: credential advice **or** an in-person delivery handover. */
private val SKIP_CRED_ASK_DEV: Regex = rx("""(?:$DEV_CRED_ADVICE|$DEV_DELIVERY_DOOR)""")

/**
 * The protective "don't send it to anyone" for money. Narrow on purpose: it needs the
 * `किसी को` + negation + send verb in that order, so "पैसे मुझे भेजो" is untouched.
 */
private val SKIP_MONEY_PROTECTIVE_DEV: Regex = rx(
    """(?:किसी को|किसी भी को|अजनबी को)\s*(?:नहीं|न|मत)\s*""" +
        """(?:भेजो|भेजना|भेजें|देना|दो|दें)\b""",
)

// ---------------------------------------------------------------------------------------------
// Stage 1 - the Devanagari speaker claim. Alone: LOW/WEAK.
// ---------------------------------------------------------------------------------------------

internal val DEV_AUTHORITY_RULES: List<Rule> = listOf(
    Rule(
        titleHinglish = "Devanagari mein sanstha ka dawa",
        detail = "Message Hindi mein bank, CBI, police ya customs ki taraf se bolne ka dawa kar " +
            "raha hai ('बैंक से बोल रहे हैं'). Asli sanstha OTP ya paise phone par nahi maangti; " +
            "akele yeh dawa scam ka saboot nahi hai.",
        severity = Severity.LOW,
        strength = Strength.WEAK,
        patterns = listOf(
            // "मैं सीबीआई अधिकारी बोल रहा हूँ" / "हम बैंक से बोल रहे हैं"
            rx("""\b(?:मैं|हम)\b[^।.!?\n]{0,30}?\b$DEV_INSTITUTION\b[^।.!?\n]{0,24}?\b$DEV_SPEAKING_VERB\b"""),
            // "बैंक से बोल रहे हैं" (no pronoun at all - the everyday phone opening)
            rx("""\b(?:बैंक|सीबीआई|पुलिस|कस्टम्स|विभाग|आरबीआई|ट्राई)\b[^।.!?\n]{0,18}?\b$DEV_SPEAKING_VERB\b"""),
            // "मैं अधिकारी बोल रहा हूँ"
            rx("""\b(?:मैं|हम)\b[^।.!?\n]{0,20}?\b$DEV_ROLE\b\s*(?:हूँ|हूं|बोल रहा|बोल रही)\b"""),
        ),
    ),
)

// ---------------------------------------------------------------------------------------------
// Stage 2 - the Devanagari threats `StageRules` cannot see. Alone: LOW/WEAK.
// ---------------------------------------------------------------------------------------------

internal val DEV_THREAT_RULES: List<Rule> = listOf(
    Rule(
        titleHinglish = "Devanagari mein parcel/khatre ki dhamki",
        detail = "Message Hindi mein parcel ya courier jabt hone, bijli/connection kaatne ya " +
            "khata freeze/block hone ki dhamki de raha hai. Yeh darr (stage 2) hai; akela " +
            "CRITICAL nahi banta.",
        severity = Severity.LOW,
        strength = Strength.WEAK,
        patterns = listOf(
            // "आपका पार्सल जब्त हो गया है" - StageRules knows पार्सल but not जब्त
            rx(
                """\b(?:पार्सल|पैकेट|कूरियर|डाक|शिपमेंट)\b[^।.!?\n]{0,40}?""" +
                    """\b(?:जब्त|ज़ब्त|रोका गया|रोक लिया|पकड़ा गया|पकड़ लिया|सीज|ड्रग्स|""" +
                    """नशीले पदार्थ|अवैध सामान|कस्टम में)\b""",
            ),
            // "बिजली काट दी जाएगी" - the काट दी/दिया/देंगे form SERVICE_DISCONNECT lacks
            rx(
                """\b(?:बिजली|गैस|पानी|कनेक्शन|मीटर|बिल|एलपीजी|इंटरनेट|ब्रॉडबैंड|लैंडलाइन)\b""" +
                    """[^।.!?\n]{0,30}?\b(?:काट दी जाएगी|काट दिया जाएगा|काट दी जाएगा|काट देंगे|""" +
                    """काट दिया|बंद कर दिया जाएगा|रुक जाएगी)\b""",
            ),
            // "आपका खाता फ्रीज/ब्लॉक हो जाएगा" - ACCOUNT_HARM has only Roman freez/block + बंद
            rx(
                """\b$DEV_READER_POSSESSIVE\b[^।.!?\n]{0,24}?""" +
                    """\b(?:खाता|खाते|एटीएम|कार्ड|सिम|केवाईसी|केवायसी|पैन|आधार|वॉलेट|यूपीआई)\b""" +
                    """[^।.!?\n]{0,24}?\b(?:फ्रीज|फ़्रीज़|सीज|निलंबित|सस्पेंड|डीएक्टिवेट|ब्लॉक)\b""",
            ),
            // "भुगतान नहीं किया तो खाता फ्रीज हो जाएगा" - the conditional threat names no possessive
            rx(
                """\b(?:नहीं किया|नहीं करने|नहीं दिया|नहीं भरा|नहीं भुगतान|नहीं जमा)\b""" +
                    """[^।.!?\n]{0,24}?\b(?:खाता|खाते|कार्ड|सिम|केवाईसी|केवायसी|पैन|वॉलेट|यूपीआई)\b""" +
                    """[^।.!?\n]{0,20}?\b(?:फ्रीज|फ़्रीज़|ब्लॉक|सीज|निलंबित|सस्पेंड|डीएक्टिवेट)\b""",
            ),
        ),
        skip = SKIP_PROTECTIVE_DEV,
    ),
)

// ---------------------------------------------------------------------------------------------
// Stage 3 - the Devanagari deadline words that are not already in URGENCY_RULES. LOW/WEAK.
// ---------------------------------------------------------------------------------------------

internal val DEV_URGENCY_RULES: List<Rule> = listOf(
    Rule(
        titleHinglish = "Devanagari mein jaldbaazi",
        detail = "Message Hindi mein turant act karne ka dabaav banata hai ('शीघ्र', 'आखिरी " +
            "मौका', 'समय खत्म'). Akele urgency scam ka saboot nahi hai.",
        severity = Severity.LOW,
        strength = Strength.WEAK,
        patterns = listOf(
            // urgency words URGENCY_RULES does not list (it has तुरंत / फौरन / जल्दी / अंतिम मौका)
            rx("""\b(?:शीघ्र|शीघ्रातिशीघ्र|तत्काल|अविलंब|बिना देर|देर मत करो|देर मत करें|देर न करें)\b"""),
            // "आखिरी मौका" / "अंतिम चेतावनी" (URGENCY_RULES has the Roman 'aakhri mauka' only)
            rx("""\b(?:आखिरी|आखरी|अंतिम)\s+(?:मौका|चेतावनी|चांस|अवसर)\b"""),
            // "समय खत्म हो गया है"
            rx("""\b(?:समय|समय सीमा)\s+(?:खत्म|समाप्त|निकल|बीत)\w*\b"""),
        ),
    ),
)

// ---------------------------------------------------------------------------------------------
// Stage 4 - isolation in Devanagari. Alone: MEDIUM/MODERATE.
// ---------------------------------------------------------------------------------------------

internal val DEV_SECRECY_RULES: List<Rule> = listOf(
    Rule(
        titleHinglish = "Devanagari mein kisi ko batane se roka",
        detail = "Message Hindi mein kisi ko, ghar walon ko ya parivar ko batane se rok raha " +
            "hai. Asli bank ya police aapse ghar walon ko chhupane ko nahi kehti.",
        severity = Severity.MEDIUM,
        strength = Strength.MODERATE,
        patterns = listOf(
            // "किसी को नहीं बताना" - the नहीं spelling SECRECY_RULES misses (it has only the short न)
            rx(
                """\b(?:किसी को|किसी भी को|घर वालों को|घर वालो को|परिवार को|पत्नी को|पति को|""" +
                    """मम्मी को|पापा को|बच्चों को|अपने लोगों को)\b[^।.!?\n]{0,16}?""" +
                    """\b(?:नहीं|न|मत)\s+(?:बता|बताना|बताएं|बताइए|बताने|बता देना|कहना|""" +
                    """शेयर|भेजना|दिखाना)\w*\b""",
            ),
            // "फोन मत काटना" / "कॉल मत काटिए" - SECRECY_RULES has this only in Roman
            rx(
                """\b(?:फोन|फ़ोन|कॉल|लाइन|मोबाइल)\b[^।.!?\n]{0,20}?""" +
                    """\b(?:मत|नहीं|न)\s*(?:काटना|काटो|काटिए|रखना|रखो|रखिए|बंद मत)\b""",
            ),
            // "फोन पर रहिए" / "लाइन पर बने रहिए" - the isolation hold, Roman twin of 'call pe raho'
            rx(
                """\b(?:फोन|फ़ोन|कॉल|लाइन)\b[^।.!?\n]{0,12}?\b(?:पर|पे)\b[^।.!?\n]{0,10}?""" +
                    """\b(?:रहो|रहिए|रहना|बने रहो|बने रहिए)\b""",
            ),
        ),
        // Issue #114: "ओटीपी किसी को न बताएं" is the bank's own safety line, not isolation.
        skip = SKIP_CRED_ADVICE_DEV,
    ),
)

// ---------------------------------------------------------------------------------------------
// Stage 5 - the Devanagari asks. STRONG + directional, each one negation-guarded.
// ---------------------------------------------------------------------------------------------

internal val DEV_ASK_RULES: List<Rule> = listOf(
    Rule(
        titleHinglish = "कोड batane ko kaha gaya",
        detail = "Message Hindi mein 'कोड' batane ya bhejne ko keh raha hai. Verification code " +
            "kisi ko batana bank, delivery ya kisi bhi asli kaam ka tareeka nahi hai.",
        severity = Severity.HIGH,
        strength = Strength.STRONG,
        patterns = listOf(
            // "कोड बताओ" / "सुरक्षा कोड भेज दो" - 'कोड' is absent from OTP_WORD, so this is new
            rx("""\b$DEV_CRED_NOUN\b[^।.!?\n]{0,26}?\b$DEV_TELL_VERB\b"""),
            // "बताओ कोड क्या है"
            rx("""\b$DEV_TELL_VERB\b[^।.!?\n]{0,20}?\b$DEV_CRED_NOUN\b"""),
        ),
        askType = AskType.OTP_PIN,
        guarded = true,
        skip = SKIP_CRED_ASK_DEV,
    ),
    Rule(
        titleHinglish = "OTP/PIN Devanagari mein daalne ko kaha",
        detail = "Message Hindi mein OTP, PIN, CVV ya password daalne/bhejne ko keh raha hai. " +
            "Bank kabhi bhi OTP ya PIN phone par nahi maangta.",
        severity = Severity.HIGH,
        strength = Strength.STRONG,
        patterns = listOf(
            // "otp डालो" / "पिन भेजिए" - only the verbs TELL is missing, so no double ask
            rx("""\b(?:otp|pin|mpin|cvv|password|passcode)\b[^।.!?\n]{0,30}?\b(?:$DEV_TELL_VERB_MISSING|$DEV_TELL_VERB_MISSING_EXTRA)\b"""),
            // "डालो otp"
            rx("""\b(?:$DEV_TELL_VERB_MISSING|$DEV_TELL_VERB_MISSING_EXTRA)\b[^।.!?\n]{0,20}?\b(?:otp|pin|mpin|cvv|password)\b"""),
        ),
        askType = AskType.OTP_PIN,
        guarded = true,
        skip = SKIP_CRED_ASK_DEV,
    ),
    Rule(
        titleHinglish = "आधार/detail Devanagari mein maangi gayi",
        detail = "Message Hindi mein Aadhaar, PAN, card ya khata number bhejne ko keh raha hai. " +
            "Yeh data identity chori ke liye use hota hai.",
        severity = Severity.HIGH,
        strength = Strength.STRONG,
        patterns = listOf(
            // "आधार नंबर भेजो" - the personal-detail rule's verbs are Roman only
            rx("""\b$DEV_ID_NOUN\b[^।.!?\n]{0,26}?\b$DEV_TELL_VERB\b"""),
            // "भेजो आधार कार्ड की फोटो"
            rx("""\b$DEV_TELL_VERB\b[^।.!?\n]{0,22}?\b$DEV_ID_NOUN\b"""),
        ),
        askType = AskType.PERSONAL_INFO,
        guarded = true,
        skip = SKIP_CRED_ASK_DEV,
    ),
    Rule(
        titleHinglish = "पैसे bhejne ko kaha (Devanagari)",
        detail = "Message Hindi mein paise bhejne, jama karne ya wapas karne ko keh raha hai. " +
            "Asli bank, court ya courier message par paise nahi mangta.",
        severity = Severity.HIGH,
        strength = Strength.STRONG,
        patterns = listOf(
            // "पैसे भेज दो" - MONEY_CUE/SEND_MONEY in StageRules are Roman only
            rx("""(?:\b$DEV_MONEY_NOUN\b|$DEV_MONEY_NOUN_EXTRA)[^।.!?\n]{0,48}?\b(?:$DEV_SEND_MONEY|$DEV_SEND_MONEY_EXTRA)\b"""),
            // "भेज दो 5000"
            rx("""\b(?:$DEV_SEND_MONEY|$DEV_SEND_MONEY_EXTRA)\b[^।.!?\n]{0,48}?(?:\b$DEV_MONEY_NOUN\b|$DEV_MONEY_NOUN_EXTRA)"""),
        ),
        askType = AskType.MONEY_TRANSFER,
        guarded = true,
        skip = SKIP_MONEY_PROTECTIVE_DEV,
    ),
    Rule(
        titleHinglish = "Personal number par call karne ko kaha (Devanagari)",
        detail = "Message Hindi mein ek personal mobile number par call karne ko keh raha hai. " +
            "Asli bank ya department toll-free number se message karta hai, personal mobile se " +
            "nahi - aur call par hi OTP ya paise maangta hai.",
        severity = Severity.HIGH,
        strength = Strength.STRONG,
        patterns = listOf(
            // "इस नंबर पर कॉल करें 9876543210"
            rx(
                """\b(?:इस|यह|उस)\s+(?:नंबर|नम्बर|मोबाइल नंबर|मोबाइल)\b[^।.!?\n]{0,12}?""" +
                    """\b(?:पर|पे)\b[^।.!?\n]{0,10}?\b$DEV_CALLBACK_VERB\b""",
            ),
            // "कॉल करें इस नंबर पर"
            rx("""\b$DEV_CALLBACK_VERB\b[^।.!?\n]{0,16}?\b(?:इस|यह|उस)\s+(?:नंबर|नम्बर)\b"""),
        ),
        askType = AskType.CALLBACK,
        guarded = true,
        skip = SKIP_OFFICIAL_NUMBER,
        requires = DEV_PERSONAL_MOBILE_RX,
    ),
    Rule(
        titleHinglish = "KYC link par update karne ko kaha (Devanagari)",
        detail = "Message Hindi mein KYC ya detail ek short link ya anjaan website par " +
            "update/verify karne ko keh raha hai. Asli bank aapko apni app, branch ya net " +
            "banking par bhejta hai.",
        severity = Severity.HIGH,
        strength = Strength.STRONG,
        patterns = listOf(
            // "केवाईसी अपडेट करो https://bit.ly/..."
            rx(
                """\b(?:केवाईसी|केवायसी|के वाई सी|पैन अपडेट|खाता अपडेट|आधार अपडेट|""" +
                    """केवाईसी वेरिफिकेशन)\b[^।.!?\n]{0,30}?""" +
                    """\b(?:अपडेट|वेरिफाई|सत्यापित|पूरा करो|क्लिक करो|लिंक पर क्लिक|करो|करें)\b""",
            ),
            // "लिंक पर क्लिक करके केवाईसी पूरी करो"
            rx(
                """\b(?:लिंक पर क्लिक|क्लिक करो|अपडेट करो)\b[^।.!?\n]{0,24}?""" +
                    """\b(?:केवाईसी|केवायसी|के वाई सी)\b""",
            ),
        ),
        askType = AskType.PERSONAL_INFO,
        guarded = true,
        requires = UNOFFICIAL_LINK_RX,
    ),
)
