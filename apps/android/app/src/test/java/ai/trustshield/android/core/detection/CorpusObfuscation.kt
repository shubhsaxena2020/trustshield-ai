package ai.trustshield.android.core.detection

/**
 * The obfuscation lane of the detection corpus: the same five-stage scam shapes the other corpus
 * files carry, written the way a sender hides them when a platform keyword filter starts catching the
 * plain spelling.
 *
 * Five obfuscation classes are covered, each on a full, realistic sentence rather than a bare token,
 * because the rules match phrases and a fixture that only proves one word matches proves nothing
 * about a message:
 *
 *  1. letter spacing — `O T P`, `b i j l i`, `k y c`, `a a p k a`, `b l o c k`;
 *  2. leetspeak / digit-letter swaps — `0tp`, `b1jli`, `upd4te`, `5bi`, `p1n`, `acc0unt`, `c0de`;
 *  3. mixed script — Latin words inside Devanagari and Devanagari words inside Latin;
 *  4. emoji / symbol insertion and compatibility (fullwidth) forms — `O😀T😀P`, `otp🔒share`,
 *     `a/c❌block`, `ａｃｃｏｕｎｔ`;
 *  5. zero-width characters inside tokens and homoglyph substitution — `O\u200BT\u200BP`,
 *     `ac\u200Bcount`, Cyrillic `ОТР` / `рау`.
 *
 * ## What `minScore` means here, and what it does not
 *
 * `minScore` is the band this row **should** land in once the sender's obfuscation stops working: 70
 * when the row carries a genuine ask (a credential told back to the sender, or money to a personal
 * UPI), 50 when the row has no ask but carries a report §6 strong marker plus three stages, 25 when
 * the only honest reading today is the no-ask ladder's caution floor. It is not an accuracy claim and
 * it is not a promise that today's build reaches it — several rows below are *gaps*: the normalizer
 * folds the token, the row is green; it does not, the row is red and stays red until the folding
 * lands. Every such row says so in its `note` with `EXPECTED RED today`.
 *
 * `expectAsk` is `true` only where the sender really does demand a credential or money **and** the
 * engine is expected to verify that demand. The two homoglyph rows set it `false` on purpose: the
 * demand is real in the message, but the row's job is to pin today's baseline, and homoglyph folding
 * is not implemented at all.
 *
 * ## What `HinglishNormalizer` already folds (checked against the source, not assumed)
 *
 *  * NFKC per code point, so fullwidth letters and punctuation become ASCII (`ａｃｃｏｕｎｔ` -> `account`);
 *  * U+200B..U+200D, U+2060, U+FEFF, U+00AD are dropped before segmentation;
 *  * Devanagari/Bengali/Tamil digits are mapped to ASCII digits;
 *  * lower-casing, so `OTP` / `Otp` / `otp` are one token;
 *  * the `otp` and `pin` tokens are re-joined from up to three single-letter units separated by
 *    space or punctuation shorter than four characters (`O T P`, `O.T.P`, `O😀T😀P`, `ओ टी पी`),
 *    and the shapes `otp` / `0tp` are recognised as a single three-character word;
 *  * Roman-Hindi spelling pairs (`bata do` -> `batao`, `bhej do` -> `bhejo`, `paise` family).
 *
 * What it does **not** do, and therefore what these fixtures are aiming at: join letter runs longer
 * than three units or runs that are not `otp`/`pin` (`b i j l i`, `k y c`, `a a p k a`), undo
 * digit-for-letter swaps inside ordinary words (`b1jli`, `bl0ck`, `upd4te`, `c0de`, `kar0`), fit
 * `p1n` into the `pin` unit-run matcher, or fold Cyrillic/other-script homoglyphs.
 */
