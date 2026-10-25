package ai.trustshield.android.core.rules

import ai.trustshield.android.core.model.Evidence
import ai.trustshield.android.core.model.NormalizedText
import ai.trustshield.android.core.model.Severity
import ai.trustshield.android.core.model.Strength
import ai.trustshield.android.core.model.TextNormalizer
import ai.trustshield.android.core.model.TextSpan
import kotlin.math.roundToInt

/**
 * A raw-input character range in which a **protective** negation is in force.
 *
 * Coordinates are always original-input (`raw`) offsets, never normalized ones, so a caller can ask
 * [LegitAssessment.isNegated] about a [TextSpan] that came straight out of the user's message.
 *
 * "Protective" is deliberately narrow: a negation only lands here when it governs a
 * credential-sharing verb (see [LegitimateIndicators]). The secrecy stage of a scam -
 * "kisi ko mat batana" - is *not* protective and therefore never appears here; A03 must still see
 * it as isolation pressure.
 */
data class NegationScope(val start: Int, val end: Int) {
    init {
        require(start >= 0) { "negation scope start must be >= 0, was $start" }
        require(end >= start) { "negation scope end ($end) must be >= start ($start)" }
    }

    val length: Int get() = end - start

    /** True when [span] shares at least one character with this scope. */
    fun overlaps(span: TextSpan): Boolean = span.start < end && start < span.end

    fun toSpan(): TextSpan = TextSpan(start, end)
}

/**
 * Result of one [LegitimateIndicators.evaluate] pass.
 *
 * @property raw the exact input that was analysed.
 * @property indicators protective/legitimate evidence, module `legit_indicators`, severity
 *   [Severity.INFORMATIONAL] or [Severity.LOW] only - a legitimate hint is never itself hidden risk.
 * @property negations raw spans where a protective negation governs a sharing verb.
 * @property riskReduction multiplier in `0.65..1.0`. `1.0` means "nothing protective found". Never
 *   lower than [LegitimateIndicators.MIN_REDUCTION]: a sympathetic phrase may soften a score by at
 *   most 35%, never erase it.
 * @property quotedRegions raw spans that the sender is *quoting* (double quotes, single quotes, or
 *   text reported before "likha hai"/"bolte hue"). A request inside one of these was not made by the
 *   sender; A03/A05 should suppress asks whose span [isQuoted].
 */
data class LegitAssessment(
    val raw: String,
    val indicators: List<Evidence>,
    val negations: List<NegationScope>,
    val riskReduction: Double,
    val quotedRegions: List<NegationScope> = emptyList(),
) {
    init {
        require(riskReduction >= LegitimateIndicators.MIN_REDUCTION && riskReduction <= 1.0) {
            "riskReduction must stay within ${LegitimateIndicators.MIN_REDUCTION}..1.0, was $riskReduction"
        }
    }

    /** True when at least one protective/legitimate indicator was found. */
    val hasProtectiveIndicator: Boolean get() = indicators.isNotEmpty()

    /** True when [span] falls inside a protective negation. */
    fun isNegated(span: TextSpan): Boolean = negations.any { it.overlaps(span) }

    /** True when [span] falls inside a region the sender is quoting (a scam example, not an ask). */
    fun isQuoted(span: TextSpan): Boolean = quotedRegions.any { it.overlaps(span) }

    companion object {
        /** A neutral assessment: nothing protective, no reduction, no scopes. */
        fun empty(raw: String = ""): LegitAssessment =
            LegitAssessment(raw, emptyList(), emptyList(), 1.0, emptyList())
    }
}

