package ai.trustshield.android.core.rules

import ai.trustshield.android.core.model.Evidence
import ai.trustshield.android.core.model.Severity
import ai.trustshield.android.core.model.Strength

/**
 * The **separate strong deterministic signal** BEHAVIOR_SPEC reserves for a no-ask transcript
 * ("authority or reward with no ask stays low/watch **unless a separate strong deterministic signal
 * applies**").
 *
 * Why this file exists: the five playbook stages that A03 detects are all `WEAK` except a
 * non-negated ask (`STRONG`) and the secrecy pair (`MODERATE`). So a real digital-arrest call that
 * has not reached its money demand yet — authority + drugs-parcel threat + "confidential case" — had
 * no strong signal at all, and A05's no-ask ceiling (`NO_ASK_CEILING`) held every such transcript at
 * `LOW_RISK`. That is safe but it is not the demonstrable ladder report §6 promises, and it left the
 * app unable to say anything stronger than "watch" about the exact call shape the feature exists
 * for.
 *
 * What this file is: the report §6 "Digital arrest ki baat" / "Nakli kaagzi shabd" voice-rule markers,
 * as one **narrow, pure** detector. Each marker is a phrase the report itself lists:
 *
 * | marker | report §6 wording |
 * |---|---|
 * | `digital_arrest_named` | "Aap digital arrest mein hain" |
 * | `video_call_hold` | "video call on rakhiye" |
 * | `camera_off_keep` | "camera band mat karna" |
 * | `hide_from_family` | "ghar walon ko mat batana" |
 * | `stay_alone_room` | "Kamre mein akele raho" |
 * | `fake_court_paper` | "Supreme Court ka order", "aapka arrest warrant" |
 * | `fd_break_transfer` | "FD tod kar transfer karo" |
 *
 * What this file is **not**: it is not a playbook stage, it is not a selftest of the person on the
 * call, and it never decides a verdict by itself. It emits [Strength.STRONG] evidence with an exact,
 * verified quote; A05 alone turns that into a score, and only inside the documented no-ask band
 * ladder (and only once two or more independent stages already corroborate it). A bare
 * "aap digital arrest mein hain" with no agency claim, no threat and no stage behind it stays a
 * watch-level reading.
 *
 * ## Why it does not live in `StageRules.kt`
 *
 * `StageRules`' `Rule` type and its `rx()` helper are file-private, and its `THREAT_RULES` /
 * `ASK_RULES` / `SECRECY_RULES` lists are being edited by other lanes. Rather than widen that file's
 * visibility or append to its lists, this module is self-contained: it owns its patterns, its own
 * portable word-boundary compilation (a deliberate copy of the same trick, so Android never sees
 * `(?U)`), and its own evidence shape. It is fused in exactly one place — `LocalScorer.score()`.
 *
 * ## Evidence discipline (identical to A03/A04)
 *
 *  * every quote is `raw.substring(span)`, so [Evidence.quoteVerifiesAgainst] holds by construction;
 *    an item whose quote cannot be located is **dropped**, never repaired;
 *  * repeats collapse on a normalized key, so saying "digital arrest" five times is one signal;
 *  * caps: at most [MAX_SIGNALS] items, first matches in declaration order (deterministic);
 *  * no clock, no randomness, no I/O, no `android.*`, no network. Same text in, same evidence out.
 *
 * ## Known limitation (stated, not hidden)
 *
 * The signal is re-derived from the transcript A05 is analysing, so it lives exactly as long as its
 * words stay in the rolling window ([ai.trustshield.android.core.score.CallSessionState]) — unlike a
 * playbook stage, which the session remembers for its TTL after the words are gone. On a two-hour
 * call the ladder can therefore fall back to `<= 24` once a marker phrase scrolls out of the window,
 * while the lit stage chips stay lit. Persisting this signal is a session-state change (same shape as
 * `recordAskTypes`) and is deliberately not part of this defect fix; the demo (4 lines, 16 s) never
 * reaches that boundary, and a real call repeats itself, which re-arms the signal.
 *
 * Suppression of quoted speech (a scam example the sender is *reporting*) is not done here: A05
 * already holds A04's `LegitAssessment` and drops evidence whose span is `isQuoted`, so this module
 * stays a pure text-to-phrase matcher.
 */
