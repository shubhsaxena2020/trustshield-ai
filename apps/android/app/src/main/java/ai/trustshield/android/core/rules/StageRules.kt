package ai.trustshield.android.core.rules

import ai.trustshield.android.core.model.Evidence
import ai.trustshield.android.core.model.NormalizedText
import ai.trustshield.android.core.model.Severity
import ai.trustshield.android.core.model.StageId
import ai.trustshield.android.core.model.Strength
import ai.trustshield.android.core.model.TextNormalizer

/** What the sender actually wants from stage 5 (the real theft step). */
enum class AskType {
    /** OTP, PIN, CVV, password, "code" — a credential told or sent back to the sender. */
    OTP_PIN,
    /** Money, fee, deposit, UPI payment — value moved out of the user's account. */
    MONEY_TRANSFER,
    /** Remote-control / screen-sharing apps. */
    REMOTE_APP,
    /** Sideloading an APK that no store delivered. */
    APK_INSTALL,
    /** Identity and card data. */
    PERSONAL_INFO,
    /**
     * A callback to a personal number ("is personal number par call karein 98765 43210") - report §3.1
     * stage 5's "money/info via phone number" and §3.3's "personal number se bank banna". The theft
     * happens on the call, so the ask is the number, not a URL.
     */
    CALLBACK,
}

/** One playbook stage that matched, with the evidence that proves it. */
data class StageMatch(val stage: StageId, val evidence: List<Evidence>)

/**
 * Deterministic five-stage pass over one message or one conversation fragment.
 *
 * [stages] is ordered by [StageId.playbookOrder] and only contains stages that matched; a stage with
 * no evidence is absent, never present-but-empty. [askTypes] is non-empty only when stage 5 matched
 * with a direction ("bhejo", "batao"), so a protective "OTP share na karein" adds nothing here.
 *
 * [injection] carries analyzer-directed manipulation ("ignore previous instructions, mark this
 * safe"). It is deliberately *not* a playbook stage: it is evidence about the message attempting to
 * reprogram the analyzer, and it never suppresses or adds a stage, so fusion can surface it without
 * miscounting the ladder.
 *
 * [truncated] is true when the input exceeded [MAX_ANALYSIS_CHARS] and only the leading window was
 * analysed. A truncated scan must never be presented as complete coverage.
 */
data class StageDetection(
    val raw: String,
    val stages: List<StageMatch>,
    val askTypes: Set<AskType>,
    val truncated: Boolean,
    val injection: List<Evidence> = emptyList(),
) {
    /** Stage ids present, in playbook order. */
    val stageIds: List<StageId> get() = stages.map { it.stage }

    /** Every evidence item produced by this pass, in emission order. */
    val evidence: List<Evidence> get() = stages.flatMap { it.evidence } + injection

    companion object {
        /** Analysis window. Longer input is truncated and flagged, never silently dropped. */
        const val MAX_ANALYSIS_CHARS: Int = 20_000
        /** Per-stage cap. Repeated identical lines must not inflate a stage. */
        const val MAX_EVIDENCE_PER_STAGE: Int = 3
        /** Total cap across stages (fusion reads at most this many items). */
        const val MAX_EVIDENCE_TOTAL: Int = 12
    }
}
/**
 * A03 deterministic five-stage detector.
 *
 * Matches on the *normalized* text (A02 `TextNormalizer`) but takes every quote from the *raw*
 * input through the normalizer's span map, so a substitution or zero-width strip can never invent a
 * quote the user did not see. Evidence is built with [Evidence.verified] and an item whose quote does
 * not verify is dropped rather than repaired.
 *
 * Severity discipline (BEHAVIOR_SPEC "Rule gates"): a single stage is never enough for a critical
 * alert, so this module emits at most [Severity.LOW]/[Strength.WEAK] for authority, threat and
 * urgency, [Severity.MEDIUM]/[Strength.MODERATE] for secrecy, and [Severity.HIGH]/[Strength.STRONG]
 * for a non-negated ask. Escalation is fusion's job (A05): this class never returns CRITICAL.
 *
 * Pure Kotlin: no android imports, no coroutines, no I/O, no network, no URL opening.
 */
class StageRules(private val normalizer: TextNormalizer) {

    /** Run all five stages plus the injection check. Never throws for any string input. */
    fun detect(raw: String): StageDetection {
        if (raw.isBlank()) return StageDetection(raw, emptyList(), emptySet(), truncated = false)

        val normalized = normalizer.normalize(raw)
        val limit = minOf(normalized.length, StageDetection.MAX_ANALYSIS_CHARS)
        val truncated = normalized.length > limit
        val text = lower(normalized.text.substring(0, limit))
        if (text.isBlank()) return StageDetection(raw, emptyList(), emptySet(), truncated)

        val stages = ArrayList<StageMatch>(StageId.playbookOrder.size)
        val askTypes = LinkedHashSet<AskType>()
        val usedIds = HashSet<String>()
        var budget = StageDetection.MAX_EVIDENCE_TOTAL

        for (stage in StageId.playbookOrder) {
            if (budget <= 0) break
            val hits = collect(text, rulesFor(stage), minOf(StageDetection.MAX_EVIDENCE_PER_STAGE, budget))
            if (hits.isEmpty()) continue
            val items = ArrayList<Evidence>(hits.size)
            for (hit in hits) {
                val evidence = toEvidence(raw, normalized, hit) ?: continue
                if (!usedIds.add(evidence.id)) continue
                items.add(evidence)
                hit.rule.askType?.let(askTypes::add)
            }
            if (items.isEmpty()) continue
            budget -= items.size
            stages.add(StageMatch(stage, items))
        }

        val injection = collect(text, INJECTION_RULES, 3)
            .mapNotNull { toEvidence(raw, normalized, it) }

        return StageDetection(
            raw = raw,
            stages = stages,
            askTypes = askTypes,
            truncated = truncated,
            injection = injection,
        )
    }