/**
 * Legitimate-message indicators and protective negation scoping (report §3.4, BEHAVIOR_SPEC
 * "Negation and legitimate pairs").
 *
 * Two separate jobs, deliberately kept apart:
 *
 *  1. **Indicators.** Message classes that real banks, utilities and couriers send: a genuine OTP
 *     notice that tells you *not* to share the code, a credit-only balance notice, KYC advice that
 *     sends you to a branch or net banking, a routine bill with a due date, a delivery OTP meant for
 *     the agent at your door. Each becomes one [Evidence] with an exact quote taken from the raw
 *     input, plus a small bounded weight folded into [LegitAssessment.riskReduction].
 *  2. **Negation scopes.** Roman-Hindi "na/nahi/mat", Devanagari "न/नहीं/मत", English
 *     "do not/don't/never/avoid/no need to", each governing a sharing verb inside its own clause,
 *     bounded forward to ~40 characters. Exposed as raw spans so another module can ask
 *     `isNegated(span)`.
 *
 * Deterministic and pure Kotlin: no `android.*`, no I/O, no clock, no randomness. Given the same
 * [normalizer] and the same raw input, [evaluate] returns the same value every run.
 *
 * **Over/under-negation.** "OTP share karna hai" carries no negation and is *not* protective. A bare
 * "mat" is not protective either: "mat bhoolna OTP bata dena" is a reminder that still ends in an
 * ask, so the negation must govern the sharing verb (only a whitelist of filler words may sit
 * between them) and a "bhool" reminder is rejected outright.
 *
 * **Bounded reduction.** [reduce] multiplies by [LegitAssessment.riskReduction] and, importantly,
 * returns the score untouched when the caller has an independent verified strong ask. A protective
 * phrase may never erase a real money/OTP request.
 *
 * Instances remember the most recent [evaluate] so the no-argument [isNegated] convenience overload
 * works; use one instance per input (or the two-argument overload) if you analyse concurrently.
 */
class LegitimateIndicators(private val normalizer: TextNormalizer) {

    private var lastAssessment: LegitAssessment? = null

    /** Analyse [raw]. Safe on blank/empty input: returns [LegitAssessment.empty] with a 1.0 factor. */
    fun evaluate(raw: String): LegitAssessment {
        if (raw.isBlank()) return LegitAssessment.empty(raw).also { lastAssessment = it }

        val norm: NormalizedText = normalizer.normalize(raw)
        val text = norm.text
        if (text.isEmpty()) return LegitAssessment.empty(raw).also { lastAssessment = it }

        val quotedN = findQuotedRegions(text)
        val negationN = findProtectiveNegations(text, quotedN)
        val hasRequestCue = containsRequestCue(text, quotedN, negationN)

        val hits = ArrayList<Hit>(8)

        // 1. Do-not-share advice. Always protective on its own.
        firstUnquoted(text, quotedN, SECURITY_ADVICE)?.let { hits += Hit(Kind.SECURITY_ADVICE, it) }

        // 2. "The bank never asks for an OTP" - educational, protective.
        firstUnquoted(text, quotedN, BANK_NEVER_ASKS)?.let { hits += Hit(Kind.BANK_NEVER_ASKS, it) }

        // 3. Explicit "this is for information only" framing.
        firstUnquoted(text, quotedN, INFORMATION_ONLY)?.let { hits += Hit(Kind.INFORMATION_ONLY, it) }

        // 4. Credit/balance notices. Only legitimate when nothing is being asked for.
        if (!hasRequestCue) {
            firstUnquoted(text, quotedN, CREDIT_NOTICE)?.let { hits += Hit(Kind.CREDIT_NOTICE, it) }
            firstUnquoted(text, quotedN, BILL_DUE_DATE)?.let { hits += Hit(Kind.BILL_DUE_DATE, it) }
        }

        // 5. Official offline channel advice (branch / net banking / official app), never a link.
        firstUnquoted(text, quotedN, OFFICIAL_CHANNEL)?.let { hits += Hit(Kind.OFFICIAL_CHANNEL, it) }

        // 6. Delivery OTP that belongs at the door, handed over in person.
        firstUnquoted(text, quotedN, DELIVERY_AT_DOOR)?.let { hits += Hit(Kind.DELIVERY_AT_DOOR, it) }

        // 7. A scam quoted inside a warning: the warning is protective, the quote is not an ask.
        if (quotedN.isNotEmpty() && hits.any { it.kind == Kind.SECURITY_ADVICE || it.kind == Kind.BANK_NEVER_ASKS }) {
            val q = quotedN.first()
            val end = minOf(q.end, q.start + 120)
            if (end > q.start) hits += Hit(Kind.QUOTED_SCAM_IN_WARNING, Scope(q.start, end))
        }

        val indicators = buildEvidence(raw, norm, hits)
        val total = hits.filter { h -> indicators.any { it.span == mappedSpan(norm, h) } }
            .map { it.kind.weight }
            .sum()
        val riskReduction = (1.0 - total).coerceIn(MIN_REDUCTION, 1.0)

        val assessment = LegitAssessment(
            raw = raw,
            indicators = indicators,
            negations = negationN.map { scope ->
                val s = norm.spanOf(scope.start, scope.end)
                NegationScope(s.start, s.end)
            },
            riskReduction = riskReduction,
            quotedRegions = quotedN.map { scope ->
                val s = norm.spanOf(scope.start, scope.end)
                NegationScope(s.start, s.end)
            },
        )
        lastAssessment = assessment
        return assessment
    }

