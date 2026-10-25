package ai.trustshield.android.input.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B06 / issue #38 — QR watcher state machine tests.
 *
 * Pure JVM: no `android.*` type is constructed. `PayeeExtractor` is stubbed here exactly as the integration
 * will bind it to `UpiQrParser` (B02) — this file does not import B02 or parse UPI itself.
 *
 * NOTE (BUILD REALITY): `:app:testDebugUnitTest` cannot compile tests in this repo — `app/build.gradle.kts`
 * declares no `testImplementation`, so `import org.junit.Test` fails with `Unresolved reference: junit`.
 * These tests are executed by the orchestrator's out-of-tree JVM harness; see the PR body for verbatim output.
 */
class QrWatcherTest {

    private val salt = ByteArray(32) { (it + 1).toByte() }
    private val phonePe = "rahul.sharma@okaxis"
    private val paytm = "9876543210@ybl"
    private val attacker = "attacker@ybl"

    /** Test clock: every decision reads this, nothing else. */
    private class TestClock(var now: Long = 0L) {
        fun read(): Long = now
    }

    /** Stand-in for `UpiQrParser`: pulls `pa` out of a UPI intent payload. */
    private val extractor = PayeeExtractor { payload ->
        val index = payload.indexOf("pa=")
        if (index < 0) {
            null
        } else {
            val pa = payload.substring(index + 3).substringBefore('&').trim()
            if (pa.contains('@')) pa else null
        }
    }

    private fun payloadFor(vpa: String): String = "upi://pay?pa=$vpa&pn=Shop&cu=INR"

    private fun watcher(
        vararg payees: String,
        clock: TestClock,
        policy: QrWatcherPolicy = QrWatcherPolicy(),
    ): QrWatcher {
        val registry = SaltedPayeeRegistry(salt)
        payees.forEach { registry.register(it) }
        return QrWatcher(extractor, registry, { clock.read() }, policy)
    }

    // ---------------------------------------------------------------- mismatch counting

    @Test
    fun threeConsecutiveValidMismatchesRaiseTampered() {
        val clock = TestClock()
        val watcher = watcher(phonePe, clock = clock)

        clock.now = 10_000
        val first = watcher.observeDecoded(payloadFor(attacker))
        assertEquals(QrGuardState.Watching, first.state)
        assertEquals(1, first.consecutiveMismatches)
        assertEquals(QrAlertLevel.Watch, first.alertLevel)

        clock.now = 11_000
        val second = watcher.observeDecoded(payloadFor(attacker))
        assertEquals(QrGuardState.Watching, second.state)
        assertEquals(2, second.consecutiveMismatches)

        clock.now = 12_000
        val third = watcher.observeDecoded(payloadFor(attacker))
        assertEquals(QrGuardState.Tampered, third.state)
        assertEquals(QrAlertLevel.Tamper, third.alertLevel)
        assertEquals(3, third.consecutiveMismatches)
    }

    @Test
    fun twoMismatchesThenAValidMatchResetsTheCounter() {
        val clock = TestClock()
        val watcher = watcher(phonePe, clock = clock)

        clock.now = 1_000
        watcher.observeDecoded(payloadFor(attacker))
        clock.now = 2_000
        assertEquals(2, watcher.observeDecoded(payloadFor(attacker)).consecutiveMismatches)

        clock.now = 3_000
        val matched = watcher.observeDecoded(payloadFor(phonePe))
        assertEquals(0, matched.consecutiveMismatches)
        assertEquals(QrGuardState.Watching, matched.state)
        assertEquals(QrAlertLevel.None, matched.alertLevel)
        assertNull(matched.lastMismatchMasked)

        // Counter really restarted: two more mismatches are still below the limit.
        clock.now = 4_000
        watcher.observeDecoded(payloadFor(attacker))
        clock.now = 5_000
        val stillWatching = watcher.observeDecoded(payloadFor(attacker))
        assertEquals(2, stillWatching.consecutiveMismatches)
        assertEquals(QrGuardState.Watching, stillWatching.state)
    }