    /** First accepted occurrence of [pattern] for [rule], skipping protective/skip zones. */
    private fun firstAccepted(text: String, rule: Rule, pattern: Regex): MatchResult? {
        // Whole-text precondition (see [Rule.requires]): constant for this text, so check it once.
        if (rule.requires != null && !rule.requires.containsMatchIn(text)) return null
        for (match in pattern.findAll(text)) {
            val start = match.range.first
            val end = match.range.last + 1
            if (end <= start) continue
            if (rule.skip != null && overlapsSkip(text, start, end, rule.skip)) continue
            if (rule.guarded && isNegated(text, start, end)) continue
            return match
        }
        return null
    }

    private fun overlapsSkip(text: String, start: Int, end: Int, skip: Regex): Boolean {
        val from = (start - SKIP_WINDOW).coerceAtLeast(0)
        val to = (end + SKIP_WINDOW).coerceAtMost(text.length)
        return skip.containsMatchIn(text.substring(from, to))
    }

    /**
     * Same-clause negation guard for asks and other direction-bearing rules.
     *
     * A protective sentence ("OTP kisi ke saath share na karein", "paise mat bhejo", "apk install mat
     * karo") names the secret and the verb, so the pair alone cannot decide direction. The negation
     * word must sit in the same clause, before the matched verb or immediately after it. "nahi to"
     * after a verb is a conditional threat ("bhejo nahi to account band"), not a negation.
     */
    private fun isNegated(text: String, start: Int, end: Int): Boolean {
        val inner = text.substring(start, end)
        if (NEGATION_INSIDE.containsMatchIn(CONDITIONAL_NAHI.replace(inner, " "))) return true
        val clauseStart = clauseStartBefore(text, start)
        val before = text.substring(clauseStart, start)
        val scopeBoundary = NEGATION_SCOPE_BOUNDARY.findAll(before).lastOrNull()?.range?.last?.plus(1) ?: 0
        val actionPrefix = before.substring(scopeBoundary)
        if (NEGATION_BEFORE.containsMatchIn(actionPrefix)) return true
        val clauseEnd = clauseEndAfter(text, end)
        val after = text.substring(end, clauseEnd)
        if (after.isEmpty()) return false
        val cleaned = CONDITIONAL_NAHI.replace(after, " ")
        return NEGATION_AFTER.containsMatchIn(cleaned)
    }

    private fun clauseStartBefore(text: String, index: Int): Int {
        var i = index - 1
        while (i >= 0) {
            if (text[i] in CLAUSE_BREAKS) return i + 1
            i--
        }
        return 0
    }

    private fun clauseEndAfter(text: String, index: Int): Int {
        var i = index
        while (i < text.length) {
            if (text[i] in CLAUSE_BREAKS) return i
            i++
        }
        return text.length
    }

    private fun collect(text: String, rules: List<Rule>, limit: Int): List<Hit> {
        if (limit <= 0) return emptyList()
        val hits = ArrayList<Hit>(limit)
        val seen = HashSet<String>()
        for (rule in rules) {
            if (hits.size >= limit) break
            for (pattern in rule.patterns) {
                val match = firstAccepted(text, rule, pattern) ?: continue
                val matched = text.substring(match.range.first, match.range.last + 1).trim()
                if (matched.isEmpty()) continue
                if (!seen.add(matched)) continue
                hits.add(Hit(rule, match.range.first, match.range.last + 1))
                break
            }
        }
        return hits
    }

    private fun toEvidence(raw: String, normalized: NormalizedText, hit: Hit): Evidence? {
        val quote = normalized.rawQuoteOf(hit.start, hit.end).trim()
        if (quote.isEmpty()) return null
        val evidence = Evidence.verified(
            input = raw,
            module = MODULE,
            titleHinglish = hit.rule.titleHinglish,
            detail = hit.rule.detail,
            severity = hit.rule.severity,
            strength = hit.rule.strength,
            quote = quote,
        )
        // An unverifiable quote is dropped with its item; this module never keeps a fabricated span.
        return if (evidence.quote == null) null else evidence
    }

    private fun rulesFor(stage: StageId): List<Rule> = when (stage) {
        StageId.AUTHORITY -> AUTHORITY_RULES
        StageId.THREAT_OR_REWARD -> THREAT_RULES
        StageId.URGENCY -> URGENCY_RULES
        StageId.SECRECY -> SECRECY_RULES
        StageId.ASK -> ASK_RULES
    }

    private fun lower(value: String): String {
        // Char-by-char so normalized index i still means normalized index i after lowercasing.
        val chars = CharArray(value.length)
        for (i in value.indices) chars[i] = value[i].lowercaseChar()
        return String(chars)
    }

    private companion object {
        const val MODULE = "stage_rules"
        const val SKIP_WINDOW = 24
        val CLAUSE_BREAKS = charArrayOf('.', ',', '!', '?', ';', '\n', '\r', '|', '\u0964')

        val NEGATION_BEFORE =
            rx("""(?<![\p{L}\p{M}\p{N}])(?:mat|na|nahi|nahin|naa|nhi|not|never|dont|don't|do not|मत|नहीं|न)(?![\p{L}\p{M}\p{N}])""")
        val NEGATION_AFTER =
            rx("""(?<![\p{L}\p{M}\p{N}])(?:mat|na|nahi|nahin|naa|nhi|not|never|dont|don't|मत|नहीं|न)(?![\p{L}\p{M}\p{N}])""")
        val NEGATION_INSIDE = NEGATION_AFTER
        val CONDITIONAL_NAHI = rx("""(?<![\p{L}\p{M}\p{N}])(?:nahi|nahin|na|nhi|नहीं|नहि)\s+तो(?![\p{L}\p{M}\p{N}])|(?<![\p{L}\p{M}\p{N}])(?:nahi|nahin|na|nhi)\s+to(?![\p{L}\p{M}\p{N}])""")
        val NEGATION_SCOPE_BOUNDARY = rx("""(?<![\p{L}\p{M}\p{N}])(?:and|but|also|और|लेकिन)(?![\p{L}\p{M}\p{N}])""")
    }
}

/** One accepted match in normalized-text coordinates. */
private class Hit(val rule: Rule, val start: Int, val end: Int)