internal val CORPUS_OBFUSCATION: List<FixtureRow> = listOf(

    // ---------------------------------------------------------------------------------------
    // 1. Letter spacing - the senders who type "O T P" or "b i j l i" one character at a time.
    // ---------------------------------------------------------------------------------------

    FixtureRow(
        id = "D-O01",
        category = "obfuscation",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """Sir main SBI bank se bol raha hoon. Aapka account band ho jayega, abhi O T P bata do.""",
        minScore = 70,
        expectAsk = true,
        note = "Spaced OTP plus 'bata do'. Already handled: the normalizer joins three single-letter " +
            "units separated by short gaps into 'otp' and collapses 'bata do' to 'batao', so the ask " +
            "reads exactly like the plain spelling. Authority + threat + urgency corroborate.",
    ),
    FixtureRow(
        id = "D-O02",
        category = "obfuscation",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """Aapka b i j l i connection aaj raat kat jayega, turant 9812345670 par call karein.""",
        minScore = 70,
        expectAsk = true,
        note = "Letter-spaced 'bijli'. Partly handled: the normalizer only re-joins runs whose letters " +
            "spell otp/pin, so 'b i j l i' stays five fragments and the utility noun is lost. The row " +
            "still reaches 70 because 'connection' names the service for stage 2 and 'kat jayega' " +
            "carries the loss verb; folding spaced words would also make the bijli bill rules fire.",
    ),
    FixtureRow(
        id = "D-O03",
        category = "obfuscation",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """K Y C complete karne ke liye turant click karein: https://kyc-verify.xyz warna aapka account band ho jayega.""",
        minScore = 70,
        expectAsk = true,
        note = "Spaced KYC in front of an unofficial-link KYC ask. Needs new folding: 'k y c' is three " +
            "units that are not otp/pin, so the KYC noun never reaches the rule and stage 5 misses a " +
            "genuine personal-data ask. EXPECTED RED today - threat (account band ho jayega) and " +
            "urgency only, no-ask ladder caps at 24.",
    ),
    FixtureRow(
        id = "D-O04",
        category = "obfuscation",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """A A P K A account band ho jayega, isliye abhi apna pin batao.""",
        minScore = 70,
        expectAsk = true,
        note = "Spaced 'aapka' as noise around a readable demand. Already handled: the five-letter run " +
            "does not match any otp/pin spelling, so the normalizer leaves it alone rather than " +
            "mangling it, and the readable 'account ... pin batao' carries all three stages.",
    ),
    FixtureRow(
        id = "D-O05",
        category = "obfuscation",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """Aapka account b l o c k ho jayega, turant apna O T P bata do.""",
        minScore = 70,
        expectAsk = true,
        note = "Spaced loss verb 'b l o c k' next to a spaced-but-foldable OTP. Mixed: the ask is " +
            "already handled (unit run), the threat is not - a five-letter spaced word is never " +
            "re-joined, so stage 2 is missing and the row sits in ask-plus-one (35..49). EXPECTED RED " +
            "today against the 70 floor; folding arbitrary spaced words closes it.",
    ),

    // ---------------------------------------------------------------------------------------
    // 2. Leetspeak / digit-for-letter swaps.
    // ---------------------------------------------------------------------------------------

    FixtureRow(
        id = "D-O06",
        category = "obfuscation",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """Aapka 0tp batao, warna account band ho jayega, sirf 10 minute bache hain.""",
        minScore = 70,
        expectAsk = true,
        note = "Zero-for-O in the credential word. Already handled: the normalizer accepts a " +
            "three-character word shaped [o0]tp as 'otp', and the digit does not disturb the " +
            "surrounding words. Threat + urgency + ask.",
    ),
    FixtureRow(
        id = "D-O07",
        category = "obfuscation",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """Aapka b1jli bill update nahi hua, aaj raat connection kat jayega, turant 9812345670 par call karein.""",
        minScore = 70,
        expectAsk = true,
        note = "'b1jli' for bijli. Needs new folding: digit-for-letter substitution inside an ordinary " +
            "word is not undone anywhere, so the utility noun is invisible. The row still reaches 70 " +
            "today because 'bill' and 'connection' name the service and the personal-mobile callback " +
            "ask fires - which is exactly why the fixture keeps the plain nouns too: a green row here " +
            "must not be read as 'b1jli is understood'.",
    ),
    FixtureRow(
        id = "D-O08",
        category = "obfuscation",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """KYC upd4te ke liye is link par turant click karein https://kyc-verify.xyz warna aapka account band ho jayega.""",
        minScore = 70,
        expectAsk = true,
        note = "'upd4te' for update. Partly handled: the KYC-link rule's verb list also contains " +
            "'click', which is spelled plainly here, so the rule still fires and the row is green. " +
            "If the sender writes 'upd4te karein' with no 'click', the same message falls to 24 - that " +
            "is the folding this row is meant to keep honest.",
    ),
    FixtureRow(
        id = "D-O09",
        category = "obfuscation",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """5bi se: aapka account 24 ghante mein bl0ck ho jayega, turant apna aadhaar number bhejo.""",
        minScore = 70,
        expectAsk = true,
        note = "Two swaps in one message: '5bi' for SBI and 'bl0ck' for block. Needs new folding: " +
            "neither is recognised, so the bank name and the loss verb are both lost while the " +
            "aadhaar-number ask reads plainly. EXPECTED RED today against 70 - ask and urgency only, " +
            "which the ask-plus-one clamp holds at 35..49.",
    ),
    FixtureRow(
        id = "D-O10",
        category = "obfuscation",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """Aapka ATM card block ho gaya hai, turant apna p1n daal do.""",
        minScore = 70,
        expectAsk = true,
        note = "'p1n' for PIN. Needs new folding: the unit-run matcher needs single letters, so a " +
            "three-character word containing a digit can never become 'pin' (unlike '0tp', which is " +
            "special-cased). Threat and urgency are plain; EXPECTED RED today - no ask, no strong " +
            "marker, so the whole message is capped at 24. Blunt case of a credential demand reading " +
            "as safe.",
    ),
    FixtureRow(
        id = "D-O11",
        category = "obfuscation",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """Aapka acc0unt bl0ck ho jayega, abhi ye c0de share kar0.""",
        minScore = 70,
        expectAsk = true,
        note = "Fully leeted account/block/code/kar0. Needs new folding across the board: every " +
            "content word is swapped, so today only 'abhi' survives. EXPECTED RED today at roughly " +
            "8/100 LOW_RISK - the sharpest row in this file, because it shows a whole message being " +
            "erased by four digit swaps rather than by one exotic trick.",
    ),

    // ---------------------------------------------------------------------------------------
    // 3. Mixed script - the normal Hinglish case, and the one the rules must not need folding for.
    // ---------------------------------------------------------------------------------------

    FixtureRow(
        id = "D-O12",
        category = "obfuscation",
        language = "mixed",
        kind = FixtureKind.SCAM,
        text = """Aapka खाता block ho gaya hai, turant OTP batao.""",
        minScore = 70,
        expectAsk = true,
        note = "Devanagari account noun inside an otherwise Latin sentence. Already handled: both " +
            "scripts are supported and the rules match either spelling of the noun, so nothing needs " +
            "folding. Kept because 'split the sentence across two scripts' is the most common " +
            "obfuscation in the wild and it must stay caught.",
    ),
    FixtureRow(
        id = "D-O13",
        category = "obfuscation",
        language = "mixed",
        kind = FixtureKind.SCAM,
        text = """आपका account band हो जाएगा, अभी अपना PIN बताओ।""",
        minScore = 70,
        expectAsk = true,
        note = "Devanagari sentence with a Latin loss verb and a Latin credential noun. Already " +
            "handled: the ask vocabulary carries 'बताओ' and the credential noun is lower-cased to " +
            "'pin', so the pair matches across the script boundary.",
    ),
    FixtureRow(
        id = "D-O14",
        category = "obfuscation",
        language = "mixed",
        kind = FixtureKind.SCAM,
        text = """आधार card number abhi bhejo, warna आपका खाता बंद हो जाएगा।""",
        minScore = 70,
        expectAsk = true,
        note = "Identity-data ask in Devanagari with the Aadhaar noun and the send verb in Latin. " +
            "Already handled for this spelling. Known neighbour gap, not exercised here: the " +
            "personal-detail rule's verb list has no Devanagari 'भेजो', so the all-Devanagari version " +
            "of the same demand does not fire - a rule-vocabulary gap, not a normalizer one.",
    ),
    FixtureRow(
        id = "D-O15",
        category = "obfuscation",
        language = "mixed",
        kind = FixtureKind.SCAM,
        text = """Sir aapka khata बंद हो जाएगा, abhi otp bata do.""",
        minScore = 70,
        expectAsk = true,
        note = "Latin account noun with a Devanagari loss verb - the mirror of D-O12. Already " +
            "handled: the threat rule lists 'बंद' beside 'band', and the OTP pair folds as usual.",
    ),

    // ---------------------------------------------------------------------------------------
    // 4. Emoji / symbol insertion and compatibility (fullwidth) forms.
    // ---------------------------------------------------------------------------------------

    FixtureRow(
        id = "D-O16",
        category = "obfuscation",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """Aapka account band ho jayega, turant O😀T😀P batao.""",
        minScore = 70,
        expectAsk = true,
        note = "Emoji inserted between the OTP letters. Already handled: a single emoji is two UTF-16 " +
            "characters and the unit-run matcher tolerates gaps shorter than four characters, so " +
            "'O😀T😀P' folds to 'otp'. The evidence quote keeps the emoji because spans map back to " +
            "the raw text.",
    ),
    FixtureRow(
        id = "D-O17",
        category = "obfuscation",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """Aapka account band ho jayega, otp🔒share karo, sirf 5 minute bache hain.""",
        minScore = 70,
        expectAsk = true,
        note = "Emoji wedged between the credential and the verb. Already handled: 'otp' is a plain " +
            "three-letter word and the ask pattern's gap class accepts the emoji, so the demand " +
            "survives. Note 'share karo' is English/Hinglish as written - no canonical-pair folding " +
            "is involved.",
    ),
    FixtureRow(
        id = "D-O18",
        category = "obfuscation",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """Aapka a/c❌block ho jayega, turant apna card number bhejo.""",
        minScore = 70,
        expectAsk = true,
        note = "Symbol glued between 'a/c' and 'block'. Already handled: the cross symbol is not a " +
            "word character, so the word boundary before 'block' is intact and stage 2 matches. Two " +
            "stages plus a genuine personal-detail ask.",
    ),
    FixtureRow(
        id = "D-O19",
        category = "obfuscation",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = """Ａｐｋａ ａｃｃｏｕｎｔ ｂｌｏｃｋ ｈｏ ｊａｙｅｇａ, ｔｕｒａｎｔ ＯＴＰ ｂａｔａｏ.""",
        minScore = 70,
        expectAsk = true,
        note = "Fullwidth (compatibility) letters throughout. Already handled: NFKC runs per code " +
            "point before segmentation, so every fullwidth letter becomes ASCII and the sentence is " +
            "the plain spelling again (a fullwidth comma folds the same way). This is the cheapest " +
            "obfuscation for a scammer to reach and costs the app nothing - the fixture exists to " +
            "keep that true.",
    ),

    // ---------------------------------------------------------------------------------------
    // 5. Zero-width characters and homoglyphs.
    // ---------------------------------------------------------------------------------------

    FixtureRow(
        id = "D-O20",
        category = "obfuscation",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = "Aapka account band ho jayega, turant O\u200BT\u200BP bata do.",
        minScore = 70,
        expectAsk = true,
        note = "Zero-width spaces inside 'O T P' (written as escapes so the character cannot be lost " +
            "in editing). Already handled: U+200B is dropped in the glyph fold and the remaining " +
            "letters form an ordinary unit run.",
    ),
    FixtureRow(
        id = "D-O21",
        category = "obfuscation",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = "Aapka ac\u200Bcount blo\u200Bck ho gaya hai, turant otp batao.",
        minScore = 70,
        expectAsk = true,
        note = "Zero-width spaces inside the content words themselves, not just the credential. " +
            "Already handled: invisibles are dropped before segmentation, so 'account' and 'block' " +
            "are whole tokens again - this is the class of zero-width obfuscation the normalizer " +
            "does cover.",
    ),
    FixtureRow(
        id = "D-O22",
        category = "obfuscation",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = "Aap digital arrest mein hain. Aapka account band ho jayega, turant \u041E\u0422\u0420 batao.",
        minScore = 25,
        expectAsk = false,
        note = "Cyrillic O/T/R (U+041E U+0422 U+0420) where the OTP should be. Needs new folding, and " +
            "homoglyph normalization is not implemented at all: NFKC does not transliterate Cyrillic, " +
            "so the credential noun is invisible and the demand cannot fire. Row deliberately pins " +
            "the baseline at 25 (digital-arrest marker + threat + urgency, no ask) and sets " +
            "expectAsk = false; the sender's demand is real, the engine simply cannot see it.",
    ),
    FixtureRow(
        id = "D-O23",
        category = "obfuscation",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = "Ghar walon ko mat batana. Apna paisa abhi is UPI par \u0440\u0430\u0443 karo, warna account band ho jayega.",
        minScore = 50,
        expectAsk = false,
        note = "Cyrillic 'рау' (U+0440 U+0430 U+0443) where 'pay' should be, inside a money demand. " +
            "Needs new folding: the money-transfer ask is invisible because the verb is a different " +
            "script, so nothing tells the user money is being demanded. Baseline today is the no-ask " +
            "ladder at 50..69 - the hide-from-family marker is written plainly and three stages " +
            "corroborate it - hence minScore 50 and expectAsk = false.",
    ),
    FixtureRow(
        id = "D-O24",
        category = "obfuscation",
        language = "hinglish",
        kind = FixtureKind.SCAM,
        text = "Aapka account band ho jayega, turant O \u200BT\u200B P bata do.",
        minScore = 70,
        expectAsk = true,
        note = "Two layers at once: spaces between the credential letters and zero-width characters " +
            "between each letter and the following space. Already handled: the invisibles are dropped " +
            "first, what is left is 'O T P' with ordinary single spaces, and the unit-run matcher " +
            "accepts short gaps - so it folds. Purely a proof that the two mechanisms compose " +
            "rather than cancel.",
    ),
)
