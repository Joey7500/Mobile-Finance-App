package io.github.joey7500.vaultflow

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import io.github.joey7500.vaultflow.ui.AppShell
import io.github.joey7500.vaultflow.ui.lock.LockScreen
import io.github.joey7500.vaultflow.ui.theme.VaultColors
import io.github.joey7500.vaultflow.ui.theme.VaultFlowTheme
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    private val app get() = application as VaultFlowApp
    private var lockMessage by mutableStateOf<String?>(null)
    private var promptShowing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Balances never appear in screenshots, screen recordings or the Recents switcher.
        if (!app.container.prefs.allowScreenshots) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }

        setContent {
            VaultFlowTheme {
                Box(Modifier.fillMaxSize().background(VaultColors.Background)) {
                    if (app.lock.unlocked) {
                        AppShell(onRestartRequested = ::restartApp)
                    } else {
                        LockScreen(lockMessage, onUnlock = ::authenticate)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        app.lock.onForeground()
        if (!app.lock.unlocked) authenticate()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) app.lock.onBackground()
    }

    /** Android 11+: strong biometrics or PIN/pattern. Android 10 cannot combine STRONG with a device credential. */
    private fun authenticators(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    private fun authenticate() {
        if (promptShowing || app.lock.unlocked) return
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguard.isDeviceSecure) {
            // Without any screen lock there is nothing to authenticate against.
            lockMessage = "This phone has no screen lock. Set a PIN or fingerprint in Android settings to protect VaultFlow."
            unlocked()
            return
        }
        val can = BiometricManager.from(this).canAuthenticate(authenticators())
        if (can != BiometricManager.BIOMETRIC_SUCCESS && can != BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
            lockMessage = "Biometric prompt unavailable (code $can). Using device unlock state."
            unlocked()
            return
        }

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    promptShowing = false
                    lockMessage = null
                    unlocked()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    promptShowing = false
                    lockMessage = errString.toString()
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock VaultFlow")
            .setSubtitle("Confirm it's you to see your finances")
            .setAllowedAuthenticators(authenticators())
            .build()
        promptShowing = true
        prompt.authenticate(info)
    }

    private fun unlocked() {
        app.lock.onUnlocked()
        lifecycleScope.launch { runCatching { app.container.initialize() } }
    }

    /** Used after switching Demo Mode or restoring a backup. */
    private fun restartApp() {
        // Drop ViewModels bound to the old database before swapping it out.
        viewModelStore.clear()
        app.rebuildContainer()
        app.lock.onUnlocked()
        lifecycleScope.launch { runCatching { app.container.initialize() } }
        recreate()
    }
}
