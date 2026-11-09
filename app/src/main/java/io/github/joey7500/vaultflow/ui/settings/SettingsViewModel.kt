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

    // ── Reserve & accounts ──
    fun setFloor(minor: Long) = viewModelScope.launch {
        c.finance.setReserveFloor(minor)
        say("Reserve floor updated")
    }

    fun saveAccount(existing: Account?, name: String, type: AccountType, balanceMinor: Long, liquid: Boolean, ownIds: String, cardIds: String) =
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

    // ── Demo ──
    fun setDemoMode(enabled: Boolean) {
        c.prefs.demoMode = enabled
    }
}
