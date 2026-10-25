package ai.trustshield.android.data.api

import ai.trustshield.android.core.model.AnalysisResult
import ai.trustshield.android.core.model.AnalysisStatus
import ai.trustshield.android.core.model.BackendStatus
import ai.trustshield.android.core.model.CONTRACT_VERSION
import ai.trustshield.android.core.model.Confidence
import ai.trustshield.android.core.model.DataOrigin
import ai.trustshield.android.core.model.Evidence
import ai.trustshield.android.core.model.InputType
import ai.trustshield.android.core.model.RiskAssessment
import ai.trustshield.android.core.model.RiskLabel
import ai.trustshield.android.core.model.Severity
import ai.trustshield.android.core.model.Strength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for the optional backend adapter. Every transport here is a fake: no test opens a socket,
 * resolves a URL or reaches the network.
 */
class ResultAdapterTest {

    // ---------------------------------------------------------------- fixtures

    private class Call(val path: String, val body: String, val contentType: String, val timeoutMillis: Long)

    private class RecordingTransport(
        private val respond: (path: String, body: String) -> HttpResult,
    ) : HttpTransport {
        val calls = mutableListOf<Call>()

        override fun post(path: String, body: String, contentType: String, timeoutMillis: Long): HttpResult {
            calls += Call(path, body, contentType, timeoutMillis)
            return respond(path, body)
        }
    }

    private fun evidenceMap(
        source: Any? = "text_rules",
        severity: Any? = "medium",
        title: Any? = "Urgency cue",
        description: Any? = "Message pushes a deadline",
        metadata: Any? = mapOf("rule" to "urgency_v2"),
    ): MutableMap<String, Any?> = mutableMapOf(
        "id" to "e1",
        "source" to source,
        "category" to "urgency",
        "severity" to severity,
        "confidence" to 0.7,
        "title" to title,
        "description" to description,
        "metadata" to metadata,
        "timestamp" to "2026-01-01T00:00:00Z",
    )

    private fun wire(
        score: Any? = 62L,
        label: Any? = "suspicious",
        confidence: Any? = "medium",
        evidence: Any? = mutableListOf<Any?>(evidenceMap()),
    ): MutableMap<String, Any?> = mutableMapOf(
        "schema_version" to "1.0",
        "analysis_id" to "abc123",
        "input_type" to "text",
        "status" to "completed",
        "risk" to mutableMapOf("score" to score, "label" to label, "confidence" to confidence),
        "evidence" to evidence,
        "classification" to mutableMapOf("primary" to "urgency", "secondary" to emptyList<Any?>()),
        "modules" to mutableMapOf<String, Any?>(),
        "explanation" to "Backend explanation",
        "recommended_actions" to mutableListOf<Any?>("Do not share the OTP"),
        "limitations" to mutableListOf<Any?>(),
    )

    private val defaultEvidenceJson =
        """[{"id":"e1","source":"text_rules","category":"urgency","severity":"medium","confidence":0.7,
           "title":"Urgency cue","description":"Message pushes a deadline","metadata":{},
           "timestamp":"2026-01-01T00:00:00Z"}]"""

    private fun okJson(
        score: Any = 62,
        label: String = "suspicious",
        evidence: String = defaultEvidenceJson,
        extra: String = "",
    ): String = """
        {
          "schema_version": "1.0",
          "analysis_id": "a1",
          "input_type": "text",
          "status": "completed",
          "risk": {"score": $score, "label": "$label", "confidence": "medium"},
          "evidence": $evidence,
          "recommended_actions": ["Do not share the OTP"],
          "limitations": []$extra
        }
    """.trimIndent()