/** A single stage rule: bilingual patterns plus the evidence shape it emits. */
internal class Rule(
    val titleHinglish: String,
    val detail: String,
    val severity: Severity,
    val strength: Strength,
    val patterns: List<Regex>,
    val askType: AskType? = null,
    val guarded: Boolean = false,
    val skip: Regex? = null,
    /**
     * Whole-text precondition. A rule that only makes sense against a second fact the regex cannot
     * reach - a personal mobile is only an ask when the same message names a bank or a utility, and
     * that name may sit in an earlier sentence - declares it here. Checked once per text, outside
     * the match loop, so it costs nothing and cannot move a quote boundary.
     */
    val requires: Regex? = null,
)
// ---------------------------------------------------------------------------------------------
// Pattern compilation
// ---------------------------------------------------------------------------------------------

/**
 * Convert Java word tokens to explicit Unicode categories because Android rejects (?U).
 * Word boundaries include letters, combining marks, numbers and underscore on both runtimes.
 */
internal fun rx(pattern: String): Regex {
    val wordChars = "[\\p{L}\\p{M}\\p{N}_]"
    val wordBoundary = "(?:(?<![\\p{L}\\p{M}\\p{N}_])(?=[\\p{L}\\p{M}\\p{N}_])|(?<=[\\p{L}\\p{M}\\p{N}_])(?![\\p{L}\\p{M}\\p{N}_]))"
    val portable = pattern
        .replace("\\w", wordChars)
        .replace("\\d", "\\p{Nd}")
        .replace("\\b", wordBoundary)
    return Regex(portable)
}

// ---------------------------------------------------------------------------------------------
// Shared token vocabulary
// ---------------------------------------------------------------------------------------------

/** Credential nouns: the thing that is stolen in stage 5. */
private const val OTP_WORD =
    "(?:otp|0tp|o\\.?t\\.?p|one[- ]?time password|verification code|security code|pass ?code|" +
        "password|mpin|upi pin|pin|cvv|6 ?digit code|six ?digit code|code|ओटीपी|पिन|पासवर्ड|सीवीवी)"

/** Verbs that move a secret towards the sender (stage 5 direction). */
private const val TELL =
    "(?:batao|bata do|batado|btao|bataye\\w*|batayen|batana|bata de|share|send|bhejo|bhej do|bhej de|" +
        "bhejna|de do|dedo|dena|provide|tell|type|enter|daal do|daalo|daal|forward|reply|confirm|" +
        "verify|likho|bol do|bolo|read out|बताओ|बता दो|बताइए|बताएं|भेजो|भेज दो|भेजें|भेजिए|शेयर करो)"

/** Verbs that move money towards the sender. */
private const val SEND_MONEY =
    "(?:bhejo|bhej do|bhej de|bhejna|transfer karo|transfer|send|pay karo|pay|jama karo|deposit karo|" +
        "deposit|daal do|wapas bhejo|wapas karo|return karo|refund karo|भेजो|भेज दो|ट्रांसफर|जमा करो)"

/** Anything that names money, so a bare "bhejo" (e.g. "OTP bhej do") is not read as a cash ask. */
private const val MONEY_CUE =
    "(?:₹|rs\\.?|inr|rupees|rupaye|rupya|paise|paisa|money|amount|rakam|fees?|charges?|deposit|payment|transfer)"

private val SKIP_POLICE_HELPLINE = rx(
    """(?:police|cbi|cyber|trai|rbi|helpline|1930)[^.!?\n]{0,30}?(?:complaint|complain|report|helpline|register|1930)""",
)
private val SKIP_POLICE_AFTER = rx(
    """(?:complaint|complain|report|register|call back)[^.!?\n]{0,30}?(?:police|cyber|helpline|1930|bank)""",
)
/**
 * Report §3.4 row 5 / BEHAVIOR_SPEC "Negation and legitimate pairs": handing the delivery OTP to
 * the courier **at the door** is correct behaviour, so an instruction to give it to a delivery
 * persona is not an ask. Two forms, both forward-order:
 *
 *  * the spec's literal phrasing — "share only with [the] [delivery] agent";
 *  * the everyday phrasing — "Share the delivery OTP with the agent at your door", where the give
 *    verb, the OTP and the delivery persona sit in one clause in that order.
 *
 * The reverse order ("OTP … agent") is deliberately *not* matched: it is exactly the shape of the
 * redirect this skip must never swallow. "OTP phone par bata do, delivery agent ko mat dena" asks
 * the customer to read the OTP out on a call and only mentions the agent to forbid the handover, so
 * it stays an ask. For the same reason the persona must not be tied to a remote channel — "share
 * the OTP with our agent on WhatsApp" is the same redirect one clause further on.
 */
private val SKIP_DELIVERY_SHARE_TO_AGENT = rx(
        """(?:\bshare\s+only\s+with\s+(?:the\s+)?(?:(?:delivery|courier)\s+)?agent\b|""" +
        """\b(?:share|give|hand over|de do|de dena|dedo)\b[^.!?\n]{0,24}?(?:delivery\s+)?otp\b""" +
        """[^.!?\n]{0,32}?\b(?:agent|delivery (?:boy|person|partner)|courier)\b""" +
        """(?!\s*(?:on|par|pe|via|over|through)\b[^.!?\n]{0,14}?""" +
        """\b(?:whats\w*|telegram|phone|call|chat|video|app|sms)\b)|""" +
        """(?:\b(?:डिलीवरी\s+)?(?:otp|ओटीपी)\b[^.!?\n]{0,36}(?:दरवाज़े|दरवाजे)\s+पर[^.!?\n]{0,24}एजेंट\b""" +
        """(?![^.!?\n]{0,32}(?:फोन|कॉल|व्हाट्सएप|video\s+call))))""",
)

/**
 * Protective anti-download advice. A cyber-police or CERT-In advisory that tells the reader **not** to
 * download an unknown APK names the same two tokens a scam does ("download" + "APK"), so the APK ask
 * rule would otherwise accuse the warning. The refusal is the verb (`बचें` / "avoid" / "beware"), never
 * a bare "alert"/"warning", because `"Warning: account band ho jayega, yeh APK install karo"` is a real
 * scam shape and must keep its ask.
 */
