package ai.trustshield.android.input.qr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3-15 / issue #94 — determinism, safety and masking tests for [SimulatedQrFixtures].
 *
 * What these tests lock down, and why:
 *  - The QR Guard demo path must drive the *merged* B02 parser (`UpiQrParser`) and B06 watcher
 *    (`QrWatcher`) with the same semantics as the live camera: three **consecutive valid** mismatches
 *    -> `Tampered`; unreadable / no-decode frames -> never `Tampered`, counter untouched, and after the
 *    five minute occlusion budget -> `Occluded`. See `docs/android/BEHAVIOR_SPEC.md`
 *    ("QR and payment semantics"), `docs/android/REQUIREMENTS.md` F07 and
 *    `docs/android/SOURCE_REPORT_senior_dev.md` §7 (states SETUP/WATCH/RE-REGISTER, edge-case table
 *    "Raat ya kam roshni", "Stand pe 2 QR").
 *  - A QR payload is inert DATA. Nothing in the demo path may open, fetch, resolve or execute a
 *    destination, so this file also reads [SimulatedQrFixtures]' own source and fails if an import or a
 *    call site could reach the network, an Android intent, a WebView or the file system.
 *  - Every fixture is synthetic: obviously fake payees (`@okaxis`, `@ybl`, `@paytm` with `dukaan`/`demo`
 *    local parts), no phone number, no OTP, and the only web address is `example.invalid` (RFC 2606
 *    reserved TLD, cannot resolve even in principle).
 *  - No raw payee may reach a message, a narration line or a `toString()`: only the masked form
 *    (`du***@okaxis`) is allowed, so a demo screen or a pasted log cannot leak a UPI id.
 *
 * Pure JVM, JUnit4, no `android.*`: the whole demo path is offline and deterministic.
 */
class SimulatedQrFixturesTest {

    private val allFlows: List<QrSimulationFlow> = listOf(
        QrSimulationFlow.Match,
        QrSimulationFlow.Mismatch,
        QrSimulationFlow.Unreadable,
        QrSimulationFlow.DualStand,
        QrSimulationFlow.MismatchAfterDarkFrames,
    )

    private val extractor: PayeeExtractor get() = SimulatedQrFixtures.payeeExtractor()

    private fun stepsOf(flow: QrSimulationFlow, kind: SimulatedFrameKind): List<QrSimulationStep> =
        SimulatedQrFixtures.run(flow).steps.filter { it.kind == kind }

    // ---------------------------------------------------------------- MATCH flow

    @Test
    fun `duplicate payee parameters are not comparable`() {
        assertNull(extractor.extract("upi://pay?pa=attacker@ybl&pa=shop@okaxis&cu=INR"))
    }

    @Test
    fun `match flow registers the shop QR and stays watching with no alert`() {
        // report §7: SETUP -> WATCH on the same `pa`, green LED, no alarm.
        val result = SimulatedQrFixtures.run(QrSimulationFlow.Match)

        assertEquals(4, result.steps.size) // register + 3 expected decodes
        val registration = result.steps.first()
        assertEquals(SimulatedFrameKind.REGISTERED, registration.kind)
        assertEquals(QrGuardState.Watching, registration.status.state)
        assertEquals(1, registration.status.registeredCount)

        val matches = result.steps.filter { it.kind == SimulatedFrameKind.EXPECTED_DECODE }
        assertEquals(3, matches.size)
        matches.forEach { step ->
            assertEquals(QrGuardState.Watching, step.status.state)
            assertEquals(QrAlertLevel.None, step.status.alertLevel)
            assertEquals(0, step.status.consecutiveMismatches)
            assertNull(step.status.lastMismatchMasked)
        }

        assertEquals(QrGuardState.Watching, result.finalStatus.state)
        assertEquals(QrAlertLevel.None, result.finalStatus.alertLevel)
        assertFalse(result.everTampered)
        assertFalse(result.everOccluded)
    }

    // ---------------------------------------------------------------- MISMATCH flow