object DigitalArrestSignals {

    /** Evidence module id for the items this file emits. */
    const val MODULE: String = "digital_arrest_rules"

    /** Upper bound on emitted items. A repeated marker can never inflate the signal. */
    const val MAX_SIGNALS: Int = 3

    /**
     * Every marker that survived verification, in report order. Empty for text that names none of
     * the report's digital-arrest phrases.
     */
    fun detect(raw: String): List<Evidence> {
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<Evidence>(MAX_SIGNALS)
        val seen = HashSet<String>(MAX_SIGNALS)
        for (marker in MARKERS) {
            if (out.size >= MAX_SIGNALS) break
            for (pattern in marker.patterns) {
                val match = pattern.find(raw) ?: continue
                val start = match.range.first
                val end = match.range.last + 1
                if (end <= start) continue
                // Scope guards, both deterministic and both narrowings only:
                //  1. a marker inside a sentence that WARNS ABOUT the scam is the sender helping the
                //     reader ("Cyber Crime police ki salah: digital arrest scam se bachein",
                //     "Beware of digital arrest fraud ... never pay", a news line "... giraftar kiya").
                //     Such a message is advisory, not a demand, so the marker is dropped.
                //  2. a bare mention of "digital arrest" is only the scam's marker when it is aimed at
                //     the reader ("aap digital arrest mein hain"). A police advisory, a news headline or
                //     a company circular names the phrase in the third person.
                val sentence = sentenceAround(raw, start, end)
                if (ADVISORY_CUE.containsMatchIn(sentence)) continue
                if (marker.requiresSecondPerson &&
                    !SECOND_PERSON.containsMatchIn(sentence) && !AUTHORITY_OR_THREAT_CONTEXT.containsMatchIn(raw)
                ) continue
                val quote = raw.substring(start, end)
                if (quote.isBlank()) continue
                if (!seen.add(normalizeKey(quote))) continue
                val evidence = Evidence.verified(
                    input = raw,
                    module = MODULE,
                    titleHinglish = marker.titleHinglish,
                    detail = marker.detail,
                    severity = Severity.HIGH,
                    strength = Strength.STRONG,
                    quote = quote,
                )
                // An unverifiable quote dies with its item; this module never keeps a guess.
                if (evidence.quote == null) continue
                out.add(evidence)
                break // one accepted hit per marker is enough
            }
        }
        return out
    }

    /** True when [raw] names at least one of the report's digital-arrest markers. */
    fun matches(raw: String): Boolean = detect(raw).isNotEmpty()

    /**
     * The sentence (or line) that contains `[start, end)`.
     *
     * Used only for the two scope guards below, so it stays deliberately crude and deterministic:
     * a sentence ends at `.`, `!`, `?`, `;` or a line break. No NLP, no locale rules, no clock.
     */
    private fun sentenceAround(raw: String, start: Int, end: Int): String {
        var from = start
        while (from > 0) {
            if (isSentenceBreak(raw[from - 1])) break
            from--
        }
        var to = end
        while (to < raw.length) {
            if (isSentenceBreak(raw[to])) break
            to++
        }
        return raw.substring(from, to)
    }

    /** `.` `!` `?` `;` or a line break — the only sentence boundaries this guard uses. */
    private fun isSentenceBreak(ch: Char): Boolean =
        ch == '.' || ch == '!' || ch == '?' || ch == ';' || ch.code == 10 || ch.code == 13

    /** Repeated identical phrases collapse to one key so they cannot inflate the signal. */
    private fun normalizeKey(quote: String): String {
        val sb = StringBuilder(quote.length)
        var lastSpace = false
        for (ch in quote) {
            if (ch.isLetterOrDigit()) {
                sb.append(ch.lowercaseChar())
                lastSpace = false
            } else if (!lastSpace) {
                sb.append(' ')
                lastSpace = true
            }
        }
        return sb.toString().trim()
    }

    // ---------------------------------------------------------------------------------------------
    // Marker table
    // ---------------------------------------------------------------------------------------------

