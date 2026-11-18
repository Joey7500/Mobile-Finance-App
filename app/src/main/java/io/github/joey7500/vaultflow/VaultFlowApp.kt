package io.github.joey7500.vaultflow

import android.app.Application
import io.github.joey7500.vaultflow.core.security.SecretVault
import io.github.joey7500.vaultflow.di.AppContainer
import io.github.joey7500.vaultflow.backup.DriveBackupWorker
import io.github.joey7500.vaultflow.email.EmailSyncWorker
import io.github.joey7500.vaultflow.ui.lock.LockState
import io.github.joey7500.vaultflow.wealth.WealthRefreshWorker

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
        if (!container.isDemo && container.vault.has(SecretVault.BACKUP_PASSWORD)) {
            DriveBackupWorker.schedule(this) // no-op until Google Drive is connected
        }
        if (!container.isDemo && container.vault.has(SecretVault.T212_API_KEY)) {
            WealthRefreshWorker.schedule(this)
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
