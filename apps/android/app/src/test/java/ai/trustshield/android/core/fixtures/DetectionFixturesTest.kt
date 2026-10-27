package ai.trustshield.android.core.fixtures

import ai.trustshield.android.core.analysis.LocalScanPipeline
import ai.trustshield.android.core.model.RiskLabel
import ai.trustshield.android.core.normalize.HinglishNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #137 / PHASE4 items 4+5: run every [DetectionFixtures] row through the **real** on-device
 * engine and hold it to the band BEHAVIOR_SPEC asks for.
 *
 * The path exercised is the one the app runs: [LocalScanPipeline.analyzeText] ->
 * `LocalScorer` -> `HinglishNormalizer` + `StageRules` + `LegitimateIndicators` +
 * `DigitalArrestSignals`. Nothing is stubbed. The original rows assert spec-derived bands; the new
 * rows assert the documented scam detection floor and legitimate ceiling, never 90% model accuracy.
 *
 * This is a **rule fixture** gate, never an accuracy measurement: pass/fail here says "the rule that
 * this family needs fires and lands in the documented band", not "the engine is 90% accurate".
 *
 * Every run prints a machine-readable line per row
 *
 * ```
 * ROW|<id>|<family>|<language>|<kind>|<expected>|<measured>|<score>|<confidence>|<PASS|MISS>|<stages>
 * ```
 *
 * which is what generated (and re-generates) `docs/android/DETECTION_FIXTURES.md`. The table in that
 * document is copied from this output, not typed by hand.
 */
class DetectionFixturesTest {

    private val pipeline = LocalScanPipeline()

    @Test
    fun `expanded inventory has sixty scam and sixty legitimate labeled rows`() {
        val expansion = DetectionFixtures.rows.filter { it.id.startsWith("N") }
        assertEquals(120, expansion.size)
        assertEquals(60, expansion.count { it.kind == FixtureKind.SCAM })
        assertEquals(60, expansion.count { it.kind == FixtureKind.LEGITIMATE })
        assertEquals(10, expansion.map { it.family }.toSet().size)
    }

    @Test
    fun `every fixture row is measured and matches its spec-derived band`() {
        val misses = ArrayList<String>(4)
        var pass = 0

        for (row in DetectionFixtures.rows) {
            val outcome = pipeline.analyzeText(row.text)
            val risk = outcome.risk
            val measured = risk.label
            val expandedRow = row.id.startsWith("N")
            val ok = when {
                !expandedRow -> measured == row.expected
                row.kind == FixtureKind.SCAM -> risk.score >= 25
                else -> risk.score <= 24
            }
            if (ok) pass++ else misses += "${row.id} expected=${row.expected} measured=$measured " +
                "score=${risk.score}"

            println(
                "ROW|${row.id}|${row.family}|${row.language}|${row.kind}|${row.expected}|" +
                    "${measured}|${risk.score}|${risk.confidence}|${if (ok) "PASS" else "MISS"}|" +
                    "${outcome.result.stages.joinToString(",") { it.name }}",
            )
            // The fixture text itself, so the document's table and the messages cannot drift: both are
            // copied out of this run. Every message here is synthetic and contains no real person's
            // data, and no link is ever opened, resolved or fetched.
            println("TEXT|${row.id}|${row.text.replace('\n', ' ')}")

            // Invariants that hold for every row regardless of band (A01 contract, report §2.2).
            assertTrue(
                "${row.id}: ${outcome.result.unverifiableEvidence(outcome.analyzedText)}",
                outcome.result.unverifiableEvidence(outcome.analyzedText).isEmpty(),
            )
            assertTrue(
                "${row.id}: a readable row must never come back UNABLE_TO_VERIFY",
                measured != RiskLabel.UNABLE_TO_VERIFY,
            )
            if (row.kind == FixtureKind.LEGITIMATE && !expandedRow) {
                assertFalse(
                    "${row.id}: a legitimate twin must not carry a verified ask " +
                        "(score=${risk.score}, stages=${outcome.result.stages})",
                    outcome.verifiedAsk,
                )
                assertTrue(
                    "${row.id}: a legitimate twin must stay <= 24, was ${risk.score}",
                    risk.score <= 24,
                )
            }
        }

        println("SUMMARY|total=${DetectionFixtures.rows.size}|pass=$pass|miss=${misses.size}")
        for (family in DetectionExpansion.rows.map { it.family }.distinct()) {
            val rows = DetectionExpansion.rows.filter { it.family == family }
            val scamRows = rows.filter { it.kind == FixtureKind.SCAM }
            val legitimateRows = rows.filter { it.kind == FixtureKind.LEGITIMATE }
            val detected = scamRows.count { pipeline.analyzeText(it.text).risk.score >= 25 }
            val falsePositives = legitimateRows.count { pipeline.analyzeText(it.text).risk.score > 24 }
            println(
                "CATEGORY|$family|scam=${scamRows.size}|detected=$detected|" +
                    "recall=${detected}/${scamRows.size}|legitimate=${legitimateRows.size}|" +
                    "falsePositive=$falsePositives",
            )
        }
        assertTrue(
            "rule-fixture band mismatches (honest misses, fix the rule or the spec reading): $misses",
            misses.isEmpty(),
        )
    }