    private fun localResult(
        score: Int,
        label: RiskLabel,
        limitations: List<String> = emptyList(),
    ): AnalysisResult = AnalysisResult(
        analysisId = "local-1",
        inputType = InputType.TEXT,
        status = if (label == RiskLabel.UNABLE_TO_VERIFY) AnalysisStatus.UNABLE_TO_VERIFY else AnalysisStatus.COMPLETED,
        risk = RiskAssessment(score = score, label = label, confidence = Confidence.MEDIUM),
        limitations = limitations,
        origin = DataOrigin.LOCAL,
        backend = BackendStatus.DISABLED,
    )

    private fun backendResult(score: Int, label: RiskLabel): AnalysisResult = AnalysisResult(
        analysisId = "backend-1",
        inputType = InputType.TEXT,
        status = AnalysisStatus.COMPLETED,
        risk = RiskAssessment(score = score, label = label, confidence = Confidence.MEDIUM),
        origin = DataOrigin.BACKEND,
        backend = BackendStatus.AVAILABLE,
    )

    private fun adapted(wire: Map<String, Any?>, expected: InputType? = InputType.TEXT): AnalysisResult {
        val outcome = ResultAdapter.fromWireSafely(wire, expected)
        if (outcome !is ResultAdapter.AdaptOutcome.Ok) {
            fail("expected Ok but got $outcome")
        }
        return (outcome as ResultAdapter.AdaptOutcome.Ok).result
    }

    private fun drift(wire: Map<String, Any?>, expected: InputType? = InputType.TEXT) {
        val outcome = ResultAdapter.fromWireSafely(wire, expected)
        assertTrue("expected Drift but got $outcome", outcome is ResultAdapter.AdaptOutcome.Drift)
    }

    // ---------------------------------------------------------------- 1. disabled by default

    @Test
    fun `default constructor is disabled and makes zero transport calls`() {
        val transport = RecordingTransport { _, _ -> HttpResult(200, okJson()) }
        val api = AnalysisApi(transport)

        assertFalse(api.isEnabled)
        assertFalse(ENRICHMENT_ENABLED_BY_DEFAULT)
        assertEquals(BackendOutcome.Disabled, api.analyzeText("Send OTP now"))
        assertEquals(BackendOutcome.Disabled, api.analyzeUrl("https://example.invalid/pay"))
        assertEquals("disabled enrichment must not touch the transport", 0, transport.calls.size)
    }

    @Test
    fun `explicitly disabled api never calls a transport that would fail the test`() {
        val api = AnalysisApi(
            transport = { _, _, _, _ -> throw AssertionError("transport invoked while disabled") },
            enabled = false,
        )
        assertEquals(BackendOutcome.Disabled, api.analyzeText("hello"))
        assertEquals(BackendOutcome.Disabled, api.analyzeUrl("https://example.invalid"))
    }

    // ---------------------------------------------------------------- 2. bounded timeout

    @Test
    fun `timeout is passed to the transport and is within the required bounds`() {
        val transport = RecordingTransport { _, _ -> HttpResult(200, okJson()) }
        val api = AnalysisApi(transport, enabled = true)

        assertTrue(api.timeoutMillis > 0)
        assertTrue(api.timeoutMillis <= AnalysisApi.MAX_TIMEOUT_MILLIS)
        api.analyzeText("hello")

        assertEquals(1, transport.calls.size)
        assertEquals(api.timeoutMillis, transport.calls[0].timeoutMillis)
        assertEquals(AnalysisApi.CONTENT_TYPE_JSON, transport.calls[0].contentType)
        assertEquals(AnalysisApi.PATH_TEXT, transport.calls[0].path)
    }

    @Test
    fun `custom timeout is forwarded unchanged`() {
        val transport = RecordingTransport { _, _ -> HttpResult(200, okJson()) }
        AnalysisApi(transport, enabled = true, timeoutMillis = 1_500L).analyzeText("hello")
        assertEquals(1_500L, transport.calls[0].timeoutMillis)
    }

