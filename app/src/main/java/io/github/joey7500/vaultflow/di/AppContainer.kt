package io.github.joey7500.vaultflow.di

import android.content.Context
import io.github.joey7500.vaultflow.core.security.AppPreferences
import io.github.joey7500.vaultflow.core.security.SecretVault
import io.github.joey7500.vaultflow.data.demo.DemoSeeder
import io.github.joey7500.vaultflow.data.local.VaultDatabase
import io.github.joey7500.vaultflow.data.repository.BackupManager
import io.github.joey7500.vaultflow.data.repository.FinanceRepository
import io.github.joey7500.vaultflow.data.repository.SettingsStore
import io.github.joey7500.vaultflow.data.repository.WealthRepository
import io.github.joey7500.vaultflow.email.EmailSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manual dependency injection: one place that wires the app together.
 * (Kept framework-free on purpose; easy to read and to swap for Hilt later.)
 *
 * Demo Mode swaps the encrypted on-disk vault for an in-memory database filled
 * with synthetic data, so the real vault is never touched while demoing.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val prefs = AppPreferences(appContext)
    val vault = SecretVault(appContext)
    val isDemo: Boolean = prefs.demoMode

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: VaultDatabase by lazy {
        if (isDemo) VaultDatabase.openInMemoryDemo(appContext) else VaultDatabase.openEncrypted(appContext)
    }
    val settings by lazy { SettingsStore(db.settings()) }
    val finance by lazy { FinanceRepository(db, settings) }
    val wealth by lazy { WealthRepository(db, settings, finance, vault, isDemo) }
    val backup by lazy { BackupManager(db) }
    val emailSync by lazy { EmailSync(vault, finance, settings) }

    private val initLock = Mutex()
    private var initialized = false

    /** Safe to call many times; seeds defaults (or demo data) once. */
    suspend fun initialize() = initLock.withLock {
        if (initialized) return@withLock
        if (isDemo) DemoSeeder.seed(db, finance, settings) else finance.ensureSeeded()
        initialized = true
    }

    fun close() {
        runCatching { if (isDemo || initialized) db.close() }
    }
}