    /** True when [span] falls inside a protective negation of the most recent [evaluate]. */
    fun isNegated(span: TextSpan): Boolean = isNegated(span, lastAssessment)

    /** Pure form: ask a specific [assessment] instead of the remembered one. */
    fun isNegated(span: TextSpan, assessment: LegitAssessment?): Boolean =
        assessment?.isNegated(span) == true

    /** True when [span] falls inside a quoted region of the most recent [evaluate]. */
    fun isQuoted(span: TextSpan): Boolean = isQuoted(span, lastAssessment)

    /** Pure form: ask a specific [assessment] instead of the remembered one. */
    fun isQuoted(span: TextSpan, assessment: LegitAssessment?): Boolean =
        assessment?.isQuoted(span) == true

    /** Multiplier from the most recent [evaluate]; 1.0 when nothing has been evaluated yet. */
    val currentRiskReduction: Double get() = lastAssessment?.riskReduction ?: 1.0

    /**
     * Apply the bounded legitimate-message reduction.
     *
     * @param hasIndependentStrongAsk true when fusion (A05) has already validated a *separate*
     *   money/credential request. The protective phrase must not touch it, so [score] is returned
     *   unchanged.
     */
    fun reduce(score: Int, hasIndependentStrongAsk: Boolean): Int =
        reduce(score, hasIndependentStrongAsk, lastAssessment)

    /** Pure form of [reduce] against a specific [assessment]. */
    fun reduce(score: Int, hasIndependentStrongAsk: Boolean, assessment: LegitAssessment?): Int {
        require(score in 0..100) { "risk score must be within 0..100, was $score" }
        if (hasIndependentStrongAsk) return score
        val factor = assessment?.riskReduction ?: 1.0
        return (score * factor).roundToInt().coerceIn(0, 100)
    }

    // ---- evidence building ------------------------------------------------------------------

    private fun mappedSpan(norm: NormalizedText, hit: Hit): TextSpan =
        norm.spanOf(hit.scope.start, hit.scope.end)

    private fun buildEvidence(raw: String, norm: NormalizedText, hits: List<Hit>): List<Evidence> {
        val out = ArrayList<Evidence>(hits.size)
        for (hit in hits) {
            val span = mappedSpan(norm, hit)
            if (!span.isNotEmpty) continue
            val start = span.start.coerceIn(0, raw.length)
            val end = span.end.coerceIn(start, raw.length)
            if (end <= start) continue
            val quote = raw.substring(start, end)
            out += Evidence(
                id = Evidence.deterministicId(MODULE, TextSpan(start, end)),
                module = MODULE,
                titleHinglish = hit.kind.titleHinglish,
                detail = hit.kind.detail,
                severity = hit.kind.severity,
                strength = hit.kind.strength,
                quote = quote,
                span = TextSpan(start, end),
            )
        }
        // Deterministic order and no duplicated ids (same module + span must never appear twice).
        return out.sortedWith(compareBy({ it.span?.start ?: Int.MAX_VALUE }, { it.id }))
            .distinctBy { it.id }
            .take(MAX_INDICATORS)
    }

    // ---- negation scoping -------------------------------------------------------------------