    @Test
    fun `mismatch flow needs exactly three consecutive valid mismatches`() {
        // BEHAVIOR_SPEC: "three consecutive valid decoded mismatches cause a warning"; two is not enough.
        val result = SimulatedQrFixtures.run(QrSimulationFlow.Mismatch)
        val foreign = result.steps.filter { it.kind == SimulatedFrameKind.FOREIGN_DECODE }

        assertEquals(3, foreign.size)
        assertEquals(listOf(1, 2, 3), foreign.map { it.status.consecutiveMismatches })

        assertEquals(QrGuardState.Watching, foreign[0].status.state)
        assertEquals(QrAlertLevel.Watch, foreign[0].status.alertLevel)

        // Two is NOT enough: still watching, only a "watch" level.
        assertEquals(QrGuardState.Watching, foreign[1].status.state)
        assertEquals(QrAlertLevel.Watch, foreign[1].status.alertLevel)
        assertEquals(2, foreign[1].status.consecutiveMismatches)

        // The third one raises the alert.
        assertEquals(QrGuardState.Tampered, foreign[2].status.state)
        assertEquals(QrAlertLevel.Tamper, foreign[2].status.alertLevel)
        assertEquals(3, foreign[2].status.consecutiveMismatches)

        assertEquals(QrGuardState.Tampered, result.finalStatus.state)
        assertEquals(QrAlertLevel.Tamper, result.finalStatus.alertLevel)
        assertTrue(result.everTampered)
        assertFalse(result.everOccluded)
    }

    @Test
    fun `mismatch flow reports masked expected and masked seen ids only`() {
        // report §7 wording: "expected ram***@okaxis, mila 98xxx@ybl" — masked on both sides.
        val result = SimulatedQrFixtures.run(QrSimulationFlow.Mismatch)
        val tamper = result.finalStatus

        assertEquals("de***@ybl", tamper.lastMismatchMasked)
        assertTrue(tamper.message.contains("du***@okaxis"))
        assertTrue(tamper.message.contains("de***@ybl"))
        assertTrue(tamper.message.contains(QrGuardCopy.TAMPERED))
        assertFalse(tamper.message.contains(SimulatedQrFixtures.MERCHANT_PAYEE))
        assertFalse(tamper.message.contains(SimulatedQrFixtures.STICKER_PAYEE))
    }

    // ---------------------------------------------------------------- UNREADABLE flow

    @Test
    fun `unreadable flow never counts never tampers and ends occluded after five minutes`() {
        // BEHAVIOR_SPEC: "No decode/glare pauses the mismatch counter and starts an occlusion timer;
        // after five minutes display unreadable/occluded, not tampered."
        val result = SimulatedQrFixtures.run(QrSimulationFlow.Unreadable)

        val frames = result.steps.filter { it.kind != SimulatedFrameKind.REGISTERED }
        assertTrue(frames.all { it.status.consecutiveMismatches == 0 })
        assertTrue(frames.none { it.status.state == QrGuardState.Tampered })
        assertTrue(frames.none { it.status.alertLevel == QrAlertLevel.Tamper })

        assertEquals(QrGuardState.Occluded, result.finalStatus.state)
        assertEquals(QrAlertLevel.Occluded, result.finalStatus.alertLevel)
        assertTrue(result.everOccluded)
        assertFalse(result.everTampered)

        // Exactly one step is occluded: the one that crosses the five minute budget.
        assertEquals(1, result.steps.count { it.status.state == QrGuardState.Occluded })

        // An unreadable frame never refreshes the last valid decode, so the timer keeps running.
        assertEquals(10_000L, result.finalStatus.lastValidDecodeAt)

        // The budget comes from the shipped policy, not from a demo-specific relaxation.
        assertEquals(300_000L, QrWatcherPolicy().occlusionMs)
        assertTrue(result.elapsedMs >= QrWatcherPolicy().occlusionMs)
        assertTrue(result.steps[6].narration.contains("2 minute 20 second"))
    }

    @Test
    fun `unreadable flow stays watching until the five minute budget is crossed`() {
        val result = SimulatedQrFixtures.run(QrSimulationFlow.Unreadable)
        val lastBeforeBudget = result.steps
            .filter { it.kind != SimulatedFrameKind.REGISTERED }
            .last { it.status.state != QrGuardState.Occluded }

        assertEquals(QrGuardState.Watching, lastBeforeBudget.status.state)
        assertEquals(QrAlertLevel.None, lastBeforeBudget.status.alertLevel)
        assertTrue(lastBeforeBudget.status.lastValidDecodeAt == 10_000L)
    }

