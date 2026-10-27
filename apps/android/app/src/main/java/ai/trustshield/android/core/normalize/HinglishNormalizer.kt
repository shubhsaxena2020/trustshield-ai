package ai.trustshield.android.core.normalize

import ai.trustshield.android.core.model.NormalizedText
import ai.trustshield.android.core.model.TextNormalizer
import java.text.Normalizer

/**
 * Which scripts a raw message actually uses.
 *
 * The app must never render "Safe" for text it cannot read (REQUIREMENTS F02/F03, report §5), so the
 * UI layer asks for a [ScriptProfile] before it trusts a [ai.trustshield.android.core.model.RiskAssessment].
 * Roman + Devanagari together is the normal Hinglish case and is *supported*; anything else that shows
 * up in a message is reported by name so the app can say
 * "Is bhasha ko abhi poori tarah check nahi kar sakte".
 */
data class ScriptProfile(
    val hasRoman: Boolean,
    val hasDevanagari: Boolean,
    val unsupportedScripts: Set<String>,
) {
    /** True when at least one script the local rules can match on is present. */
    val hasSupportedScript: Boolean get() = hasRoman || hasDevanagari

    /** True when the message contains text in a script the local rules do not cover. */
    val hasUnsupportedScript: Boolean get() = unsupportedScripts.isNotEmpty()
}

/**
 * The Hinglish/Hindi text normalizer that every local rule runs against (REQUIREMENTS F02).
 *
 * What it does, in order:
 *  1. NFKC-ish fold, per code point, so compatibility forms (fullwidth letters, `ﬁ`) become ASCII-ish;
 *  2. drops invisible characters (U+200B..U+200D, U+2060, U+FEFF, U+00AD) and control characters,
 *     keeping `\n` and `\t`;
 *  3. maps Devanagari (`०`-`९`), Bengali (`০`-`৯`) and Tamil (`௦`-`௯`) digits to `0`-`9`;
 *  4. lower-cases, so `OTP`, `Otp` and `otp` are one token for both rules and de-obfuscation;
 *  5. collapses runs of spaces/tabs to one space and trims both ends;
 *  6. de-obfuscates the [OTP] and `pin` tokens (`0TP`, `O.T.P`, `O T P`, `O-T-P`, `O🔥T🔥P`,
 *     `ओटीपी`, `P.I.N`, `पिन`), leaving ordinary words such as `optional`, `total`, `stop`,
 *     `ott` and `topper` untouched;
 *  7. canonicalizes common Roman-Hindi spelling variants ([CANONICAL_WORDS] and [CANONICAL_PAIRS])
 *     so downstream rules match exactly one form: `btao`/`bataiye`/`bata do`/`bolo` -> `batao`,
 *     `bhej do`/`bhejna` -> `bhejo`, `paisa`/`pese`/`rupaye` -> `paise`. Negation words
 *     (`mat`, `na`) are never deleted, only left alone;
 *  8. folds two *whitelisted* obfuscation shapes and nothing else. A digit-substituted word
 *     (`5bi`, `b1jli`, `bl0ck`, `4ccount`) is folded back to its plain spelling only when the
 *     mapped result is one of [LEET_SPELLINGS]; a token spelled out one letter per segment
 *     (`k y c`, `s b i`, `a a d h a a r`, `k.y.c`, `k🔥y🔥c`) is joined only when the joined
 *     letters are one of [SPACED_SPELLINGS] and at least three letters took part. Numbers, dates,
 *     `a b c` and two-letter runs are left exactly as written, because outside those two
 *     whitelists this stage is a no-op.
 *
 * The critical invariant: **every** normalized character carries the raw range it came from, so
 * `NormalizedText.rawStart(i)`/`rawEnd(i)`/`spanOf()`/`rawQuoteOf()` always return text that is an
 * exact substring of the input the user actually saw. A substitution emits every replacement
 * character with the raw range of the whole source group (`0TP` -> three characters all pointing at
 * raw `[4, 7)`). A leetspeak fold is length-preserving, so it maps each emitted character to the
 * single raw glyph it replaced (`b1jli` -> `bijli`, where the `i` quotes raw `1`); a letter-spacing
 * fold maps the whole joined token to the raw run it spans (`k y c` -> `kyc`, where every character
 * quotes raw `k y c`). No character is ever emitted without a raw origin.
 *
 * Determinism: pure function of the input, no state, no I/O, no `android.*` imports, no clock, so it
 * is safe to call from any thread.
 *
 * Trimming and substitutions preserve each normalized character's actual raw source range, including
 * the first character; evidence quotes therefore do not need special handling at index zero.
 */
