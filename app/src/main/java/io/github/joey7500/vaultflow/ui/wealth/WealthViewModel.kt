package io.github.joey7500.vaultflow.ui.wealth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.joey7500.vaultflow.data.local.entity.AccountEntity
import io.github.joey7500.vaultflow.data.local.entity.ManualAssetEntity
import io.github.joey7500.vaultflow.data.repository.FinanceRepository
import io.github.joey7500.vaultflow.data.repository.WealthRepository
import io.github.joey7500.vaultflow.data.repository.WealthState
import io.github.joey7500.vaultflow.domain.model.Account
import io.github.joey7500.vaultflow.domain.model.ManualAsset
import io.github.joey7500.vaultflow.domain.model.ManualAssetKind
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class WealthViewModel(private val wealth: WealthRepository, private val finance: FinanceRepository) : ViewModel() {

    val state: StateFlow<WealthState?> = wealth.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val refreshing = MutableStateFlow(false)
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    init {
        // Auto-refresh when data is older than 6 hours.
        viewModelScope.launch {
            val s = wealth.state.first()
            val last = s.lastRefreshAt ?: 0L
            if (System.currentTimeMillis() - last > 6 * 3600 * 1000L) refresh(quiet = true)
        }
    }

    fun refresh(quiet: Boolean = false) {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            val problems = runCatching { wealth.refresh() }.getOrElse { listOf(it.message ?: "Refresh failed") }
            refreshing.value = false
            when {
                problems.isNotEmpty() -> _messages.tryEmit(problems.joinToString("\n"))
                !quiet -> _messages.tryEmit("Updated")
            }
        }
    }

    fun setGoldGrams(grams: Double) = viewModelScope.launch {
        wealth.setGoldGrams(grams)
        refresh(quiet = true)
    }

    fun setManualGoldPrice(czkPerGram: Double?) = viewModelScope.launch { wealth.setManualGoldPrice(czkPerGram) }

    fun setManualTrading212(minor: Long?) = viewModelScope.launch { wealth.setManualTrading212(minor) }

    /** Re-anchors the account: "this is my balance right now". */
    fun setAccountBalance(account: Account, balanceMinor: Long) = viewModelScope.launch {
        val entity = finance.accountEntities().firstOrNull { it.id == account.id } ?: return@launch
        finance.saveAccount(entity.copy(anchorBalanceMinor = balanceMinor, anchorDateMillis = System.currentTimeMillis()))
        _messages.tryEmit("${account.name} balance set")
    }

    fun saveAsset(existing: ManualAsset?, name: String, kind: ManualAssetKind, valueMinor: Long) = viewModelScope.launch {
        finance.saveManualAsset(
            ManualAssetEntity(
                id = existing?.id ?: 0, name = name.trim().ifBlank { "Asset" }, kind = kind.name,
                valueMinor = valueMinor, updatedAtMillis = System.currentTimeMillis(), note = existing?.note ?: "",
            ),
        )
    }

    fun deleteAsset(asset: ManualAsset) = viewModelScope.launch { finance.deleteManualAsset(asset.id) }

    @Suppress("unused")
    private fun AccountEntity.touch() = copy(anchorDateMillis = System.currentTimeMillis())
}
