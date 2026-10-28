package ai.trustshield.android.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test

class HindiCopyTest {
    @Test
    fun translationTablesHaveOneCanonicalEntryPerEnglishKey() {
        assertTrue("duplicate Hindi translations: ${HindiCopy.duplicateEnglishKeys()}", HindiCopy.duplicateEnglishKeys().isEmpty())
    }

    @Test
    fun everyAutomaticHindiTranslationUsesDevanagariOrAnApprovedTechnicalTerm() {
        val allowList = setOf("Call Shield", "SBI KYC")
        val missingScript = HindiCopy.allHindiValues().filterNot { value ->
            value.isBlank() || value.any { it in '\u0900'..'\u097f' } || value in allowList
        }
        assertTrue("Hindi copy without Devanagari: $missingScript", missingScript.isEmpty())
    }

    @Test
    fun reviewedPreviouslyMissingStringsNoLongerFallBackToEnglish() {
        listOf(
            "QR Guard",
            "Registered: {id}",
            "Scan another QR or start watching.",
            "Camera panel — ",
            "This is a digital-arrest scam pattern. No police or CBI officer arrests anyone over a video call. End the call and call 1930."
        ).forEach { english ->
            val hindi = HindiCopy.forEnglish(english)
            assertTrue("Hindi fallback for '$english': $hindi", hindi != english)
            assertTrue("Hindi translation lacks Devanagari: $english", hindi.any { it in '\u0900'..'\u097f' })
        }
    }
}
