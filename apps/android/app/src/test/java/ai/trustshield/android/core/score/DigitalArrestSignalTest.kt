package ai.trustshield.android.core.score

import ai.trustshield.android.core.model.Evidence
import ai.trustshield.android.core.model.RiskLabel
import ai.trustshield.android.core.model.StageId
import ai.trustshield.android.core.model.Strength
import ai.trustshield.android.core.normalize.HinglishNormalizer
import ai.trustshield.android.core.rules.DigitalArrestSignals
import ai.trustshield.android.ui.call.CallDemoRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Gate for the separate strong deterministic signal (report §6 digital-arrest markers) and for the
 * documented no-ask band ladder that consumes it (issue #119 defect A).
 *
 * JVM-only plain JUnit4: no Android runtime, no Compose, no microphone, no clock. Every expectation
 * is a band or an exact engine number, never a preferred position.
 */
class DigitalArrestSignalTest {

    private val normalizer = HinglishNormalizer()
    private val scorer = LocalScorer(normalizer)

    // ---------------------------------------------------------------------------------------------
    // The signal itself
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `every report section 6 marker yields strong evidence with an exact quote`() {
        val fixtures = mapOf(
            "aap digital arrest mein hain" to "Aap digital arrest mein hain.",
            "video call on rakhiye" to "Video call on rakhiye, kuch mat kijiye.",
            "camera band mat karna" to "Camera band mat karna, sirf aawaz chahiye.",
            "ghar walon ko mat batana" to "Yeh baat ghar walon ko mat batana.",
            "kamre mein akele raho" to "Aap kamre mein akele raho.",
            "supreme court ka order" to "Supreme Court ka order aa gaya hai aapke naam par.",
            "arrest warrant" to "Aapka arrest warrant issue ho chuka hai.",
            "fd tod kar transfer karo" to "Apni FD tod kar transfer karo.",
        )
        for ((marker, text) in fixtures) {
            val evidence = DigitalArrestSignals.detect(text)
            assertTrue("no signal for $marker", evidence.isNotEmpty())
            assertTrue("signal for $marker must be STRONG", evidence.all { it.strength == Strength.STRONG })
            for (item in evidence) {
                assertEquals(DigitalArrestSignals.MODULE, item.module)
                assertNotNull("$marker: quote missing", item.quote)
                assertNotNull("$marker: span missing", item.span)
                assertTrue(
                    "$marker: quote must be an exact substring",
                    item.quoteVerifiesAgainst(text),
                )
                assertEquals(text.substring(item.span!!.start, item.span!!.end), item.quote)
            }
        }
    }

    @Test
    fun `ordinary text and the protective twin pairs produce no signal`() {
        val benign = listOf(
            "Aapka OTP 482913 hai. Kisi ke saath share na karein.",
            "123456 is your OTP for txn. Do not share it with anyone.",
            "Aapke khaate mein Rs 5,000 jama hue hain. Avl bal 12,000.",
            "KYC due hai, branch ya net banking se update karein.",
            "Bijli ka bill 30 Oct ko due hai.",
            "Main SBI head office se bol raha hoon",
            "Aapke Aadhaar se bheje gaye parcel mein drugs mile hain.",
            "",
            "   ",
        )
        for (text in benign) {
            assertTrue("false positive on: $text", DigitalArrestSignals.detect(text).isEmpty())
        }
    }

    @Test
    fun `repeated markers collapse and the list length is capped`() {
        // Hardening follow-up: a bare third-person mention no longer counts (it is how an advisory or a
        // news line reads), so the collapse property is pinned on the second-person scam phrasing.
        val text = "Aap digital arrest mein hain. Aap digital arrest mein hain. " +
            "Aap digital arrest mein hain."
        assertEquals(1, DigitalArrestSignals.detect(text).size)

        // A bare mention with no addressee is advisory-shaped and must be silent.
        assertEquals(
            0,
            DigitalArrestSignals.detect("Digital arrest. Digital arrest. Digital arrest.").size,
        )

        val many = "Aap digital arrest mein hain. Camera band mat karna. Kamre mein akele raho. " +
            "Ghar walon ko mat batana. Aapka arrest warrant hai."
        assertTrue(DigitalArrestSignals.detect(many).size <= DigitalArrestSignals.MAX_SIGNALS)
    }

    @Test
    fun `detection is deterministic and never throws`() {
        val text = "Aap digital arrest mein hain. Video call on rakhiye."
        val first = DigitalArrestSignals.detect(text)
        val second = DigitalArrestSignals.detect(text)
        assertEquals(first.map { it.id }, second.map { it.id })
        assertEquals(first.map { it.quote }, second.map { it.quote })
    }

    // ---------------------------------------------------------------------------------------------
    // Band ladder (BEHAVIOR_SPEC "unless a separate strong deterministic signal applies")
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `no strong signal keeps a no-ask transcript at the watch ceiling`() {
        // authority + threat + secrecy, no ask, no digital-arrest marker: unchanged behaviour.
        val risk = scorer.analyze(
            "Main SBI se bol raha hoon. Aapka account band ho jayega, yeh confidential hai.",
        ).risk
        assertTrue("no ask => watch, was ${risk.score}", risk.score <= 24)
        assertEquals(RiskLabel.LOW_RISK, risk.label)
    }

    @Test
    fun `strong signal with two independent stages reaches caution but not suspicious`() {
        val text = "Main Mumbai Cyber Crime se Inspector Rathore bol raha hoon. " +
            "Aapke Aadhaar se bheje gaye parcel mein drugs mile hain. Aap digital arrest mein hain."
        val scored = scorer.analyzeScored(text)
        val risk = scored.result.risk
        assertTrue("expected 25..49, was ${risk.score}", risk.score in 25..49)
        assertEquals(RiskLabel.CAUTION, risk.label)
        assertFalse("a no-ask transcript must never escalate", scored.escalateCritical)
        assertFalse(scored.verifiedAsk)
    }

    @Test
    fun `strong signal with three independent stages reaches suspicious but never high risk`() {
        val text = "Main Mumbai Cyber Crime se Inspector Rathore bol raha hoon. " +
            "Aapke Aadhaar se bheje gaye parcel mein drugs mile hain. Aap digital arrest mein hain. " +
            "Yeh confidential case hai, kisi ko mat batana, video call cut mat karna."
        val scored = scorer.analyzeScored(text)
        val risk = scored.result.risk
        assertTrue("expected 50..69, was ${risk.score}", risk.score in 50..69)
        assertEquals(RiskLabel.SUSPICIOUS, risk.label)
        assertFalse("no ask => no release", scored.escalateCritical)
        assertTrue(scored.result.stages.containsAll(
            listOf(StageId.AUTHORITY, StageId.THREAT_OR_REWARD, StageId.SECRECY),
        ))
    }

    @Test
    fun `a signal with no playbook stage stays a watch reading`() {
        val risk = scorer.analyze("Aap digital arrest mein hain.").risk
        assertTrue("one phrase alone must not accuse, was ${risk.score}", risk.score <= 24)
        assertEquals(RiskLabel.LOW_RISK, risk.label)
    }

    @Test
    fun `a signal with a single other stage stays a watch reading`() {
        // "arrest warrant" is one THREAT stage and nothing else: one stage + the signal is not a
        // corroborated pattern, so the ladder keeps it at the watch ceiling.
        val risk = scorer.analyze("Aapka arrest warrant issue ho chuka hai.").risk
        assertTrue("one other stage must stay watch, was ${risk.score}", risk.score <= 24)
        assertEquals(RiskLabel.LOW_RISK, risk.label)
    }

    @Test
    fun `a quoted marker inside a reported warning is not a signal`() {
        // The marker sits inside the words the sender is *quoting* (A04 quoted regions).
        val text = "Fraudster ne kaha \"aap digital arrest mein hain\" - yeh dhokha hai. " +
            "Main CBI se bol raha hoon. Aapke parcel mein drugs mile hain."
        val scored = scorer.analyzeScored(text)
        assertTrue(
            "a quoted marker must not lift the ladder, was ${scored.result.risk.score}",
            scored.result.risk.score <= 24,
        )
    }

    @Test
    fun `the signal evidence travels with the result and keeps its quote`() {
        val text = "Main CBI se bol raha hoon. Aapke parcel mein drugs mile hain. " +
            "Aap digital arrest mein hain."
        val scored = scorer.analyzeScored(text)
        val signal = scored.result.evidence.filter { it.module == DigitalArrestSignals.MODULE }
        assertTrue("signal evidence missing", signal.isNotEmpty())
        for (item in signal) {
            val quote = item.quote
            assertNotNull(quote)
            assertTrue(item.quoteVerifiesAgainst(scored.analyzedText))
            assertNotNull("quote must resolve to a span", Evidence.locate(scored.analyzedText, quote!!))
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Headline evidence: the shipped demo ladder, printed from a real run
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `print the measured demo ladder`() {
        val runner = CallDemoRunner()
        val sb = StringBuilder()
        sb.appendLine("step|score|label|confidence|stages|escalate|position|haptics|litChips")
        while (!runner.progress().finished) {
            val step = runner.advance().steps.last()
            sb.append(step.index).append('|')
                .append(step.score).append('|')
                .append(step.label).append('|')
                .append(step.confidence).append('|')
                .append(step.stages.joinToString(",")).append('|')
                .append(step.escalated).append('|')
                .append(step.position).append('|')
                .append(step.haptics).append('|')
                .append(step.chips.filter { it.lit }.joinToString(",") { it.stage.name })
                .appendLine()
            for (chip in step.chips.filter { it.lit }) {
                sb.append("    chip ").append(step.index).append(' ')
                    .append(chip.stage.name).append(" -> ")
                    .append(chip.quotes.joinToString(" | "))
                    .appendLine()
            }
        }
        val rendered = sb.toString()
        val out = File(System.getProperty("java.io.tmpdir"), "ts-ladder.txt")
        out.writeText(rendered)
        println("LADDER_WRITTEN=" + out.absolutePath)
        println(rendered)
    }
}
