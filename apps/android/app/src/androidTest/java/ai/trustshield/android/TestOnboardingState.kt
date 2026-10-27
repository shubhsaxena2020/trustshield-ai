package ai.trustshield.android

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry

internal fun markOnboardingCompleteForTest() {
    InstrumentationRegistry.getInstrumentation().targetContext
        .getSharedPreferences("trustshield_onboarding", Context.MODE_PRIVATE)
        .edit()
        .putBoolean("complete", true)
        .commit()
}
