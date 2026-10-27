package ai.trustshield.android.ui.result

import ai.trustshield.android.core.payment.PaymentAdvisoryVerdict
import ai.trustshield.android.core.payment.PaymentHint
import ai.trustshield.android.core.payment.PaymentHintKind
import ai.trustshield.android.core.payment.PaymentHintSeverity
import ai.trustshield.android.ui.theme.ToneCopy

/**
 * Bilingual copy for the payment advisory card ([PaymentAdvisoryCard]).
 *
 * ## What this object is allowed to say
 * A payment screenshot never proves that money moved, and a hint is never a finding about a person
 * (`core/payment/PaymentAdvisoryContract.kt`, `docs/android/BEHAVIOR_SPEC.md` "QR and payment
 * semantics", `docs/android/UI_SPEC.md` "Copy and states"). So every string here:
 *
 *  - describes a **pattern / hint**, never a confirmed fraud ("hint hai, fraud ka proof nahi");
 *  - is **actionable** — one thing the user can do next — instead of alarmist;
 *  - never carries an absolute claim: no "100%", no "guaranteed", no "safe to pay", no "verified"
 *    and nothing `AbsoluteSafetyGuard` would have to scrub;
 *  - never invents information: the payee is printed only from [PaymentHint.maskedPayee] (already
 *    `sho***@okaxis`-shaped, never a raw UPI ID) and an amount only from [PaymentHint.amountMinor].
 *    When a hint has no masked payee or no amount, the copy falls back to a generic sentence rather
 *    than printing a placeholder or a figure the user never saw.
 *
 * ## Language policy
 * Hinglish is the default voice (`UiLanguage.HINGLISH`): short Roman-Hindi sentences, everyday
 * words, no formal Sanskritised vocabulary. English is plain Indian English. Hindi is written in
 * Devanagari and passed as the **third** [ToneCopy] argument on purpose, so the fallback table in
 * `ui/theme/HindiCopy` is never consulted for this card. Rendering always goes through
 * `ToneCopy.pick(language)`.
 *
 * ## The mandatory last line
 * [CONFIRMATION] is not optional and is **not** another hint: the card must render it as the last
 * element of the advisory (and as the last clause of the card's content description) so that a user
 * — or TalkBack — hears where the real confirmation lives: their own bank SMS or UPI app history.
 * `PaymentAdvisory.confirmationRequired` is always `true`; the copy layer cannot disable it.
 *
 * `PaymentHint.count` is deliberately not printed: none of the current hint kinds needs a counter,
 * and printing an unexplained number would be one more thing the user has to decode.
 */
object PaymentAdvisoryCopy {

    /** The currency mark, escaped so the source file stays readable in any editor. */
    private const val RUPEE_SIGN: String = "\u20B9"

    private const val ZERO_RUPEES: String = RUPEE_SIGN + "0"

    /** Template markers replaced by [withAmount] / [withPayee]; never rendered as-is. */
    private const val AMOUNT: String = "{amount}"
    private const val PAYEE: String = "{payee}"

    /** Section heading drawn above the advisory card. */
    val SECTION_TITLE: ToneCopy = ToneCopy(
        hinglish = "Payment check",
        english = "Payment check",
        hindi = "भुगतान जाँच"
    )

    /**
     * The mandatory confirmation line — **must be rendered last** by the UI.
     *
     * The Hinglish wording is pinned character-for-character to
     * `BEHAVIOR_SPEC.md` ("Always show: Pakka confirmation sirf aapke bank SMS ya UPI app ki history
     * mein milega.") and to `PaymentAdvisoryContract`'s doc comment, so it cannot drift.
     */
    val CONFIRMATION: ToneCopy = ToneCopy(
        hinglish = "Pakka confirmation sirf aapke bank SMS ya UPI app ki history mein milega.",
        english = "Final confirmation only comes from your bank SMS or your UPI app's history.",
        hindi = "अंतिम पुष्टि केवल आपके बैंक SMS या UPI ऐप के लेन-देन इतिहास से ही मिलती है।"
    )

