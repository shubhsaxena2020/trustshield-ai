package ai.trustshield.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MainActivitySavedStatePrivacyTest {
    @Test
    fun sensitiveScanAndResultStateStayInTheNonConfigurationHolder() {
        val source = findMainActivitySource().readText()
        val saveMethod = source.substringAfter("override fun onSaveInstanceState(outState: Bundle)")
            .substringBefore("private fun routeFromIntent")

        assertTrue(source.contains("override fun onRetainCustomNonConfigurationInstance(): Any? = retainedUi"))
        listOf("incomingText", "resultOutcome", "scanState", "registeredPayees").forEach { field ->
            assertTrue("$field must be retained in memory", source.contains("val $field = mutableStateOf"))
            assertFalse("$field must not be saved in the Bundle", saveMethod.contains(field))
        }
        assertFalse("message text must never be written to the Bundle", saveMethod.contains("incomingText"))
        assertTrue("only navigation metadata is saved", saveMethod.contains("STATE_ENTRY_SEQUENCE"))
        assertTrue("the visible route is the only saved string", saveMethod.contains("putString(STATE_VISIBLE_ROUTE"))
    }

    private fun findMainActivitySource(): File {
        var dir = File("").absoluteFile
        repeat(6) {
            val file = File(dir, "src/main/java/ai/trustshield/android/MainActivity.kt")
            if (file.isFile) return file
            dir = dir.parentFile ?: return error("Cannot locate MainActivity.kt")
        }
        error("Cannot locate MainActivity.kt")
    }
}