private val SKIP_ANTI_DOWNLOAD_ADVICE = rx(
    """(?:बचें|बचे|बचना|बचाएं|avoid(?:ing)?|beware|do\s+not\s+download|don'?t\s+download|""" +
        """never\s+download|download\s+mat\s+karo|download\s+na\s+karein)""",
)

// ---------------------------------------------------------------------------------------------
// Stage 1 — AUTHORITY. Alone: LOW/WEAK (BEHAVIOR_SPEC: "Alone: weak/watch").
// ---------------------------------------------------------------------------------------------

private val AUTHORITY_RULES: List<Rule> = listOf(
    Rule(
        titleHinglish = "Sanstha ka dawa",
        detail = "Message bank, police ya kisi sarkari agency ki taraf se bolne ka dawa karta hai. " +
            "Akele yeh scam ka saboot nahi hai, lekin asli sanstha OTP ya paise phone par nahi maangti.",
        severity = Severity.LOW,
        strength = Strength.WEAK,
        patterns = listOf(
            rx(
                """\b(?:main|mein|hum|this is|i am|i'm|speaking from|मैं|हम)\b[^.!?\n]{0,34}?""" +
                    """\b(?:cbi|सीबीआई|police|पुलिस|trai|ट्राई|rbi|आरबीआई|customs|कस्टम्स|narcotics|""" +
                    """enforcement directorate|ईडी|cyber ?(?:cell|crime)|income tax|आयकर|supreme court|""" +
                    """high court|court|head ?office|bank|sbi|hdfc|icici|axis|kotak|pnb|officer|अधिकारी)\b""",
            ),
            rx(
                """\b(?:sbi|hdfc|icici|axis|kotak|pnb|bank of baroda|canara|union bank|yes bank|idfc|bank)\b""" +
                    """[^.!?\n]{0,24}?\b(?:head ?office|ki taraf se|se bol|branch|officer|manager|department)\b""",
            ),
            rx(
                """\b(?:officer|customer ?care|customer ?support|courier|delivery agent|inspector|dcp|ips|""" +
                    """commissioner|customs|police|cbi|ed officer|trai|rbi|अधिकारी|पुलिस)\b""" +
                    """[^.!?\n]{0,26}?(?:bol raha|bol rahi|bol rahe|se baat|se bol|here|speaking)\w*\b""",
            ),
            rx(
                """\b(?:cbi|सीबीआई|enforcement directorate|ईडी|narcotics|नार्कोटिक्स|customs|कस्टम्स|trai|ट्राई|""" +
                    """rbi|आरबीआई|supreme court|सुप्रीम कोर्ट|cyber ?(?:cell|crime|branch)|crime branch|""" +
                    """income tax department|पुलिस|police)\b""",
            ),
        ),
        skip = SKIP_POLICE_HELPLINE,
    ),
    Rule(
        titleHinglish = "Sarkari officer ka dawa",
        detail = "Message khud ko officer, customer-care ya bank branch ka aadmi batata hai. " +
            "Asli officer video call par case clear karne ke liye paise nahi maangta.",
        severity = Severity.LOW,
        strength = Strength.WEAK,
        patterns = listOf(
            rx(
                """\b(?:senior officer|bank officer|investigation officer|sub[- ]?inspector|branch manager|""" +
                    """customer ?care executive|verification officer|अधिकारी हूँ|अधिकारी हूं)\b""",
            ),
        ),
        skip = SKIP_POLICE_AFTER,
    ),
) + EXTENDED_AUTHORITY_RULES

// ---------------------------------------------------------------------------------------------
// Stage 2 — THREAT_OR_REWARD. Alone: LOW/WEAK. Severity discipline lives here, not in fusion.
// ---------------------------------------------------------------------------------------------

private const val ACCOUNT_NOUN =
    "(?:account|ac ?count|a/c|khata|खाता|card|कार्ड|sim|सिम|number|kyc|केवायसी|pan|aadhaar|आधार|wallet|upi)"
private const val ACCOUNT_HARM =
    "(?:block\\w*|band ho\\w*|band\\b|बंद|suspend\\w*|freez\\w*|deactivat\\w*|close\\w*|closed|expire\\w*|" +
        "restrict\\w*|terminat\\w*|seize\\w*|band ho jayega)"