    /**
     * One text glyph per severity, so colour is never the only channel telling WARNING from INFO
     * (`docs/android/UI_SPEC.md` accessibility acceptance). The marks match the risk tones
     * (`!`, `‼`) and stay readable on a device with no emoji or icon font.
     */
    fun severityGlyph(hint: PaymentHint): String = when (hint.severity) {
        PaymentHintSeverity.INFO -> GLYPH_INFO
        PaymentHintSeverity.CAUTION -> GLYPH_CAUTION
        PaymentHintSeverity.WARNING -> GLYPH_WARNING
    }

    private const val GLYPH_INFO: String = "\u2139"     // ℹ
    private const val GLYPH_CAUTION: String = "!"       // !
    private const val GLYPH_WARNING: String = "\u203C"  // ‼

    /**
     * Verdict headline.
     *
     * [PaymentAdvisoryVerdict.CLEAR] says only that **no payment-specific warning hint was found** —
     * it never says "safe" and never says the payment is verified. [PaymentAdvisoryVerdict.STOP]
     * always reads as *do not pay yet*, which is the strongest thing this screen may say.
     */
    fun verdictHeadline(verdict: PaymentAdvisoryVerdict): ToneCopy = when (verdict) {
        PaymentAdvisoryVerdict.CLEAR -> ToneCopy(
            hinglish = "Payment ke liye koi khatre ka hint nahi mila",
            english = "No payment-specific warning hint found",
            hindi = "भुगतान से जुड़ा कोई चेतावनी संकेत नहीं मिला"
        )
        PaymentAdvisoryVerdict.CHECK -> ToneCopy(
            hinglish = "Is payment ko pehle check karein",
            english = "Check this payment before you pay",
            hindi = "भुगतान से पहले इसकी जाँच करें"
        )
        PaymentAdvisoryVerdict.STOP -> ToneCopy(
            hinglish = "Abhi payment na karein",
            english = "Do not pay yet",
            hindi = "अभी भुगतान न करें"
        )
    }

    /**
     * One practical next step per verdict — never a promise about the outcome.
     *
     * CLEAR still asks the user to check the amount and payee themselves on the payment screen;
     * CHECK asks them to confirm who the payee actually is; STOP asks them to talk to the shop or
     * payee directly and to pay only through their own UPI app.
     */
    fun verdictAdvice(verdict: PaymentAdvisoryVerdict): ToneCopy = when (verdict) {
        PaymentAdvisoryVerdict.CLEAR -> ToneCopy(
            hinglish = "Amount aur payee payment screen par khud check karein, phir pay karein.",
            english = "Still check the amount and payee on the payment screen yourself, then pay.",
            hindi = "भुगतान स्क्रीन पर राशि और प्राप्तकर्ता स्वयं जाँचें, फिर भुगतान करें।"
        )
        PaymentAdvisoryVerdict.CHECK -> ToneCopy(
            hinglish = "Payee ki pehchan confirm karein, jaise dukaan par khud poochh kar, phir pay karein.",
            english = "Confirm who the payee is — for example by asking at the shop — before you pay.",
            hindi = "भुगतान करने से पहले पुष्टि करें कि पैसा किसे जा रहा है, जैसे दुकान पर खुद पूछकर।"
        )
        PaymentAdvisoryVerdict.STOP -> ToneCopy(
            hinglish = "Dukaandaar ya payee se seedha baat karein aur apne UPI app se hi pay karein.",
            english = "Talk to the shop or the payee directly, and pay only through your own UPI app.",
            hindi = "दुकानदार या प्राप्तकर्ता से सीधे बात करें और भुगतान अपने UPI ऐप से ही करें।"
        )
    }

