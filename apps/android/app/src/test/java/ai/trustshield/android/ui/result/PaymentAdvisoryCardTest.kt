package ai.trustshield.android.ui.result

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM source-contract checks for the card's user-visible and accessible UI path. */
class PaymentAdvisoryCardTest {
    @Test
    fun cardExposesOneMergedTalkBackNodeWithAllVisibleContentInOrder() {
        val source = File("src/main/java/ai/trustshield/android/ui/result/PaymentAdvisoryCard.kt").readText()

        assertTrue("card should be a visible full-width surface", source.contains("Surface(") && source.contains(".fillMaxWidth()"))
        assertTrue("card content should merge into one accessible node", source.contains(".semantics(mergeDescendants = true)"))
        assertTrue("merged node should expose its spoken description", source.contains("contentDescription = spokenDescription"))

        val spokenClauses = listOf(
            "append(sectionTitle)",
            "append(headline)",
            "append(advice)",
            "hintRows.forEach { row ->",
            "append(confirmation)"
        ).map(source::indexOf)
        assertTrue("every expected spoken clause should be present", spokenClauses.all { it >= 0 })
        assertTrue("TalkBack clauses should be in strict title, verdict, advice, hints, confirmation order", spokenClauses.zipWithNext().all { (before, after) -> before < after })
        assertTrue("confirmation must be the final spoken-description clause", source.indexOf("append(confirmation)") > source.indexOf("hintRows.forEach { row ->"))

        val drawnContent = listOf(
            "Text(\n                text = sectionTitle",
            "AdvisoryVerdictBlock(verdict = advisory.verdict",
            "hintRows.forEach { row ->",
            "AdvisoryConfirmationLine(text = confirmation)"
        ).map(source::indexOf)
        assertTrue("every expected visible element should be present", drawnContent.all { it >= 0 })
        assertTrue("visible content should have strict title, verdict, hints, confirmation order", drawnContent.zipWithNext().all { (before, after) -> before < after })
        assertTrue("visible confirmation must follow the hint loop", drawnContent.last() > source.indexOf("hintRows.forEach { row ->"))
        assertTrue("section heading should have heading semantics", source.contains("Modifier.semantics { heading() }"))
    }

    @Test
    fun accessibleCardDoesNotRelyOnColorAndSupportsWrappedScalableText() {
        val source = File("src/main/java/ai/trustshield/android/ui/result/PaymentAdvisoryCard.kt").readText()

        assertTrue("verdict must have a visible text glyph", source.contains("glyph = advisoryVerdictGlyph(verdict)"))
        assertTrue("each hint must have a visible severity glyph", source.contains("glyph = row.first"))
        assertTrue("hint copy should wrap within available row width", source.contains("Modifier.weight(1f)"))
        assertFalse("fixed height may clip enlarged text", Regex("\\.height\\(").containsMatchIn(source))
        assertFalse("line cap may truncate enlarged text", Regex("\\b(maxLines|softWrap\\s*=\\s*false)\\b").containsMatchIn(source))
        assertFalse("fixed font size opts out of scalable typography", Regex("\\bfontSize\\s*=").containsMatchIn(source))
        assertTrue("headline should use scalable Material typography", source.contains("MaterialTheme.typography.titleMedium"))
        assertTrue("body copy should use scalable Material typography", source.contains("MaterialTheme.typography.bodyMedium"))
    }

    @Test
    fun resultScreenShowsTheCardOnlyWhenAdvisoryIsAvailable() {
        val source = File("src/main/java/ai/trustshield/android/ui/result/ResultScreen.kt").readText()

        assertTrue(source.contains("state.paymentAdvisory?.let { advisory ->"))
        assertTrue(source.contains("PaymentAdvisoryCard(advisory = advisory, language = language)"))
        assertTrue("payment copy should precede confidence and evidence", source.indexOf("PaymentAdvisoryCard(advisory = advisory, language = language)") <
            source.indexOf("ConfidenceBadgeView(badge = state.confidence, language = language)"))
        assertTrue("evidence should follow confidence", source.indexOf("EvidenceSection(state = state, language = language)") >
            source.indexOf("ConfidenceBadgeView(badge = state.confidence, language = language)"))
    }
}
