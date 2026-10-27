package ai.trustshield.android.ui.settings

import ai.trustshield.android.data.preferences.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceCheckTest {
    @Test
    fun deviceCheckCopyUsesHindiWhenHindiIsSelected() {
        assertEquals("\u0938\u092e\u0930\u094d\u0925\u093f\u0924", DeviceCheckCopy.localeStatus(DeviceCheckLocale.SUPPORTED, AppLanguage.HINDI))
        assertEquals("\u0909\u092a\u0932\u092c\u094d\u0927 \u0928\u0939\u0940\u0902", DeviceCheckCopy.availability(false, AppLanguage.HINDI))
        assertEquals("\u0905\u0928\u0941\u092e\u0924\u093f \u0928\u0939\u0940\u0902 \u092e\u093f\u0932\u0940", DeviceCheckCopy.permission(false, AppLanguage.HINDI))
    }

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
