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
            value.any { it in '\u0900'..'\u097f' } || value in allowList
        }
        assertTrue("Hindi copy without Devanagari: $missingScript", missingScript.isEmpty())
    }
}