    @Test
    fun `timeout bounds reject zero and anything above the ceiling`() {
        val transport = RecordingTransport { _, _ -> HttpResult(200, okJson()) }
        for (bad in listOf(0L, -1L, AnalysisApi.MAX_TIMEOUT_MILLIS + 1, 60_000L)) {
            try {
                AnalysisApi(transport, enabled = true, timeoutMillis = bad)
                fail("timeoutMillis=$bad should have been rejected")
            } catch (expected: IllegalArgumentException) {
                // documented: a caller cannot ask for an unbounded wait
            }
        }
        AnalysisApi(transport, enabled = true, timeoutMillis = AnalysisApi.MAX_TIMEOUT_MILLIS)
    }

    // ---------------------------------------------------------------- 3. status mapping

    @Test
    fun `200 with a valid body adapts to an Ok result`() {
        val transport = RecordingTransport { _, _ -> HttpResult(200, okJson()) }
        val outcome = AnalysisApi(transport, enabled = true).analyzeText("Send OTP in 10 minutes")

        assertTrue("expected Ok but got $outcome", outcome is BackendOutcome.Ok)
        val result = (outcome as BackendOutcome.Ok).result
        assertEquals(62, result.risk.score)
        assertEquals(RiskLabel.SUSPICIOUS, result.risk.label)
        assertEquals(DataOrigin.BACKEND, result.origin)
        assertEquals(BackendStatus.AVAILABLE, result.backend)
        assertEquals(CONTRACT_VERSION, result.contractVersion)
    }

    @Test
    fun `429 maps to a retryable rate limit`() {
        val outcome = AnalysisApi(
            RecordingTransport { _, _ -> HttpResult(429, """{"detail":"slow down"}""") },
            enabled = true,
        ).analyzeText("hello")

        assertEquals(BackendOutcome.Unavailable(AnalysisApi.REASON_RATE_LIMITED, true), outcome)
    }

    @Test
    fun `5xx maps to a retryable server error`() {
        val outcome = AnalysisApi(
            RecordingTransport { _, _ -> HttpResult(503, "upstream down") },
            enabled = true,
        ).analyzeText("hello")

        assertEquals(BackendOutcome.Unavailable(AnalysisApi.REASON_SERVER_ERROR, true), outcome)
    }

    @Test
    fun `transport exception maps to offline and never propagates`() {
        val outcome = AnalysisApi(
            RecordingTransport { _, _ -> throw java.io.IOException("no route to host") },
            enabled = true,
        ).analyzeText("hello")

        assertEquals(BackendOutcome.Unavailable(AnalysisApi.REASON_OFFLINE, true), outcome)
    }

    @Test
    fun `socket timeout maps to TIMEOUT rather than OFFLINE`() {
        val outcome = AnalysisApi(
            RecordingTransport { _, _ -> throw java.net.SocketTimeoutException("read timed out") },
            enabled = true,
        ).analyzeUrl("https://example.invalid")

        assertEquals(BackendOutcome.Unavailable(AnalysisApi.REASON_TIMEOUT, true), outcome)
    }

    @Test
    fun `wrapped timeout is still classified as TIMEOUT`() {
        val outcome = AnalysisApi(
            RecordingTransport { _, _ ->
                throw RuntimeException("transport failed", java.util.concurrent.TimeoutException("deadline"))
            },
            enabled = true,
        ).analyzeText("hello")

        assertEquals(BackendOutcome.Unavailable(AnalysisApi.REASON_TIMEOUT, true), outcome)
    }

    @Test
    fun `200 with no body maps to offline`() {
        val outcome = AnalysisApi(
            RecordingTransport { _, _ -> HttpResult(200, null) },
            enabled = true,
        ).analyzeText("hello")

        assertEquals(BackendOutcome.Unavailable(AnalysisApi.REASON_OFFLINE, true), outcome)
    }

    @Test
    fun `unparsable body maps to non-retryable schema drift`() {
        val outcome = AnalysisApi(
            RecordingTransport { _, _ -> HttpResult(200, "<html>gateway error</html>") },
            enabled = true,
        ).analyzeText("hello")

        assertEquals(BackendOutcome.Unavailable(AnalysisApi.REASON_SCHEMA_DRIFT, false), outcome)
    }

