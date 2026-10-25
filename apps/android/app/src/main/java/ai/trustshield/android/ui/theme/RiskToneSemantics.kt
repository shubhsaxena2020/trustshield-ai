package ai.trustshield.android.ui.theme

/**
 * A short bilingual label. Same shape the rest of the app uses for copy (`docs/android` section 5):
 * simple Hinglish by default, English available as an option.
 */
data class ToneCopy(val hinglish: String, val english: String)

/**
 * Everything a screen must render alongside a [RiskTone] so that **colour is never the only
 * differentiator** (HEAD_ORDERS §2, UI_SPEC "label+icon+text as well as color", Quality gate #6).
 *
 * A screen using a tone MUST draw:
 *  1. [label] as visible text (Hinglish by default, English when the user picked English);
 *  2. [iconName] as a Material icon (resolved by the calling screen — C02 owns no `res/`);
 *  3. [contentDescription] on the composite, so TalkBack reads the tone even without colour.
 *
 * Colour is the third, supplementary channel only. [RiskToneCopy.assertions] documents the contract
 * and `ThemeTest` verifies every tone satisfies it.
 */
data class RiskTonePresentation(
    val tone: RiskTone,
    val label: ToneCopy,
    /** Material Icons key, e.g. `Icons.Filled.Shield`. */
    val iconName: String,
    /** Text glyph fallback for previews/tests and for devices without the icon font. */
    val glyph: String,
    val contentDescription: ToneCopy
)

object RiskToneCopy {

    /** Human-facing summary of the non-colour contract; screens must satisfy all three. */
    const val REQUIRES_LABEL_TEXT: Boolean = true
    const val REQUIRES_ICON: Boolean = true
    const val REQUIRES_CONTENT_DESCRIPTION: Boolean = true

    /** Colour is a supplementary channel; it may never be the only signal. */
    const val COLOUR_IS_SOLE_DIFFERENTIATOR: Boolean = false

    val NEUTRAL = RiskTonePresentation(
        tone = RiskTone.NEUTRAL,
        label = ToneCopy("Jaankari", "Information"),
        iconName = "Icons.Outlined.Info",
        glyph = "i",
        contentDescription = ToneCopy(
            "Jaankari — koi khatra nahi mila",
            "Information — no risk signal found"
        )
    )

    val GREEN = RiskTonePresentation(
        tone = RiskTone.GREEN,
        label = ToneCopy("Kam khatra", "Low risk"),
        iconName = "Icons.Outlined.CheckCircle",
        glyph = "✓",
        contentDescription = ToneCopy(
            "Kam khatra — sirf sanket kam mile",
            "Low risk — few warning signals found"
        )
    )

    val AMBER = RiskTonePresentation(
        tone = RiskTone.AMBER,
        label = ToneCopy("Dhyan dein", "Caution"),
        iconName = "Icons.Outlined.WarningAmber",
        glyph = "!",
        contentDescription = ToneCopy(
            "Dhyan dein — kuch khatre ke sanket mile",
            "Caution — some warning signals found"
        )
    )

    val RED = RiskTonePresentation(
        tone = RiskTone.RED,
        label = ToneCopy("Zyada khatra", "High risk"),
        iconName = "Icons.Filled.Report",
        glyph = "!!",
        contentDescription = ToneCopy(
            "Zyada khatra — turant rukhein aur verify karein",
            "High risk — stop and verify now"
        )
    )

    val all: List<RiskTonePresentation> = listOf(NEUTRAL, GREEN, AMBER, RED)

    fun of(tone: RiskTone): RiskTonePresentation = when (tone) {
        RiskTone.NEUTRAL -> NEUTRAL
        RiskTone.GREEN -> GREEN
        RiskTone.AMBER -> AMBER
        RiskTone.RED -> RED
    }

    /** True when the tone's visible text and icon are enough on their own, without colour. */
    fun isSelfDescribing(tone: RiskTone): Boolean {
        val p = of(tone)
        val labelOk = p.label.hinglish.isNotBlank() && p.label.english.isNotBlank()
        val iconOk = p.iconName.isNotBlank() && p.glyph.isNotBlank()
        val cdOk = p.contentDescription.hinglish.isNotBlank() &&
            p.contentDescription.english.isNotBlank()
        return labelOk && iconOk && cdOk && !COLOUR_IS_SOLE_DIFFERENTIATOR
    }
}
