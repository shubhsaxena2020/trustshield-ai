package ai.trustshield.android.ui.navigation

import ai.trustshield.android.core.analysis.LocalScanPipeline
import ai.trustshield.android.core.model.InputType
import ai.trustshield.android.ui.qr.RegisteredPayee
import ai.trustshield.android.core.payment.PaymentHintKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisToResultPaymentAdvisoryTest {
    @Test
    fun ocrReceiptAttachesAdvisoryUsingCurrentRegisteredPayees() {
        val text = "Payment Successful. Rs 6,000 paid to shop@okaxis. UTR 412345678901."
        val outcome = LocalScanPipeline().analyzeImageText(text)

        val result = AnalysisToResult.build(outcome, listOf(RegisteredPayee("known@okaxis")))
        val advisory = result.paymentAdvisory

        assertNotNull(advisory)
        assertTrue(outcome.result.inputType == InputType.IMAGE)
        assertTrue(advisory!!.hints.any { it.kind == PaymentHintKind.SCREENSHOT_ONLY })
        assertTrue(advisory.hints.any { it.kind == PaymentHintKind.PAYEE_NOT_REGISTERED })
        assertEquals("sho***@okaxis", advisory.fields.payeeUpi)
        assertTrue(advisory.fields.amountRaw.isEmpty())
        assertTrue(advisory.fields.utr.isEmpty())
        assertTrue(advisory.fields.maskedUpi.contains("***"))
        assertTrue(advisory.fields.toString().contains("***"))
        assertTrue(!advisory.fields.toString().contains("shop@okaxis"))
    }

    @Test
    fun paymentLikeTypedTextDoesNotClaimOcrProvenance() {
        val outcome = LocalScanPipeline().analyzeText("Please send Rs 6,000 now")
        assertEquals(InputType.TEXT, outcome.result.inputType)
        assertNull(AnalysisToResult.build(outcome).paymentAdvisory)
    }
}