    @Test
    fun `valid json that is not an object maps to a non-retryable bad body`() {
        val outcome = AnalysisApi(
            RecordingTransport { _, _ -> HttpResult(200, """["not","an","object"]""") },
            enabled = true,
        ).analyzeText("hello")

        assertEquals(BackendOutcome.Unavailable(AnalysisApi.REASON_BAD_BODY, false), outcome)
    }

    @Test
    fun `unmapped 4xx maps to a non-retryable bad request`() {
        val outcome = AnalysisApi(
            RecordingTransport { _, _ -> HttpResult(422, """{"detail":"invalid"}""") },
            enabled = true,
        ).analyzeText("hello")

        assertEquals(BackendOutcome.Unavailable(AnalysisApi.REASON_BAD_REQUEST, false), outcome)
    }

    @Test
    fun `missing evidence in the payload is schema drift not a low risk result`() {
        val body = """{"analysis_id":"a1","input_type":"text","status":"completed",
            "risk":{"score":80,"label":"high_risk","confidence":"high"}}"""
        val outcome = AnalysisApi(
            RecordingTransport { _, _ -> HttpResult(200, body) },
            enabled = true,
        ).analyzeText("hello")

        assertEquals(BackendOutcome.Unavailable(AnalysisApi.REASON_SCHEMA_DRIFT, false), outcome)
    }

    @Test
    fun `unknown risk label is schema drift`() {
        val outcome = AnalysisApi(
            RecordingTransport { _ , _ -> HttpResult(200, okJson(label = "definitely_safe")) },
            enabled = true,
        ).analyzeText("hello")

        assertEquals(BackendOutcome.Unavailable(AnalysisApi.REASON_SCHEMA_DRIFT, false), outcome)
    }

    // ---------------------------------------------------------------- 4. evidence honesty

    @Test
    fun `evidence with an unknown severity is dropped`() {
        val result = adapted(
            wire(
                evidence = mutableListOf<Any?>(
                    evidenceMap(severity = "catastrophic", title = "Kept out"),
                    evidenceMap(severity = "high", title = "Kept"),
                ),
            ),
        )

        assertEquals(1, result.evidence.size)
        assertEquals("Kept", result.evidence[0].titleHinglish)
        assertTrue(
            "dropped items must be reported, not silently swallowed",
            result.limitations.any { it.contains("dropped") },
        )
    }

    @Test
    fun `evidence ids are deterministic and unique within one result`() {
        val first = adapted(
            wire(evidence = mutableListOf<Any?>(evidenceMap(), evidenceMap(), evidenceMap())),
        )
        val second = adapted(
            wire(evidence = mutableListOf<Any?>(evidenceMap(), evidenceMap(), evidenceMap())),
        )
        assertEquals(3, first.evidence.size)
        assertEquals(3, first.evidence.map { it.id }.toSet().size)
        assertEquals(first.evidence.map { it.id }, second.evidence.map { it.id })
    }

    @Test
    fun `no strength or quote is ever invented`() {
        val result = adapted(
            wire(
                evidence = mutableListOf<Any?>(
                    evidenceMap(severity = "informational"),
                    evidenceMap(severity = "low"),
                    evidenceMap(severity = "medium"),
                    evidenceMap(severity = "high"),
                    evidenceMap(severity = "critical"),
                ),
            ),
        )

        assertEquals(5, result.evidence.size)
        // medium+ is documented as MODERATE, everything below as WEAK - never STRONG.
        assertEquals(Strength.WEAK, result.evidence[0].strength)
        assertEquals(Strength.WEAK, result.evidence[1].strength)
        assertEquals(Strength.MODERATE, result.evidence[2].strength)
        assertEquals(Strength.MODERATE, result.evidence[3].strength)
        assertEquals(Strength.MODERATE, result.evidence[4].strength)
        assertTrue(result.evidence.none { it.strength == Strength.STRONG })
        assertTrue(result.evidence.all { it.quote == null })
        assertTrue(result.evidence.all { it.span == null })
        assertTrue(result.limitations.any { it.contains("no verbatim quote") })
    }