class HinglishNormalizer : TextNormalizer {

    override fun normalize(raw: String): NormalizedText {
        if (raw.isEmpty()) return NormalizedText.identity(raw)

        val glyphs = foldGlyphs(raw)
        val segments = segment(glyphs)
        val kept = trimEdges(segments)
        val out = Emission(raw.length)

        var k = 0
        while (k < kept.size) {
            val seg = kept[k]
            if (seg.kind == SegKind.WORD) {
                val otp = matchOtp(kept, k)
                if (otp != null) {
                    out.emit(OTP, seg.start, otp.last().end)
                    k += otp.size
                    continue
                }
                val pin = matchPin(kept, k)
                if (pin != null) {
                    out.emit(PIN, seg.start, pin.last().end)
                    k += pin.size
                    continue
                }
                val leet = matchLeet(seg)
                if (leet != null) {
                    // Length-preserving fold: each character keeps the raw glyph it replaced, so the
                    // token quotes `b1jli` while its second letter still quotes raw `1`.
                    for (i in leet.indices) {
                        val glyph = seg.glyphs[i]
                        out.emitChar(leet[i], glyph.start, glyph.end)
                    }
                    k++
                    continue
                }
                val pair = matchPair(kept, k)
                if (pair != null) {
                    out.emit(pair.second, seg.start, pair.first.last().end)
                    k += pair.first.size
                    continue
                }
                val canonical = CANONICAL_WORDS[seg.text]
                if (canonical != null && canonical != seg.text) {
                    out.emit(canonical, seg.start, seg.end)
                    k++
                    continue
                }
                val spaced = matchSpacedToken(kept, k)
                if (spaced != null) {
                    out.emit(spaced.token, seg.start, spaced.end)
                    k += spaced.consumed
                    continue
                }
            }
            out.emitSegment(seg)
            k++
        }

        return NormalizedText.of(raw, out.text(), out.starts(), out.ends())
    }

    /** True when [raw] is longer than [MAX_TEXT_CHARS] and only its prefix was normalized. */
    fun isTruncated(raw: String): Boolean = raw.length > MAX_TEXT_CHARS

    /** Which scripts [raw] uses. Unsupported scripts are reported by name so the UI can decline. */
    fun scriptProfile(raw: String): ScriptProfile {
        var roman = false
        var devanagari = false
        val unsupported = LinkedHashSet<String>()
        var i = 0
        while (i < raw.length) {
            val cp = Character.codePointAt(raw, i)
            i += Character.charCount(cp)
            when {
                cp in 'A'.code..'Z'.code || cp in 'a'.code..'z'.code -> roman = true
                cp in DEVANAGARI_BLOCK -> devanagari = true
                isMappedDigit(cp) -> Unit // digits we translate are not a script signal
                else -> scriptNameOf(cp)?.let(unsupported::add)
            }
        }
        return ScriptProfile(roman, devanagari, unsupported)
    }

    // ---- stage 1: code-point fold into glyphs ------------------------------------------------

    /**
     * One atomic piece of normalized text: the characters it produces plus the raw range they came
     * from. A single raw code point can expand (NFKC) or translate (digit), but its raw range never
     * grows beyond that code point.
     */
    private class Glyph(val text: String, val start: Int, val end: Int)

