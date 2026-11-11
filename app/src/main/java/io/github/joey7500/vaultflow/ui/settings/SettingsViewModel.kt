package io.github.joey7500.vaultflow.ui.settings

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.joey7500.vaultflow.core.security.SecretVault
import io.github.joey7500.vaultflow.data.local.entity.AccountEntity
import io.github.joey7500.vaultflow.data.local.entity.CaptureLogEntity
import io.github.joey7500.vaultflow.data.local.entity.SenderEntity
import io.github.joey7500.vaultflow.data.repository.SettingsStore
import io.github.joey7500.vaultflow.di.AppContainer
import io.github.joey7500.vaultflow.domain.model.Account
import io.github.joey7500.vaultflow.domain.model.AccountType
import io.github.joey7500.vaultflow.domain.model.Category
import io.github.joey7500.vaultflow.domain.model.ClassificationRule
import io.github.joey7500.vaultflow.domain.model.FlowKind
import io.github.joey7500.vaultflow.email.EmailSyncWorker
import io.github.joey7500.vaultflow.email.ImapMailFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val accounts: List<Account> = emptyList(),
    val balances: Map<Long, Long> = emptyMap(),
    val categories: List<Category> = emptyList(),
    val rules: List<ClassificationRule> = emptyList(),
    val senders: List<SenderEntity> = emptyList(),
    val log: List<CaptureLogEntity> = emptyList(),
    val floorMinor: Long = 0,
    val captureLogEnabled: Boolean = true,
)

class SettingsViewModel(private val c: AppContainer) : ViewModel() {

    val isDemo = c.isDemo
    val hasT212 = MutableStateFlow(c.vault.has(SecretVault.T212_API_KEY))
    val busy = MutableStateFlow(false)
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    private val lists = combine(c.finance.accounts, c.finance.accountBalances, c.finance.categories, c.finance.rules) { a, b, cat, r ->
        SettingsUiState(accounts = a, balances = b, categories = cat, rules = r)
    }