    @Test
    fun `unreadable flow includes glare garbage and a url frame but the url is never opened`() {
        val result = SimulatedQrFixtures.run(QrSimulationFlow.Unreadable)
        val kinds = result.steps.map { it.kind }

        assertTrue(kinds.contains(SimulatedFrameKind.UNPARSEABLE_DECODE))
        assertTrue(kinds.contains(SimulatedFrameKind.BLURRED_FRAME))
        // The URL fixture is fed as a decoded-but-unparseable frame: data, never a destination.
        assertEquals(QrPayloadKind.URL, UpiQrParser.classify(SimulatedQrFixtures.urlQr()))
        assertNull(extractor.extract(SimulatedQrFixtures.urlQr()))
    }

    // ---------------------------------------------------------------- counter pause

    @Test
    fun `unreadable frames pause the mismatch counter without resetting it`() {
        // report §7 edge cases: "Raat ya kam roshni" and "Sticker aadha chipka" are unreadable, not tamper;
        // the earlier valid mismatches must still be there when a valid decode returns.
        val result = SimulatedQrFixtures.run(QrSimulationFlow.MismatchAfterDarkFrames)
        val counts = result.steps
            .filter { it.kind != SimulatedFrameKind.REGISTERED }
            .map { it.status.consecutiveMismatches }

        assertEquals(listOf(1, 2, 2, 2, 3), counts)

        result.steps
            .filter { it.kind == SimulatedFrameKind.BLURRED_FRAME || it.kind == SimulatedFrameKind.UNPARSEABLE_DECODE }
            .forEach { step ->
                assertEquals(2, step.status.consecutiveMismatches)
                assertEquals(QrGuardState.Watching, step.status.state)
                assertEquals(QrAlertLevel.Watch, step.status.alertLevel)
            }

        assertEquals(QrGuardState.Tampered, result.finalStatus.state)
        assertTrue(result.everTampered)
    }

    // ---------------------------------------------------------------- dual stand

    @Test
    fun `dual stand registers both payees and every expected decode is a match`() {
        // report §7 edge case: "Stand pe 2 QR (PhonePe + Paytm)" -> several UPI ids, all of them match.
        val result = SimulatedQrFixtures.run(QrSimulationFlow.DualStand)

        result.steps.forEach { assertEquals(2, it.status.registeredCount) }
        val expected = result.steps.filter { it.kind == SimulatedFrameKind.EXPECTED_DECODE }
        assertEquals(4, expected.size)
        expected.forEach { step ->
            assertEquals(0, step.status.consecutiveMismatches)
            assertEquals(QrAlertLevel.None, step.status.alertLevel)
            assertEquals(QrGuardState.Watching, step.status.state)
        }
        assertFalse(result.everTampered)
    }

    // ---------------------------------------------------------------- fixture classification

    @Test
    fun `url fixture classifies as url data and exposes no payee`() {
        val url = SimulatedQrFixtures.urlQr()

        assertEquals(QrPayloadKind.URL, UpiQrParser.classify(url))
        val parsed = UpiQrParser.parse(url)
        assertEquals(QrPayloadKind.URL, parsed.kind)
        assertFalse(parsed.isUpi)
        assertNull(parsed.payeeVpa)
        assertTrue(parsed.flags.isEmpty())
        assertNull(extractor.extract(url))

        // Reserved TLD: no live host is even named, let alone contacted.
        assertTrue(url.startsWith("https://example.invalid/"))
    }

    @Test
    fun `merchant fixture parses to the fake payee and a spoofable display name`() {
        val parsed = UpiQrParser.parse(SimulatedQrFixtures.merchantQr())

        assertEquals(QrPayloadKind.UPI, parsed.kind)
        assertEquals(SimulatedQrFixtures.MERCHANT_PAYEE, parsed.payeeVpa)
        assertEquals(SimulatedQrFixtures.MERCHANT_DISPLAY_NAME, parsed.payeeName)
        assertTrue(parsed.hasFlag(UpiFlag.PAYMENT_REQUEST))
        assertEquals(SimulatedQrFixtures.MERCHANT_PAYEE, extractor.extract(SimulatedQrFixtures.merchantQr()))

        // The sticker copies the same `pn`: only `pa` decides, the label is untrusted by design.
        val sticker = UpiQrParser.parse(SimulatedQrFixtures.stickerQr())
        assertEquals(SimulatedQrFixtures.MERCHANT_DISPLAY_NAME, sticker.payeeName)
        assertEquals(SimulatedQrFixtures.STICKER_PAYEE, sticker.payeeVpa)
    }