    private fun findProtectiveNegations(text: String, quoted: List<Scope>): List<Scope> {
        val out = ArrayList<Scope>(4)
        for (m in NEGATION_MARKER.findAll(text)) {
            if (out.size >= MAX_NEGATIONS) break
            val markerStart = m.range.first
            val markerEnd = m.range.last + 1
            if (quoted.any { markerStart < it.end && it.start < markerEnd }) continue

            // A "mat bhoolna ..." reminder is an ask with a memory nudge, never protective advice.
            val forgetTail = text.substring(markerEnd, minOf(text.length, markerEnd + 14))
            if (FORGET_CUE.containsMatchIn(forgetTail)) continue

            val clause = clauseBounds(text, markerStart)
            val windowStart = maxOf(clause[0], markerStart - NEGATION_WINDOW)
            val windowEnd = minOf(clause[1], markerEnd + NEGATION_WINDOW)
            val sensitiveNearby = windowEnd > windowStart &&
                SENSITIVE.containsMatchIn(text.substring(windowStart, windowEnd))

            // Pre-positional negation ("do not share", "never share", "मत भेजें").
            var anchor: Int? = null
            var shareFamily = false
            val forwardEnd = minOf(clause[1], markerEnd + FORWARD_LIMIT)
            if (forwardEnd > markerEnd) {
                val forwardText = text.substring(markerEnd, forwardEnd)
                val verb = PROTECT_VERB.find(forwardText)
                if (verb != null && isFiller(forwardText.substring(0, verb.range.first))) {
                    anchor = markerStart
                    shareFamily = SHARE_VERB.matches(verb.value)
                }
            }
            // Post-positional Roman-Hindi negation ("share na karein", "batana nahi").
            if (anchor == null) {
                val backwardStart = maxOf(clause[0], markerStart - FORWARD_LIMIT)
                if (backwardStart < markerStart) {
                    val backwardText = text.substring(backwardStart, markerStart)
                    for (c in PROTECT_VERB.findAll(backwardText).toList().asReversed()) {
                        val verbEnd = c.range.last + 1
                        if (isFiller(backwardText.substring(verbEnd))) {
                            anchor = backwardStart + c.range.first
                            shareFamily = SHARE_VERB.matches(c.value)
                            break
                        }
                    }
                }
            }
            if (anchor == null) continue
            // The negation must be about a secret. "Share" already implies the secret; "bata"/"bhej"
            // only count when an OTP/PIN/code/card word is in the same clause.
            if (!shareFamily && !sensitiveNearby) continue

            val end = minOf(clause[1], anchor + FORWARD_LIMIT).coerceAtLeast(markerEnd)
            out += Scope(anchor, end)
        }
        return mergeScopes(out)
    }

    private fun clauseBounds(text: String, index: Int): IntArray {
        var start = index.coerceIn(0, text.length)
        while (start > 0 && !isClauseSeparator(text[start - 1])) start--
        var end = index.coerceIn(0, text.length)
        while (end < text.length && !isClauseSeparator(text[end])) end++
        return intArrayOf(start, end)
    }

    private fun isClauseSeparator(c: Char): Boolean =
        c == '.' || c == '!' || c == '?' || c == '\n' || c == ';' || c == '\u0964'

    /** Only a tiny closed set of words may sit between a negation and the verb it governs. */
    private fun isFiller(text: String): Boolean {
        if (text.isEmpty()) return true
        val tokens = text.split(NON_LETTER).filter { it.isNotEmpty() }
        return tokens.all { it.lowercase() in FILLER_WORDS }
    }

    // ---- request-cue gate -------------------------------------------------------------------

    /**
     * True when the message asks the reader to *do* something - unless that cue sits inside a quoted
     * scam example or inside a protective negation ("do not share"). Factual notices (credit alert,
     * routine bill) only count as legitimate when this is false.
     */
    private fun containsRequestCue(text: String, quoted: List<Scope>, negations: List<Scope>): Boolean =
        REQUEST_CUE.findAll(text).any { m ->
            val s = m.range.first
            val e = m.range.last + 1
            val inQuote = quoted.any { s < it.end && it.start < e }
            val inNegation = negations.any { s < it.end && it.start < e }
            !inQuote && !inNegation
        }

    // ---- quoted regions ---------------------------------------------------------------------

    private fun findQuotedRegions(text: String): List<Scope> {
        val out = ArrayList<Scope>(3)
        for (m in DOUBLE_QUOTED.findAll(text)) {
            val g = m.groups[1] ?: continue
            out += Scope(g.range.first, g.range.last + 1)
        }
        for (m in SINGLE_QUOTED.findAll(text)) {
            val g = m.groups[1] ?: continue
            out += Scope(g.range.first, g.range.last + 1)
        }
        for (m in REPORTED_CUE.findAll(text)) {
            val end = m.range.first
            var start = end
            while (start > 0 && end - start < REPORTED_BACK_LIMIT && !isReportBoundary(text[start - 1])) start--
            if (end - start >= REPORTED_MIN_LENGTH) out += Scope(start, end)
        }
        return mergeScopes(out)
    }

    private fun isReportBoundary(c: Char): Boolean =
        c == '.' || c == '!' || c == '?' || c == '\n' || c == ';' || c == ':' ||
            c == '"' || c == '\u201C' || c == '\u201D' || c == '\u0964'

    // ---- small helpers ----------------------------------------------------------------------