    @Test
    fun `severity is mapped through fromWire not through a default`() {
        val result = adapted(
            wire(
                evidence = mutableListOf<Any?>(
                    evidenceMap(severity = "CRITICAL"),
                    evidenceMap(severity = "informational"),
                ),
            ),
        )
        assertEquals(Severity.CRITICAL, result.evidence[0].severity)
        assertEquals(Severity.INFORMATIONAL, result.evidence[1].severity)
    }

    @Test
    fun `source maps to module and description maps to detail`() {
        val result = adapted(wire())
        val item = result.evidence.single()
        assertEquals("text_rules", item.module)
        assertEquals("Message pushes a deadline", item.detail)
    }

    @Test
    fun `backend english title is kept verbatim without a prefix`() {
        val result = adapted(wire())
        assertEquals("Urgency cue", result.evidence.single().titleHinglish)
    }

    @Test
    fun `metadata is not copied into the result`() {
        val result = adapted(
            wire(
                evidence = mutableListOf<Any?>(
                    evidenceMap(
                        metadata = mapOf(
                            "matched_url" to "https://scam.example/verify",
                            "api_key" to "sk-should-never-surface",
                        ),
                    ),
                ),
            ),
        )
        val rendered = result.toString()
        assertFalse(rendered.contains("scam.example"))
        assertFalse(rendered.contains("sk-should-never-surface"))
    }

    @Test
    fun `evidence with a blank title or blank source is dropped`() {
        val result = adapted(
            wire(
                evidence = mutableListOf<Any?>(
                    evidenceMap(title = "   "),
                    evidenceMap(source = ""),
                ),
            ),
        )
        assertTrue(result.evidence.isEmpty())
        assertTrue(result.limitations.any { it.contains("2 backend evidence item(s) were dropped") })
    }

    // ---------------------------------------------------------------- 5. OCR raw text

    @Test
    fun `ocr raw text and normalized text never reach the adapted result`() {
        val payload = wire().apply {
            this["modules"] = mutableMapOf(
                "ocr" to mutableMapOf(
                    "status" to "completed",
                    "detail" to "tesseract",
                    "data" to mutableMapOf(
                        "raw_text" to "OTP 123456 do not share with anyone",
                        "normalized_text" to "otp 123456 do not share",
                    ),
                ),
            )
        }
        val result = adapted(payload)
        val rendered = result.toString()
        assertFalse(rendered.contains("123456"))
        assertFalse(rendered.contains("raw_text"))
        assertFalse(rendered.contains("normalized_text"))
    }

    // ---------------------------------------------------------------- 6. schema drift

    @Test
    fun `missing risk is drift`() {
        drift(wire().apply { remove("risk") })
    }

    @Test
    fun `risk that is not an object is drift`() {
        drift(wire().apply { this["risk"] = "high" })
    }

    @Test
    fun `non numeric score is drift`() {
        drift(wire(score = "eighty"))
        drift(wire(score = true))
        drift(wire(score = 80.5))
    }

    @Test
    fun `out of range score is drift`() {
        drift(wire(score = 150L))
        drift(wire(score = -1L))
    }

    @Test
    fun `unknown risk label is drift and never becomes low risk`() {
        drift(wire(label = "maybe_safe"))
        drift(wire().apply { this["risk"] = mutableMapOf("score" to 62L, "confidence" to "medium") })
    }

    @Test
    fun `absent evidence is drift`() {
        drift(wire().apply { remove("evidence") })
        drift(wire(evidence = "no evidence"))
    }

    @Test
    fun `blank analysis id is drift`() {
        drift(wire().apply { this["analysis_id"] = "   " })
    }

