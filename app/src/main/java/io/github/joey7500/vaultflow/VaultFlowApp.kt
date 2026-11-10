package io.github.joey7500.vaultflow

import android.app.Application
import io.github.joey7500.vaultflow.core.security.SecretVault
import io.github.joey7500.vaultflow.di.AppContainer
import io.github.joey7500.vaultflow.email.EmailSyncWorker
import io.github.joey7500.vaultflow.ui.lock.LockState

class VaultFlowApp : Application() {

    val lock = LockState()

    /** Created in onCreate(): the Context is not usable during field initialisation. */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        if (!container.isDemo && container.vault.has(SecretVault.GMAIL_APP_PASSWORD)) {
            EmailSyncWorker.schedulePeriodic(this)
        }
    }

    /** Switching Demo Mode rebuilds the object graph against the other database. */
    @Synchronized
    fun rebuildContainer(): AppContainer {
        container.close()
        container = AppContainer(this)
        return container
    }
}
