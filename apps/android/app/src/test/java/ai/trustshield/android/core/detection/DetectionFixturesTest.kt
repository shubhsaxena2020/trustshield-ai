package ai.trustshield.android.core.detection

import ai.trustshield.android.core.analysis.LocalScanPipeline
import ai.trustshield.android.core.analysis.ScanOutcome
import ai.trustshield.android.core.model.AnalysisStatus
import ai.trustshield.android.core.model.RiskLabel
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Detection fixture harness: every row of [ALL_FIXTURES] scored through the real engine
 * (`LocalScanPipeline().analyzeText`) and judged against what that row pins.
 *
 * This is a **detection-quality gate**, not an accuracy claim. Each row carries its own expectation:
 *
 *  * [FixtureKind.SCAM] - PASS when `score >= minScore`, `verifiedAsk == expectAsk` (when the row
 *    pins it), and `result.unverifiableEvidence(text)` is empty;
 *  * [FixtureKind.CONTROL] - PASS when `score <= maxScore`, `verifiedAsk != true`, and
 *    `result.unverifiableEvidence(text)` is empty.
 *
 * Rows are grouped by family so a red run names the family in the method name, and every failure
 * message repeats id, category, expected floor/ceiling, measured score, label, verifiedAsk, stages
 * and the first [PREVIEW_CHARS] characters of the text. Rows are asserted one by one and all
 * failures of a family are collected, so one run shows the whole family rather than its first bad
 * row.
 *
 * Two invariants are pinned separately because they are contract-level, not row-level: an exact
 * quote must exist for any scam reading at or above caution (>= [CAUTION_FLOOR]), and no fixture may
 * silently degrade to `UNABLE_TO_VERIFY`.
 *
 * [writeFixtureReport] dumps the measured numbers for every row to `build/reports/detection-fixtures/
 * fixtures.tsv` (relative to the module dir). It runs in `@After`, so the file is written even when a
 * family goes red, and it never fails a test on I/O.
 */
class DetectionFixturesTest {

    private val pipeline = LocalScanPipeline()