    private fun foldGlyphs(raw: String): ArrayList<Glyph> {
        val limit = rawLimit(raw)
        val glyphs = ArrayList<Glyph>(limit)
        var i = 0
        while (i < limit) {
            val cp = Character.codePointAt(raw, i)
            val width = Character.charCount(cp)
            val start = i
            i += width
            if (isInvisible(cp)) continue
            if (isDroppedControl(cp)) continue
            val source = raw.substring(start, i)
            val folded = nfkc(source)
            if (folded.isEmpty()) continue
            val text = StringBuilder(folded.length)
            for (ch in folded) {
                text.append(Character.toLowerCase(mapDigit(ch)))
            }
            glyphs.add(Glyph(text.toString(), start, i))
        }
        return glyphs
    }

    /** Raw prefix we are allowed to look at; never splits a surrogate pair. */
    private fun rawLimit(raw: String): Int {
        if (raw.length <= MAX_TEXT_CHARS) return raw.length
        var cut = MAX_TEXT_CHARS
        if (Character.isHighSurrogate(raw[cut - 1])) cut--
        return cut
    }

    private fun nfkc(source: String): String {
        // ASCII is already NFKC-stable; skipping it keeps the per-character fold cheap.
        if (source.length == 1 && source[0].code < 0x80) return source
        return try {
            Normalizer.normalize(source, Normalizer.Form.NFKC)
        } catch (_: RuntimeException) {
            source
        }
    }

    private fun mapDigit(c: Char): Char = when (c) {
        in '\u0966'..'\u096F' -> '0' + (c - '\u0966') // Devanagari ०-९
        in '\u09E6'..'\u09EF' -> '0' + (c - '\u09E6') // Bengali ০-৯
        in '\u0BE6'..'\u0BEF' -> '0' + (c - '\u0BE6') // Tamil ௦-௯
        else -> c
    }

    private fun isMappedDigit(cp: Int): Boolean =
        cp in 0x0966..0x096F || cp in 0x09E6..0x09EF || cp in 0x0BE6..0x0BEF

    private fun isInvisible(cp: Int): Boolean = cp in INVISIBLE

    private fun isDroppedControl(cp: Int): Boolean =
        cp != '\n'.code && cp != '\t'.code && Character.getType(cp) == Character.CONTROL.toInt()

    // ---- stage 2: segmentation ---------------------------------------------------------------

    private enum class SegKind { WORD, SPACE, NEWLINE, OTHER }

    private class Segment(val kind: SegKind, val glyphs: MutableList<Glyph>) {
        /**
         * Concatenated glyph text. Filled in by [freeze] once segmentation is complete: glyphs are
         * merged into the list after construction, so this must not be computed in the constructor.
         */
        var text: String = ""
            private set

        val start: Int get() = glyphs.first().start
        val end: Int get() = glyphs.last().end

        fun freeze() {
            text = buildString { glyphs.forEach { append(it.text) } }
        }
    }

    private fun segment(glyphs: List<Glyph>): ArrayList<Segment> {
        val segments = ArrayList<Segment>(glyphs.size)
        for (glyph in glyphs) {
            val kind = kindOf(glyph.text)
            val last = segments.lastOrNull()
            if (last != null && last.kind == kind) {
                last.glyphs.add(glyph)
            } else {
                segments.add(Segment(kind, mutableListOf(glyph)))
            }
        }
        segments.forEach { it.freeze() }
        return segments
    }

    private fun kindOf(text: String): SegKind = when {
        text.all { it == ' ' || it == '\t' } -> SegKind.SPACE
        text.all { it == '\n' } -> SegKind.NEWLINE
        text.all { isWordChar(it) } -> SegKind.WORD
        else -> SegKind.OTHER
    }

    /**
     * Letters, digits and combining marks. Matras (`ी`, `ा`) are marks, not letters, so treating only
     * `isLetterOrDigit` as a word character would shred every Devanagari word at its first vowel sign.
     */
    private fun isWordChar(c: Char): Boolean =
        Character.isLetterOrDigit(c) || isMark(c)

    private fun isMark(c: Char): Boolean = Character.getType(c).toInt() in MARK_TYPES

