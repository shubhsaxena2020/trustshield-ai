package ai.trustshield.android.core.flood

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FloodDetectorTest {
    @Test fun normalConversationOverAnHourDoesNotAlert() {
        val detector = FloodDetector()
        repeat(10) { detector.record(sms(it * 7 * 60_000L, "friend")) }
        assertEquals(FloodSeverity.NONE, detector.snapshot().severity)
    }

    @Test fun twoFamilyCallsDoNotAlert() {
        val detector = FloodDetector()
        detector.record(call(0, "family", CallOutcome.MISSED))
        detector.record(call(60_000, "family", CallOutcome.ANSWERED))
        assertEquals(FloodSeverity.NONE, detector.snapshot().severity)
    }

    @Test fun bankOtpAndConfirmationDoNotAlert() {
        val detector = FloodDetector()
        detector.record(sms(0, "bank", true))
        detector.record(sms(20_000, "bank", false))
        assertEquals(FloodSeverity.NONE, detector.snapshot().severity)
    }

    @Test fun broadSmsThresholdIsInclusiveAtEightWithinSixtySeconds() {
        val detector = FloodDetector()
        repeat(7) { detector.record(sms(it * 8_000L, "s$it")) }
        assertEquals(FloodSeverity.WATCH, detector.snapshot().severity)
        assertEquals(FloodSeverity.FLOOD, detector.record(sms(56_000, "s7")).severity)
    }

    @Test fun exactWindowBoundaryExpiresOldSms() {
        val detector = FloodDetector()
        repeat(7) { detector.record(sms(it.toLong(), "s$it")) }
        detector.record(sms(66_001, "new"))
        assertEquals(FloodSeverity.NONE, detector.snapshot().severity)
        assertEquals(1, detector.snapshot().count)
    }

    @Test fun sameSenderAndOtpServiceRulesTrigger() {
        val same = FloodDetector()
        repeat(5) { same.record(sms(it * 5_000L, "one")) }
        assertEquals(FloodReason.SMS_SAME_SENDER, same.snapshot().reason)

        val otp = FloodDetector()
        repeat(6) { otp.record(sms(it * 10_000L, "service-${it % 3}", true)) }
        assertEquals(FloodReason.SMS_OTP_SERVICES, otp.snapshot().reason)
    }

    @Test fun missedOrRejectedRepeatCallsAndDistributedCallsTrigger() {
        val repeated = FloodDetector()
        repeated.record(call(0, "number", CallOutcome.MISSED))
        repeated.record(call(60_000, "number", CallOutcome.REJECTED))
        assertEquals(FloodSeverity.WATCH, repeated.snapshot().severity)
        assertEquals(FloodSeverity.FLOOD, repeated.record(call(120_000, "number", CallOutcome.ANSWERED)).severity)

        val distributed = FloodDetector()
        repeat(4) { distributed.record(call(it * 60_000L, "number-$it", CallOutcome.ANSWERED)) }
        assertEquals(FloodReason.CALL_DISTRIBUTED, distributed.snapshot().reason)
    }

    @Test fun clockRollbackDoesNotRewindWindow() {
        val detector = FloodDetector()
        repeat(7) { detector.record(sms(it * 1_000L, "s$it")) }
        val stale = detector.record(sms(-50_000, "stale"))
        assertEquals(FloodSeverity.WATCH, stale.severity)
        assertEquals(7, stale.count)
    }

    @Test fun longRunMemoryIsBoundedAndOldEventsDrop() {
        val detector = FloodDetector()
        repeat(5_000) { detector.record(sms(it * 10L, "sender-$it")) }
        assertTrue(detector.retainedEventCount <= FloodDetector.MAX_EVENTS)
        detector.record(sms(10_000_000, "new"))
        assertEquals(1, detector.retainedEventCount)
    }

    @Test fun otpHeuristicDoesNotTreatPlainDigitsAsOtp() {
        assertFalse(isOtpLike("Your bill number is 123456"))
        assertTrue(isOtpLike("Your OTP is 123456"))
        assertTrue(isOtpLike("Use this verification code to sign in"))
    }

    @Test fun randomizedInputNeverExceedsMemoryBoundOrReturnsInvalidCounts() {
        val random = Random(0xB0B5)
        val detector = FloodDetector()
        var time = 0L
        repeat(10_000) {
            time += random.nextLong(0, 30_000)
            val event = if (random.nextBoolean()) sms(time, "sender-${random.nextInt(20)}", random.nextBoolean())
                else call(time, "caller-${random.nextInt(8)}", CallOutcome.entries.random(random))
            val result = detector.record(event)
            assertTrue(result.count >= 0)
            assertTrue(detector.retainedEventCount <= FloodDetector.MAX_EVENTS)
        }
    }

    private fun sms(at: Long, sender: String, otp: Boolean = false) =
        FloodEvent(at, sender, FloodKind.SMS, isOtpLike = otp)
    private fun call(at: Long, caller: String, outcome: CallOutcome) =
        FloodEvent(at, caller, FloodKind.CALL, callOutcome = outcome)
}