    /** One digital-arrest marker: a stable id, its Hinglish evidence copy, and its patterns. */
    private class Marker(
        val titleHinglish: String,
        val detail: String,
        val patterns: List<Regex>,
        /** True when the marker only counts if the sentence addresses the reader (see SECOND_PERSON). */
        val requiresSecondPerson: Boolean = false,
    )

    /**
     * Report §6 marker table, in the order the report lists it. Each entry is deliberately a
     * *phrase*, not a loose keyword: "arrest" alone belongs to stage 2 (THREAT), while "arrest
     * warrant" is the fake-paper marker the report calls out by name.
     */
    private val MARKERS: List<Marker> = listOf(
        Marker(
            titleHinglish = "Digital arrest ka naam liya gaya",
            detail = "Bhejne wale ne khud 'digital arrest' bola. Aisi koi kanooni prakriya hoti hi " +
                "nahi; sarkar ne baar-baar kaha hai ki digital arrest naam ki cheez nahi hai.",
            patterns = listOf(
                rx("""\bdigital[\s.-]*arrest\b"""),
                rx("""\bडिजिटल\s*अरेस्ट\b"""),
            ),
            requiresSecondPerson = true,
        ),
        Marker(
            titleHinglish = "Video call par bane rehne ko kaha",
            detail = "Video call 'on' rakhne ka aadesh digital arrest ka classic isolation step hai: " +
                "aap call par atke rahein, kisi se salah na le sakein.",
            patterns = listOf(
                rx(
                    """\bvideo[\s-]?call\b[^.!?\n]{0,14}?\b(?:on|pe|par)\b[^.!?\n]{0,10}?""" +
                        """\b(?:rakhiye|rakho|raho|rakhna|rahiye|ban(?:e)?\s*raho)\b""",
                ),
                rx("""\bvideo[\s-]?call\s+(?:rakhna|rakho|rakhiye)\b"""),
            ),
        ),
        Marker(
            titleHinglish = "Camera band rakhne ko kaha",
            detail = "Camera band rakhne ka matlab hai woh dikhna nahi chahte ki aap akele hain ya " +
                "koi saath baithha hai — digital arrest scam ka pehchaan.",
            patterns = listOf(
                rx("""\bcam(?:era)?\s+band\s+mat\b"""),
                rx("""\bcam(?:era)?\b[^.!?\n]{0,12}?\bband\b[^.!?\n]{0,10}?\b(?:na|nahi|nahin|mat)\b"""),
            ),
        ),
        Marker(
            titleHinglish = "Ghar walon ko batane se roka",
            detail = "Ghar walon, family ya jeevansaathi ko na batane ka aadesh hi digital arrest ka " +
                "asli hathiyar hai: koi aapko sahi salah na de.",
            patterns = listOf(
                rx(
                    """\b(?:ghar\s*wal(?:o|on|a)?\s*ko|ghar\s*waalo\w*\s*ko|family\s*ko|biwi\s*ko|""" +
                        """wife\s*ko|husband\s*ko|mummy\s*ko|papa\s*ko|bachch?o\w*\s*ko|""" +
                        """घर\s*वालों\s*को|परिवार\s*को)\b[^.!?\n]{0,18}?""" +
                        """\b(?:mat|na|nahi|nahin|never|do\s+not)\b[^.!?\n]{0,14}?""" +
                        """\b(?:batana|bataye\w*|batao|batana\s+hai|tell|inform|share|bhoolna)\b""",
                ),
            ),
            requiresSecondPerson = true,
        ),
        Marker(
            titleHinglish = "Kamre mein akele rehne ko kaha",
            detail = "Akele kamre mein rehne ka aadesh isolation hai: asli police ya bank aapse akele " +
                "rehne ko nahi kehti.",
            patterns = listOf(
                rx(
                    """\bkamre?\s*(?:mein|me)\b[^.!?\n]{0,16}?\b(?:akele|akela|alone)\b""" +
                        """[^.!?\n]{0,16}?\b(?:raho|rahiye|rehna|stay|rakho)\b""",
                ),
            ),
            requiresSecondPerson = true,
        ),
        Marker(
            titleHinglish = "Nakli court order ya arrest warrant",
            detail = "Supreme Court ka 'order' ya arrest warrant dikhana digital arrest ka jaal hai. " +
                "Asli order kabhi video call par paid 'verification' nahi maangta.",
            patterns = listOf(
                rx(
                    """\b(?:supreme\s*court|high\s*court|सुप्रीम\s*कोर्ट)\b[^.!?\n]{0,18}?""" +
                        """\b(?:order|आदेश|notice|summons)\b""",
                ),
                rx("""\barrest\s*warrant\b|\bgiraftari\s+warrant\b|\bगिरफ्तारी\s*वारंट\b"""),
            ),
        ),
        Marker(
            titleHinglish = "FD tod kar transfer karne ko kaha",
            detail = "FD tod kar 'verification' account mein bhejne ka aadesh: asli bank kabhi " +
                "fixed deposit tod kar bhejne ko nahi kehta.",
            patterns = listOf(
                rx("""\bfd\b[^.!?\n]{0,16}?\b(?:tod|todd?o|break|band)\b"""),
                rx("""\bfd\b[^.!?\n]{0,22}?\b(?:transfer|bhejo|bhej\s*do|jama)\b"""),
                rx(
                    """\bfixed\s*deposit\b[^.!?\n]{0,20}?\b(?:tod|transfer|bhejo|break)\b""",
                ),
            ),
        ),
    )