    @Test
    fun unreadableFramesNeverCountAsTamperAndPauseTheCounter() {
        val clock = TestClock()
        val watcher = watcher(phonePe, clock = clock)

        clock.now = 1_000
        watcher.observeDecoded(payloadFor(attacker)) // 1
        clock.now = 2_000
        assertEquals(1, watcher.observeUnreadable().consecutiveMismatches)
        clock.now = 3_000
        watcher.observeDecoded(payloadFor(attacker)) // 2
        clock.now = 4_000
        watcher.observeDecoded(payloadFor("not-a-upi-qr")) // no payee -> not a valid mismatch
        clock.now = 5_000
        val afterGlare = watcher.observeUnreadable()
        assertEquals(2, afterGlare.consecutiveMismatches)
        assertEquals(QrGuardState.Watching, afterGlare.state)

        // Only a third VALID mismatch raises the alert.
        clock.now = 6_000
        val tampered = watcher.observeDecoded(payloadFor(attacker))
        assertEquals(QrGuardState.Tampered, tampered.state)
        assertEquals(3, tampered.consecutiveMismatches)
    }

    // ---------------------------------------------------------------- occlusion

    @Test
    fun fiveMinutesWithoutValidDecodeIsOccludedNeverTampered() {
        val clock = TestClock()
        val watcher = watcher(phonePe, clock = clock)

        clock.now = 1_000
        val watching = watcher.observeDecoded(payloadFor(phonePe))
        assertEquals(1_000L, watching.lastValidDecodeAt)

        clock.now = 1_000 + 299_999 // 04:59.999
        val justBefore = watcher.observeUnreadable()
        assertEquals(QrGuardState.Watching, justBefore.state)
        assertEquals(1_000L, justBefore.lastValidDecodeAt) // never reset by a bad frame

        clock.now = 1_000 + 300_000 // 05:00
        val occluded = watcher.observeUnreadable()
        assertEquals(QrGuardState.Occluded, occluded.state)
        assertEquals(QrAlertLevel.Occluded, occluded.alertLevel)
        assertEquals(0, occluded.consecutiveMismatches)
        assertEquals(1_000L, occluded.lastValidDecodeAt)
        assertNotEquals(QrGuardState.Tampered, occluded.state)
    }

    @Test
    fun validDecodeAtFourFiftyNinePreventsOcclusion() {
        val clock = TestClock()
        val watcher = watcher(phonePe, clock = clock)

        clock.now = 0
        watcher.observeDecoded(payloadFor(phonePe))

        clock.now = 299_000 // 04:59
        assertEquals(QrGuardState.Watching, watcher.observeUnreadable().state)

        // A valid (mismatching) decode at 04:59 restarts the occlusion window.
        clock.now = 299_000
        val revived = watcher.observeDecoded(payloadFor(attacker))
        assertEquals(299_000L, revived.lastValidDecodeAt)
        assertEquals(1, revived.consecutiveMismatches)
        assertEquals(QrGuardState.Watching, revived.state)

        clock.now = 300_500 // > 5 min after construction, only 1.5 s after the last valid decode
        val stillWatching = watcher.observeUnreadable()
        assertEquals(QrGuardState.Watching, stillWatching.state)
        assertEquals(QrAlertLevel.Watch, stillWatching.alertLevel)
        assertEquals(299_000L, stillWatching.lastValidDecodeAt)
    }

    // ---------------------------------------------------------------- multiple payees

    @Test
    fun twoRegisteredPayeesBothCountAsMatches() {
        val clock = TestClock()
        val watcher = watcher(phonePe, paytm, clock = clock) // dual stand: PhonePe + Paytm

        clock.now = 1_000
        val first = watcher.observeDecoded(payloadFor(phonePe))
        assertEquals(2, first.registeredCount)
        assertEquals(QrGuardState.Watching, first.state)
        assertEquals(0, first.consecutiveMismatches)

        clock.now = 2_000
        val second = watcher.observeDecoded(payloadFor(paytm))
        assertEquals(QrGuardState.Watching, second.state)
        assertEquals(0, second.consecutiveMismatches)
        assertEquals(QrAlertLevel.None, second.alertLevel)
    }

    @Test
    fun mismatchAgainstTheSecondRegisteredPayeeCounts() {
        val clock = TestClock()
        // Only the second payee is registered: a PhonePe QR on this stand is now the foreign one.
        val watcher = watcher(paytm, clock = clock)

        clock.now = 1_000
        watcher.observeDecoded(payloadFor(phonePe))
        clock.now = 2_000
        watcher.observeDecoded(payloadFor(phonePe))
        clock.now = 3_000
        val tampered = watcher.observeDecoded(payloadFor(phonePe))

        assertEquals(QrGuardState.Tampered, tampered.state)
        assertEquals(3, tampered.consecutiveMismatches)
        assertEquals("ra***@okaxis", tampered.lastMismatchMasked)

        // And the registered payee itself still matches.
        clock.now = 4_000
        val backToNormal = watcher.acknowledgeTamper()
        assertEquals(QrGuardState.Watching, backToNormal.state)
        assertEquals(0, watcher.observeDecoded(payloadFor(paytm)).consecutiveMismatches)
    }