    /**
     * Copy for one hint. The `when` lists **every** [PaymentHintKind] explicitly and has no `else`,
     * so adding a kind to the pinned contract fails compilation here instead of silently rendering
     * an empty row.
     */
    fun forHint(hint: PaymentHint): ToneCopy = when (hint.kind) {
        PaymentHintKind.PAYEE_MATCHES_REGISTERED -> withPayee(
            hint = hint,
            generic = ToneCopy(
                hinglish = "Yeh payee aapke save kiye hue payee se match karta hai. Phir bhi amount khud check karein.",
                english = "This payee matches a payee you saved. Still check the amount yourself.",
                hindi = "यह प्राप्तकर्ता आपके सहेजे गए प्राप्तकर्ता से मेल खाता है। फिर भी राशि स्वयं जाँचें।"
            ),
            hinglish = "Yeh payee {payee} aapke save kiye hue payee se match karta hai. Phir bhi amount khud check karein.",
            english = "This payee {payee} matches a payee you saved. Still check the amount yourself.",
            hindi = "यह प्राप्तकर्ता {payee} आपके सहेजे गए प्राप्तकर्ता से मेल खाता है। फिर भी राशि स्वयं जाँचें।"
        )
        PaymentHintKind.PAYEE_NOT_REGISTERED -> withPayee(
            hint = hint,
            generic = ToneCopy(
                hinglish = "Yeh payee aapke save kiye hue payee mein nahi hai. Yeh sirf ek hint hai, fraud ka proof nahi.",
                english = "This payee is not among the payees you saved. That is only a hint, not proof of fraud.",
                hindi = "यह प्राप्तकर्ता आपके सहेजे गए प्राप्तकर्ताओं में नहीं है। यह केवल एक संकेत है, धोखाधड़ी का प्रमाण नहीं।"
            ),
            hinglish = "Yeh payee {payee} aapke save kiye hue payee mein nahi hai. Yeh sirf ek hint hai, fraud ka proof nahi.",
            english = "This payee {payee} is not among the payees you saved. That is only a hint, not proof of fraud.",
            hindi = "यह प्राप्तकर्ता {payee} आपके सहेजे गए प्राप्तकर्ताओं में नहीं है। यह केवल एक संकेत है, धोखाधड़ी का प्रमाण नहीं।"
        )
        PaymentHintKind.PAYEE_MISSING -> ToneCopy(
            hinglish = "Screenshot se koi UPI ID padhi nahi ja saki. Payee dekhe bina paisa na bhejein.",
            english = "No UPI ID could be read from the screenshot. Do not send money without seeing the payee.",
            hindi = "स्क्रीनशॉट से कोई UPI ID पढ़ी नहीं जा सकी। प्राप्तकर्ता देखे बिना पैसे न भेजें।"
        )
        PaymentHintKind.PAYEE_NAME_SPOOFABLE -> ToneCopy(
            hinglish = "Screen par dikh raha naam aur UPI ID, dono apne UPI app mein check karein.",
            english = "Check both the displayed name and UPI ID in your own UPI app.",
            hindi = "अपने UPI ऐप में दिख रहा नाम और UPI ID, दोनों जाँचें।"
        )
        PaymentHintKind.NO_REGISTERED_PAYEES -> ToneCopy(
            hinglish = "Is phone par koi payee save nahi hai, isliye payee ki tulna nahi ho saki.",
            english = "No payee is saved on this phone, so the payee could not be compared.",
            hindi = "इस फ़ोन पर कोई प्राप्तकर्ता सहेजा नहीं गया है, इसलिए प्राप्तकर्ता की तुलना नहीं हो सकी।"
        )
        PaymentHintKind.AMOUNT_LARGE -> withAmount(
            hint = hint,
            generic = ToneCopy(
                hinglish = "Yeh amount bada hai. Itni badi raashi bhejne se pehle payee se baat karein.",
                english = "This amount is large. Talk to the payee before sending a sum this big.",
                hindi = "यह राशि बड़ी है। इतनी बड़ी रकम भेजने से पहले प्राप्तकर्ता से बात करें।"
            ),
            hinglish = "Yeh amount {amount} bada hai. Itni badi raashi bhejne se pehle payee se baat karein.",
            english = "This amount, {amount}, is large. Talk to the payee before sending a sum this big.",
            hindi = "यह राशि {amount} बड़ी है। इतनी बड़ी रकम भेजने से पहले प्राप्तकर्ता से बात करें।"
        )
        PaymentHintKind.AMOUNT_ROUND_LARGE -> withAmount(
            hint = hint,
            generic = ToneCopy(
                hinglish = "Badi gol amount apne aap mein scam ka proof nahi hai. Refund ya fees ki baat ho, toh details khud check karein.",
                english = "A large round amount is not proof of a scam by itself. If it is described as a refund or fee, check the details yourself.",
                hindi = "बड़ी गोल राशि अपने आप में धोखाधड़ी का प्रमाण नहीं है। इसे रिफ़ंड या फ़ीस बताया जाए, तो विवरण स्वयं जाँचें।"
            ),
            hinglish = "{amount} ek badi gol amount hai. Refund ya fees ki baat ho, toh details khud check karein.",
            english = "{amount} is a large round amount. If it is described as a refund or fee, check the details yourself.",
            hindi = "{amount} एक बड़ी गोल राशि है। इसे रिफ़ंड या फ़ीस बताया जाए, तो विवरण स्वयं जाँचें।"
        )
        PaymentHintKind.AMOUNT_MISSING -> ToneCopy(
            hinglish = "Screenshot mein amount padha nahi ja saka. Screen par amount dekhe bina pay na karein.",
            english = "No amount could be read from the screenshot. Do not pay without seeing the amount on the screen.",
            hindi = "स्क्रीनशॉट में राशि पढ़ी नहीं जा सकी। स्क्रीन पर राशि देखे बिना भुगतान न करें।"
        )
        PaymentHintKind.STATUS_PENDING -> ToneCopy(
            hinglish = "Screenshot pending dikhata hai. Paisa pahuncha ya nahi, yeh bank SMS ya UPI history se dekhein.",
            english = "The screenshot says pending. Check your bank SMS or UPI history to see whether the money arrived.",
            hindi = "स्क्रीनशॉट में स्थिति लंबित दिख रही है। पैसा पहुँचा या नहीं, यह बैंक SMS या UPI इतिहास से देखें।"
        )
        PaymentHintKind.STATUS_FAILED -> ToneCopy(
            hinglish = "Screenshot failed ya cancelled dikhata hai. Dobara pay karne se pehle apni UPI history check karein.",
            english = "The screenshot says failed or cancelled. Check your UPI history before paying again.",
            hindi = "स्क्रीनशॉट में भुगतान असफल या रद्द दिख रहा है। दोबारा भुगतान करने से पहले अपना UPI इतिहास देखें।"
        )
        PaymentHintKind.STATUS_UNKNOWN -> ToneCopy(
            hinglish = "Status line padhi nahi ja saki, isliye paisa pahuncha ya nahi yeh pata nahi. Bank SMS se confirm karein.",
            english = "No status line could be read, so it is not known whether the money arrived. Confirm from your bank SMS.",
            hindi = "स्थिति की पंक्ति पढ़ी नहीं जा सकी, इसलिए पैसा पहुँचा या नहीं यह ज्ञात नहीं। बैंक SMS से पुष्टि करें।"
        )
        PaymentHintKind.UTR_MISSING -> ToneCopy(
            hinglish = "Screenshot success dikhata hai par koi UTR ya reference number nahi hai. Yeh settlement ka proof nahi hai.",
            english = "The screenshot says success but shows no UTR or reference number. That is not proof that the payment settled.",
            hindi = "स्क्रीनशॉट सफलता दिखाता है पर कोई UTR या संदर्भ संख्या नहीं है। यह भुगतान पूरा होने का प्रमाण नहीं है।"
        )
        PaymentHintKind.UTR_PRESENT -> ToneCopy(
            hinglish = "Screenshot par UTR dikh raha hai. Isse apni UPI app ki history se mila kar dekhein.",
            english = "A UTR is visible on the screenshot. Match it against the history in your own UPI app.",
            hindi = "स्क्रीनशॉट पर UTR दिख रहा है। इसे अपने UPI ऐप के इतिहास से मिलाकर देखें।"
        )
        PaymentHintKind.TIME_MISSING -> ToneCopy(
            hinglish = "Koi date ya time padha nahi ja saka, isliye haal ka payment wali jaanch nahi ho saki.",
            english = "No date or time could be read, so the recent-payment check could not run.",
            hindi = "कोई तारीख़ या समय पढ़ा नहीं जा सका, इसलिए हाल के भुगतान वाली जाँच नहीं हो सकी।"
        )
        PaymentHintKind.TIME_RECENT -> ToneCopy(
            hinglish = "Screenshot ka time bilkul haal ka hai. Turant bhejne ka dabaav ho to ruk kar sochein.",
            english = "The screenshot's time is very recent. If there is pressure to pay at once, stop and think.",
            hindi = "स्क्रीनशॉट का समय बहुत हाल का है। तुरंत भेजने का दबाव हो तो रुककर सोचें।"
        )
        PaymentHintKind.URGENCY_PRESSURE -> ToneCopy(
            hinglish = "Message mein jaldi, abhi ya turant jaisa dabaav hai. Jaldbaazi scam ka aam pattern hai.",
            english = "The message pressures you to act quickly, now or immediately. Urgency is a common scam pattern.",
            hindi = "संदेश में जल्दी, अभी या तुरंत जैसा दबाव है। जल्दबाज़ी धोखाधड़ी का आम पैटर्न है।"
        )
        PaymentHintKind.SCREENSHOT_ONLY -> ToneCopy(
            hinglish = "Sirf ek screenshot hai. Screenshot paisa aane ka proof nahi hota — bank SMS ya UPI history dekhein.",
            english = "There is only a screenshot. A screenshot is not proof that money arrived — check your bank SMS or UPI history.",
            hindi = "सिर्फ़ एक स्क्रीनशॉट है। स्क्रीनशॉट पैसा आने का प्रमाण नहीं होता — बैंक SMS या UPI इतिहास देखें।"
        )
    }