private val THREAT_RULES: List<Rule> = listOf(
    Rule(
        titleHinglish = "Account band karne ki dhamki",
        detail = "Message account, KYC, SIM ya card band/suspend hone ki dhamki deta hai. " +
            "Yeh darr stage 2 hai; akele CRITICAL nahi banta.",
        severity = Severity.LOW,
        strength = Strength.WEAK,
        patterns = listOf(
            rx("""\b$ACCOUNT_NOUN\b[^.!?\n]{0,44}?\b$ACCOUNT_HARM\b"""),
            rx("""\b$ACCOUNT_HARM\b[^.!?\n]{0,30}?\b$ACCOUNT_NOUN\b"""),
        ),
    ),
    Rule(
        titleHinglish = "Arrest ya legal dhamki",
        detail = "Message arrest, FIR, warrant, money laundering ya drug-parcel ka darr dikhata hai — " +
            "digital-arrest scam ka core stage.",
        severity = Severity.LOW,
        strength = Strength.WEAK,
        patterns = listOf(
            rx(
                """\b(?:arrest\w*|giraftar\w*|गिरफ़्तार\p{M}*|गिरफ्तार\p{M}*|fir\b|एफआईआर|warrant\w*|वारंट|""" +
                    """non[- ]?bailable|money laundering|मनी लॉन्ड्रिंग|drugs? found|drugs? seized|""" +
                    """illegal parcel|court (?:notice|summons|case)|legal action|case (?:registered|darj))\b""",
            ),
            rx(
                """\b(?:parcel|package|courier|shipment|पार्सल)\b[^.!?\n]{0,44}?""" +
                    """\b(?:held|on hold|seized|drugs|illegal|customs|blocked|cancel\w*|रोका)\b""",
            ),
        ),
    ),
    Rule(
        titleHinglish = "Inaam ya lottery ka laalach",
        detail = "Message lottery, prize, cashback ya guaranteed return ka laalach deta hai. " +
            "Akele laalach stage 2 hai; paise maangna stage 5 hai.",
        severity = Severity.LOW,
        strength = Strength.WEAK,
        patterns = listOf(
            rx(
                """\b(?:lottery|लॉटरी|lucky draw|kbc|prize|इनाम|inam\b|inaam|cashback|कैशबैक|""" +
                    """guaranteed (?:returns?|profit)|assured (?:returns?|profit)|double (?:your )?money|""" +
                    """won\b|jeet\w*|jeeta|jeeti|लाख|crore|karod)\b""",
            ),
            rx("""\b\d+\s?%\s?(?:return|returns|profit|monthly|daily|मुनाफा|मुनाफ़ा|रिटर्न|प्रॉफिट)\b"""),
            rx("""\b(?:selected|select ho gaye|congratulations|badhai ho)\b[^.!?\n]{0,30}?\b(?:job|prize|reward|offer)\b"""),
        ),
    ),
    Rule(
        titleHinglish = "Ghar baithe kamai ka laalach",
        detail = "Message ghar baithe naukri, roz ki kamai ya 'salary' ka lalach de raha hai. " +
            "Aise offer ke saath registration fee maangna hi asli scam step hota hai.",
        severity = Severity.LOW,
        strength = Strength.WEAK,
        patterns = listOf(
            rx(
                """\b(?:work from home|work-from-home|ghar baithe|घर बैठे|part[- ]?time)\b""" +
                    """[^.!?\n]{0,30}?\b(?:job|naukri|नौकरी|kaam|काम)\b""",
            ),
            rx(
                """\b(?:job|naukri|नौकरी|kaam|काम|salary|तनख्वाह)\b[^.!?\n]{0,34}?""" +
                    """\b(?:salary|income|kamai|kamao|kamaye|kamaayen|earn|per day|roz|रोज|""" +
                    """कमाओ|कमाई|कमाएं|महीना|monthly)\b""",
            ),
            rx("""\b(?:roz|रोज|daily|per day)\b[^.!?\n]{0,20}?\b(?:₹|rs\.?|inr|कमाओ|कमाई|earn|kamao|kamai)\b"""),
        ),
    ),
    Rule(
        titleHinglish = "Warna police/case ki dhamki",
        detail = "Message 'warna police complaint / case' ki dhamki dekar dabaav banata hai. Asli " +
            "bank ya department paise wapas maangne ke liye dhamki nahi deta.",
        severity = Severity.LOW,
        strength = Strength.WEAK,
        patterns = listOf(
            rx(
                """\b(?:warna|varna|nahi to|नहीं तो|वरना)\b[^.!?\n]{0,30}?""" +
                    """\b(?:police|पुलिस|complaint|शिकायत|fir\b|एफआईआर|case|केस|legal action|report)\b""",
            ),
        ),
    ),
) + UTILITY_DISCONNECT_RULES + EXTENDED_THREAT_RULES
// ---------------------------------------------------------------------------------------------
// Stage 3 — URGENCY. Alone: LOW/WEAK ("Akele: weak").
// ---------------------------------------------------------------------------------------------

private val URGENCY_RULES: List<Rule> = listOf(
    Rule(
        titleHinglish = "Jaldbaazi (time pressure)",
        detail = "Message turant act karne ka dabaav banata hai. Akele urgency scam ka saboot nahi hai.",
        severity = Severity.LOW,
        strength = Strength.WEAK,
        patterns = listOf(
            rx(
                """\b(?:abhi|abhi hi|abhee|turant|तुरंत|अभी|अभी ही|foran|फौरन|jaldi|जल्दी|aaj raat|आज रात|""" +
                    """aaj hi|last date(?: hai)? aaj|aaj last date|आज ही|last chance|aakhri mauka|अंतिम मौका|before midnight|midnight se pehle|""" +
                    """final warning|turant karo|abhi karo)\b""",
            ),
            rx(
                """\b\d{1,2}\s?(?:ghant\w*|hours?|hrs?|minutes?|mins?|mint|मिनट|घंटे|घंटा)\b""",
            ),
            rx(
                """\b(?:ghant\w*|hours?|minutes?|मिनट)\b[^.!?\n]{0,14}?\b(?:bache|bacha|left|remaining|baaki)\b""",
            ),
        ),
    ),
) + EXTENDED_URGENCY_RULES

// ---------------------------------------------------------------------------------------------
// Stage 4 — SECRECY / isolation. Alone: MEDIUM/MODERATE ("moderate with a claim/threat").
// ---------------------------------------------------------------------------------------------

/**
 * Isolation / intimidation framing. Mirrors the vocabulary A04 uses for the same decision, so the
 * two modules agree on what "isolation pressure" looks like. When any of this sits in the same
 * sentence as a tell-family phrase, that phrase is the scam's stage 4 and the credential-scoped
 * skip below must not be applied.
 */
private const val SECRECY_THREAT_FRAMING =
    "(?:warna|varna|nahi\\s+to|confidential|gopniya|गोपनीय|\\bfir\\b|एफआईआर|police|पुलिस|cbi|सीबीआई|" +
        "customs|narcotics|arrest|giraftar|गिरफ़्तार|गिरफ्तार|raid|legal\\s+action|digital\\s+arrest|" +
        "case\\s+(?:hai|darj|registered)|chhup\\w*|chupa\\w*|video\\s+call|call\\s+cut|akele|अकेले)"

/** Verbs that turn a nearby credential into a *request* instead of advice. Never skipped. */
private const val SECRECY_ASK_VERB =
    "(?:batao|bata\\s*do|batado|bata\\s*de|bhejo|bhej\\s*do|bhej\\s*de|de\\s*do|dedo|" +
        "share\\s*karo|send|forward|reply|bol\\s*do|bolo|daal\\s*do|daalo|likho)"