    @Test
    fun `no payee garbage and malformed fixtures are unreadable content not mismatches`() {
        val garbage = SimulatedQrFixtures.garbageFrame()
        assertEquals(QrPayloadKind.TEXT, UpiQrParser.classify(garbage))
        assertNull(extractor.extract(garbage))

        val noPayee = SimulatedQrFixtures.noPayeeQr()
        val noPayeeParsed = UpiQrParser.parse(noPayee)
        assertEquals(QrPayloadKind.UPI, noPayeeParsed.kind)
        assertTrue(noPayeeParsed.hasFlag(UpiFlag.MISSING_PAYEE))
        assertNull(noPayeeParsed.payeeVpa)
        assertNull(extractor.extract(noPayee))

        val malformed = SimulatedQrFixtures.malformedPayeeQr()
        val malformedParsed = UpiQrParser.parse(malformed)
        assertEquals(QrPayloadKind.UPI, malformedParsed.kind)
        assertTrue(malformedParsed.hasFlag(UpiFlag.MALFORMED_PAYEE))
        // A structurally broken `pa` is unreadable for the watcher: never a mismatch, never tamper.
        assertNull(extractor.extract(malformed))
    }

    @Test
    fun `fixtures are synthetic with no live host no phone number and no otp`() {
        val payloads = listOf(
            SimulatedQrFixtures.merchantQr(),
            SimulatedQrFixtures.secondMerchantQr(),
            SimulatedQrFixtures.stickerQr(),
            SimulatedQrFixtures.noPayeeQr(),
            SimulatedQrFixtures.malformedPayeeQr(),
            SimulatedQrFixtures.garbageFrame(),
            SimulatedQrFixtures.urlQr(),
        )

        payloads.forEach { payload ->
            assertFalse(payload.contains("http://"))
            assertFalse(payload.contains("localhost"))
            assertFalse(payload.contains("127.0.0.1"))
            // No 6+ digit run anywhere: no OTP, no PIN, no phone number can hide in a fixture.
            assertFalse("digit run in $payload", Regex("[0-9]{6,}").containsMatchIn(payload))
        }

        // Every payee local part is obviously fake, and every handle is on the documented demo list.
        val payees = listOf(
            SimulatedQrFixtures.MERCHANT_PAYEE,
            SimulatedQrFixtures.SECOND_MERCHANT_PAYEE,
            SimulatedQrFixtures.STICKER_PAYEE,
        )
        val allowedHandles = setOf("okaxis", "ybl", "paytm")
        payees.forEach { payee ->
            assertTrue(payee, payee.startsWith("dukaan") || payee.startsWith("demo."))
            assertTrue(payee, payee.substringAfter('@') in allowedHandles)
        }
        assertEquals(3, payees.toSet().size) // three distinct payees: shop, second counter, sticker
    }

    // ---------------------------------------------------------------- masking / privacy

    @Test
    fun `no raw payee reaches any status message narration or toString`() {
        val forbidden = listOf(
            SimulatedQrFixtures.MERCHANT_PAYEE,
            SimulatedQrFixtures.SECOND_MERCHANT_PAYEE,
            SimulatedQrFixtures.STICKER_PAYEE,
        )

        allFlows.forEach { flow ->
            val result = SimulatedQrFixtures.run(flow)
            val rendered = buildString {
                append(result).append(' ').append(result.narrate())
                result.steps.forEach { step ->
                    append(' ').append(step)
                    append(' ').append(step.narration)
                    append(' ').append(step.status)
                    append(' ').append(step.status.message)
                }
                append(' ').append(result.finalStatus)
                append(' ').append(result.finalStatus.message)
            }

            forbidden.forEach { raw ->
                assertFalse("$flow leaked the raw payee $raw", rendered.contains(raw))
                assertFalse(
                    "$flow leaked the local part of $raw",
                    rendered.contains(raw.substringBefore('@')),
                )
            }
            // The masked form is what a demo screen is allowed to show.
            assertTrue("$flow lost the masked shop payee", rendered.contains("du***@okaxis"))
        }
    }

    @Test
    fun `result toString is aggregate only and carries no message body`() {
        val result = SimulatedQrFixtures.run(QrSimulationFlow.Mismatch)
        val text = result.toString()

        assertTrue(text, text.contains("flow=mismatch"))
        assertTrue(text, text.contains("state=Tampered"))
        assertTrue(text, text.contains("alert=Tamper"))
        assertTrue(text, text.contains("steps=4"))
        assertFalse(text, text.contains(QrGuardCopy.TAMPERED)) // no user copy, let alone a payee
        assertFalse(text, text.contains("mila"))
        assertFalse(text, text.contains("*@")) // not even the masked id belongs in an aggregate line
    }

