package ai.trustshield.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceCheckTest {
    @Test
    fun hindiLocaleSupportStatesStayDistinctInBothLanguages() {
        assertEquals("Supported", DeviceCheckCopy.localeStatus(DeviceCheckLocale.SUPPORTED, english = true))
        assertEquals("Not supported", DeviceCheckCopy.localeStatus(DeviceCheckLocale.UNSUPPORTED, english = true))
        assertEquals(
            "Not checked on this Android version",
            DeviceCheckCopy.localeStatus(DeviceCheckLocale.NOT_CHECKED, english = true),
        )
        assertEquals("Support mila", DeviceCheckCopy.localeStatus(DeviceCheckLocale.SUPPORTED, english = false))
        assertEquals("Support nahi mila", DeviceCheckCopy.localeStatus(DeviceCheckLocale.UNSUPPORTED, english = false))
    }

    @Test
    fun recognizerAndPermissionStatusDoNotConflateUnavailableWithDenied() {
        assertEquals("Unavailable", DeviceCheckCopy.availability(false, english = true))
        assertEquals("Not granted", DeviceCheckCopy.permission(false, english = true))
        assertEquals("Mila", DeviceCheckCopy.availability(true, english = false))
        assertEquals("Mili", DeviceCheckCopy.permission(true, english = false))
    }
}