/**
 * Roman-Hindi imperatives that turn the *tail* of the sentence into a request. Narrower than
 * [SECRECY_ASK_VERB] on purpose: English "send"/"forward" in a tail is ordinary bank boilerplate
 * ("we will never send you a link"), a bare Hindi imperative after a protective clause is not.
 */
private const val SECRECY_TRAILING_ASK =
    "(?:batao|bata\\s*do|bata\\s*dena|batado|bata\\s*de|bhejo|bhej\\s*do|bhej\\s*de|de\\s*do|dedo|" +
        "share\\s*karo|bol\\s*do|bolo|daal\\s*do|daalo)"

/**
 * Protective, credential-scoped sharing advice — the one construction that must never be read as
 * stage-4 secrecy (issue #114; BEHAVIOR_SPEC "Negation and legitimate pairs", report §3.4).
 *
 * Stage 4 is about **isolation**: "kisi ko mat batana" is isolation pressure only when the thing the
 * sender wants kept quiet is the case itself. When the same sentence scopes the tell-family phrase to
 * a **credential** ("Yeh OTP kisi ko na batayein", "code kisi ko mat batana"), the speaker is telling
 * the reader *not to hand the secret over* — that is the bank's own safety line, A04 already emits it
 * as a legitimate indicator, and the two cards must never contradict each other.
 *
 * Deterministic scoping rule (no clock, no model, no score feedback):
 *
 *  1. **Same sentence.** The pattern is anchored to a sentence start (`^` or after `.` `!` `?` `;`
 *     `|` newline) and never crosses a sentence break, so a credential in a *different* sentence is
 *     not "the same advice".
 *  2. **Credential first, adjacent.** A credential noun ([OTP_WORD]: OTP / code / PIN / CVV /
 *     password / "6 digit code" / ओटीपी …) must appear *before* the tell-family phrase, inside the
 *     detector's existing `SKIP_WINDOW` (24 chars either side of the secrecy match) and with at most
 *     16 characters between the noun and the phrase.
 *  3. **No ask verb in the gap.** If an ask verb (`batao`, `bhejo`, `de do`, `share karo`, …) sits
 *     between the credential and the phrase, the phrase is not advice about that credential —
 *     "Mera password hai, batao kisi ko mat" keeps its stage 4.
 *  4. **Threat scope wins.** If the sentence also carries [SECRECY_THREAT_FRAMING] (`warna`,
 *     `confidential`, `police`, `FIR`, `arrest`, `case hai`, `video call`, `call cut`, `akele`, …)
 *     the skip is *not* applied. "Kisi ko mat batana, warna police case kar degi. OTP batao." and
 *     "Yeh confidential case hai, OTP kisi ko mat batana" keep stage 4; a credential word never
 *     overrides an explicit threat or isolation clause. The check runs from the sentence start, so
 *     framing *before* the phrase counts too.
 *  5. **No ask in the tail.** A Roman-Hindi imperative after the phrase ([SECRECY_TRAILING_ASK]) makes
 *     the sentence a request — "Aapka OTP 123456 kisi ko mat batana, turant batao" keeps stage 4.
 *  6. **English share-family.** "Do not share it with anyone" names no credential because "share"
 *     already implies the secret, and "with anyone / no one" names the protection, so that shape is
 *     skipped on its own (same reason A04 treats the share-family as protective).
 *
 * Known bound: framing further back than the sentence start (or a credential in the previous
 * sentence, "OTP aaya hai. Ise kisi ko na batayein") does not skip, so the stage-4 card stays. That
 * direction is deliberate — a visible isolation card is the safe failure, a hidden one is not.
 *
 * Not applied: the ask side keeps its own negation guard, and this skip neither adds nor removes an
 * [AskType]. Residual risk is bounded: a message that pairs credential-scoped advice with a real
 * unnegated ask still fires stage 5 (weight 30) on its own, so the verdict never drops below the ask
 * floor because stage 4 was correctly suppressed.
 */
private val SKIP_CREDENTIAL_SHARE_ADVICE = rx(
    "(?:^|[.!?;\\n|])\\s*" +
        "(?![^.!?;\\n|]{0,80}?$SECRECY_THREAT_FRAMING)" +
        "(?:" +
        "(?:(?!$SECRECY_ASK_VERB)[^.!?;\\n|]){0,16}?\\b$OTP_WORD\\b" +
        "(?:(?!$SECRECY_ASK_VERB)[^.!?;\\n|]){0,16}?" +
        "(?:kisi\\s+(?:ko|se)|kisi\\s+bhi\\s+ko|ghar\\s+wa\\w*\\s+ko|family\\s+ko|wife\\s+ko|" +
        "husband\\s+ko|papa\\s+ko|mummy\\s+ko|bachch?on\\s+ko|किसी\\s+को|घर\\s+वालों\\s+को)\\s*" +
        "(?:mat|na|nahi|nahin|मत|न)\\b" +
        "|" +
        "(?:do\\s+not|don'?t|never)\\s+(?:share|disclose|reveal|forward)\\b[^.!?;\\n|]{0,24}?" +
        "(?:with\\s+(?:anyone|anybody|any\\s*one|no\\s*one|others)|\\b$OTP_WORD\\b)" +
        ")" +
        "(?![^.!?;\\n|]{0,30}?$SECRECY_TRAILING_ASK)",
)