    private fun firstUnquoted(text: String, quoted: List<Scope>, pattern: Regex): Scope? {
        for (m in pattern.findAll(text)) {
            val s = m.range.first
            val e = m.range.last + 1
            if (quoted.any { s < it.end && it.start < e }) continue
            return Scope(s, e)
        }
        return null
    }

    private data class Scope(val start: Int, val end: Int)

    private data class Hit(val kind: Kind, val scope: Scope)

    private fun mergeScopes(scopes: List<Scope>): List<Scope> {
        if (scopes.isEmpty()) return emptyList()
        val sorted = scopes.sortedWith(compareBy({ it.start }, { it.end }))
        val out = ArrayList<Scope>(sorted.size)
        var current = sorted.first()
        for (next in sorted.subList(1, sorted.size)) {
            current = if (next.start <= current.end) {
                Scope(current.start, maxOf(current.end, next.end))
            } else {
                out += current
                next
            }
        }
        out += current
        return out
    }

    /** The legitimate-message classes recognised by this package. */
    private enum class Kind(
        val titleHinglish: String,
        val detail: String,
        val severity: Severity,
        val strength: Strength,
        val weight: Double,
    ) {
        SECURITY_ADVICE(
            "OTP/code share karne se mana kiya gaya hai",
            "Message saaf-saaf kehta hai ki OTP, PIN ya code kisi ke saath share na karein. " +
                "Yeh asli bank alerts ka tarika hai - asli bank aapko apna OTP kabhi nahi maangta.",
            Severity.LOW,
            Strength.MODERATE,
            0.16,
        ),
        BANK_NEVER_ASKS(
            "Bank khud OTP kabhi nahi maangta",
            "Message batata hai ki bank ya koi official kabhi OTP/PIN nahi maangta. " +
                "Yeh bachav ki salah hai, koi maang nahi.",
            Severity.LOW,
            Strength.MODERATE,
            0.10,
        ),
        INFORMATION_ONLY(
            "Message sirf jaankari ke liye hai",
            "Message khud kehta hai ki yeh sirf jaankari ke liye hai, isme koi kaam karne ki maang nahi.",
            Severity.INFORMATIONAL,
            Strength.WEAK,
            0.05,
        ),
        CREDIT_NOTICE(
            "Credit/balance alert - koi maang nahi",
            "Yeh bank ka jama (credit) ya balance alert hai. Isme paisa bhejne, OTP ya PIN dene " +
                "ki koi maang nahi hai.",
            Severity.INFORMATIONAL,
            Strength.WEAK,
            0.12,
        ),
        OFFICIAL_CHANNEL(
            "Official channel - branch ya net banking",
            "Message branch, net banking ya official app par bhejta hai, kisi anjaan link par nahi. " +
                "Asli bank ke KYC messages isi tarah batate hain.",
            Severity.LOW,
            Strength.WEAK,
            0.07,
        ),
        BILL_DUE_DATE(
            "Bill ki due date di gayi hai",
            "Routine bill reminder tareekh deta hai, 'aaj raat kat jayegi' jaisa dabaav nahi. " +
                "Asli bill messages due date ke saath aate hain.",
            Severity.INFORMATIONAL,
            Strength.WEAK,
            0.06,
        ),
        DELIVERY_AT_DOOR(
            "Delivery OTP darwaze par dene ke liye",
            "OTP sirf darwaze par aaye delivery agent ko dena hai, phone par kisi ko nahi. " +
                "Yeh delivery ka normal tareeka hai.",
            Severity.LOW,
            Strength.WEAK,
            0.07,
        ),
        QUOTED_SCAM_IN_WARNING(
            "Warning ke andar scam ka example quote kiya gaya hai",
            "Message scam ka example quote karke usse bachne ko kehta hai. Quote ke andar ki maang " +
                "bhejne wale ki maang nahi hai.",
            Severity.INFORMATIONAL,
            Strength.MODERATE,
            0.05,
        ),
    }