    // ---------------------------------------------------------------------------------------------
    // Scope guards (see detect())
    // ---------------------------------------------------------------------------------------------

    /**
     * Protective / advisory framing. When one of these appears in the same sentence as a marker, the
     * sender is warning *about* the scam (police advisory, bank awareness SMS, news report), so the
     * marker is not the scammer's demand and is dropped.
     *
     * Deliberately excludes the bare word "salah" and "paisa mat bhejein" alone: both occur in real
     * isolation demands ("kisi se salah mat lena", "paise mat bhejo, pehle verify karo"). Only
     * unambiguous *warning-about-the-scam* phrasing is listed.
     */
    private val ADVISORY_CUE: Regex = rx(
        """\b(?:se\s*bachein|se\s*bachaye|se\s*bachao|se\s*bachne|se\s*bachayen|bach\s*karein|""" +
            """beware|advisory|awareness|\bmyth\b|myths|\bnews\b|giraftar\s+kiya|arrested|""" +
            """ke\s*baare\s*mein|ke\s*bare\s*mein|ke\s*baare\s*main|\babout\b|""" +
            """1930\s*(?:par|pe|पर)\s*call|report\s+(?:it|this)\s+to)\b""",
    )

    /**
     * Second person. A bare "digital arrest" mention is the scam's marker only when it is aimed at the
     * reader ("aap digital arrest mein hain"); a third-person mention (news, advisory, notice) is not.
     */
    private val SECOND_PERSON: Regex = rx(
        """\b(?:aap|aapko|aapka|aapki|aapke|aap\s+par|aap\s+pe|tum|tumhe|tumhara|""" +
            """you|you're|you\s+are|your)\b""",
    )

    private val AUTHORITY_OR_THREAT_CONTEXT: Regex = rx(
        """\b(?:warna|varna|police|cbi|customs|officer|bank|sbi|court|warrant|""" +
            """complaint|legal\s+action|account\s+(?:band|block|suspend)|case\s+(?:hai|darj|registered))\b""",
    )

    // ---------------------------------------------------------------------------------------------
    // Pattern compilation (same portable trick as StageRules.rx, kept local on purpose)
    // ---------------------------------------------------------------------------------------------

    /**
     * Compile a pattern case-insensitively with explicit Unicode word boundaries.
     *
     * Android rejects `(?U)`, and Java's `\b` is ASCII-only, so `\b` is spelled out as
     * letter/mark/number lookarounds (the identical technique A03 uses). `\s`, `\d` and character
     * classes are left to the engine; no other token is rewritten, so a pattern here can always be
     * read as the phrase it matches.
     */
    private fun rx(pattern: String): Regex {
        val wordChars = "[\\p{L}\\p{M}\\p{N}_]"
        val wordBoundary = "(?:(?<!$wordChars)(?=$wordChars)|(?<=$wordChars)(?!$wordChars))"
        val portable = pattern.replace("\\b", wordBoundary)
        return Regex(portable, RegexOption.IGNORE_CASE)
    }
}