    @Test
    fun `every narration line is a non-blank hinglish line and never a payload dump`() {
        allFlows.forEach { flow ->
            val result = SimulatedQrFixtures.run(flow)
            val lines = result.narrate().lines()

            assertEquals(flow.toString(), result.steps.size, lines.size)
            lines.forEach { line ->
                assertTrue(line.isNotBlank())
                assertFalse(line.contains("upi://"))
                assertFalse(line.contains("https://"))
            }
        }
    }

    // ---------------------------------------------------------------- purity of the demo path

    @Test
    fun `main source imports nothing and calls nothing that could reach io a network or an intent`() {
        val source = mainSourceFile().readText()
        val imports = source.lines()
            .map { it.trim() }
            .filter { it.startsWith("import ") }

        // Same-package Kotlin: no import at all, so no `java.net`, `java.io`, `android.*` or HTTP client.
        assertEquals(emptyList<String>(), imports)

        // Only code lines are scanned; the KDoc is allowed to name the rules it forbids.
        val code = source.lines().filterNot { line ->
            val trimmed = line.trim()
            trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")
        }.joinToString("\n")

        listOf(
            "Intent",
            "startActivity",
            "WebView",
            "openConnection",
            "HttpURLConnection",
            "OkHttp",
            "Socket",
            "ProcessBuilder",
            "Runtime.getRuntime",
            "FileInputStream",
            "FileOutputStream",
            "System.getenv",
            "System.getProperty",
        ).forEach { token ->
            assertFalse("SimulatedQrFixtures.kt must not use $token", code.contains(token))
        }

        // No UPI id literal outside the fixture constants block would still be fine, but no raw payee may
        // appear as a narration literal: guard the copy strings against the fixture payees.
        code.lines()
            .filter { it.contains("narration") || it.contains("QrGuardCopy") }
            .forEach { line ->
                assertFalse(line, line.contains(SimulatedQrFixtures.MERCHANT_PAYEE))
                assertFalse(line, line.contains(SimulatedQrFixtures.STICKER_PAYEE))
            }
    }

    @Test
    fun `fixture source file is reachable from the test working directory`() {
        // Guards the scan above: if this file moves, the purity test must fail loudly, not silently pass.
        val file = mainSourceFile()
        assertTrue(file.isFile)
        assertTrue(file.readText().contains("object SimulatedQrFixtures"))
    }

    private fun mainSourceFile(): File {
        val relative = "src/main/java/ai/trustshield/android/input/qr/SimulatedQrFixtures.kt"
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val direct = File(dir, relative)
            if (direct.isFile) return direct
            val fromRepoRoot = File(dir, "app/$relative")
            if (fromRepoRoot.isFile) return fromRepoRoot
            dir = dir.parentFile
        }
        throw AssertionError(
            "SimulatedQrFixtures.kt not found below ${File("").absolutePath} (cwd=${File("").absolutePath})",
        )
    }

    // ---------------------------------------------------------------- determinism

    @Test
    fun `every flow produces identical results when run twice`() {
        // Determinism is a product requirement here: a demo must never depend on wall-clock time,
        // device speed, network state or randomness.
        allFlows.forEach { flow ->
            val first = SimulatedQrFixtures.run(flow)
            val second = SimulatedQrFixtures.run(flow)

            assertEquals("$flow statuses", first, second)
            assertEquals("$flow toString", first.toString(), second.toString())
            assertEquals("$flow narration", first.narrate(), second.narrate())
            assertEquals("$flow final status", first.finalStatus, second.finalStatus)
        }
    }

    @Test
    fun `each flow names the rule it demonstrates`() {
        // Exhaustive: a new flow must add a wire name and a Hinglish title, so the demo UI has copy.
        val wires = allFlows.map { it.wire }
        assertEquals(wires.size, wires.toSet().size)
        allFlows.forEach { flow ->
            assertTrue(flow.wire.isNotBlank())
            assertTrue(flow.title.isNotBlank())
            assertTrue("${flow.wire} must declare its registered payees", flow.registeredPayees.isNotEmpty())
        }
        assertEquals(5, allFlows.size)
        assertTrue(QrSimulationFlow.Mismatch.registeredPayees.contains(SimulatedQrFixtures.MERCHANT_PAYEE))
        assertEquals(2, QrSimulationFlow.DualStand.registeredPayees.size)
    }
}