    @Test
    fun emptyRegistryStaysInSetupWithoutCounting() {
        val clock = TestClock()
        val watcher = watcher(clock = clock)

        assertEquals(QrGuardState.Setup, watcher.currentState)
        clock.now = 1_000
        val status = watcher.observeDecoded(payloadFor(attacker))
        assertEquals(QrGuardState.Setup, status.state)
        assertEquals(QrAlertLevel.None, status.alertLevel)
        assertEquals(0, status.registeredCount)
        assertEquals(0, status.consecutiveMismatches)
    }

    // ---------------------------------------------------------------- re-register

    @Test
    fun reRegisterRefusedWithoutTheFiveSecondLongPress() {
        val clock = TestClock()
        val watcher = watcher(phonePe, clock = clock)

        val refused = watcher.requestReRegister(longPressMs = 4_999, pinVerified = true)
        assertTrue(refused is ReRegisterResult.Refused)
        assertEquals(
            ReRegisterRefusal.LONG_PRESS_INCOMPLETE,
            (refused as ReRegisterResult.Refused).reason,
        )
        assertEquals(QrGuardState.ReRegisterPending, watcher.currentState)
        assertEquals(1, watcher.status().registeredCount) // nothing was replaced
    }

    @Test
    fun reRegisterRefusedWhenThePinCheckFails() {
        val clock = TestClock()
        val watcher = watcher(phonePe, clock = clock)

        val refused = watcher.requestReRegister(longPressMs = 5_000, pinVerified = false)
        assertTrue(refused is ReRegisterResult.Refused)
        assertEquals(ReRegisterRefusal.PIN_REQUIRED, (refused as ReRegisterResult.Refused).reason)
        assertEquals(QrGuardState.ReRegisterPending, watcher.currentState)
        assertEquals(1, watcher.status().registeredCount)
    }

    @Test
    fun reRegisterAcceptedWithLongPressAndPin() {
        val clock = TestClock()
        val watcher = watcher(phonePe, clock = clock)

        clock.now = 7_000
        val accepted = watcher.requestReRegister(longPressMs = 5_000, pinVerified = true)
        assertEquals(ReRegisterResult.Accepted, accepted)
        assertEquals(QrGuardState.Setup, watcher.currentState)

        clock.now = 8_000
        val registered = watcher.registerPayee("newshop@okhdfc")
        assertEquals(QrGuardState.Watching, registered.state)
        assertEquals(2, registered.registeredCount)
        assertTrue(registered.message.isNotEmpty())
    }

    // ---------------------------------------------------------------- tamper stickiness

    @Test
    fun tamperIsStickyUntilAcknowledgedAndDoesNotBecomeOccluded() {
        val clock = TestClock()
        val watcher = watcher(phonePe, clock = clock)

        clock.now = 1_000
        watcher.observeDecoded(payloadFor(attacker))
        clock.now = 2_000
        watcher.observeDecoded(payloadFor(attacker))
        clock.now = 3_000
        assertEquals(QrGuardState.Tampered, watcher.observeDecoded(payloadFor(attacker)).state)

        // Restoring the original QR must not silently clear the alert.
        clock.now = 4_000
        assertEquals(QrGuardState.Tampered, watcher.observeDecoded(payloadFor(phonePe)).state)

        // Covering the camera must not weaken it into "unreadable" either.
        clock.now = 4_000 + 600_000
        assertEquals(QrGuardState.Tampered, watcher.observeUnreadable().state)

        clock.now = 4_100 + 600_000
        val acknowledged = watcher.acknowledgeTamper()
        assertEquals(QrGuardState.Watching, acknowledged.state)
        assertEquals(QrAlertLevel.None, acknowledged.alertLevel)
        assertEquals(0, acknowledged.consecutiveMismatches)
    }

    // ---------------------------------------------------------------- masking / registry

