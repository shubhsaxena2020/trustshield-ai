package ai.trustshield.android.core.analysis

import ai.trustshield.android.core.model.Confidence
import ai.trustshield.android.core.model.RiskLabel
import ai.trustshield.android.core.model.StageId
import ai.trustshield.android.core.rules.LegitimateIndicators
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Package P3-03 gate (#82): the seven Hinglish demo fixtures, pinned against the real
 * [LocalScanPipeline].
 *
 * ## What these tests are, and are not
 *
 * These are **rule and normaliser** fixtures over synthetic, hand-written messages. They are not a
 * quality measurement, they are not drawn from any evaluation set, and they move no reported
 * number: 82.1 recall / 78.3 specificity / 82.1 precision / 82.1 F1 on the sealed evaluation set and
 * the unmet 90 target all stay exactly as reported, because nothing here changes the engine. A green
 * run of this class means "the deterministic rules still do on seven demo messages what the spec
 * says they do", nothing more.
 *
 * ## Why the expectations may look conservative
 *
 * The bands come from `docs/android/BEHAVIOR_SPEC.md` and were written before this class was ever
 * run. Where the spec allows a range (a threat with no ask stays at watch level; an ask plus one
 * corroboration sits in the caution band) the fixture allows that range. Where the spec is exact (a
 * validated ask plus two stages is the high-risk floor; a protective "do not share" is never an ask)
 * the fixture pins a single label. If the engine disagrees, the test fails and the printed miss
 * table names the fixture - no expectation is widened to turn a red run green.
 *
 * ## Risk versus confidence
 *
 * Both are reported as separate values everywhere, because the contract keeps them apart: a fixture
 * can carry a high score with medium confidence (a short message) and that is legal.
 *
 * Plain JVM only. No Android framework object, no device, no network - if the pipeline ever needed
 * one, this class could not run.
 *
 * ## Rule artefacts observed in this run, recorded rather than pinned
 *
 *  * `delivery-otp` (fixture 7) is a **real miss**: expected `LOW_RISK` with no ask, measured
 *    `CAUTION` / 30 / HIGH with `[ASK]`. The expectations for it are untouched, so this class is
 *    red on purpose until A03 stops reading "share only with the agent at your door" as a remote
 *    credential ask. See the known-miss note on [DemoMessages].
 *  * `legit-otp` (fixture 5) scores 12 instead of 0 because `StageRules` rule 2 of SECRECY treats
 *    the protective sentence "Do not share it with anyone" as isolation pressure, while
 *    `LegitimateIndicators` correctly reads it as protective advice. The label is still `LOW_RISK`
 *    and no ask is produced, so the band this issue specifies is met; the extra stage is reported
 *    here as a rule-level false positive for A03 to guard with the same protective-negation check,
 *    not something this package may paper over.
 */
class DemoMessagesTest {

    private val pipeline = LocalScanPipeline()

    // ---------------------------------------------------------------------------------------
    // Fixture inventory: a deletion must never pass silently
    // ---------------------------------------------------------------------------------------

    @Test
    fun `there are exactly seven fixtures with unique stable ids`() {
        val fixtures = DemoMessages.all

        assertEquals(
            "P3-03 ships exactly seven fixtures; a missing one hides a whole scenario",
            DemoMessages.FIXTURE_COUNT,
            fixtures.size,
        )
        assertEquals(7, fixtures.size)
        assertEquals("ids must be unique", fixtures.size, fixtures.map { it.id }.toSet().size)
        assertEquals(
            "fixture order is part of the demo script and must be stable",
            listOf(
                DemoMessages.ID_SBI_KYC_OTP,
                DemoMessages.ID_DIGITAL_ARREST,
                DemoMessages.ID_ELECTRICITY_BILL,
                DemoMessages.ID_JOB_FEE,
                DemoMessages.ID_LEGIT_OTP,
                DemoMessages.ID_CREDIT_ALERT,
                DemoMessages.ID_DELIVERY_OTP,
            ),
            fixtures.map { it.id },
        )
        for (fixture in fixtures) {
            assertNotNull("byId must find ${fixture.id}", DemoMessages.byId(fixture.id))
            assertTrue("${fixture.id}: text must not be blank", fixture.text.isNotBlank())
            assertTrue("${fixture.id}: title must not be blank", fixture.titleHinglish.isNotBlank())
            assertTrue("${fixture.id}: note must explain the band", fixture.expectation.note.isNotBlank())
            assertTrue(
                "${fixture.id}: an expectation with no allowed label can never pass",
                fixture.expectation.allowedLabels.isNotEmpty(),
            )
            assertTrue(
                "${fixture.id}: allowed and forbidden stages must not overlap",
                fixture.expectation.allowedLabels.isNotEmpty() &&
                    fixture.expectation.requiredStages.none { it in fixture.expectation.forbiddenStages },
            )
        }
        assertEquals(null, DemoMessages.byId("no-such-fixture"))
    }

    // ---------------------------------------------------------------------------------------
    // The band assertion, one fixture at a time so a failure names itself
    // ---------------------------------------------------------------------------------------

    @Test
    fun `every fixture lands inside its expected band`() {
        for (fixture in DemoMessages.all) {
            val outcome = pipeline.analyzeText(fixture.text)
            val label = outcome.risk.label
            val score = outcome.risk.score
            val confidence = outcome.risk.confidence
            val stages = outcome.result.stages
            val where = "${fixture.id} (score=$score confidence=$confidence stages=$stages)"

            assertTrue(
                "$where: label $label is not in the honest band " +
                    "${fixture.expectation.allowedLabels} - ${fixture.expectation.note}",
                label in fixture.expectation.allowedLabels,
            )
            for (required in fixture.expectation.requiredStages) {
                assertTrue(
                    "$where: required stage $required is missing - ${fixture.expectation.note}",
                    stages.contains(required),
                )
            }
            for (forbidden in fixture.expectation.forbiddenStages) {
                assertFalse(
                    "$where: forbidden stage $forbidden was produced - ${fixture.expectation.note}",
                    stages.contains(forbidden),
                )
            }
            assertEquals(
                "$where: every evidence quote must be an exact substring of the analysed text",
                emptyList<Any>(),
                outcome.result.unverifiableEvidence(outcome.analyzedText),
            )
            assertTrue("$where: score must stay in 0..100", score in 0..100)
            // Risk and confidence are two fields, reported separately, never merged into one number.
            assertTrue(
                "$where: confidence is an independent enum value",
                confidence == Confidence.LOW ||
                    confidence == Confidence.MEDIUM ||
                    confidence == Confidence.HIGH,
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // The miss report: expected vs actual for all seven, printed, never swallowed
    // ---------------------------------------------------------------------------------------

    @Test
    fun `miss report prints all seven fixtures and names any miss`() {
        val rows = DemoMessages.all.map { fixture ->
            val outcome = pipeline.analyzeText(fixture.text)
            DemoResultRow(
                id = fixture.id,
                expected = describe(fixture.expectation),
                actualLabel = outcome.risk.label,
                actualScore = outcome.risk.score,
                actualConfidence = outcome.risk.confidence,
                stages = outcome.result.stages,
                missed = isMiss(fixture.expectation, outcome),
            )
        }

        println("P3-03 demo fixture report (rule/normaliser fixtures, not a quality metric)")
        println(
            "id".padEnd(18) + "| " + "expected".padEnd(56) + "| " + "actual".padEnd(14) +
                "| score | conf   | stages                              | missed",
        )
        println("-".repeat(150))
        for (row in rows) {
            println(
                row.id.padEnd(18) + "| " + row.expected.padEnd(56) + "| " +
                    row.actualLabel.name.padEnd(14) + "| " + row.actualScore.toString().padEnd(5) + " | " +
                    row.actualConfidence.name.padEnd(6) + " | " +
                    row.stages.joinToString(",").padEnd(35) + " | " + row.missed,
            )
        }
        println("-".repeat(150))

        val missed = rows.filter { it.missed }
        assertTrue(
            "the engine disagrees with the honest expectation for: " +
                missed.joinToString("; ") { "${it.id} expected ${it.expected} but got ${it.actualLabel} " +
                    "(score ${it.actualScore}, ${it.actualConfidence}, stages ${it.stages})" },
            missed.isEmpty(),
        )
    }

    // ---------------------------------------------------------------------------------------
    // Do not cry wolf: the legitimate fixtures and the no-ask guard
    // ---------------------------------------------------------------------------------------

    @Test
    fun `legitimate fixtures never ask and never reach high risk`() {
        assertEquals(3, DemoMessages.legitimateIds.size)
        for (id in DemoMessages.legitimateIds) {
            val fixture = assertHasFixture(id)
            val outcome = pipeline.analyzeText(fixture.text)
            val where = "$id (score=${outcome.risk.score} confidence=${outcome.risk.confidence} " +
                "stages=${outcome.result.stages})"

            assertFalse("$where: a legitimate notice must not produce an ask stage", outcome.result.stages.contains(StageId.ASK))
            assertFalse("$where: a legitimate notice must not be a verified ask", outcome.verifiedAsk)
            assertFalse("$where: a legitimate notice must not escalate the UI", outcome.escalateCritical)
            assertFalse("$where: a legitimate notice must not be HIGH_RISK", outcome.risk.label == RiskLabel.HIGH_RISK)
            assertFalse("$where: a legitimate notice must not be SUSPICIOUS", outcome.risk.label == RiskLabel.SUSPICIOUS)
            assertEquals("$where: a legitimate notice stays in the watch band", RiskLabel.LOW_RISK, outcome.risk.label)
        }
    }

    @Test
    fun `legitimate fixtures carry protective evidence with verified quotes`() {
        for (id in DemoMessages.legitimateIds) {
            val fixture = assertHasFixture(id)
            val outcome = pipeline.analyzeText(fixture.text)

            assertTrue(
                "$id: the protective/legitimate signal must be visible as evidence, was " +
                    "${outcome.result.evidence.map { it.module }}",
                outcome.result.evidence.any { it.module == LegitimateIndicators.MODULE },
            )
            assertTrue(
                "$id: no evidence item may claim a quote the text does not contain",
                outcome.result.unverifiableEvidence(outcome.analyzedText).isEmpty(),
            )
        }
    }

    @Test
    fun `electricity bill fixture never reaches the alarm bands`() {
        val fixture = assertHasFixture(DemoMessages.ID_ELECTRICITY_BILL)
        val outcome = pipeline.analyzeText(fixture.text)
        val where = "electricity-bill (score=${outcome.risk.score} confidence=${outcome.risk.confidence} " +
            "stages=${outcome.result.stages})"

        // BEHAVIOR_SPEC: threat + urgency with no ask stays at watch level. Reaching an alarm band
        // here is a false alarm on a routine utility message, which is the failure this guards.
        assertFalse("$where: must not reach HIGH_RISK", outcome.risk.label == RiskLabel.HIGH_RISK)
        assertFalse("$where: must not reach SUSPICIOUS", outcome.risk.label == RiskLabel.SUSPICIOUS)
        assertFalse("$where: must not reach UNABLE_TO_VERIFY", outcome.risk.label == RiskLabel.UNABLE_TO_VERIFY)
        assertTrue(
            "$where: must stay at or below CAUTION, was ${outcome.risk.label}",
            outcome.risk.label == RiskLabel.LOW_RISK || outcome.risk.label == RiskLabel.CAUTION,
        )
        assertFalse("$where: a personal callback number is not a credential or money ask", outcome.verifiedAsk)
        assertFalse("$where: no ask stage without an ask", outcome.result.stages.contains(StageId.ASK))
    }

    // ---------------------------------------------------------------------------------------
    // Synthetic content: nothing that could look like real PII
    // ---------------------------------------------------------------------------------------

    @Test
    fun `fixture text is synthetic and free of realistic otp card or phone digits`() {
        // The only digit runs longer than three characters allowed anywhere in this file: a sample
        // OTP, a sample delivery OTP, the masked tail of a sample account, and an advertised daily
        // earning. Every other run is three characters or fewer, so nothing can resemble a phone
        // number, card number or customer id.
        val allowedLongRuns = setOf("123456", "4521", "1234", "5000")
        val digitRun = Regex("""\d+""")

        for (fixture in DemoMessages.all) {
            val text = fixture.text
            for (run in digitRun.findAll(text).map { it.value }) {
                if (run.length <= 3) continue
                assertTrue(
                    "${fixture.id}: digit run '$run' is not a documented fake demo token - " +
                        "no realistic OTP, card, account or phone number may enter these fixtures",
                    run in allowedLongRuns,
                )
            }
            assertFalse(
                "${fixture.id}: a contiguous 10+ digit run is a phone or account number, never fake",
                Regex("""\d{10,}""").containsMatchIn(text),
            )
            assertFalse("${fixture.id}: no URL may appear in a fixture", text.contains("http"))
            assertFalse("${fixture.id}: no URL may appear in a fixture", text.lowercase().contains("www."))
            assertFalse(
                "${fixture.id}: no email or UPI handle may appear in a fixture",
                text.contains("@"),
            )
            assertFalse(
                "${fixture.id}: no international phone prefix in a fixture",
                text.contains("+91"),
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // Determinism
    // ---------------------------------------------------------------------------------------

    @Test
    fun `analysing the same fixture twice yields identical label score and evidence ids`() {
        for (fixture in DemoMessages.all) {
            val first = pipeline.analyzeText(fixture.text)
            val second = pipeline.analyzeText(fixture.text)

            assertEquals("${fixture.id}: label must be deterministic", first.risk.label, second.risk.label)
            assertEquals("${fixture.id}: score must be deterministic", first.risk.score, second.risk.score)
            assertEquals(
                "${fixture.id}: confidence must be deterministic",
                first.risk.confidence,
                second.risk.confidence,
            )
            assertEquals(
                "${fixture.id}: evidence ids must be deterministic",
                first.result.evidence.map { it.id },
                second.result.evidence.map { it.id },
            )
            assertEquals("${fixture.id}: stages must be deterministic", first.result.stages, second.result.stages)
            assertEquals("${fixture.id}: analysisId must be deterministic", first.result.analysisId, second.result.analysisId)
        }
    }

    // ---------------------------------------------------------------------------------------
    // A5 escalation, only where the spec supports it
    // ---------------------------------------------------------------------------------------

    @Test
    fun `digital arrest fixture raises the critical escalation flag`() {
        val fixture = assertHasFixture(DemoMessages.ID_DIGITAL_ARREST)
        val outcome = pipeline.analyzeText(fixture.text)

        // BEHAVIOR_SPEC: 'Critical' is a UI escalation for a high-risk money ask with multiple
        // corroborations. This fixture has a money ask plus four independent stages.
        assertTrue("the digital-arrest fixture must verify an ask", outcome.verifiedAsk)
        assertTrue(
            "a money ask with four corroborations must raise the UI escalation " +
                "(score=${outcome.risk.score} confidence=${outcome.risk.confidence})",
            outcome.escalateCritical,
        )
        assertEquals(RiskLabel.HIGH_RISK, outcome.risk.label)
    }

    @Test
    fun `only the scam fixtures may produce an ask stage`() {
        val asking = DemoMessages.all.filter { pipeline.analyzeText(it.text).result.stages.contains(StageId.ASK) }
            .map { it.id }
            .toSet()

        assertTrue(
            "an ask stage appeared in a legitimate fixture: ${asking - DemoMessages.scamIds}",
            (asking - DemoMessages.scamIds).isEmpty(),
        )
        assertFalse(
            "the SBI KYC fixture must produce an ask stage, it appeared in $asking",
            (DemoMessages.scamIds - asking).contains(DemoMessages.ID_SBI_KYC_OTP),
        )
    }

    // ---------------------------------------------------------------------------------------
    // The claim itself
    // ---------------------------------------------------------------------------------------

    @Test
    fun `the fixture file makes no quality claim`() {
        val disclaimer = DemoMessages.CLAIM_DISCLAIMER.lowercase()

        assertFalse(
            "the disclaimer must not carry the word a quality claim needs",
            disclaimer.contains("accuracy"),
        )
        assertFalse("the disclaimer must not claim a holdout figure", disclaimer.contains("holdout"))
        assertFalse(
            "the disclaimer must not carry a percentage, so it cannot be read as a score",
            disclaimer.contains("%"),
        )
        assertTrue(
            "the disclaimer must say the fixtures are not a quality measurement",
            disclaimer.contains("not a measurement of model quality"),
        )
        assertTrue(
            "the disclaimer must say no reported number changes",
            disclaimer.contains("modify no reported number"),
        )
        assertTrue(
            "the disclaimer must keep the honest reported numbers",
            disclaimer.contains("82.1") && disclaimer.contains("78.3") && disclaimer.contains("90 target unmet"),
        )
    }

    // ---------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------

    private fun assertHasFixture(id: String): DemoMessage =
        DemoMessages.byId(id) ?: throw AssertionError("fixture '$id' disappeared from DemoMessages.all")

    private fun describe(expectation: DemoExpectation): String {
        val labels = expectation.allowedLabels.sortedBy { it.ordinal }.joinToString("/") { it.name }
        val required = expectation.requiredStages.sortedBy { it.ordinal }.joinToString(",") { it.name }
        return if (required.isEmpty()) labels else "$labels need $required"
    }

    private fun isMiss(expectation: DemoExpectation, outcome: ScanOutcome): Boolean {
        val stages = outcome.result.stages
        return outcome.risk.label !in expectation.allowedLabels ||
            !stages.containsAll(expectation.requiredStages) ||
            stages.any { it in expectation.forbiddenStages }
    }
}