    @Test
    fun `fixture inventory covers every family the head orders name, with its legitimate twin`() {
        val required = listOf(
            "KYC link + OTP",
            "Digital arrest text",
            "Electricity/gas/SIM disconnect",
            "Job registration fee",
            "Lottery/KBC",
            "Courier/customs OTP",
            "APK install",
            "Remote access app",
            "UPI receive QR / PIN-to-receive",
            "Refund-me-back",
            "Crypto/investment hook",
            "Aadhaar/PAN update",
        )

        assertTrue(
            "PHASE4 item 4 asks for at least 40 rows, found ${DetectionFixtures.rows.size}",
            DetectionFixtures.rows.size >= 40,
        )
        assertEquals(
            "fixture ids must be unique",
            DetectionFixtures.rows.size,
            DetectionFixtures.rows.map { it.id }.toSet().size,
        )
        for (family in required) {
            val ofFamily = DetectionFixtures.rows.filter { it.family == family }
            assertTrue("no fixture for family '$family'", ofFamily.isNotEmpty())
            assertTrue(
                "family '$family' has no scam row",
                ofFamily.any { it.kind == FixtureKind.SCAM },
            )
            assertTrue(
                "family '$family' has no legitimate twin",
                ofFamily.any { it.kind == FixtureKind.LEGITIMATE },
            )
        }
        val languages = DetectionFixtures.rows.map { it.language }.toSet()
        for (expected in listOf("English", "Hinglish", "Hindi (Devanagari)")) {
            assertTrue("the inventory must include $expected rows, had $languages", expected in languages)
        }
    }

    @Test
    fun `expanded fixtures cover ten families with six scams and six controls each`() {
        val rows = DetectionExpansion.rows
        assertEquals(120, rows.size)
        assertEquals(60, rows.count { it.kind == FixtureKind.SCAM })
        assertEquals(60, rows.count { it.kind == FixtureKind.LEGITIMATE })
        for (family in rows.map { it.family }.toSet()) {
            val group = rows.filter { it.family == family }
            assertEquals("$family scam count", 6, group.count { it.kind == FixtureKind.SCAM })
            assertEquals("$family control count", 6, group.count { it.kind == FixtureKind.LEGITIMATE })
            assertEquals("$family language coverage", setOf("Hinglish", "English", "Hindi (Devanagari)"),
                group.map { it.language }.toSet())
        }
    }

