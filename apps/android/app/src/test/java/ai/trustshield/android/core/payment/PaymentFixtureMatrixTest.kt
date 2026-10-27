package ai.trustshield.android.core.payment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the synthetic, resource-backed adversarial matrix through the actual parser and engine. */
class PaymentFixtureMatrixTest {
    private val engine = PaymentAdvisoryEngine()

    @Test
    fun `payment advisory fixture matrix agrees with parser and engine`() {
        val stream = javaClass.classLoader!!.getResourceAsStream("payment-advisory/matrix.tsv")
            ?: error("payment-advisory/matrix.tsv missing")
        val rows = stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }
                .map { it.split('\t') }.toList()
        }
        assertEquals("fixture corpus should stay intentionally broad", 10, rows.size)
        rows.forEach { row ->
            require(row.size == 9) { "${row.firstOrNull()}: expected 9 columns, got ${row.size}" }
            val (id, text, registered, matched, now, amount, status, hints, verdict) = row
            val fields = PaymentFieldsParser.parse(text)
            assertEquals("$id amount", amount.ifEmpty { null }?.toLong(), fields.amountMinor)
            assertEquals("$id status", PaymentStatus.valueOf(status), fields.status)
            val advisory = engine.evaluate(
                PaymentAdvisoryInput(
                    sourceText = text,
                    fields = fields,
                    registeredPayeeCount = registered.toInt(),
                    payeeMatchesRegistered = matched.toBooleanStrict(),
                    nowEpochMs = now.toLong(),
                    ocrUsed = true
                )
            )
            assertEquals("$id hints", hints.split('|').map(PaymentHintKind::valueOf), advisory.hints.map { it.kind })
            assertEquals("$id verdict", PaymentAdvisoryVerdict.valueOf(verdict), advisory.verdict)
            assertTrue("$id requires bank/app confirmation", advisory.confirmationRequired)
        }
    }
}