    /** Drops spaces/newlines at both ends, without touching the spans of what stays. */
    private fun trimEdges(segments: List<Segment>): List<Segment> {
        var from = 0
        var to = segments.size
        while (from < to && isBlank(segments[from].kind)) from++
        while (to > from && isBlank(segments[to - 1].kind)) to--
        return segments.subList(from, to)
    }

    private fun isBlank(kind: SegKind): Boolean =
        kind == SegKind.SPACE || kind == SegKind.NEWLINE

    // ---- stage 3: obfuscation + canonicalization ---------------------------------------------

    /**
     * Matches the OTP token starting at segment [k]. Returns the segments consumed, or `null`.
     *
     * Only OTP-shaped spellings match; a three-letter word is accepted only when it is literally
     * `otp`/`0tp`, so `optional`, `total`, `stop`, `ott`, `topper` can never be mangled.
     */
    private fun matchOtp(segments: List<Segment>, k: Int): List<Segment>? {
        val first = segments[k]
        val text = first.text
        if (text.length == 3) {
            val c0 = text[0]
            if ((c0 == 'o' || c0 == '0') && text[1] == 't' && text[2] == 'p') return listOf(first)
        }
        if (text == DEVANAGARI_OTP) return listOf(first)
        return matchUnitRun(segments, k, OTP_SPELLINGS)
    }

    /** Matches `pin` spellings. A bare `pin` is already canonical and is left to the normal path. */
    private fun matchPin(segments: List<Segment>, k: Int): List<Segment>? {
        val first = segments[k]
        if (first.text == DEVANAGARI_PIN) return listOf(first)
        return matchUnitRun(segments, k, PIN_SPELLINGS)
    }

    /**
     * Matches up to three single-letter units separated by short punctuation/space/emoji gaps
     * (`O.T.P`, `O T P`, `O🔥T🔥P`, `ओ टी पी`). Returns `null` unless the joined letters are a
     * known spelling, so this can only ever fire on the token it targets.
     */
    private fun matchUnitRun(
        segments: List<Segment>,
        k: Int,
        spellings: Set<String>,
    ): List<Segment>? {
        val used = ArrayList<Segment>(3)
        val joined = StringBuilder(6)
        var i = k
        while (used.size < UNIT_COUNT && i < segments.size) {
            val seg = segments[i]
            when (seg.kind) {
                SegKind.WORD -> {
                    if (!isUnit(seg)) return null
                    used.add(seg)
                    joined.append(seg.text)
                    i++
                }
                SegKind.SPACE, SegKind.OTHER -> {
                    if (used.isEmpty()) return null
                    if (seg.text.length > MAX_UNIT_GAP) return null
                    i++
                }
                SegKind.NEWLINE -> return null
            }
        }
        if (used.size < UNIT_COUNT) return null
        if (!spellings.contains(joined.toString())) return null
        // Consume every segment from [k] through the last matched unit, separators included, so the
        // caller's index advance stays in step with what was actually written out.
        return segments.subList(k, i)
    }

    /** A single letter (`o`) or one Devanagari letter plus its matras (`टी`). */
    private fun isUnit(seg: Segment): Boolean {
        if (seg.kind != SegKind.WORD) return false
        val text = seg.text
        if (!Character.isLetter(text[0])) return false
        if (text[0].code !in DEVANAGARI_BLOCK) return text.length == 1
        return text.length <= 3 && text.drop(1).all { isMark(it) }
    }

    /** `bata do` / `bhej do` -> one token spanning both words. */
    private fun matchPair(segments: List<Segment>, k: Int): Pair<List<Segment>, String>? {
        if (k + 2 >= segments.size) return null
        val head = segments[k]
        val gap = segments[k + 1]
        val tail = segments[k + 2]
        if (head.kind != SegKind.WORD || tail.kind != SegKind.WORD || gap.kind != SegKind.SPACE) {
            return null
        }
        val replacement = CANONICAL_PAIRS[head.text + " " + tail.text] ?: return null
        return listOf(head, gap, tail) to replacement
    }

    // ---- stage 3b: whitelist-gated obfuscation folds ----------------------------------------

