package ai.trustshield.android.data.api

import ai.trustshield.android.core.model.AnalysisResult
import ai.trustshield.android.core.model.AnalysisStatus
import ai.trustshield.android.core.model.BackendStatus
import ai.trustshield.android.core.model.CONTRACT_VERSION
import ai.trustshield.android.core.model.Confidence
import ai.trustshield.android.core.model.DataOrigin
import ai.trustshield.android.core.model.Evidence
import ai.trustshield.android.core.model.InputType
import ai.trustshield.android.core.model.RiskLabel
import ai.trustshield.android.core.model.Severity
import ai.trustshield.android.core.model.StageId
import ai.trustshield.android.core.model.Strength
import ai.trustshield.android.core.normalize.HinglishNormalizer
import ai.trustshield.android.core.score.LocalScorer
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Package A08 (#32): offline and API contract fixtures.
 *
 * Two jobs, both about honesty at the boundary:
 *
 *  1. **Golden fixtures.** Ten realistic backend response bodies are hand-written from the schema in
 *     `services/analysis/fusion/result.py` and `services/analysis/evidence/models.py`, kept as raw
 *     strings here, and adapted through [ResultAdapter]/[MiniJson]. Each adapted [AnalysisResult] is
 *     asserted field by field, so a schema change or an adapter shortcut fails loudly. The fixtures
 *     are pinned by [CONTRACT_VERSION]; see `docs/android/evidence/api-contract.md`.
 *  2. **Offline is the product.** The local engine must produce a complete, attributable result with
 *     no backend object in scope and no socket anywhere, and backend enrichment must stay disabled.
 *
 * Every transport in this file is a local fake. No test opens a socket, resolves a URL, follows a
 * QR destination or makes any network request - there is no `MockWebServer` on the classpath and no
 * new dependency was added for this package.
 *
 * Adapted-by-hand fixtures are labelled in the doc as `fixture, not a live backend call`.
 */
class OfflineContractTest {

    private val normalizer = HinglishNormalizer()

    // ---------------------------------------------------------------------------------------
    // Golden fixtures (hand-written from the documented backend schema, one per message)
    // ---------------------------------------------------------------------------------------

    @Test
    fun `fixtures are pinned to the contract version`() {
        // Changing either string is a contract change: the fixtures below must be re-read against
        // `services/analysis/fusion/result.py` in the same commit.
        assertEquals("android-contract/1.0", CONTRACT_VERSION)
        assertEquals("1.0", ResultAdapter.BACKEND_SCHEMA_VERSION)

        for ((name, body) in allFixtures()) {
            val parsed = MiniJson.parse(body)
            assertTrue("$name must parse as JSON, got $parsed", parsed is Map<*, *>)
        }
    }

    @Test
    fun `high risk text fixture adapts exactly`() {
        val result = adapt(F1_HIGH_RISK_TEXT)

        assertEquals("a1b2c3d4e5f6a7b8", result.analysisId)
        assertEquals(InputType.TEXT, result.inputType)
        assertEquals(AnalysisStatus.COMPLETED, result.status)
        // Score and confidence are separate fields on purpose: 86 is not "confidence 86%".
        assertEquals(86, result.risk.score)
        assertEquals(RiskLabel.HIGH_RISK, result.risk.label)
        assertEquals(Confidence.HIGH, result.risk.confidence)
        assertEquals(4, result.evidence.size)
        assertEquals(
            listOf(
                "text_rules@backend:authority_claim:0",
                "text_rules@backend:arrest_threat:1",
                "text_rules@backend:secrecy:2",
                "text_rules@backend:payment_request:3",
            ),
            result.evidence.map { it.id },
        )
        // Severity maps straight through; strength is the documented derivation, never STRONG.
        assertEquals(
            listOf(Severity.HIGH, Severity.CRITICAL, Severity.HIGH, Severity.CRITICAL),
            result.evidence.map { it.severity },
        )
        assertEquals(
            listOf(Strength.MODERATE, Strength.MODERATE, Strength.MODERATE, Strength.MODERATE),
            result.evidence.map { it.strength },
        )
        // The backend has no five-stage playbook, so nothing is invented for it.
        assertTrue(result.stages.isEmpty())
        assertEquals(DataOrigin.BACKEND, result.origin)
        assertEquals(BackendStatus.AVAILABLE, result.backend)
        assertEquals(CONTRACT_VERSION, result.contractVersion)
        assertEquals(2, result.actions.size)
    }

    @Test
    fun `high risk fixture never invents a quote or a span`() {
        val result = adapt(F1_HIGH_RISK_TEXT)

        for (item in result.evidence) {
            assertNull("quote must stay null: the wire has no quote field (${item.id})", item.quote)
            assertNull("span must stay null when there is no quote (${item.id})", item.span)
            // A backend item cannot be verified against the input, so it must never claim to be.
            assertTrue(result.unverifiableEvidence(ANY_INPUT).isEmpty())
        }
        // Not a single backend item claims a strong diagnostic signal.
        assertFalse(result.evidence.any { it.strength == Strength.STRONG })
        assertTrue(
            "the derivation must be disclosed in limitations, was ${result.limitations}",
            result.limitations.any { it.contains("no verbatim quote and no strength") },
        )
    }

    @Test
    fun `low risk legitimate bank credit fixture adapts exactly`() {
        val result = adapt(F2_LEGIT_BANK_CREDIT)

        assertEquals("b2c3d4e5f6a7b8c9", result.analysisId)
        assertEquals(InputType.TEXT, result.inputType)
        assertEquals(AnalysisStatus.COMPLETED, result.status)
        assertEquals(4, result.risk.score)
        assertEquals(RiskLabel.LOW_RISK, result.risk.label)
        assertEquals(Confidence.HIGH, result.risk.confidence)
        // A clean credit notice carries no evidence, so no quote-derivation limitation is added.
        assertTrue(result.evidence.isEmpty())
        assertTrue(result.limitations.isEmpty())
        assertEquals(1, result.actions.size)
        assertEquals(DataOrigin.BACKEND, result.origin)
    }

    @Test
    fun `unable to verify fixture keeps its limitations and is never completed`() {
        val result = adapt(F3_UNABLE_TAMIL)

        assertEquals(0, result.risk.score)
        assertEquals(RiskLabel.UNABLE_TO_VERIFY, result.risk.label)
        assertEquals(Confidence.LOW, result.risk.confidence)
        // The wire said "completed"; a `completed` unable_to_verify is still not a finished verdict.
        assertEquals(AnalysisStatus.UNABLE_TO_VERIFY, result.status)
        assertEquals(
            listOf(
                "Language not supported by the rule set: Tamil.",
                "No attributable evidence could be extracted.",
            ),
            result.limitations,
        )
        assertTrue(result.evidence.isEmpty())
    }

    @Test
    fun `ocr fixture adapts as an image result`() {
        val result = adapt(F4_OCR_IMAGE, expected = null)

        assertEquals(InputType.IMAGE, result.inputType)
        assertEquals(6, result.risk.score)
        assertEquals(RiskLabel.LOW_RISK, result.risk.label)
        assertEquals(Confidence.MEDIUM, result.risk.confidence)
        assertEquals(1, result.evidence.size)
        val only = result.evidence.single()
        assertEquals("ocr@backend:delivery_notice:0", only.id)
        assertEquals(Severity.INFORMATIONAL, only.severity)
        assertEquals(Strength.WEAK, only.strength)
        assertNull(only.quote)
        assertNull(only.span)
    }

    @Test
    fun `ocr raw text and normalized text never reach the adapted result`() {
        val result = adapt(F4_OCR_IMAGE, expected = null)
        val dump = result.toString() + stringsOf(result).joinToString(" | ")

        // `modules` is ignored wholesale, which is what keeps raw image text out of the UI (N01).
        assertFalse("OCR raw_text leaked: $dump", dump.contains(OCR_RAW_TEXT))
        assertFalse("OCR normalized_text leaked: $dump", dump.contains(OCR_NORMALIZED_TEXT))
        assertFalse(dump.contains("raw_text"))
        assertFalse(dump.contains("normalized_text"))
        assertFalse(dump.contains("bbox"))
    }

    @Test
    fun `adapted result cannot carry a url bearing field`() {
        val result = adapt(F4_OCR_IMAGE, expected = null)
        val dump = result.toString() + stringsOf(result).joinToString(" | ")

        // The payload carries `modules.ocr.data.image_url`; nothing may become tappable on device.
        assertFalse("a url reached the result: $dump", dump.contains("http://"))
        assertFalse("a url reached the result: $dump", dump.contains("https://"))
        assertFalse(dump.contains("cdn.example"))
        // Structurally: neither the result nor its evidence has any url-shaped property.
        val names = (AnalysisResult::class.java.declaredFields.map { it.name } +
            Evidence::class.java.declaredFields.map { it.name }).map { it.lowercase() }
        assertTrue(
            "unexpected url-shaped field in the contract: $names",
            names.none { it.contains("url") || it.contains("link") },
        )
    }

    @Test
    fun `suspicious kyc fixture adapts exactly`() {
        val result = adapt(F5_SUSPICIOUS_KYC)

        assertEquals(61, result.risk.score)
        assertEquals(RiskLabel.SUSPICIOUS, result.risk.label)
        assertEquals(Confidence.MEDIUM, result.risk.confidence)
        assertEquals(AnalysisStatus.COMPLETED, result.status)
        assertEquals(2, result.evidence.size)
        assertEquals(listOf(Severity.HIGH, Severity.MEDIUM), result.evidence.map { it.severity })
        // Backend titles are English and are kept byte-for-byte: no prefix, no invented Hinglish.
        assertEquals("Account suspension threat", result.evidence[0].titleHinglish)
        assertEquals(2, result.actions.size)
    }

    @Test
    fun `backend under read of a hinglish otp ask is reported as low risk`() {
        val result = adapt(F6_OTP_ASK_UNDERREAD)

        // The fixture deliberately models a backend whose English-centric rules miss a Hinglish ask.
        assertEquals(22, result.risk.score)
        assertEquals(RiskLabel.LOW_RISK, result.risk.label)
        assertEquals(Confidence.LOW, result.risk.confidence)
        assertEquals(1, result.evidence.size)
        assertEquals(Strength.WEAK, result.evidence.single().strength)
        // Nothing in the adapter "fixes" this: the low backend label survives adaptation intact so the
        // UI can show it next to the local CAUTION instead of replacing it.
        assertEquals(DataOrigin.BACKEND, result.origin)
    }

    @Test
    fun `malformed fixture without risk is schema drift and never a low risk result`() {
        val outcome = ResultAdapter.fromWireSafely(wireOf(F9_MISSING_RISK), InputType.TEXT)

        assertTrue("expected drift, was $outcome", outcome is ResultAdapter.AdaptOutcome.Drift)
        val drift = outcome as ResultAdapter.AdaptOutcome.Drift
        assertEquals("missing risk", drift.reason)
    }

    @Test
    fun `unknown severity evidence is dropped and counted`() {
        val result = adapt(F10_UNKNOWN_SEVERITY)

        // Item 1 says "severe", which is not a contract severity: dropped, not defaulted to MEDIUM.
        assertEquals(2, result.evidence.size)
        assertEquals(
            listOf("text_rules@backend:urgency:0", "text_rules@backend:urgency:2"),
            result.evidence.map { it.id },
        )
        assertTrue(
            "a drop must be disclosed, was ${result.limitations}",
            result.limitations.any { it.contains("1 backend evidence item(s) were dropped") },
        )
        assertEquals(55, result.risk.score)
        assertEquals(RiskLabel.SUSPICIOUS, result.risk.label)
    }

    @Test
    fun `unknown severity items never become medium severity`() {
        val result = adapt(F10_UNKNOWN_SEVERITY)
        assertTrue(result.evidence.none { it.severity == Severity.MEDIUM })
        assertTrue(result.evidence.all { it.severity == Severity.HIGH || it.severity == Severity.LOW })
    }

    // ---------------------------------------------------------------------------------------
    // Offline / no-network proof
    // ---------------------------------------------------------------------------------------

    @Test
    fun `enrichment is disabled by default`() {
        assertFalse(ENRICHMENT_ENABLED_BY_DEFAULT)
        val api = AnalysisApi(CountingTransport())
        assertFalse(api.isEnabled)
    }

    @Test
    fun `disabled api returns Disabled and never invokes a throwing transport`() {
        val transport = ThrowingTransport()
        val api = AnalysisApi(transport)

        assertEquals(BackendOutcome.Disabled, api.analyzeText(M1_LADDER))
        assertEquals(BackendOutcome.Disabled, api.analyzeUrl("http://sbi-kyc.xyz/login"))

        // Zero calls: a disabled pipeline must not even try, so no socket, no DNS, no leakage.
        assertEquals("the transport was called while enrichment was disabled", 0, transport.calls)
    }

    @Test
    fun `local engine alone produces a complete offline verdict on the ladder`() {
        // No AnalysisApi, no transport, no fixture in scope: this is the shipped configuration.
        val result = LocalScorer(normalizer).analyze(M1_LADDER)

        assertEquals(DataOrigin.LOCAL, result.origin)
        assertEquals(BackendStatus.DISABLED, result.backend)
        assertTrue("ladder must produce evidence", result.evidence.isNotEmpty())
        assertTrue(result.risk.score >= 70)
        assertEquals(RiskLabel.HIGH_RISK, result.risk.label)
        // Every local quote is a real substring of what the user saw.
        assertTrue(result.verifiableEvidence(M1_LADDER).isNotEmpty())
        assertTrue(result.unverifiableEvidence(M1_LADDER).isEmpty())
        for (item in result.evidence) {
            assertNotNull("local evidence keeps its quote (${item.id})", item.quote)
            assertNotNull("local evidence keeps its span (${item.id})", item.span)
            assertTrue("span must resolve against the analysed text (${item.id})", item.quoteVerifiesAgainst(M1_LADDER))
        }
    }

    @Test
    fun `local engine alone stays calm on a legitimate bank credit notice`() {
        val result = LocalScorer(normalizer).analyze(M2_LEGIT_BANK_CREDIT)

        assertEquals(DataOrigin.LOCAL, result.origin)
        assertEquals(BackendStatus.DISABLED, result.backend)
        assertEquals(RiskLabel.LOW_RISK, result.risk.label)
        // Measured 26 October 2026: one item, and it is A04's positive "credit alert, no ask" indicator
        // (module legit_indicators, informational/weak) - nothing accusing. The zero-evidence case is
        // the plain delivery notice below, because a bank notice always earns the legit-indicator.
        assertTrue(result.evidence.isNotEmpty())
        assertTrue(result.stages.isEmpty())
        assertTrue(
            "a legit notice must only produce legitimate indicators, was ${result.evidence.map { it.module }}",
            result.evidence.all { it.module == "legit_indicators" },
        )
        assertTrue(result.evidence.all { it.severity == Severity.INFORMATIONAL })
        assertTrue(result.evidence.all { it.strength == Strength.WEAK })
        assertTrue(result.unverifiableEvidence(M2_LEGIT_BANK_CREDIT).isEmpty())
        // The absence-of-evidence limitation applies only when no evidence exists; this result has
        // positive informational evidence. The UI still must not describe LOW_RISK as a guarantee.
        assertFalse(
            "a result with positive evidence must not use the no-evidence limitation",
            result.limitations.any { it == LocalScorer.LIMIT_NO_EVIDENCE },
        )
    }

    @Test
    fun `a plain legitimate notice produces no evidence at all`() {
        val result = LocalScorer(normalizer).analyze(M5_AMAZON)

        assertEquals(DataOrigin.LOCAL, result.origin)
        assertEquals(BackendStatus.DISABLED, result.backend)
        assertEquals(RiskLabel.LOW_RISK, result.risk.label)
        assertEquals(0, result.risk.score)
        assertTrue("expected no evidence, was ${result.evidence}", result.evidence.isEmpty())
        assertTrue(result.stages.isEmpty())
    }

    @Test
    fun `a protective never share line never becomes an accusation`() {
        // Real bank SMS wording: A04 legit indicators lower the score, so the protective line is not
        // read as coercion. Measured 26 October 2026: 10/100 low_risk, three evidence items.
        val result = LocalScorer(normalizer).analyze(M2B_BANK_CREDIT_PROTECTIVE)

        assertEquals(RiskLabel.LOW_RISK, result.risk.label)
        assertTrue("score must stay under the caution band, was ${result.risk.score}", result.risk.score < 25)
        // No stage may be ASK: a protective "do not share" is not a request for a code.
        assertFalse(result.stages.contains(StageId.ASK))
        assertTrue(result.evidence.all { it.severity != Severity.CRITICAL })
    }

    @Test
    fun `offline analysis is deterministic across runs`() {
        val first = LocalScorer(normalizer).analyze(M1_LADDER)
        val second = LocalScorer(normalizer).analyze(M1_LADDER)
        assertEquals(first, second)
        assertEquals(first.analysisId, second.analysisId)
    }

    @Test
    fun `every adapted fixture reports the local contract version`() {
        for ((name, body) in adaptedFixtures()) {
            val result = adapt(body, expected = null)
            assertEquals("$name drifted off the contract version", CONTRACT_VERSION, result.contractVersion)
            assertEquals("$name must be attributed to the backend", DataOrigin.BACKEND, result.origin)
            assertEquals("$name must report the backend as available", BackendStatus.AVAILABLE, result.backend)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Timeout / 429 / 503 / drift mapping (fake transports only)
    // ---------------------------------------------------------------------------------------

    @Test
    fun `socket timeout maps to a retryable timeout outcome`() {
        val api = AnalysisApi({ _, _, _, _ -> throw SocketTimeoutException("read timed out") }, enabled = true)
        val outcome = api.analyzeText(M1_LADDER)
        assertEquals(BackendOutcome.Unavailable(AnalysisApi.REASON_TIMEOUT, retryable = true), outcome)
    }

    @Test
    fun `thrown io failure maps to offline and never propagates`() {
        val api = AnalysisApi({ _, _, _, _ -> throw IOException("network is unreachable") }, enabled = true)
        val outcome = api.analyzeText(M1_LADDER)
        assertEquals(BackendOutcome.Unavailable(AnalysisApi.REASON_OFFLINE, retryable = true), outcome)
    }

    @Test
    fun `absent body maps to offline rather than an empty result`() {
        val api = AnalysisApi({ _, _, _, _ -> HttpResult(200, null) }, enabled = true)
        assertEquals(
            BackendOutcome.Unavailable(AnalysisApi.REASON_OFFLINE, retryable = true),
            api.analyzeText(M1_LADDER),
        )
    }

    @Test
    fun `429 rate limit body maps to a retryable rate limited outcome`() {
        val api = AnalysisApi({ _, _, _, _ -> HttpResult(429, F7_RATE_LIMIT_429) }, enabled = true)
        val outcome = api.analyzeText(M1_LADDER)
        assertEquals(BackendOutcome.Unavailable(AnalysisApi.REASON_RATE_LIMITED, retryable = true), outcome)
        // The retry hint is body text, not a verdict: no result object is produced at all.
        assertFalse(outcome is BackendOutcome.Ok)
    }

    @Test
    fun `503 busy body maps to a retryable server error`() {
        val api = AnalysisApi({ _, _, _, _ -> HttpResult(503, F8_SERVER_BUSY_503) }, enabled = true)
        assertEquals(
            BackendOutcome.Unavailable(AnalysisApi.REASON_SERVER_ERROR, retryable = true),
            api.analyzeText(M1_LADDER),
        )
    }

    @Test
    fun `garbage body maps to non retryable schema drift`() {
        val api = AnalysisApi({ _, _, _, _ -> HttpResult(200, "{\"risk\": oops") }, enabled = true)
        assertEquals(
            BackendOutcome.Unavailable(AnalysisApi.REASON_SCHEMA_DRIFT, retryable = false),
            api.analyzeText(M1_LADDER),
        )
    }

    @Test
    fun `200 with a schema drifted body is drift not a verdict`() {
        val api = AnalysisApi({ _, _, _, _ -> HttpResult(200, F9_MISSING_RISK) }, enabled = true)
        assertEquals(
            BackendOutcome.Unavailable(AnalysisApi.REASON_SCHEMA_DRIFT, retryable = false),
            api.analyzeText(M1_LADDER),
        )
    }

    @Test
    fun `no outcome path throws for the ui`() {
        val responses = listOf(
            HttpResult(200, F1_HIGH_RISK_TEXT),
            HttpResult(200, F9_MISSING_RISK),
            HttpResult(200, "[]"),
            HttpResult(429, F7_RATE_LIMIT_429),
            HttpResult(503, F8_SERVER_BUSY_503),
            HttpResult(400, "{\"detail\":\"bad request\"}"),
            HttpResult(200, null),
        )
        for (response in responses) {
            val api = AnalysisApi({ _, _, _, _ -> response }, enabled = true)
            // The assertion is that this call returns at all: the UI never needs a try/catch.
            val outcome = api.analyzeText(M1_LADDER)
            assertNotNull(outcome)
        }
    }

    @Test
    fun `enabled api posts the documented json body to the documented path`() {
        val transport = CountingTransport(HttpResult(200, F2_LEGIT_BANK_CREDIT))
        val api = AnalysisApi(transport, enabled = true, timeoutMillis = 2_500L)

        api.analyzeText(M1_LADDER)
        api.analyzeUrl("http://sbi-kyc.xyz/login")

        assertEquals(
            listOf(AnalysisApi.PATH_TEXT, AnalysisApi.PATH_URL),
            transport.paths,
        )
        assertEquals(AnalysisApi.CONTENT_TYPE_JSON, transport.contentTypes.toSet().single())
        assertEquals(listOf(2_500L, 2_500L), transport.timeouts)
        // The URL is sent as an opaque string field; the payload is never fetched or interpreted.
        assertEquals("{\"url\":" + MiniJson.encodeString("http://sbi-kyc.xyz/login") + "}", transport.bodies[1])
        assertTrue(transport.bodies[0].startsWith("{\"text\":"))
    }

    // ---------------------------------------------------------------------------------------
    // Unsupported input
    // ---------------------------------------------------------------------------------------

    @Test
    fun `empty input is unable to verify with a hinglish limitation`() {
        val result = LocalScorer(normalizer).analyze("")

        assertEquals(RiskLabel.UNABLE_TO_VERIFY, result.risk.label)
        assertEquals(AnalysisStatus.UNABLE_TO_VERIFY, result.status)
        assertEquals(Confidence.LOW, result.risk.confidence)
        assertTrue(result.limitations.contains(LocalScorer.LIMIT_NO_TEXT))
        assertTrue(result.evidence.isEmpty())
    }

    @Test
    fun `emoji only input is unable to verify and never safe`() {
        val result = LocalScorer(normalizer).analyze("\uD83C\uDF89\uD83C\uDF89\uD83C\uDF89\uD83D\uDC4D")

        assertEquals(RiskLabel.UNABLE_TO_VERIFY, result.risk.label)
        assertFalse(result.risk.label == RiskLabel.LOW_RISK)
        assertTrue(result.limitations.isNotEmpty())
        assertTrue(result.limitations.contains(LocalScorer.LIMIT_NO_TEXT))
    }

    @Test
    fun `tamil script input names the unsupported script in hinglish`() {
        val result = LocalScorer(normalizer).analyze(M6_TAMIL)

        assertEquals(RiskLabel.UNABLE_TO_VERIFY, result.risk.label)
        assertEquals(AnalysisStatus.UNABLE_TO_VERIFY, result.status)
        val limitation = result.limitations.single { it.startsWith(LocalScorer.LIMIT_UNSUPPORTED_SCRIPT_PREFIX) }
        assertTrue("limitation must name the script, was $limitation", limitation.contains("Tamil"))
        // Simple Hinglish copy, not an English error string.
        assertTrue(limitation.contains("Is bhasha ko"))
    }

    @Test
    fun `unsupported input keeps the retry advice and never a safe action`() {
        val result = LocalScorer(normalizer).analyze(M6_TAMIL)

        assertTrue(result.actions.contains(LocalScorer.ACTION_UNABLE_HOLD))
        assertTrue(result.actions.contains(LocalScorer.ACTION_UNABLE_RETRY))
        assertFalse(result.actions.contains(LocalScorer.ACTION_LOW_WATCH))
    }

    @Test
    fun `a disabled backend does not change the local unable to verify verdict`() {
        val scorer = LocalScorer(normalizer)
        val before = scorer.analyze(M6_TAMIL)

        val api = AnalysisApi(ThrowingTransport())

        assertEquals(BackendOutcome.Disabled, api.analyzeText(M6_TAMIL))

        val after = scorer.analyze(M6_TAMIL)
        assertEquals(RiskLabel.UNABLE_TO_VERIFY, after.risk.label)
        assertEquals(before, after)
        assertEquals(before.analysisId, after.analysisId)
    }

    // ---------------------------------------------------------------------------------------
    // Local vs backend divergence
    // ---------------------------------------------------------------------------------------

    @Test
    fun `disagreement names both scores and never downgrades the local warning`() {
        val local = LocalScorer(normalizer).analyze(M3_OTP_ASK)
        val backend = adapt(F6_OTP_ASK_UNDERREAD)

        assertEquals(RiskLabel.CAUTION, local.risk.label)
        assertEquals(RiskLabel.LOW_RISK, backend.risk.label)

        val disagreement = ResultAdapter.describeDisagreement(local, backend)
        assertNotNull("labels differ, so a disagreement must be reported", disagreement)
        val report = disagreement!!
        assertEquals(local.risk.score, report.localScore)
        assertEquals(backend.risk.score, report.backendScore)
        assertEquals(RiskLabel.CAUTION, report.localLabel)
        assertEquals(RiskLabel.LOW_RISK, report.backendLabel)
        // The copy must name both engines and must not present the backend as the winner.
        assertTrue(report.message.contains(report.localScore.toString()))
        assertTrue(report.message.contains(report.backendScore.toString()))
        assertTrue(report.message.contains("on-device"))
    }

    @Test
    fun `agreeing labels produce no disagreement`() {
        val local = LocalScorer(normalizer).analyze(M2_LEGIT_BANK_CREDIT)
        val backend = adapt(F2_LEGIT_BANK_CREDIT)

        assertEquals(RiskLabel.LOW_RISK, local.risk.label)
        assertEquals(RiskLabel.LOW_RISK, backend.risk.label)
        assertNull(ResultAdapter.describeDisagreement(local, backend))
    }

    @Test
    fun `a backend unable to verify never overrides a local warning`() {
        val local = LocalScorer(normalizer).analyze(M1_LADDER)
        val backend = adapt(F3_UNABLE_TAMIL)

        val report = ResultAdapter.describeDisagreement(local, backend)
        assertNotNull(report)
        assertTrue(report!!.message.contains("stays"))
        assertEquals(RiskLabel.HIGH_RISK, report.localLabel)
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private fun wireOf(json: String): Map<String, Any?> {
        val parsed = MiniJson.parse(json)
        assertTrue("fixture is not valid JSON", parsed is Map<*, *>)
        @Suppress("UNCHECKED_CAST")
        val raw = parsed as Map<*, *>
        val out = LinkedHashMap<String, Any?>(raw.size)
        for ((key, value) in raw) if (key is String) out[key] = value
        return out
    }

    private fun adapt(json: String, expected: InputType? = InputType.TEXT): AnalysisResult {
        return when (val outcome = ResultAdapter.fromWireSafely(wireOf(json), expected)) {
            is ResultAdapter.AdaptOutcome.Ok -> outcome.result
            is ResultAdapter.AdaptOutcome.Drift -> {
                fail("expected an adapted result, got drift: ${outcome.reason}")
                throw IllegalStateException("unreachable")
            }
        }
    }

    private fun stringsOf(result: AnalysisResult): List<String> = buildList {
        add(result.analysisId)
        addAll(result.limitations)
        addAll(result.actions)
        for (item in result.evidence) {
            add(item.id)
            add(item.module)
            add(item.titleHinglish)
            add(item.detail)
            if (item.quote != null) add(item.quote)
        }
    }

    /** Fixtures whose job is to adapt. F7/F8 are transport-error bodies and F9 is drift. */
    private fun adaptedFixtures(): List<Pair<String, String>> =
        allFixtures().filterNot { it.first in NON_RESULT_FIXTURES }

    private fun allFixtures(): List<Pair<String, String>> = listOf(
        "F1 high-risk text" to F1_HIGH_RISK_TEXT,
        "F2 legit bank credit" to F2_LEGIT_BANK_CREDIT,
        "F3 unable to verify" to F3_UNABLE_TAMIL,
        "F4 ocr image" to F4_OCR_IMAGE,
        "F5 suspicious kyc" to F5_SUSPICIOUS_KYC,
        "F6 otp under-read" to F6_OTP_ASK_UNDERREAD,
        "F7 rate limit" to F7_RATE_LIMIT_429,
        "F8 server busy" to F8_SERVER_BUSY_503,
        "F9 missing risk" to F9_MISSING_RISK,
        "F10 unknown severity" to F10_UNKNOWN_SEVERITY,
    )

    /** Never reached by a disabled API; fails loudly if a transport call escapes. */
    private class ThrowingTransport : HttpTransport {
        var calls: Int = 0
            private set

        override fun post(path: String, body: String, contentType: String, timeoutMillis: Long): HttpResult {
            calls++
            throw IllegalStateException("offline analysis must never touch a transport ($path)")
        }
    }

    private class CountingTransport(private val response: HttpResult = HttpResult(200, null)) : HttpTransport {
        val paths = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val contentTypes = mutableListOf<String>()
        val timeouts = mutableListOf<Long>()

        override fun post(path: String, body: String, contentType: String, timeoutMillis: Long): HttpResult {
            paths += path
            bodies += body
            contentTypes += contentType
            timeouts += timeoutMillis
            return response
        }
    }

    // ---------------------------------------------------------------------------------------
    // Fixture bodies (hand-written from services/analysis/fusion/result.py and
    // services/analysis/evidence/models.py, 26 October 2026) and the six matrix messages.
    // ---------------------------------------------------------------------------------------

    private companion object {
        const val ANY_INPUT = "Main Mumbai Cyber Crime se Inspector Rathore bol raha hoon."

        const val M1_LADDER = "Main Mumbai Cyber Crime se Inspector Rathore bol raha hoon. " +
            "Aapke Aadhaar se bheje gaye parcel mein drugs mile hain. " +
            "Yeh confidential case hai, kisi ko mat batana, video call cut mat karna. " +
            "Case clear karne ke liye RBI verification account mein 50,000 bhejo."

        const val M2_LEGIT_BANK_CREDIT = "Dear Customer, Rs.5,000.00 has been credited to your " +
            "A/c XX4521 on 26-10-2026 from SBI salary. Available balance is Rs.12,450.75. - SBI"

        /** The same notice in its real-world wording, with a protective never-share line. */
        const val M2B_BANK_CREDIT_PROTECTIVE = "Dear Customer, Rs.5,000.00 has been credited to your " +
            "A/c XX4521 on 26-10-2026 from SBI salary. Available balance is Rs.12,450.75. " +
            "Never share your OTP, PIN or password with anyone. - SBI"

        const val M3_OTP_ASK = "Bhai jo OTP aaya hai woh batao"

        const val M4_KYC_PHISH = "Dear SBI customer, your account will be blocked today because KYC " +
            "is pending. Update immediately at http://sbi-kyc.xyz/login or your account will be suspended."

        const val M5_AMAZON = "Your Amazon order #402-5512 has been delivered to the reception. " +
            "Thanks for shopping with us."

        const val M6_TAMIL = "\u0B89\u0B99\u0BCD\u0B95\u0BB3\u0BCD \u0B95\u0BA3\u0B95\u0BCD\u0B95\u0BC1 " +
            "\u0BAE\u0BC1\u0B9F\u0B95\u0BCD\u0B95\u0BAA\u0BCD\u0BAA\u0B9F\u0BC1\u0BAE\u0BCD, " +
            "\u0BB5\u0BBF\u0BB5\u0BB0\u0B99\u0BCD\u0B95\u0BB3\u0BC8 \u0B85\u0BA9\u0BC1\u0BAA\u0BCD\u0BAA\u0BC1\u0B99\u0BCD\u0B95\u0BB3\u0BCD"

        const val OCR_RAW_TEXT = "Your Amazon order #402-5512 has been delivered."
        const val OCR_NORMALIZED_TEXT = "Your Amazon order 402 5512 has been delivered."

        /** Bodies that are deliberately not adaptable: transport errors and schema drift. */
        val NON_RESULT_FIXTURES = setOf("F7 rate limit", "F8 server busy", "F9 missing risk")

        /** (a) High-risk text result with several evidence items. */
        const val F1_HIGH_RISK_TEXT = """
{
  "schema_version": "1.0",
  "analysis_id": "a1b2c3d4e5f6a7b8",
  "created_at": "2026-10-26T04:12:07.918273+00:00",
  "input_type": "text",
  "status": "completed",
  "risk": {"score": 86, "label": "high_risk", "confidence": "high"},
  "classification": {"primary": "digital_arrest", "secondary": ["impersonation", "payment_fraud"]},
  "evidence": [
    {
      "id": "9f2c1a7b3d4e",
      "source": "text_rules",
      "category": "authority_claim",
      "severity": "high",
      "confidence": 0.82,
      "title": "Claims to be a police officer",
      "description": "The message asserts a law-enforcement identity and a case reference.",
      "metadata": {"rule": "authority_claim_v3", "matched_phrase": "Inspector Rathore"},
      "timestamp": "2026-10-26T04:12:07Z"
    },
    {
      "id": "1c4b8e2f9a01",
      "source": "text_rules",
      "category": "arrest_threat",
      "severity": "critical",
      "confidence": 0.91,
      "title": "Threatens arrest over a parcel",
      "description": "Claims drugs were found in a parcel linked to the recipient.",
      "metadata": {"rule": "threat_arrest_v2"},
      "timestamp": "2026-10-26T04:12:07Z"
    },
    {
      "id": "5d7e3a9c2b18",
      "source": "text_rules",
      "category": "secrecy",
      "severity": "high",
      "confidence": 0.77,
      "title": "Asks the recipient to stay silent",
      "description": "Tells the user not to discuss the matter and not to end the call.",
      "metadata": {},
      "timestamp": "2026-10-26T04:12:07Z"
    },
    {
      "id": "7a0f6c1d8e34",
      "source": "text_rules",
      "category": "payment_request",
      "severity": "critical",
      "confidence": 0.88,
      "title": "Requests a transfer to a verification account",
      "description": "Asks for a bank transfer so a pending case can be closed.",
      "metadata": {"amount": 50000, "currency": "INR"},
      "timestamp": "2026-10-26T04:12:07Z"
    }
  ],
  "modules": {
    "text_rules": {"status": "completed", "detail": "12 rules evaluated", "data": {}},
    "ml": {"status": "not_run", "detail": "classifier disabled", "data": {}}
  },
  "explanation": "Police impersonation, an arrest threat, a secrecy instruction and a payment demand in one message.",
  "recommended_actions": ["Do not transfer any money.", "Hang up and call the official police helpline."],
  "limitations": ["Backend text rules cover Hindi and English; Hinglish coverage is partial."]
}
"""

        /** (b) Low-risk legitimate bank credit notice. */
        const val F2_LEGIT_BANK_CREDIT = """
{
  "schema_version": "1.0",
  "analysis_id": "b2c3d4e5f6a7b8c9",
  "created_at": "2026-10-26T09:03:11.004512+00:00",
  "input_type": "text",
  "status": "completed",
  "risk": {"score": 4, "label": "low_risk", "confidence": "high"},
  "classification": {"primary": "transaction_notice", "secondary": []},
  "evidence": [],
  "modules": {"text_rules": {"status": "completed", "detail": "no indicators", "data": {}}},
  "explanation": "A routine credit notification containing a standard do-not-share warning.",
  "recommended_actions": ["Check the transaction in your bank app if you do not recognise it."],
  "limitations": []
}
"""

        /** (c) unable_to_verify with limitations (Tamil input). */
        const val F3_UNABLE_TAMIL = """
{
  "schema_version": "1.0",
  "analysis_id": "c3d4e5f6a7b8c9d0",
  "created_at": "2026-10-26T09:14:52.771900+00:00",
  "input_type": "text",
  "status": "completed",
  "risk": {"score": 0, "label": "unable_to_verify", "confidence": "low"},
  "classification": {"primary": "unsupported_language", "secondary": []},
  "evidence": [],
  "modules": {
    "text_rules": {"status": "completed", "detail": "0 rules applicable", "data": {}},
    "language": {"status": "unavailable", "detail": "no model for Tamil", "data": {}}
  },
  "explanation": "The language of the message is not covered by the rule set.",
  "recommended_actions": ["Re-send the message in Roman or Devanagari Hinglish."],
  "limitations": ["Language not supported by the rule set: Tamil.", "No attributable evidence could be extracted."]
}
"""

        /** (d) Response containing OCR text under modules.ocr.data. */
        const val F4_OCR_IMAGE = """
{
  "schema_version": "1.0",
  "analysis_id": "d4e5f6a7b8c9d0e1",
  "created_at": "2026-10-26T10:22:39.550311+00:00",
  "input_type": "image",
  "status": "completed",
  "risk": {"score": 6, "label": "low_risk", "confidence": "medium"},
  "classification": {"primary": "transaction_notice", "secondary": []},
  "evidence": [
    {
      "id": "3e8b1f7c2a55",
      "source": "ocr",
      "category": "delivery_notice",
      "severity": "informational",
      "confidence": 0.64,
      "title": "Delivery notice screenshot",
      "description": "Recognised text describes a delivered order and contains no request.",
      "metadata": {"module": "ocr", "block_count": 3},
      "timestamp": "2026-10-26T10:22:39Z"
    }
  ],
  "modules": {
    "ocr": {
      "status": "completed",
      "detail": "tesseract 5.3.4, 412 ms",
      "data": {
        "raw_text": "Your Amazon order #402-5512 has been delivered.",
        "normalized_text": "Your Amazon order 402 5512 has been delivered.",
        "blocks": [{"text": "Your Amazon order #402-5512", "bbox": [12, 40, 620, 92]}],
        "image_url": "https://cdn.example/uploads/scan-4025512.jpg"
      }
    }
  },
  "explanation": "A delivery notification with no payment or credential request.",
  "recommended_actions": ["No action needed beyond your usual checks."],
  "limitations": ["Screenshot text recognition is approximate."]
}
"""

        /** (e) 429 rate-limit body (FastAPI HTTPException detail). */
        const val F7_RATE_LIMIT_429 = """
{"detail": "Too many requests; please slow down and retry shortly"}
"""

        /** FastAPI 503 body when the heavy-work gate is full. */
        const val F8_SERVER_BUSY_503 = """
{"detail": "The server is busy analysing other uploads; please retry shortly"}
"""

        /** (f) Malformed / schema-drifted body: no `risk` object at all. */
        const val F9_MISSING_RISK = """
{
  "schema_version": "1.0",
  "analysis_id": "deadbeefcafe0001",
  "input_type": "text",
  "status": "completed",
  "evidence": [],
  "recommended_actions": []
}
"""

        /** Suspicious KYC-phishing link message (matrix row M4). */
        const val F5_SUSPICIOUS_KYC = """
{
  "schema_version": "1.0",
  "analysis_id": "e5f6a7b8c9d0e1f2",
  "created_at": "2026-10-26T11:41:02.665004+00:00",
  "input_type": "text",
  "status": "completed",
  "risk": {"score": 61, "label": "suspicious", "confidence": "medium"},
  "classification": {"primary": "phishing_link", "secondary": ["account_threat"]},
  "evidence": [
    {
      "id": "8b1c4d7e0f22",
      "source": "url_static",
      "category": "domain_mismatch",
      "severity": "high",
      "confidence": 0.79,
      "title": "Account suspension threat",
      "description": "The link host imitates a bank but is not a bank domain.",
      "metadata": {"host": "sbi-kyc.xyz", "registered_days_ago": 4},
      "timestamp": "2026-10-26T11:41:02Z"
    },
    {
      "id": "2d9a6b3c8e14",
      "source": "text_rules",
      "category": "urgency",
      "severity": "medium",
      "confidence": 0.68,
      "title": "Same-day deadline",
      "description": "The message says the account is blocked today.",
      "metadata": {"rule": "urgency_deadline_v1"},
      "timestamp": "2026-10-26T11:41:02Z"
    }
  ],
  "modules": {
    "url_static": {"status": "completed", "detail": "whois and host heuristics", "data": {}},
    "ml": {"status": "not_run", "detail": "classifier disabled", "data": {}}
  },
  "explanation": "A KYC deadline plus a lookalike bank host.",
  "recommended_actions": ["Open the bank app yourself instead of the link.", "Do not enter any credentials."],
  "limitations": []
}
"""

        /** Backend under-reads a Hinglish OTP ask (matrix row M3). */
        const val F6_OTP_ASK_UNDERREAD = """
{
  "schema_version": "1.0",
  "analysis_id": "f6a7b8c9d0e1f2a3",
  "created_at": "2026-10-26T12:02:44.120087+00:00",
  "input_type": "text",
  "status": "completed",
  "risk": {"score": 22, "label": "low_risk", "confidence": "low"},
  "classification": {"primary": "general_message", "secondary": []},
  "evidence": [
    {
      "id": "6f0a2c9d4b71",
      "source": "text_rules",
      "category": "weak_signal",
      "severity": "informational",
      "confidence": 0.31,
      "title": "No strong indicators found",
      "description": "English rule set matched no authority, threat or payment pattern.",
      "metadata": {"language": "hinglish", "matched_rules": 0},
      "timestamp": "2026-10-26T12:02:44Z"
    }
  ],
  "modules": {"text_rules": {"status": "completed", "detail": "0 strong matches", "data": {}}},
  "explanation": "No English-language scam pattern matched this message.",
  "recommended_actions": ["Treat unexpected requests for codes with care."],
  "limitations": ["Hinglish coverage is partial; only English and Hindi patterns were evaluated."]
}
"""

        /** Schema drift #2: a severity value the contract does not know. */
        const val F10_UNKNOWN_SEVERITY = """
{
  "schema_version": "1.0",
  "analysis_id": "0a1b2c3d4e5f6071",
  "created_at": "2026-10-26T12:30:00.000000+00:00",
  "input_type": "text",
  "status": "completed",
  "risk": {"score": 55, "label": "suspicious", "confidence": "medium"},
  "classification": {"primary": "urgency", "secondary": []},
  "evidence": [
    {
      "id": "aa11bb22cc33",
      "source": "text_rules",
      "category": "urgency",
      "severity": "high",
      "confidence": 0.7,
      "title": "Deadline pressure",
      "description": "The message sets a same-day deadline.",
      "metadata": {},
      "timestamp": "2026-10-26T12:30:00Z"
    },
    {
      "id": "dd44ee55ff66",
      "source": "text_rules",
      "category": "urgency",
      "severity": "severe",
      "confidence": 0.9,
      "title": "Escalated deadline",
      "description": "A severity value this contract does not have.",
      "metadata": {},
      "timestamp": "2026-10-26T12:30:00Z"
    },
    {
      "id": "778899aabbcc",
      "source": "text_rules",
      "category": "urgency",
      "severity": "low",
      "confidence": 0.4,
      "title": "Mild nudge",
      "description": "A softer ask to hurry.",
      "metadata": {},
      "timestamp": "2026-10-26T12:30:00Z"
    }
  ],
  "modules": {"text_rules": {"status": "completed", "detail": "3 rules matched", "data": {}}},
  "explanation": "Several urgency cues, one with an unreadable severity.",
  "recommended_actions": ["Do not act on the deadline without verifying."],
  "limitations": []
}
"""
    }
}
