package io.github.joey7500.vaultflow.core.security

import android.content.Context

/** Non-sensitive flags that must be readable before the encrypted DB is opened. */
class AppPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    var demoMode: Boolean
        get() = prefs.getBoolean(KEY_DEMO, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DEMO, value).commit()
        }

    /** Off by default: FLAG_SECURE hides balances from screenshots and the Recents preview. */
    var allowScreenshots: Boolean
        get() = prefs.getBoolean(KEY_SCREENSHOTS, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SCREENSHOTS, value).apply()
        }

    var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ONBOARDING, value).apply()
        }

    private companion object {
        const val KEY_DEMO = "demo_mode"
        const val KEY_ONBOARDING = "onboarding_done"
        const val KEY_SCREENSHOTS = "allow_screenshots"
    }
}