    // ---------------------------------------------------------------------------------------
    // Category families. One method per family so the red test name says which family broke.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `authority family digital arrest kyc bank and otp sharing rows pass`() {
        assertFamily("authority family (digital_arrest, kyc_bank, otp_sharing)", AUTHORITY_CATEGORIES)
    }

    @Test
    fun `service family utility bill parcel customs job offer and loan app rows pass`() {
        assertFamily("service family (utility_bill, parcel_customs, job_offer, loan_app)", SERVICE_CATEGORIES)
    }

    @Test
    fun `money family upi collect refund investment crypto lottery kbc and remote access rows pass`() {
        assertFamily(
            "money family (upi_collect_refund, investment_crypto, lottery_kbc, remote_access)",
            MONEY_CATEGORIES,
        )
    }

    @Test
    fun `obfuscation family rows pass`() {
        assertFamily("obfuscation family", OBFUSCATION_CATEGORIES)
    }

    @Test
    fun `legit control rows stay under their ceiling`() {
        assertRows("legit_control controls", controlRows())
    }

    // ---------------------------------------------------------------------------------------
    // Contract invariants
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a scam row at caution or above always quotes an exact evidence line`() {
        val offenders = mutableListOf<String>()
        for (row in ALL_FIXTURES) {
            if (row.kind != FixtureKind.SCAM) continue
            val outcome = pipeline.analyzeText(row.text)
            if (outcome.risk.score < CAUTION_FLOOR) continue
            if (quotedCount(outcome, row.text) > 0) continue
            offenders += "fixture ${row.id} [${row.category}/${row.language}] reached " +
                "score=${outcome.risk.score} (${outcome.risk.label.name}) with no verified quote: " +
                "stages=${stagesOf(outcome)} evidence=${outcome.result.evidence.size} " +
                "text=${preview(row.text)}"
        }
        assertTrue(
            "${offenders.size} scam rows at caution or above carry no exact quote:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `no fixture is unable to verify unless the corpus lists it`() {
        val surprises = mutableListOf<String>()
        for (row in ALL_FIXTURES) {
            if (row.id in EXPECTED_UNABLE_TO_VERIFY) continue
            val outcome = pipeline.analyzeText(row.text)
            val degraded = outcome.risk.label == RiskLabel.UNABLE_TO_VERIFY ||
                outcome.result.status == AnalysisStatus.UNABLE_TO_VERIFY
            if (!degraded) continue
            surprises += "fixture ${row.id} [${row.category}/${row.language}] came back " +
                "${outcome.risk.label.name}/${outcome.result.status.name} " +
                "limitations=${outcome.result.limitations.joinToString("; ")} " +
                "text=${preview(row.text)}"
        }
        assertTrue(
            "${surprises.size} fixture rows degraded to UNABLE_TO_VERIFY instead of a verdict:\n" +
                surprises.joinToString("\n"),
            surprises.isEmpty(),
        )
    }

    @Test
    fun `every corpus row is unique and sits in a family this harness scores`() {
        val duplicateIds = ALL_FIXTURES.groupBy { it.id }.filterValues { it.size > 1 }.keys
        assertTrue(
            "duplicate fixture ids: " + duplicateIds.joinToString(", "),
            duplicateIds.isEmpty(),
        )
        val unmapped = ALL_FIXTURES.filter { it.category !in KNOWN_CATEGORIES }
        assertTrue(
            "fixture rows in categories the harness never scores: " +
                unmapped.joinToString(", ") { "${it.id}:${it.category}" },
            unmapped.isEmpty(),
        )
    }

    // ---------------------------------------------------------------------------------------
    // Report
    // ---------------------------------------------------------------------------------------

    /**
     * Write the measured table, corpus order, PASS/FAIL per row.
     *
     * Runs after every test method (pass or fail), so a red run still leaves the numbers on disk.
     * The path is relative on purpose: the unit-test working directory is the module dir, so the
     * file lands in `apps/android/app/build/reports/detection-fixtures/fixtures.tsv`. Any I/O
     * problem is swallowed - the report is a side effect, never a gate.
     */
    @After
    fun writeFixtureReport() {
        try {
            val file = File(REPORT_PATH)
            file.parentFile?.mkdirs()
            val lines = mutableListOf(COLUMNS.joinToString("\t"))
            for (row in ALL_FIXTURES) {
                val outcome = pipeline.analyzeText(row.text)
                val status = if (problemsOf(row, outcome).isEmpty()) "PASS" else "FAIL"
                lines += listOf(
                    cell(row.id),
                    cell(row.category),
                    cell(row.language),
                    row.kind.name,
                    row.minScore.toString(),
                    row.maxScore.toString(),
                    row.expectAsk?.toString() ?: "",
                    outcome.risk.score.toString(),
                    outcome.risk.label.name,
                    outcome.verifiedAsk.toString(),
                    stagesOf(outcome),
                    quotedCount(outcome, row.text).toString(),
                    status,
                    cell(row.note),
                ).joinToString("\t")
            }
            file.writeText(lines.joinToString("\n") + "\n")
        } catch (ignored: Throwable) {
            // Reporting must not turn a detection verdict red, and a read-only build dir is not a
            // detection bug.
        }
    }

    // ---------------------------------------------------------------------------------------
    // Row machinery
    // ---------------------------------------------------------------------------------------

    private fun controlRows(): List<FixtureRow> =
        ALL_FIXTURES.filter { it.kind == FixtureKind.CONTROL || it.category in CONTROL_CATEGORIES }

    private fun assertFamily(label: String, categories: List<String>) {
        val rows = ALL_FIXTURES.filter { it.category in categories }
        assertTrue(
            "$label: no fixture rows for ${categories.joinToString("/")} - the corpus is missing this family",
            rows.isNotEmpty(),
        )
        assertRows(label, rows)
    }

    private fun assertRows(label: String, rows: List<FixtureRow>) {
        val failures = mutableListOf<String>()
        for (row in rows) {
            val outcome = pipeline.analyzeText(row.text)
            val problems = problemsOf(row, outcome)
            try {
                assertTrue(passLine(row, outcome, problems), problems.isEmpty())
            } catch (failure: AssertionError) {
                failures += failure.message ?: row.id
            }
        }
        if (failures.isNotEmpty()) {
            fail("$label: ${failures.size}/${rows.size} fixture rows failed\n\n" + failures.joinToString("\n\n"))
        }
    }

    /**
     * The row contract. Anything returned here is a reason the row failed; an empty list is PASS.
     */
    private fun problemsOf(row: FixtureRow, outcome: ScanOutcome): List<String> {
        val problems = mutableListOf<String>()
        // Quotes are checked against the raw fixture literal, exactly as the corpus promises. When
        // the engine trimmed surrounding whitespace this check is stricter than the engine's own
        // (span offsets move), so the message names that cause instead of leaving it guessed.
        val unverifiable = outcome.result.unverifiableEvidence(row.text)
        if (unverifiable.isNotEmpty()) {
            val hint = if (row.text == outcome.analyzedText) {
                ""
            } else {
                " (engine verified against a ${outcome.analyzedText.length}-char trimmed text," +
                    " fixture literal is ${row.text.length} chars - check the literal)"
            }
            problems += "unverifiable evidence: " +
                unverifiable.joinToString(",") { it.id } + hint
        }
        when (row.kind) {
            FixtureKind.SCAM -> {
                if (outcome.risk.score < row.minScore) {
                    problems += "score ${outcome.risk.score} < minScore ${row.minScore}"
                }
                val expectedAsk = row.expectAsk
                if (expectedAsk != null && outcome.verifiedAsk != expectedAsk) {
                    problems += "verifiedAsk=${outcome.verifiedAsk} but expected $expectedAsk"
                }
            }

            FixtureKind.CONTROL -> {
                if (outcome.risk.score > row.maxScore) {
                    problems += "score ${outcome.risk.score} > maxScore ${row.maxScore}"
                }
                if (outcome.verifiedAsk) {
                    problems += "legitimate text produced a verified ask"
                }
            }
        }
        return problems
    }

    private fun passLine(row: FixtureRow, outcome: ScanOutcome, problems: List<String>): String {
        val verdict = if (problems.isEmpty()) "PASS" else "FAIL: " + problems.joinToString("; ")
        return buildString {
            append("fixture ").append(row.id)
            append(" [").append(row.category).append('/').append(row.language).append('/')
            append(row.kind.name).append("] ").append(verdict)
            append('\n')
            append("  expected minScore=").append(row.minScore)
            append(" maxScore=").append(row.maxScore)
            append(" expectAsk=").append(row.expectAsk?.toString() ?: "null")
            append('\n')
            append("  measured score=").append(outcome.risk.score)
            append(" label=").append(outcome.risk.label.name)
            append(" verifiedAsk=").append(outcome.verifiedAsk)
            append('\n')
            append("  stages=").append(stagesOf(outcome))
            append(" evidence=").append(outcome.result.evidence.size)
            append(" quoted=").append(quotedCount(outcome, row.text))
            append('\n')
            append("  text=").append(preview(row.text))
        }
    }

    private fun quotedCount(outcome: ScanOutcome, text: String): Int =
        outcome.result.verifiableEvidence(text).count { it.quote != null }

    private fun stagesOf(outcome: ScanOutcome): String =
        if (outcome.result.stages.isEmpty()) "-" else outcome.result.stages.joinToString("|") { it.name }

    private fun preview(text: String): String =
        text.take(PREVIEW_CHARS).replace('\n', ' ').replace('\r', ' ')

    private fun cell(value: String): String =
        value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    companion object {
        /** Caution band floor: at or above this, a scam reading must quote something exact. */
        private const val CAUTION_FLOOR = 25

        /** How much of the raw text a failure message and the report keep. */
        private const val PREVIEW_CHARS = 120

        /** Module-relative on purpose: unit tests run with the module dir as working dir. */
        private const val REPORT_PATH = "build/reports/detection-fixtures/fixtures.tsv"

        private val COLUMNS = listOf(
            "id",
            "category",
            "language",
            "kind",
            "expected_min",
            "expected_max",
            "expect_ask",
            "score",
            "label",
            "verified_ask",
            "stages",
            "evidence_quoted",
            "status",
            "note",
        )

        private val AUTHORITY_CATEGORIES = listOf("digital_arrest", "kyc_bank", "otp_sharing")
        private val SERVICE_CATEGORIES = listOf("utility_bill", "parcel_customs", "job_offer", "loan_app")
        private val MONEY_CATEGORIES =
            listOf("upi_collect_refund", "investment_crypto", "lottery_kbc", "remote_access")
        private val OBFUSCATION_CATEGORIES = listOf("obfuscation")
        private val CONTROL_CATEGORIES = listOf("legit_control")

        private val KNOWN_CATEGORIES: List<String> =
            AUTHORITY_CATEGORIES + SERVICE_CATEGORIES + MONEY_CATEGORIES +
                OBFUSCATION_CATEGORIES + CONTROL_CATEGORIES

        /**
         * Rows allowed to degrade to `UNABLE_TO_VERIFY`. Empty on purpose: an unreadable fixture is
         * a corpus bug. Add an id here only when the row is deliberately judging unreadable input.
         */
        private val EXPECTED_UNABLE_TO_VERIFY: Set<String> = emptySet()
    }
}
