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

    @Test
    fun manifestUsesOnlyTheReviewedPermissionAllowList() {
        val granted = Regex("""<uses-permission\s+android:name="([^"]+)"\s*/>""")
            .findAll(manifest()).map { it.groupValues[1] }.toSet()
        assertTrue("permission allow-list changed: $granted", granted == setOf(
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.RECEIVE_SMS",
            "android.permission.READ_PHONE_STATE",
            "android.permission.READ_CALL_LOG",
            "android.permission.POST_NOTIFICATIONS"
        ))
    }
}