    /**
     * Folds a digit-substituted *word* back to its plain spelling, or returns `null`.
     *
     * Leetspeak (`5bi`, `b1jli`, `bl0ck`, `4ccount`, `p1n`) is the cheapest way to slip a token past
     * a word matcher, but a blanket digit map would shred ordinary text, so this fold is gated
     * twice: the segment must have the length of a whitelisted token, and the mapped result must
     * *be* one of [LEET_SPELLINGS]. Everything else is emitted exactly as it arrived, which is why
     * `4567`, `Rs 5000`, `1c1c1` (folded text `icici` is whitelisted but no fixture relies on it)
     * and `optional` are unaffected. `1` is ambiguous (`i` or `l`); both readings are tried in a
     * fixed order so `b1jli` and `b1ock` fold while the result never depends on iteration order.
     *
     * The fold is length-preserving, so the caller emits each character with the raw glyph it
     * replaced: no span is invented and a quote for the whole token is still the raw spelling.
     */
    private fun matchLeet(seg: Segment): String? {
        val text = seg.text
        if (text.length < MIN_LEET_TOKEN_LENGTH || text.length > MAX_LEET_TOKEN_LENGTH) return null
        // A glyph that expanded under NFKC (one raw code point, several characters) has no 1:1
        // source per emitted character, so that segment is left alone rather than approximated.
        if (seg.glyphs.size != text.length) return null
        val primary = leetFold(text, LEET_PRIMARY_READING)
        // Nothing was substituted (or the token is already plain): the normal path emits it as is.
        if (primary == text) return null
        if (LEET_SPELLINGS.contains(primary)) return primary
        if (text.indexOf(AMBIGUOUS_ONE) < 0) return null
        val secondary = leetFold(text, LEET_SECONDARY_READING)
        return if (LEET_SPELLINGS.contains(secondary)) secondary else null
    }

    /** Applies [LEET_SUBSTITUTIONS], with [oneReading] standing in for the ambiguous `1`. */
    private fun leetFold(text: String, oneReading: Char): String {
        val out = StringBuilder(text.length)
        for (c in text) {
            val mapped = when {
                c == AMBIGUOUS_ONE -> oneReading
                else -> LEET_SUBSTITUTIONS[c] ?: c
            }
            out.append(mapped)
        }
        return out.toString()
    }

    /**
     * Joins `k y c`, `s b i`, `a a d h a a r`, `k.y.c` or `k🔥y🔥c` into one token, or returns
     * `null`.
     *
     * A unit is exactly one ASCII letter; a separator is exactly one space or a short symbol/emoji
     * gap, and a newline never joins. At least [MIN_SPACED_LETTERS] letters must join into one of
     * [SPACED_SPELLINGS], so `a b c`, `x y z`, `i am fine` and every two-letter run are left as
     * written. Only the *maximal* run is folded: a capped run that continues (`a a d h a a r s`)
     * is rejected rather than folded from its prefix, which keeps the cost per position constant.
     *
     * The caller emits the token as one chunk over the whole raw range, so its quote is the
     * original spelling with the separators still in it (`k y c`, `k🔥y🔥c`).
     */
    private fun matchSpacedToken(segments: List<Segment>, k: Int): Fold? {
        if (!isSingleAsciiLetter(segments[k])) return null
        val joined = StringBuilder(MAX_SPACED_LETTERS)
        joined.append(segments[k].text)
        var lastEnd = segments[k].end
        var i = k + 1
        while (i < segments.size && joined.length < MAX_SPACED_LETTERS) {
            if (!isSpacedGap(segments[i])) break
            if (i + 1 >= segments.size) break
            val next = segments[i + 1]
            if (!isSingleAsciiLetter(next)) break
            joined.append(next.text)
            lastEnd = next.end
            i += 2
        }
        if (joined.length < MIN_SPACED_LETTERS) return null
        val capped = joined.length >= MAX_SPACED_LETTERS
        if (capped && i + 1 < segments.size &&
            isSpacedGap(segments[i]) && isSingleAsciiLetter(segments[i + 1])
        ) {
            return null
        }
        val token = joined.toString()
        if (!SPACED_SPELLINGS.contains(token)) return null
        return Fold(token, i - k, lastEnd)
    }

