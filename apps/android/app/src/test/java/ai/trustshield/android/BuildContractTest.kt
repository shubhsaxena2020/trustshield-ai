package ai.trustshield.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build-level privacy contract (found by the head's code review, finding BS-01): ML Kit pulls a
 * telemetry transport that merges android.permission.INTERNET into the final manifest even though
 * our own manifest never declares it. The source manifest must remove it explicitly.
 */
class BuildContractTest {
    private fun manifest(): String {
        val candidates = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
        return candidates.first { it.exists() }.readText()
    }

    @Test
    fun manifestRemovesTheInternetPermissionThatLibrariesMerge() {
        val text = manifest()
        val removal = Regex(
            """<uses-permission\s+android:name="android\.permission\.INTERNET"\s+tools:node="remove"\s*/>"""
        )
        assertTrue("INTERNET must be removed with tools:node=remove", removal.containsMatchIn(text))
        assertTrue("tools namespace must be declared", text.contains("xmlns:tools=\"http://schemas.android.com/tools\""))
    }

    @Test
    fun manifestNeverDeclaresInternetAsAGrant() {
        val grant = Regex(
            """<uses-permission\s+android:name="android\.permission\.INTERNET"\s*/>"""
        )
        assertTrue("INTERNET must never be granted", !grant.containsMatchIn(manifest()))
    }
}
