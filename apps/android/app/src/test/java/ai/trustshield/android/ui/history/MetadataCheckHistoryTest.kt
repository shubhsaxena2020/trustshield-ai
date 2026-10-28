package ai.trustshield.android.ui.history

import ai.trustshield.android.core.model.RiskLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataCheckHistoryTest {
    @Test
    fun encodingPersistsOnlyTimestampVerdictAndOptionalScore() {
        val entry = MetadataCheck(1_798_000_000_000, RiskLabel.HIGH_RISK, 82)

        val encoded = MetadataCheckHistory.encode(listOf(entry))

        assertEquals("1798000000000|high_risk|82", encoded)
        assertEquals(3, encoded.split('|').size)
        assertEquals(listOf(entry), MetadataCheckHistory.decode(encoded))
    }

    @Test
    fun unverifiableResultDoesNotPersistAnInventedScore() {
        val entry = MetadataCheck(1_798_000_000_000, RiskLabel.UNABLE_TO_VERIFY, null)

        assertEquals("1798000000000|unable_to_verify|-", MetadataCheckHistory.encode(listOf(entry)))
        assertEquals(listOf(entry), MetadataCheckHistory.decode(MetadataCheckHistory.encode(listOf(entry))))
    }
}