    @Test
    fun `fromWire throws a documented exception where fromWireSafely drifts`() {
        try {
            ResultAdapter.fromWire(wire().apply { remove("risk") })
            fail("expected SchemaDriftException")
        } catch (expected: SchemaDriftException) {
            assertTrue(expected.message!!.contains("risk"))
        }
    }

    @Test
    fun `unknown input type falls back to the caller endpoint type`() {
        val result = adapted(wire().apply { this["input_type"] = "document" }, InputType.URL)
        assertEquals(InputType.URL, result.inputType)
        drift(wire().apply { this["input_type"] = "hologram" }, expected = null)
    }

    @Test
    fun `unable to verify stays unable to verify and explains itself`() {
        val result = adapted(wire(score = 0L, label = "unable_to_verify"))
        assertEquals(RiskLabel.UNABLE_TO_VERIFY, result.risk.label)
        assertEquals(AnalysisStatus.UNABLE_TO_VERIFY, result.status)
        assertTrue(result.limitations.isNotEmpty())
    }

    @Test
    fun `queued backend status is reported as partial`() {
        val result = adapted(wire().apply { this["status"] = "queued" })
        assertEquals(AnalysisStatus.PARTIAL, result.status)
    }

    @Test
    fun `unusable backend confidence is lowered not raised`() {
        val result = adapted(wire(confidence = "certain"))
        assertEquals(Confidence.LOW, result.risk.confidence)
        assertTrue(result.limitations.any { it.contains("confidence") })
    }

    @Test
    fun `a newer backend schema version still adapts but is reported`() {
        val result = adapted(wire().apply { this["schema_version"] = "1.1" })
        assertEquals(CONTRACT_VERSION, result.contractVersion)
        assertTrue(result.limitations.any { it.contains("1.1") })
    }

    // ---------------------------------------------------------------- 7. no URL is opened or exposed

    @Test
    fun `url payload is never fetched and never lands in the result`() {
        val hostile = "https://secure-bank-verify.example/pay?token=abc"
        val body = okJson(
            extra = ""","url":"$hostile","metadata":{"source_url":"$hostile"}""",
        )
        // metadata also carries the URL, on purpose.
        val transport = RecordingTransport { _, _ -> HttpResult(200, body) }
        val outcome = AnalysisApi(transport, enabled = true).analyzeUrl(hostile)

        assertTrue("expected Ok but got $outcome", outcome is BackendOutcome.Ok)
        // Exactly one call: the backend endpoint. Nothing resolved or opened the link.
        assertEquals(1, transport.calls.size)
        assertEquals(AnalysisApi.PATH_URL, transport.calls[0].path)
        assertTrue(transport.calls[0].body.contains(hostile))

        val result = (outcome as BackendOutcome.Ok).result
        assertFalse(result.toString().contains("secure-bank-verify.example"))
        assertFalse(result.toString().contains("token=abc"))
    }

    @Test
    fun `result types have no url bearing field at all`() {
        val urlLike = { type: Class<*> ->
            type.declaredFields.filter { it.name.lowercase().contains("url") }
        }
        assertTrue(urlLike(AnalysisResult::class.java).isEmpty())
        assertTrue(urlLike(Evidence::class.java).isEmpty())
        assertTrue(urlLike(ai.trustshield.android.core.model.RiskAssessment::class.java).isEmpty())
    }

    // ---------------------------------------------------------------- 8. disagreement is described

    @Test
    fun `backend low versus local high is described and not resolved`() {
        val local = localResult(score = 85, label = RiskLabel.HIGH_RISK)
        val backend = backendResult(score = 20, label = RiskLabel.LOW_RISK)

        val disagreement = ResultAdapter.describeDisagreement(local, backend)
        assertNotNull(disagreement)
        disagreement!!
        assertEquals(20, disagreement.backendScore)
        assertEquals(85, disagreement.localScore)
        assertEquals(RiskLabel.LOW_RISK, disagreement.backendLabel)
        assertEquals(RiskLabel.HIGH_RISK, disagreement.localLabel)
        assertTrue(disagreement.message.contains("85"))
        assertTrue(disagreement.message.contains("20"))
        // The local verdict is what the user already sees; it must survive.
        assertEquals(RiskLabel.HIGH_RISK, local.risk.label)
    }