    val state: StateFlow<SettingsUiState> = combine(
        lists,
        c.finance.senders,
        c.finance.captureLog,
        c.finance.reserveFloorMinor,
        c.settings.all.map { it[SettingsStore.CAPTURE_LOG_ENABLED] != "false" },
    ) { base, senders, log, floor, logOn ->
        base.copy(senders = senders, log = log, floorMinor = floor, captureLogEnabled = logOn)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    private fun say(msg: String) {
        _messages.tryEmit(msg)
    }

    // ── Vault ──
    fun saveT212(key: String, secret: String) {
        c.vault.put(SecretVault.T212_API_KEY, key)
        c.vault.put(SecretVault.T212_API_SECRET, secret)
        hasT212.value = c.vault.has(SecretVault.T212_API_KEY)
        say("Saved to the encrypted vault")
    }

    fun clearT212() {
        c.vault.put(SecretVault.T212_API_KEY, null)
        c.vault.put(SecretVault.T212_API_SECRET, null)
        hasT212.value = false
        say("Trading 212 credentials removed")
    }

    fun testT212(key: String, secret: String) = viewModelScope.launch {
        val k = key.ifBlank { c.vault.get(SecretVault.T212_API_KEY).orEmpty() }
        val s = secret.ifBlank { c.vault.get(SecretVault.T212_API_SECRET).orEmpty() }
        if (k.isBlank()) {
            say("Enter an API key first")
            return@launch
        }
        busy.value = true
        val result = runCatching { c.wealth.testTrading212(k, s) }
        busy.value = false
        say(result.getOrElse { it.message ?: "Connection failed" })
    }

    // ── Gmail (bank e-mails) ──
    val hasGmail = MutableStateFlow(c.emailSync.isConfigured)
    val gmailAddress = MutableStateFlow(c.vault.get(SecretVault.GMAIL_ADDRESS).orEmpty())
    val emailStatus: StateFlow<String> = c.settings.all
        .map { s ->
            val at = s[SettingsStore.EMAIL_LAST_SYNC_AT]?.toLongOrNull()
            val res = s[SettingsStore.EMAIL_LAST_RESULT].orEmpty()
            if (at == null) res.ifBlank { "Never synced" } else "Last check ${io.github.joey7500.vaultflow.ui.components.formatDateTime(at)} · $res"
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    fun saveGmail(context: android.content.Context, address: String, appPassword: String) {
        c.vault.put(SecretVault.GMAIL_ADDRESS, address.trim().lowercase())
        if (appPassword.isNotBlank()) c.vault.put(SecretVault.GMAIL_APP_PASSWORD, appPassword.replace(" ", ""))
        hasGmail.value = c.emailSync.isConfigured
        gmailAddress.value = address.trim().lowercase()
        if (hasGmail.value) EmailSyncWorker.schedulePeriodic(context)
        say("Gmail saved to the encrypted vault")
    }

    fun clearGmail(context: android.content.Context) {
        c.vault.put(SecretVault.GMAIL_ADDRESS, null)
        c.vault.put(SecretVault.GMAIL_APP_PASSWORD, null)
        hasGmail.value = false
        gmailAddress.value = ""
        EmailSyncWorker.cancelAll(context)
        say("Gmail access removed")
    }

    fun testGmail(address: String, appPassword: String) = viewModelScope.launch {
        val a = address.ifBlank { c.vault.get(SecretVault.GMAIL_ADDRESS).orEmpty() }
        val p = appPassword.ifBlank { c.vault.get(SecretVault.GMAIL_APP_PASSWORD).orEmpty() }
        if (a.isBlank() || p.isBlank()) {
            say("Enter the Gmail address and App Password first")
            return@launch
        }
        busy.value = true
        val r = runCatching { withContext(Dispatchers.IO) { ImapMailFetcher.test(a, p) } }
        busy.value = false
        say(r.fold({ "Connected ✓ · $it e-mails in the last 3 days" }, { it.message ?: "Connection failed" }))
    }

    fun syncEmailNow() = viewModelScope.launch {
        busy.value = true
        val r = c.emailSync.run()
        busy.value = false
        say(r.error?.let { "E-mail check failed: $it" } ?: "Checked ${r.checked} e-mails · ${r.processed} new bank messages")
    }

    // ── Reserve & accounts ──
    fun setFloor(minor: Long) = viewModelScope.launch {
        c.finance.setReserveFloor(minor)
        say("Reserve floor updated")
    }

    fun saveAccount(existing: Account?, name: String, type: AccountType, balanceMinor: Long, liquid: Boolean, ownIds: String, cardIds: String, aliases: String, overdraftMinor: Long) =
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val prev = existing?.let { e -> c.finance.accountEntities().firstOrNull { it.id == e.id } }
            val balanceChanged = prev == null || state.value.balances[prev.id] != balanceMinor
            c.finance.saveAccount(
                AccountEntity(
                    id = prev?.id ?: 0,
                    name = name.trim().ifBlank { "Account" },
                    type = type.name,
                    anchorBalanceMinor = if (balanceChanged) balanceMinor else prev!!.anchorBalanceMinor,
                    anchorDateMillis = if (balanceChanged) now else prev!!.anchorDateMillis,
                    isLiquid = liquid,
                    ownIdentifiers = ownIds.trim(),
                    cardIdentifiers = cardIds.lines().map { l -> l.filter(Char::isDigit) }.filter { it.length >= 4 }.joinToString("\n"),
                    transferAliases = aliases.lines().map { it.trim() }.filter { it.length >= 3 }.joinToString("\n"),
                    overdraftMinor = overdraftMinor,
                    lastCheckAtMillis = prev?.lastCheckAtMillis,
                    lastCheckDiffMinor = prev?.lastCheckDiffMinor,
                    lastReportedMinor = prev?.lastReportedMinor,
                    sortOrder = prev?.sortOrder ?: state.value.accounts.size,
                ),
            )
            say("Account saved")
        }

    fun deleteAccount(account: Account) = viewModelScope.launch { c.finance.deleteAccount(account.id) }

    // ── Capture ──
    fun updateSender(pkg: String, enabled: Boolean, accountId: Long?) = viewModelScope.launch { c.finance.updateSender(pkg, enabled, accountId) }
    fun forgetSender(pkg: String) = viewModelScope.launch { c.finance.deleteSender(pkg) }
    fun setCaptureLog(enabled: Boolean) = viewModelScope.launch {
        c.settings.put(SettingsStore.CAPTURE_LOG_ENABLED, enabled.toString())
        if (!enabled) c.finance.clearCaptureLog()
    }
    fun clearLog() = viewModelScope.launch { c.finance.clearCaptureLog() }

    fun mergeDuplicates() = viewModelScope.launch {
        val n = c.finance.repairDuplicates()
        say(if (n == 0) "No duplicates found" else "Merged $n duplicate Wallet report(s)")
    }

    // ── Rules & categories ──
    fun deleteRule(rule: ClassificationRule) = viewModelScope.launch { c.finance.deleteRule(rule.id) }
    fun addCategory(name: String, group: String, kind: FlowKind) = viewModelScope.launch {
        c.finance.addCategory(name, group, kind, 0)
        say("Category added. Set its budget on the Budget tab.")
    }
    fun archiveCategory(category: Category) = viewModelScope.launch { c.finance.archiveCategory(category) }

    // ── Backup ──
    fun export(resolver: ContentResolver, uri: Uri, password: CharArray) = viewModelScope.launch {
        busy.value = true
        val result = runCatching {
            val blob = c.backup.export(password)
            withContext(Dispatchers.IO) {
                resolver.openOutputStream(uri, "wt")?.use { it.write(blob.toByteArray(Charsets.UTF_8)) }
                    ?: error("Could not open the file")
            }
        }
        password.fill(' ')
        busy.value = false
        say(result.fold({ "Encrypted backup saved" }, { it.message ?: "Export failed" }))
    }

    fun import(resolver: ContentResolver, uri: Uri, password: CharArray, onDone: () -> Unit) = viewModelScope.launch {
        busy.value = true
        val result = runCatching {
            val blob = withContext(Dispatchers.IO) {
                resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("Could not open the file")
            }
            c.backup.import(blob, password)
        }
        password.fill(' ')
        busy.value = false
        result.fold({ say("Restored $it transactions"); onDone() }, { say(it.message ?: "Import failed") })
    }

    // ── Budget period ──
    val periodSettings: StateFlow<io.github.joey7500.vaultflow.domain.budget.PeriodSettings> = c.finance.periodSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), io.github.joey7500.vaultflow.domain.budget.PeriodSettings())

    fun setPeriod(startDay: Int, graceDays: Int) = viewModelScope.launch {
        c.finance.setPeriodSettings(startDay, graceDays)
        say(if (startDay == 1) "Budget follows calendar months" else "Budget periods now start on day $startDay")
    }

    // ── Privacy ──
    val allowScreenshots = MutableStateFlow(c.prefs.allowScreenshots)

    fun setAllowScreenshots(activity: android.app.Activity?, allow: Boolean) {
        c.prefs.allowScreenshots = allow
        allowScreenshots.value = allow
        val flag = android.view.WindowManager.LayoutParams.FLAG_SECURE
        if (allow) activity?.window?.clearFlags(flag) else activity?.window?.addFlags(flag)
    }

    // ── Google Drive backup ──
    data class DriveStatus(val connected: Boolean = false, val hasPassword: Boolean = false, val lastResult: String = "")

    private val driveRefresh = MutableStateFlow(0)
    val driveStatus: StateFlow<DriveStatus> = combine(c.settings.all, driveRefresh) { s, _ ->
        val at = s[SettingsStore.DRIVE_LAST_BACKUP_AT]?.toLongOrNull()
        DriveStatus(
            connected = s[SettingsStore.DRIVE_CONNECTED] == "true",
            hasPassword = c.driveBackup.hasPassword,
            lastResult = listOfNotNull(
                at?.let { "Last backup " + io.github.joey7500.vaultflow.ui.components.formatDateTime(it) },
                s[SettingsStore.DRIVE_LAST_RESULT],
            ).joinToString(" · "),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DriveStatus())

    val driveBackups = MutableStateFlow<List<io.github.joey7500.vaultflow.backup.DriveFile>>(emptyList())

    fun notify(message: String) = say(message)

    fun setBackupPassword(context: android.content.Context, password: CharArray) {
        c.vault.put(SecretVault.BACKUP_PASSWORD, String(password))
        password.fill(' ')
        driveRefresh.value++
        io.github.joey7500.vaultflow.backup.DriveBackupWorker.schedule(context)
        say("Backup password saved in the encrypted vault")
    }

    fun onDriveConnected(context: android.content.Context) = viewModelScope.launch {
        c.driveBackup.markConnected(true)
        io.github.joey7500.vaultflow.backup.DriveBackupWorker.schedule(context)
        say(if (c.driveBackup.hasPassword) "Google Drive connected · first backup running" else "Google Drive connected · now set a backup password")
        if (c.driveBackup.hasPassword) driveBackupNow()
    }

    fun disconnectDrive(context: android.content.Context) = viewModelScope.launch {
        c.driveBackup.markConnected(false)
        io.github.joey7500.vaultflow.backup.DriveBackupWorker.cancel(context)
        say("Google Drive backups stopped (existing backups stay in your Drive)")
    }

    fun driveBackupNow() {
        viewModelScope.launch {
            busy.value = true
            val r = runCatching { c.driveBackup.backupNow() }
            busy.value = false
            say(r.fold({ "Backed up to Google Drive: $it" }, { "Backup failed: ${it.message}" }))
        }
    }

    fun loadDriveBackups() = viewModelScope.launch {
        driveBackups.value = emptyList()
        runCatching { c.driveBackup.listBackups() }
            .onSuccess { driveBackups.value = it }
            .onFailure { say("Could not list backups: ${it.message}") }
    }

    fun restoreFromDrive(file: io.github.joey7500.vaultflow.backup.DriveFile, password: CharArray, onDone: () -> Unit) = viewModelScope.launch {
        busy.value = true
        val r = runCatching { c.driveBackup.restore(file, password) }
        password.fill(' ')
        busy.value = false
        r.fold({ say("Restored $it transactions from Google Drive"); onDone() }, { say(it.message ?: "Restore failed") })
    }

    // ── Widget ──
    val widgetShowAmount = MutableStateFlow(c.prefs.widgetShowAmount)

    fun setWidgetShowAmount(context: android.content.Context, show: Boolean) {
        c.prefs.widgetShowAmount = show
        widgetShowAmount.value = show
        io.github.joey7500.vaultflow.widget.SafeToSpendWidget.refresh(context)
    }

    // ── Demo ──
    fun setDemoMode(enabled: Boolean) {
        c.prefs.demoMode = enabled
    }
}