    /** A folded token, the number of segments it consumed, and the raw end it reaches. */
    private class Fold(val token: String, val consumed: Int, val end: Int)

    /** `a`-`z` only; segmentation already lower-cased, so a one-character word segment is a letter. */
    private fun isSingleAsciiLetter(seg: Segment): Boolean {
        if (seg.kind != SegKind.WORD) return false
        val text = seg.text
        if (text.length != 1) return false
        return text[0] in 'a'..'z'
    }

    /** Exactly one space, or a short symbol/emoji gap. Tabs and newlines never join. */
    private fun isSpacedGap(seg: Segment): Boolean = when (seg.kind) {
        SegKind.SPACE -> seg.text == " "
        SegKind.OTHER -> seg.text.length <= MAX_UNIT_GAP
        else -> false
    }

    // ---- stage 4: emit with the raw span map -------------------------------------------------

    private class Emission(rawLength: Int) {
        private val text = StringBuilder(rawLength)
        private var starts = IntArray(minOf(rawLength, 256).coerceAtLeast(16))
        private var ends = IntArray(starts.size)
        private var size = 0

        fun emit(chunk: String, start: Int, end: Int) {
            text.append(chunk)
            repeat(chunk.length) {
                push(start, end)
            }
        }

        fun emitSegment(seg: Segment) {
            when (seg.kind) {
                SegKind.SPACE -> emit(" ", seg.start, seg.end)
                else -> seg.glyphs.forEach { emit(it.text, it.start, it.end) }
            }
        }

        /** Emits one character that came from exactly one raw glyph (a length-preserving fold). */
        fun emitChar(c: Char, start: Int, end: Int) {
            text.append(c)
            push(start, end)
        }

        private fun push(start: Int, end: Int) {
            if (size == starts.size) {
                val grown = maxOf(16, size * 2)
                starts = starts.copyOf(grown)
                ends = ends.copyOf(grown)
            }
            starts[size] = start
            ends[size] = end
            size++
        }

        fun text(): String = text.toString()

        fun starts(): IntArray = starts.copyOf(size)

        fun ends(): IntArray = ends.copyOf(size)
    }

