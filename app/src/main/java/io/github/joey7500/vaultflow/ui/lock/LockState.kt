package io.github.joey7500.vaultflow.ui.lock

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Process-wide lock state. Lives in the Application object, so it survives
 * screen rotation but NOT process death: a restarted app is always locked.
 */
class LockState(private val relockAfterMs: Long = 30_000L) {
    var unlocked by mutableStateOf(false)
        private set

    private var backgroundedAt = 0L

    fun onUnlocked() {
        unlocked = true
    }

    fun onBackground() {
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    /** Called when the UI comes back; relocks after [relockAfterMs] in the background. */
    fun onForeground() {
        if (unlocked && backgroundedAt > 0 && SystemClock.elapsedRealtime() - backgroundedAt > relockAfterMs) {
            unlocked = false
        }
    }

    fun lockNow() {
        unlocked = false
    }
}