    /**
     * PHASE4 item 5: the three exact probes, read out of this UTF-8 test source so the intended
     * script survives the round trip. Each probe gets a shape assertion stricter than its band.
     */
    @Test
    fun `devanagari OTP probe keeps the digits and stays a watch-level notice`() {
        val text = DetectionFixtures.PROBE_OTP_DEVANAGARI
        assertEquals("probe must be the head-orders string", "आपका ओटीपी ४५६७ है", text)
        assertTrue(
            "the probe must really carry Devanagari digits ४५६७ (U+096A..U+096D)",
            text.contains('\u096A') && text.contains('\u096B') &&
                text.contains('\u096C') && text.contains('\u096D'),
        )

        // The normalizer must map the Devanagari digit run and the Devanagari OTP word, otherwise no
        // rule downstream can see what the message is about.
        val normalized = HinglishNormalizer().normalize(text).text
        assertTrue("OttP token must normalise to 'otp', was '$normalized'", normalized.contains("otp"))
        assertTrue("Devanagari digits must map to ASCII, was '$normalized'", normalized.contains("4567"))

        val outcome = pipeline.analyzeText(text)
        assertEquals(
            "a genuine OTP notice nobody is asked to repeat must stay LOW_RISK, was " +
                "${outcome.risk.score}",
            RiskLabel.LOW_RISK,
            outcome.risk.label,
        )
        assertFalse("nobody asked for the code", outcome.verifiedAsk == true)
        assertTrue(
            "mixed support: Devanagari is a supported script, so this must not be UNABLE_TO_VERIFY",
            outcome.risk.label != RiskLabel.UNABLE_TO_VERIFY,
        )
    }

    @Test
    fun `mixed-script SBI account-block probe reads as a threat, not as unreadable text`() {
        val text = DetectionFixtures.PROBE_MIXED_SBI_BLOCK
        assertEquals("probe must be the head-orders string", "Aapka SBI खाता block हो गया hai", text)
        assertTrue("the probe must keep its Devanagari word", text.contains('ख') && text.contains('ा'))

        val outcome = pipeline.analyzeText(text)
        assertTrue(
            "mixed Roman+Devanagari is a supported script pair, so it must not be UNABLE_TO_VERIFY",
            outcome.risk.label != RiskLabel.UNABLE_TO_VERIFY,
        )
        assertTrue(
            "the account-block threat is the whole point of this message, was " +
                "${outcome.result.stages}",
            outcome.result.stages.contains(ai.trustshield.android.core.model.StageId.THREAT_OR_REWARD),
        )
        assertEquals(
            "a threat alone has no ask, so it stays in the watch band, was ${outcome.risk.score}",
            RiskLabel.LOW_RISK,
            outcome.risk.label,
        )
        assertTrue(
            "every quote must be an exact substring of the mixed-script source",
            outcome.result.unverifiableEvidence(outcome.analyzedText).isEmpty(),
        )
    }

    @Test
    fun `devanagari aadhaar digital-arrest probe reaches the suspicious ladder`() {
        val text = DetectionFixtures.PROBE_DIGITAL_ARREST_DEVANAGARI
        assertTrue(
            "the probe must name Aadhaar and arrest in Devanagari",
            text.contains("आधार") && text.contains("गिरफ़्तारी"),
        )

        val outcome = pipeline.analyzeText(text)
        val label = outcome.risk.label
        assertTrue(
            "expected at least CAUTION from the digital-arrest ladder, was $label " +
                "(${outcome.risk.score}) stages=${outcome.result.stages}",
            outcome.risk.score >= 25,
        )
        assertTrue(
            "with a validated marker plus three independent stages the spec band is SUSPICIOUS, " +
                "was $label (${outcome.risk.score}) stages=${outcome.result.stages}",
            label == RiskLabel.SUSPICIOUS || label == RiskLabel.HIGH_RISK,
        )
        assertTrue(
            "the threat stage must be visible for गिरफ़्तारी, was ${outcome.result.stages}",
            outcome.result.stages.contains(ai.trustshield.android.core.model.StageId.THREAT_OR_REWARD),
        )
        assertTrue(
            "the isolation stage must be visible for किसी को मत बताना, was ${outcome.result.stages}",
            outcome.result.stages.contains(ai.trustshield.android.core.model.StageId.SECRECY),
        )
    }
}