    /**
     * Indian-grouped rupee text: **120000 → "₹1,200"**, **120050 → "₹1,200.50"**, **0 → "₹0"**.
     *
     * Grouping is done by hand (`xx,xx,xxx`) instead of through `NumberFormat`, so the rendered
     * string cannot change with the device locale, an Android version or a font. Paise are printed
     * only when non-zero — a whole-rupee amount never grows a ".00". Anything at or below zero
     * renders as "₹0": a hint must never show a negative or a made-up amount.
     */
    fun formatRupees(amountMinor: Long): String {
        if (amountMinor <= 0L) return ZERO_RUPEES
        val rupees = amountMinor / 100L
        val paise = (amountMinor % 100L).toInt()

        val out = StringBuilder(16)
        out.append(RUPEE_SIGN)
        appendIndianGrouping(out, rupees)
        if (paise != 0) {
            out.append('.')
            if (paise < 10) out.append('0')
            out.append(paise)
        }
        return out.toString()
    }

    /**
     * Appends [rupees] with Indian digit grouping: the last three digits stay together, every group
     * above them is two digits (`1,20,000`). A single `StringBuilder` pass, no collections.
     */
    private fun appendIndianGrouping(out: StringBuilder, rupees: Long) {
        val digits = rupees.toString()
        if (digits.length <= 3) {
            out.append(digits)
            return
        }
        val headEnd = digits.length - 3
        var index = 0
        var groupSize = if (headEnd % 2 == 0) 2 else 1
        while (index < headEnd) {
            if (index > 0) out.append(',')
            val end = minOf(index + groupSize, headEnd)
            out.append(digits, index, end)
            index = end
            groupSize = 2
        }
        out.append(',')
        out.append(digits, headEnd, digits.length)
    }

