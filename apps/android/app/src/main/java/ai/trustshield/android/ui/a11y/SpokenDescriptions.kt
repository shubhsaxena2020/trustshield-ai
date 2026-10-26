package ai.trustshield.android.ui.a11y

/**
 * Builders for the strings that TalkBack actually announces (#97).
 *
 * Why this exists: several screens merge a card into a single semantics node and then set an
 * explicit `contentDescription`. When a node has both a `contentDescription` and merged child text,
 * TalkBack reads the description and the child text is not guaranteed to be spoken — so any visible
 * sentence missing from the description is effectively invisible to a screen-reader user. Two such
 * drops were found by reading the 26-Oct tree:
 *
 *  * Call Shield's sticky warning announced the body and the "no accusation" line but never the risk
 *    band label ("Zyada khatra"), so the escalation level was only visible, not audible.
 *  * Call Shield's risk meter and the Result risk card announced the band but never their honesty
 *    copy — "Confidence … kisi par shak ka percentage nahi" and the Result band summary.
 *
 * The builders are plain String functions so [SpokenDescriptionsTest] can prove, on a JVM without a
 * device, that no part of a merged card disappears from its announcement.
 */
object SpokenDescriptions {

    /** Terminal punctuation TalkBack already pauses on. */
    private const val TERMINATORS = ".!?\u2026"

    /**
     * Joins [parts] into one announcement: blanks and nulls are skipped, runs of whitespace collapse
     * to a single space, an identical part is announced once, and each part ends with punctuation so
     * the screen reader pauses between statements instead of running them together.
     */
    fun merge(vararg parts: String?): String {
        val seen = LinkedHashSet<String>()
        for (part in parts) {
            val clean = part?.trim()?.replace(WHITESPACE, " ") ?: continue
            if (clean.isEmpty()) continue
            seen.add(withStop(clean))
        }
        return seen.joinToString(" ")
    }

    /**
     * The sticky Call Shield warning: the risk band label first (so the level is heard, not only
     * seen), then the warning body, then the "this is not an accusation" line.
     */
    fun warningBanner(toneLabel: String, body: String, noAccusation: String): String =
        merge(toneLabel, body, noAccusation)

    /**
     * The Call Shield risk meter: band label, band description, the confidence reading with its
     * heading, and both honesty explainers. The explainers are the point — they are what keeps the
     * reading from sounding like a verdict about a person.
     */
    fun riskMeter(
        toneLabel: String,
        toneDescription: String,
        confidenceHeading: String,
        confidenceLabel: String,
        confidenceExplainer: String,
        riskExplainer: String
    ): String = merge(
        toneLabel,
        toneDescription,
        confidenceHeading + ": " + confidenceLabel,
        confidenceExplainer,
        riskExplainer
    )

    /**
     * The Result risk card: the band description plus the band summary. The summary carries the
     * "sirf sanket" caveat, so it must be part of the same announcement as the band.
     */
    fun resultRiskCard(riskDescription: String, summary: String): String =
        merge(riskDescription, summary)

    private fun withStop(text: String): String =
        if (text.last() in TERMINATORS) text else "$text."

    private val WHITESPACE = Regex("\\s+")
}