private val SECRECY_RULES: List<Rule> = listOf(
    Rule(
        titleHinglish = "Kisi ko batane se roka",
        detail = "Message kisi ko batane se rok raha hai. Asli bank ya police aapse ghar walon ko " +
            "chhupane ko nahi kehti.",
        severity = Severity.MEDIUM,
        strength = Strength.MODERATE,
        patterns = listOf(
            rx(
                """\b(?:kisi ko|kisi se|ghar walon ko|ghar walo|ghar walon|family ko|wife ko|husband ko|""" +
                    """papa ko|mummy ko|bachchon ko|bachon ko|kisi bhi ko|किसी को|घर वालों को)\s*""" +
                    """(?:mat|na|nahi|nahin|मत|न)\b""",
            ),
            rx(
                """\b(?:mat|na|nahi|nahin|never|do not|don't|dont)\b[^.!?\n]{0,20}?""" +
                    """\b(?:batana|bataye\w*|batao|batana hai|tell|inform|share|bhoolna)\b""",
            ),
        ),
        // Issue #114: "OTP kisi ko na batayein" is the bank's own safety line, not isolation.
        skip = SKIP_CREDENTIAL_SHARE_ADVICE,
    ),
    Rule(
        titleHinglish = "Confidential case",
        detail = "Message ko confidential ya 'under investigation' bataya gaya hai — akela rehne " +
            "ko majboor karne ka tareeka.",
        severity = Severity.MEDIUM,
        strength = Strength.MODERATE,
        patterns = listOf(
            rx("""\b(?:confidential|गोपनीय|secret case|confidential case|under investigation|gopniya)\b"""),
        ),
    ),
    Rule(
        titleHinglish = "Call/line par rokne ki koshish",
        detail = "Message call cut ya phone rakhne se rok raha hai; isse aap dusre se salah nahi le " +
            "sakte.",
        severity = Severity.MEDIUM,
        strength = Strength.MODERATE,
        patterns = listOf(
            rx(
                """\b(?:call cut mat|phone mat rakho|call mat kato|call band mat|do not disconnect|""" +
                    """don't disconnect|dont disconnect|stay on (?:the )?(?:call|line)|call pe raho|""" +
                    """line pe raho|phone rakho mat)\b""",
            ),
            rx(
                """\b(?:call|phone|line)\b[^.!?\n]{0,26}?\b(?:cut|disconnect|rakh)\w*\b[^.!?\n]{0,14}?""" +
                    """\b(?:mat|na|nahi)\b""",
            ),
        ),
    ),
    Rule(
        titleHinglish = "Video call par raho",
        detail = "Message video call par bane rahne ko keh raha hai — digital-arrest scam ka classic " +
            "isolation step.",
        severity = Severity.MEDIUM,
        strength = Strength.MODERATE,
        patterns = listOf(
            rx(
                """\b(?:video call|video-call|video kyc|skype|whatsapp video|वीडियो कॉल)\b""" +
                    """[^.!?\n]{0,30}?\b(?:aao|aayein|aaiye|raho|rahiye|join|pe raho|par raho|karo)\b""",
            ),
            rx("""\bcamera band mat\b|\bcam band mat\b"""),
            rx(
                """\b(?:camera|cam|वेबकैम)\b[^.!?\n]{0,22}?\b(?:band|off|close)\b[^.!?\n]{0,14}?""" +
                    """\b(?:mat|na|nahi)\b""",
            ),
        ),
    ),
    Rule(
        titleHinglish = "Akele raho",
        detail = "Message akele rehne ko keh raha hai, taaki koi aapko salah na de.",
        severity = Severity.MEDIUM,
        strength = Strength.MODERATE,
        patterns = listOf(
            rx("""\b(?:akele|akela|alone|अकेले)\b[^.!?\n]{0,20}?\b(?:raho|rahiye|rehna|stay|rakho)\b"""),
        ),
    ),
) + EXTENDED_SECRECY_RULES
// ---------------------------------------------------------------------------------------------
// Stage 5 — ASK, with direction. Non-negated: HIGH/STRONG. Every rule here is negation-guarded, so
// "OTP kisi ke saath share na karein" stays a warning instead of becoming an ask.
// ---------------------------------------------------------------------------------------------