    @Test
    fun maskingNeverRevealsTheFullVpa() {
        assertEquals("ra***@okaxis", PayeeMasking.mask("rahul.sharma@okaxis"))
        assertEquals("98***@ybl", PayeeMasking.mask(paytm))
        assertEquals(PayeeMasking.HIDDEN, PayeeMasking.mask("no-at-sign"))
        assertEquals(PayeeMasking.HIDDEN, PayeeMasking.mask("@okaxis"))
        assertEquals(PayeeMasking.HIDDEN, PayeeMasking.mask("rahul@"))
        assertFalse(PayeeMasking.mask("ab@okaxis").contains("ab@"))
        assertFalse(PayeeMasking.mask("rahul.sharma@okaxis").contains("rahul"))

        val clock = TestClock()
        val watcher = watcher(phonePe, clock = clock)
        clock.now = 1_000
        watcher.observeDecoded(payloadFor(attacker))
        clock.now = 2_000
        watcher.observeDecoded(payloadFor(attacker))
        clock.now = 3_000
        val tampered = watcher.observeDecoded(payloadFor(attacker))

        val rendered = tampered.toString() + " " + tampered.message
        assertFalse(rendered.contains(attacker))
        assertFalse(rendered.contains(phonePe))
        assertFalse(rendered.contains("rahul"))
        assertFalse(rendered.contains("attacker"))
        assertTrue(tampered.message.contains("ra***@okaxis"))
        assertTrue(tampered.message.contains("at***@ybl"))
        assertEquals("at***@ybl", tampered.lastMismatchMasked)
    }

    @Test
    fun registryRoundtripClearAndSalting() {
        val registry = SaltedPayeeRegistry(salt)
        registry.register(phonePe)
        registry.register(paytm)
        registry.register(phonePe) // idempotent

        assertEquals(2, registry.registered().size)
        val masks = registry.registered().map { it.masked }
        assertTrue(masks.contains("ra***@okaxis"))
        assertTrue(masks.contains("98***@ybl"))
        for (payee in registry.registered()) {
            assertEquals(64, payee.identifier.length) // HMAC-SHA256 hex
            assertTrue(payee.identifier.all { it in '0'..'9' || it in 'a'..'f' })
            assertFalse(payee.toString().contains("rahul"))
            assertFalse(payee.toString().contains(paytm))
        }

        assertTrue(registry.matches(phonePe))
        assertTrue(registry.matches("  $phonePe  "))
        assertFalse(registry.matches(attacker))

        // A different salt yields a different identifier: the stored value is salted, not plain.
        val otherSalt = SaltedPayeeRegistry(ByteArray(32) { 7 })
        otherSalt.register(phonePe)
        assertNotEquals(registry.registered().first().identifier, otherSalt.registered().first().identifier)

        registry.clear()
        assertTrue(registry.registered().isEmpty())
        assertFalse(registry.matches(phonePe))
    }

    // ---------------------------------------------------------------- offline / determinism

    @Test
    fun frozenInjectedClockGivesDeterministicLocalVerdicts() {
        fun run(frozenNow: Long): List<QrWatcherStatus> {
            val clock = TestClock(frozenNow)
            val watcher = watcher(phonePe, paytm, clock = clock)
            val frames = listOf(
                payloadFor(phonePe),
                payloadFor(attacker),
                "garbage-frame",
                payloadFor(paytm),
                payloadFor(attacker),
                payloadFor(attacker),
                payloadFor(attacker),
            )
            return frames.mapIndexed { index, frame ->
                if (index == 2) watcher.observeUnreadable() else watcher.observeDecoded(frame)
            }
        }

        val first = run(42_000)
        val second = run(42_000)
        assertEquals(first, second) // data-class equality: same states, counters, levels, messages
        assertEquals(QrGuardState.Tampered, first.last().state)
        // Every decision used the injected clock and nothing else.
        assertTrue(first.all { it.lastValidDecodeAt == null || it.lastValidDecodeAt == 42_000L })
    }

    @Test
    fun nonUpiPayloadIsNeverTamperAndStillFeedsTheOcclusionTimer() {
        val clock = TestClock()
        val watcher = watcher(phonePe, clock = clock)

        for (index in 1..5) {
            clock.now = index * 1_000L
            val status = watcher.observeDecoded("https://example.in/offer?id=$index")
            assertEquals(QrGuardState.Watching, status.state)
            assertEquals(0, status.consecutiveMismatches)
            assertNull(status.lastValidDecodeAt)
        }

        // Five minutes of decodable-but-unparseable frames is "unreadable", not "tampered".
        clock.now = 301_000
        val occluded = watcher.observeDecoded("https://example.in/offer?id=9")
        assertEquals(QrGuardState.Occluded, occluded.state)
        assertEquals(0, occluded.consecutiveMismatches)
    }
}