    companion object {
        /**
         * Longest raw input we normalize. Anything longer is folded from its prefix only; the caller
         * surfaces the coverage limitation instead of the normalizer pretending it saw everything.
         */
        const val MAX_TEXT_CHARS: Int = 20_000

        const val OTP: String = "otp"
        const val PIN: String = "pin"

        private val DEVANAGARI_OTP = "ओटीपी"
        private val DEVANAGARI_PIN = "पिन"

        private val OTP_SPELLINGS = setOf("otp", DEVANAGARI_OTP)
        private val PIN_SPELLINGS = setOf("pin", DEVANAGARI_PIN)

        private const val UNIT_COUNT = 3
        private const val MAX_UNIT_GAP = 3

        /**
         * The only words a digit-substituted segment may fold back to.
         *
         * This set *is* the fold's safety margin: a mapped token outside it is emitted unchanged, so
         * adding a word here widens what the normalizer rewrites and needs the same review as a rule.
         */
        private val LEET_SPELLINGS: Set<String> = setOf(
            "otp", "pin", "kyc", "upi", "apk", "cvv", "sbi", "hdfc", "icici",
            "anydesk", "teamviewer", "bijli", "bank", "account", "block",
        )

        private val MIN_LEET_TOKEN_LENGTH: Int = LEET_SPELLINGS.minOf { it.length }
        private val MAX_LEET_TOKEN_LENGTH: Int = LEET_SPELLINGS.maxOf { it.length }

        /**
         * One-way digit map for [matchLeet]. `1` is ambiguous and handled per reading, not here.
         *
         * `@` and `$` are listed because the spec names them, but they segment as `OTHER`, never as
         * part of a `WORD`, so they cannot currently reach this map.
         */
        private val LEET_SUBSTITUTIONS: Map<Char, Char> = mapOf(
            '0' to 'o',
            '3' to 'e',
            '4' to 'a',
            '5' to 's',
            '7' to 't',
            '8' to 'b',
            '9' to 'g',
            '@' to 'a',
            '$' to 's',
        )

        private const val AMBIGUOUS_ONE: Char = '1'

        /** `1` is read as `i` first, then as `l`; a fixed order keeps the fold deterministic. */
        private const val LEET_PRIMARY_READING: Char = 'i'
        private const val LEET_SECONDARY_READING: Char = 'l'

        /**
         * The only tokens a run of single letters may join into.
         *
         * `ed` is listed for parity with the spec but is unreachable: it is shorter than
         * [MIN_SPACED_LETTERS], and two-letter runs are deliberately never joined.
         */
        private val SPACED_SPELLINGS: Set<String> = setOf(
            "otp", "pin", "kyc", "upi", "apk", "cvv", "sbi", "cbi", "ed", "dob", "aadhaar",
            "bank",
        )

        /** Shorter runs are ordinary text (`a b`, `e d`, `i.e.`), never an obfuscated token. */
        private const val MIN_SPACED_LETTERS = 3

        private val MAX_SPACED_LETTERS: Int = SPACED_SPELLINGS.maxOf { it.length }

        private val DEVANAGARI_BLOCK = 0x0900..0x097F

        private val MARK_TYPES = setOf(
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(),
        )

        private val INVISIBLE = setOf(0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF, 0x00AD)

        /**
         * Roman-Hindi variants collapsed to one form so rules match once. Negation (`mat`, `na`) is
         * intentionally absent: it must survive normalisation.
         */
        private val CANONICAL_WORDS: Map<String, String> = buildMap {
            for (tell in listOf(
                "btao", "batao", "bataao", "btaao", "bataiye", "bataiye", "bataye", "batae",
                "bata", "btado", "btana", "bolo", "bol", "bataoon", "bataun",
            )) {
                put(tell, "batao")
            }
            for (send in listOf(
                "bhejo", "bhej", "bhejdo", "bhejna", "bheej", "bheejdo", "bhejde", "bhejwa",
            )) {
                put(send, "bhejo")
            }
            for (money in listOf(
                "paise", "paisa", "pese", "paisey", "paysa", "payse", "rupaye", "rupay",
                "rupiya", "rupaiya", "rupaiye",
            )) {
                put(money, "paise")
            }
        }

        /** Two-word variants that only read as one action when adjacent. */
        private val CANONICAL_PAIRS: Map<String, String> = mapOf(
            "bata do" to "batao",
            "bhej do" to "bhejo",
            "bheej do" to "bhejo",
        )

        private fun scriptNameOf(cp: Int): String? = when (cp) {
            in 0x0980..0x09FF -> "Bengali"
            in 0x0A00..0x0A7F -> "Gurmukhi"
            in 0x0A80..0x0AFF -> "Gujarati"
            in 0x0B80..0x0BFF -> "Tamil"
            in 0x0C00..0x0C7F -> "Telugu"
            in 0x0C80..0x0CFF -> "Kannada"
            in 0x0D00..0x0D7F -> "Malayalam"
            in 0x0D80..0x0DFF -> "Sinhala"
            in 0x0E00..0x0E7F -> "Thai"
            in 0x0590..0x05FF -> "Hebrew"
            in 0x0600..0x06FF, in 0x0750..0x077F, in 0xFB50..0xFDFF, in 0xFE70..0xFEFF -> "Arabic"
            in 0x0400..0x04FF, in 0x0500..0x052F -> "Cyrillic"
            in 0x3040..0x30FF -> "Kana"
            in 0x3400..0x4DBF, in 0x4E00..0x9FFF, in 0xF900..0xFAFF -> "Han"
            in 0x1100..0x11FF, in 0xAC00..0xD7AF -> "Hangul"
            else -> null
        }
    }
}