    @Test
    fun `agreeing labels produce no disagreement even when scores differ`() {
        assertNull(
            ResultAdapter.describeDisagreement(
                localResult(score = 72, label = RiskLabel.HIGH_RISK),
                backendResult(score = 95, label = RiskLabel.HIGH_RISK),
            ),
        )
    }

    @Test
    fun `local unable to verify against a confident backend is a coverage gap`() {
        val disagreement = ResultAdapter.describeDisagreement(
            localResult(
                score = 0,
                label = RiskLabel.UNABLE_TO_VERIFY,
                limitations = listOf("OCR unreadable"),
            ),
            backendResult(score = 88, label = RiskLabel.HIGH_RISK),
        )
        assertNotNull(disagreement)
        assertTrue(disagreement!!.message.contains("coverage"))
    }

    // ---------------------------------------------------------------- 9. MiniJson

    @Test
    fun `MiniJson parses nested objects arrays and numbers`() {
        val parsed = MiniJson.parse(
            """{"a":[1,2,{"b":[true,false,null]}],"c":{"d":-12.5,"e":1e3}}""",
        ) as Map<*, *>

        assertEquals(1L, (parsed["a"] as List<*>)[0])
        val nested = (parsed["a"] as List<*>)[2] as Map<*, *>
        assertEquals(listOf(true, false, null), nested["b"])
        val c = parsed["c"] as Map<*, *>
        assertEquals(-12.5, c["d"])
        assertEquals(1000.0, c["e"])
    }

    @Test
    fun `MiniJson handles escaped quotes backslashes and control escapes`() {
        assertEquals("a\"b", MiniJson.parse("""  "a\"b"  """))
        assertEquals("c\\d", MiniJson.parse(""""c\\d""""))
        assertEquals("line1\nline2\tend", MiniJson.parse(""""line1\nline2\tend""""))
        assertEquals("/", MiniJson.parse(""""\/""""))
    }

    @Test
    fun `MiniJson decodes unicode escapes including surrogate pairs`() {
        assertEquals("हि", MiniJson.parse(""""\u0939\u093f""""))
        assertEquals("\uD83D\uDE00", MiniJson.parse(""""\uD83D\uDE00""""))
        assertEquals("हि", MiniJson.parse(""""हि""""))
    }

    @Test
    fun `MiniJson round trips through encodeString`() {
        val original = "He said \"send OTP\" \\ <ok> \n\t हिंदी \u0001"
        val encoded = MiniJson.encodeString(original)
        assertEquals(original, MiniJson.parse(encoded))
    }

    @Test
    fun `MiniJson returns null for malformed input`() {
        val malformed = listOf(
            "",
            "{",
            "{\"a\":}",
            "{\"a\":1",
            "{\"a\" 1}",
            "[1,2,]",
            "{\"a\":1} trailing",
            "\"unterminated",
            "nul",
            "{'a':1}",
        )
        for (input in malformed) {
            assertNull("expected null for: $input", MiniJson.parse(input))
        }
    }

    @Test
    fun `MiniJson refuses nesting deeper than the bound`() {
        val deep = "[".repeat(MiniJson.MAX_DEPTH + 8) + "]".repeat(MiniJson.MAX_DEPTH + 8)
        assertNull(MiniJson.parse(deep))

        val allowed = "[".repeat(MiniJson.MAX_DEPTH - 2) + "]".repeat(MiniJson.MAX_DEPTH - 2)
        assertTrue(MiniJson.parse(allowed) is List<*>)
    }
}