    companion object {
        /** Evidence module id for every indicator produced here. */
        const val MODULE: String = "legit_indicators"

        /** A protective phrase may never remove more than 35% of the score. */
        const val MIN_REDUCTION: Double = 0.65

        /** Forward reach, in characters, of a negation inside its own clause. */
        const val FORWARD_LIMIT: Int = 40

        private const val NEGATION_WINDOW = 60
        private const val REPORTED_BACK_LIMIT = 120
        private const val REPORTED_MIN_LENGTH = 8
        private const val MAX_INDICATORS = 8
        private const val MAX_NEGATIONS = 24

        private val NON_LETTER = Regex("[^\\p{L}]+")

        private val FILLER_WORDS: Set<String> = setOf(
            // English
            "it", "this", "that", "these", "those", "your", "the", "my", "our", "any", "anyone",
            "anybody", "someone", "somebody", "others", "other", "to", "with", "from", "ever",
            "at", "all", "is", "are", "be", "of", "and", "or", "only", "just", "secret",
            "information", "details", "detail", "number", "code", "otp", "pin", "cvv",
            "password", "passcode", "card",
            // Roman-Hindi
            "ise", "is", "ye", "yeh", "yah", "isme", "ismein", "inhe", "usko", "use",
            "kisi", "koi", "kisee", "ke", "ka", "ki", "ko", "se", "saath", "sath", "aur", "bhi",
            "kabhi", "kbhi", "apna", "apni", "apne", "aap", "aapka", "aapki", "aapke", "aapko",
            "tum", "mujhe", "hum", "hamare", "hamaare", "hamaari", "hamari", "wala", "wali",
            "wale", "waala", "hai", "hain", "hota", "hoti", "sirf", "matra", "jaankari",
            "jankari", "koi", "ki", "apna",
        )

        /** Clause/sentence separators end a negation's reach. `।` (danda) counts for Devanagari. */
        private val NEGATION_MARKER = Regex(
            "(?:do\\s+not|don'?t|never|avoid|no\\s+need\\s+to" +
                "|(?<![a-z])(?:nahin|nahi|na|mat)(?![a-z])" +
                "|\u0928\u0939\u0940\u0902" +
                "|(?<![^\\s])\u0928(?=[\\s.,!?;:]|$)" +
                "|\u092E\u0924(?=[\\s.,!?;:]|$))",
            RegexOption.IGNORE_CASE,
        )

        /** Verbs that can be governed by a protective negation. */
        private val PROTECT_VERB = Regex(
            "(?:shar\\w*|send\\w*|giv(?:e|ing|en)\\w*|tell\\w*|disclos\\w*|reveal\\w*|forward\\w*" +
                "|provid\\w*|bata\\w*|bhej\\w*|dena|diya|diye" +
                "|\u0938\u093E\u091D\u093E|\u0936\u0947\u092F\u0930|\u092C\u0924\u093E\\w*" +
                "|\u092D\u0947\u091C\\w*|\u0926\u0947\u0928\u093E|\u0926\u0940\u091C\u093F\\w*)",
            RegexOption.IGNORE_CASE,
        )

        /** "share" and its Devanagari twins already imply "the secret" - no object needed. */
        private val SHARE_VERB = Regex(
            "(?:shar\\w*|\u0938\u093E\u091D\u093E|\u0936\u0947\u092F\u0930)",
            RegexOption.IGNORE_CASE,
        )

        private val FORGET_CUE = Regex(
            "(?:bhool|bhul|forget|\u092D\u0942\u0932)", RegexOption.IGNORE_CASE,
        )

        private val SENSITIVE = Regex(
            "(?:\\botp\\b|\\bo\\.?t\\.?p\\b|\\bpin\\b|\\bcvv\\b|\\bpassword\\b|\\bpasscode\\b" +
                "|\\bcode\\b|\\bcard\\b|\\baadhaar\\b|\\baadhar\\b|upi\\s*pin" +
                "|\u0913\u091F\u0940\u092A\u0940|\u092A\u093F\u0928|\u092A\u093E\u0938\u0935\u0930\u094D\u0921" +
                "|\u0915\u094B\u0921|\u0906\u0927\u093E\u0930|\u0938\u0940\u0935\u0940\u0935\u0940)",
            RegexOption.IGNORE_CASE,
        )

        private val SECURITY_ADVICE = Regex(
            "(?:do\\s+not\\s+(?:share|disclose|reveal|give|forward)" +
                "|don'?t\\s+(?:share|disclose|reveal|give|forward)" +
                "|never\\s+(?:share|disclose|reveal|give|forward)" +
                "|not\\s+to\\s+share" +
                "|share\\s+(?:it\\s+)?with\\s+no\\s*one" +
                "|shar(?:e|ing)\\s+(?:na|nahi|nahin|mat)\\s+(?:karein|karen|karo|karna|kijiye|kijiyega)" +
                "|(?:na|nahi|nahin|mat)\\s+share\\s+(?:karein|karen|karo|karna|kijiye)" +
                "|kisi\\s+ke\\s+(?:saath|sath)\\s+share\\s+(?:na|nahi|nahin|mat)" +
                "|kisi\\s+(?:ko|se)\\s+(?:na|nahi|nahin|mat)\\s+(?:batayein|batao)" +
                "|kabhi\\s+(?:bhi\\s+)?share\\s+(?:na|nahi|nahin|mat)" +
                "|\u0938\u093E\u091D\u093E\\s+(?:\u0928|\u092E\u0924)\\s+\u0915\u0930\u0947\u0902" +
                "|\u0936\u0947\u092F\u0930\\s+(?:\u0928|\u092E\u0924)\\s+\u0915\u0930\u0947\u0902" +
                "|\u0915\u093F\u0938\u0940\\s+\u0915\u094B\\s+(?:\u0928|\u092E\u0924)\\s+\u092C\u0924\u093E\u090F\u0902" +
                "|\u0915\u093F\u0938\u0940\\s+\u0915\u0947\\s+\u0938\u093E\u0925\\s+(?:\u0938\u093E\u091D\u093E|\u0936\u0947\u092F\u0930)\\s+\u0928" +
                "|\u0915\u092D\u0940\\s+(?:\u0938\u093E\u091D\u093E|\u0936\u0947\u092F\u0930)\\s+\u0928\\s+\u0915\u0930\u0947\u0902)",
            RegexOption.IGNORE_CASE,
        )

        private val BANK_NEVER_ASKS = Regex(
            "(?:bank\\s+(?:kabhi\\s+(?:bhi\\s+)?)?(?:otp|pin|code|password)\\s+(?:nahi|nahin|na)\\s+maang\\w*" +
                "|(?:hamari\\s+bank|hamara\\s+bank)\\s+kabhi\\s+(?:bhi\\s+)?" +
                "|(?:bank|we)\\s+never\\s+(?:ask|maang)\\w*" +
                "|never\\s+ask\\w*\\s+(?:you\\s+)?(?:for\\s+)?(?:your\\s+)?(?:otp|pin|password|cvv|code)" +
                "|no\\s*one\\s+(?:will\\s+|would\\s+|ever\\s+)?ask\\s+(?:you\\s+)?(?:for\\s+)?" +
                "(?:your\\s+)?(?:otp|pin|password|cvv)" +
                "|koi\\s+bhi\\s+(?:otp|pin)\\s+(?:nahi|nahin)\\s+maang\\w*" +
                "|\u092C\u0948\u0902\u0915\\s+\u0915\u092D\u0940\\s+(?:\u0913\u091F\u0940\u092A\u0940|\u092A\u093F\u0928|otp)\\s+\u0928\u0939\u0940\u0902\\s+\u092E\u093E\u0902\u0917\\w*)",
            RegexOption.IGNORE_CASE,
        )

        private val INFORMATION_ONLY = Regex(
            "(?:(?:jaankari|jankari|jaan\\s+kari)\\s+ke\\s+liye\\s+(?:hai|hain)" +
                "|information\\s+ke\\s+liye\\s+(?:hai|hain)" +
                "|for\\s+your\\s+information" +
                "|for\\s+information\\s+only" +
                "|no\\s+action\\s+(?:is\\s+)?(?:required|needed)" +
                "|koi\\s+(?:kaam|karyavaahi|karwai)\\b[^.!?\\n]{0,30}\\b(?:zaroorat|jarurat)\\s+nahi" +
                "|bank\\s+ki\\s+taraf\\s+se\\s+(?:jaankari|jankari)" +
                "|\u091C\u093E\u0928\u0915\u093E\u0930\u0940\\s+\u0915\u0947\\s+\u0932\u093F\u090F\\s+\u0939\u0948" +
                "|\u0915\u094B\u0908\\s+\u0915\u093E\u0930\u094D\u0930\u0935\u093E\u0908\\s+\u0928\\s+\u0915\u0930\u0947\u0902)",
            RegexOption.IGNORE_CASE,
        )

        private val CREDIT_NOTICE = Regex(
            "(?:(?:rs\\.?|inr|\\u20B9|\u0930\u0941)\\.?\\s?[\\d,]+(?:\\.\\d+)?\\s+" +
                "(?:has\\s+been\\s+|is\\s+)?credited" +
                "|credited\\s+to\\s+(?:your\\s+)?(?:a/c|ac\\b|account)" +
                "|credited\\s+to\\s+your\\s+account" +
                "|avl\\s*\\.?\\s*bal" +
                "|available\\s+balance" +
                "|(?:khate|khata)\\s+me(?:in)?\\s+(?:jama|credit)" +
                "|\u091C\u092E\u093E\\s+\u0939\u0941\u0906)",
            RegexOption.IGNORE_CASE,
        )

        private val OFFICIAL_CHANNEL = Regex(
            "(?:visit\\s+(?:your\\s+|the\\s+|nearest\\s+)?branch" +
                "|branch\\s+(?:par|pe|mein)\\s+jaa\\w*" +
                "|branch\\s+visit\\s+kar\\w*" +
                "|via\\s+net\\s*-?\\s*banking" +
                "|net\\s*-?\\s*banking\\s+(?:se|ke\\s+through)" +
                "|official\\s+(?:app|website|number|branch)" +
                "|shakha\\s+mein\\s+jaa\\w*" +
                "|(?:\u0905\u092A\u0928\u0940\\s+)?\u0936\u093E\u0916\u093E\\s+(?:\u092E\u0947\u0902|\u092A\u0930)\\s+\u091C\u093E" +
                "|\u0928\u0947\u091F\\s+\u092C\u0948\u0902\u0915\u093F\u0902\u0917)",
            RegexOption.IGNORE_CASE,
        )

        private val BILL_DUE_DATE = Regex(
            "(?:bill[^.!?\\n]{0,40}\\bdue\\s+on\\s+\\d{1,2}(?:\\s*(?:st|nd|rd|th))?" +
                "(?:\\s*(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\\w*)?" +
                "|due\\s+on\\s+\\d{1,2}\\s*(?:st|nd|rd|th)?\\s*" +
                "(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)" +
                "|due\\s+date[^.!?\\n]{0,20}\\d" +
                "|\u092C\u093F\u0932[^\u0964.!?\\n]{0,40}\u0926\u0947\u092F" +
                "|\u0926\u0947\u092F\\s+\u0924\u093F\u0925\u093F)",
            RegexOption.IGNORE_CASE,
        )

        private val DELIVERY_AT_DOOR = Regex(
            "(?:agent\\s+at\\s+your\\s+door" +
                "|delivery\\s+agent" +
                "|share\\s+only\\s+with\\s+(?:the\\s+)?(?:delivery\\s+)?agent" +
                "|(?:darwaze|darwaje|darwaja)\\s+(?:par|pe)" +
                "|\u0921\u093F\u0932\u0940\u0935\u0930\u0940\\s+(?:\u090F\u091C\u0947\u0902\u091F|agent)" +
                "|\u0926\u0930\u0935\u093E\u091C\u093C\u0947\\s+\u092A\u0930" +
                "|\u0926\u0930\u0935\u093E\u091C\u0947\\s+\u092A\u0930)",
            RegexOption.IGNORE_CASE,
        )

        /** Anything that reads as "now do something". Gates the factual-notice classes. */
        private val REQUEST_CUE = Regex(
            "(?:bhej\\w*|bata\\w*|karo\\b|karein\\b|karen\\b|kijiye\\b|daal\\w*|de\\s*do|dedo" +
                "|de\\s*dena|send\\w*|shar\\w*|transfer\\w*|pay\\b|call\\b|click\\w*|install\\w*" +
                "|download\\w*|update\\w*|verify\\w*|confirm\\w*|reply\\b|scan\\w*|submit\\w*" +
                "|provid\\w*|enter\\b|tell\\b|give\\b|forward\\w*|login\\b|open\\b|visit\\w*" +
                "|buy\\w*|register\\w*)",
            RegexOption.IGNORE_CASE,
        )

        private val DOUBLE_QUOTED = Regex(
            "[\"\u201C]([^\"\u201C\u201D\\n]{4,200})[\"\u201D]",
        )

        private val SINGLE_QUOTED = Regex(
            "(?<![A-Za-z0-9])'([^'\\n]{4,200})'(?![A-Za-z0-9])",
        )

        private val REPORTED_CUE = Regex(
            "(?:bolte\\s+hue|likha\\s+hai|likha\\s+hua|kehte\\s+hain|kaha\\s+hai)",
            RegexOption.IGNORE_CASE,
        )
    }
}