    /**
     * Fills `{amount}` when the hint carries a positive amount, otherwise returns [generic] so no
     * placeholder and no invented figure can reach the screen.
     */
    private fun withAmount(
        hint: PaymentHint,
        generic: ToneCopy,
        hinglish: String,
        english: String,
        hindi: String
    ): ToneCopy {
        val amount = hint.amountMinor?.takeIf { it > 0L }?.let { formatRupees(it) } ?: return generic
        return ToneCopy(
            hinglish = hinglish.replace(AMOUNT, amount),
            english = english.replace(AMOUNT, amount),
            hindi = hindi.replace(AMOUNT, amount)
        )
    }

    /**
     * Fills `{payee}` with the **masked** payee only ([PaymentHint.maskedPayee]), otherwise returns
     * [generic]. A raw UPI ID is never an input here by construction.
     */
    private fun withPayee(
        hint: PaymentHint,
        generic: ToneCopy,
        hinglish: String,
        english: String,
        hindi: String
    ): ToneCopy {
        val payee = hint.maskedPayee.trim()
        if (payee.isEmpty()) return generic
        return ToneCopy(
            hinglish = hinglish.replace(PAYEE, payee),
            english = english.replace(PAYEE, payee),
            hindi = hindi.replace(PAYEE, payee)
        )
    }
}