private val ASK_RULES: List<Rule> = listOf(
    Rule(
        titleHinglish = "OTP/PIN maanga gaya",
        detail = "Message OTP, PIN, CVV ya password wapas maang raha hai. Bank kabhi bhi OTP/PIN " +
            "phone, message ya kisi app par nahi maangta.",
        severity = Severity.HIGH,
        strength = Strength.STRONG,
        patterns = listOf(
            rx("""\b$OTP_WORD\b[^.!?\n]{0,44}?\b$TELL\b"""),
            rx("""\b$TELL\b[^.!?\n]{0,34}?\b$OTP_WORD\b"""),
            rx("""\bread\b[^.!?\n]{0,24}?\b(?:otp|verification code|security code)\b[^.!?\n]{0,20}?\b(?:to me|over the phone|on this call)\b"""),
            rx("""\b(?:otp|pin|cvv|password|code)\b[^.!?\n]{0,22}?\b(?:daal|enter|type|likh|forward|reply)\w*\b"""),
        ),
        askType = AskType.OTP_PIN,
        guarded = true,
        skip = SKIP_DELIVERY_SHARE_TO_AGENT,
    ),
    Rule(
        titleHinglish = "Paise bhejne ko kaha gaya",
        detail = "Message paise, fee ya deposit bhejne ko keh raha hai. Asli bank, court ya courier " +
            "kabhi message par paise nahi mangta.",
        severity = Severity.HIGH,
        strength = Strength.STRONG,
        patterns = listOf(
            rx("""(?:₹|rs\.?|inr)\s?\d[\d,]*[^.!?\n]{0,40}?\b$SEND_MONEY\b"""),
            rx("""\b\d{3,}[\d,]*[^.!?\n]{0,40}?\b$SEND_MONEY\b"""),
            rx("""\b(?:paise|paisa|money|amount|rakam|rupees|rupaye|rupya)\b[^.!?\n]{0,40}?\b$SEND_MONEY\b"""),
            rx("""\b(?:pay|transfer|send|deposit)\b[^.!?\n]{0,48}?(?:₹\s?\d{3,}|\b(?:rs\.?|inr)\s?\d{3,}|\b\d{3,})"""),
            rx("""\b$SEND_MONEY\b[^.!?\n]{0,24}?\b(?:paise|paisa|money|amount|fee|fees|deposit|rupaye)\b"""),
            rx("""$MONEY_CUE[^.!?\n]{0,26}?\b(?:chahiye|maango|mang rahe|demand|need)\b"""),
            rx("""\b(?:qr|upi)\b[^.!?\n]{0,28}?\bscan\w*\b|\bscan\w*\b[^.!?\n]{0,28}?\b(?:qr|upi)\b"""),
        ),
        askType = AskType.MONEY_TRANSFER,
        guarded = true,
    ),
    Rule(
        titleHinglish = "Remote access maanga gaya",
        detail = "Message AnyDesk/TeamViewer jaise remote app ya screen share maang raha hai. Isse " +
            "doosra banda aapka phone dekh aur chala sakta hai.",
        severity = Severity.HIGH,
        strength = Strength.STRONG,
        patterns = listOf(
            rx(
                """\b(?:anydesk|any desk|team ?viewer|teamviewer|quick ?support|quicksupport|rustdesk|""" +
                    """remotedesk|remote desk|remote (?:access|desktop|support)|screen ?mirror|desktop share)\b""" +
                    """[^.!?\n]{0,32}\b(?:download|install|open|launch|run|connect|share|enable|allow|grant|khol|kholo|chalao|jodo)\b""",
            ),
            rx(
                """\b(?:download|install|open|launch|run|connect|share|enable|allow|grant|khol|kholo|chalao|jodo)\b""" +
                    """[^.!?\n]{0,32}\b(?:anydesk|any desk|team ?viewer|teamviewer|quick ?support|quicksupport|rustdesk|""" +
                    """remotedesk|remote desk|remote (?:access|desktop|support)|screen ?mirror|desktop share)\b""",
            ),
            rx("""\bscreen\s*share\s*(?:karo|kar|please|now)\b"""),
            rx("""\bshare\s+(?:your|apna)\s+screen\b"""),
        ),
        askType = AskType.REMOTE_APP,
        guarded = true,
    ),
    Rule(
        titleHinglish = "APK install karne ko kaha",
        detail = "Message store ke bahar se APK install karne ko keh raha hai. Aisa app aapka phone " +
            "aur bank access le sakta hai.",
        severity = Severity.HIGH,
        strength = Strength.STRONG,
        patterns = listOf(
            rx("""\b(?:download|install|डाउनलोड|इंस्टॉल)\b[^.!?\n]{0,26}?(?:[\w.-]+\.apk|\bapk\b)"""),
            rx("""(?:[\w.-]+\.apk|\bapk\b)[^.!?\n]{0,26}?\b(?:download|install|डाउनलोड|इंस्टॉल)\b"""),
        ),
        askType = AskType.APK_INSTALL,
        guarded = true,
        skip = SKIP_ANTI_DOWNLOAD_ADVICE,
    ),
    Rule(
        titleHinglish = "Personal detail maangi gayi",
        detail = "Message Aadhaar, card number, account number ya DOB jaisi personal detail maang " +
            "raha hai. Yeh data identity chori ke liye use hota hai.",
        severity = Severity.HIGH,
        strength = Strength.STRONG,
        patterns = listOf(
            rx(
                """\b(?:aadhaar|aadhar|adhaar|आधार|card number|card no|debit card|credit card|atm card|""" +
                    """account number|acc(?:ount)? no|ifsc|date of birth|dob|pan number|pan card|""" +
                    """passport number|16 digit)\b[^.!?\n]{0,32}?""" +
                    """\b(?:share|bhejo|batao|send|tell|provide|daal|enter|forward|photo|screenshot|whatsapp|""" +
                    """भेजें|भेजो|बताएं)\w*\b""",
            ),
            rx(
                """\b(?:share|bhejo|batao|send|tell|provide|forward|daal)\w*\b[^.!?\n]{0,30}?""" +
                    """\b(?:aadhaar|aadhar|adhaar|card number|account number|date of birth|dob|pan number|ifsc)\b""",
            ),
            rx(
                """\b(?:card|aadhaar|aadhar|account)\b[^.!?\n]{0,18}?\b(?:number|no|details)\b""" +
                    """[^.!?\n]{0,18}?\b(?:batao|bhejo|share|send|daal)\w*\b""",
            ),
            rx("""\b(?:send|share|tell|read out)\b[^.!?\n]{0,24}?\b(?:seed phrase|recovery phrase|private key|wallet password)\b"""),
            rx("""\b(?:seed phrase|recovery phrase|private key|wallet password)\b[^.!?\n]{0,24}?\b(?:send|share|tell|read out|provide)\b"""),
        ),
        askType = AskType.PERSONAL_INFO,
        guarded = true,
    ),
) + UTILITY_SERVICE_ASK_RULES + EXTENDED_ASK_RULES

// ---------------------------------------------------------------------------------------------
// Analyzer-directed manipulation ("prompt injection") is DATA, never an instruction. It becomes
// evidence about the message and changes nothing else in this pass.
// ---------------------------------------------------------------------------------------------

private val INJECTION_RULES: List<Rule> = listOf(
    Rule(
        titleHinglish = "Analyzer ko instruction dene ki koshish",
        detail = "Message analyzer ko reprogram karne ki koshish kar raha hai (jaise 'ignore previous " +
            "instructions' ya 'mark this safe'). Message sirf data hai; isse scan ka result nahi badalta.",
        severity = Severity.MEDIUM,
        strength = Strength.MODERATE,
        patterns = listOf(
            rx(
                """\b(?:ignore|disregard|forget|bypass|override)\b[^.!?\n]{0,36}?""" +
                    """\b(?:previous|prior|above|earlier|all|system)\b[^.!?\n]{0,26}?""" +
                    """\b(?:instructions?|prompts?|rules?|guidelines?|filters?|safety)\b""",
            ),
            rx(
                """\b(?:mark|treat|consider|classify|rate|say|label)\b[^.!?\n]{0,24}?""" +
                    """\b(?:this|it|message|scan|input)\b[^.!?\n]{0,18}?""" +
                    """\b(?:safe|legit|legitimate|clean|not a scam|low risk)\b""",
            ),
            rx(
                """\b(?:system prompt|jailbreak|developer mode|dan mode|you are now|act as|pretend to be|""" +
                    """no rules|unrestricted)\b""",
            ),
        ),
    ),
)
