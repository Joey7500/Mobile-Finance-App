package io.github.joey7500.vaultflow.ui.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.joey7500.vaultflow.data.repository.FinanceRepository
import io.github.joey7500.vaultflow.domain.budget.BudgetEngine
import io.github.joey7500.vaultflow.domain.budget.MonthSummary
import io.github.joey7500.vaultflow.domain.budget.ReserveCalculator
import io.github.joey7500.vaultflow.domain.budget.ReserveStatus
import io.github.joey7500.vaultflow.domain.model.Account
import io.github.joey7500.vaultflow.domain.model.Category
import io.github.joey7500.vaultflow.domain.model.Transaction
import io.github.joey7500.vaultflow.domain.model.TransactionSource
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BudgetUiState(
    val month: YearMonth = YearMonth.now(),
    val loaded: Boolean = false,
    val summary: MonthSummary? = null,
    val reserve: ReserveStatus? = null,
    val transactions: List<Transaction> = emptyList(),
    val categories: List<Category> = emptyList(),
    val accounts: List<Account> = emptyList(),
) {
    val isCurrentMonth: Boolean get() = month == YearMonth.now()
    val uncertain: List<Transaction>
        get() {
            val uncertainId = categories.firstOrNull { it.isUncertain }?.id
            val known = categories.map { it.id }.toSet()
            return transactions.filter { it.categoryId == null || it.categoryId == uncertainId || it.categoryId !in known }
        }

    fun categoryName(id: Long?): String = categories.firstOrNull { it.id == id }?.name ?: "Uncertain"
}

@OptIn(ExperimentalCoroutinesApi::class)
class BudgetViewModel(private val finance: FinanceRepository) : ViewModel() {

    private val month = MutableStateFlow(YearMonth.now())
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    val state: StateFlow<BudgetUiState> = month.flatMapLatest { m ->
        combine(
            finance.categories,
            finance.transactionsIn(m),
            finance.accounts,
            finance.accountBalances,
            finance.reserveFloorMinor,
        ) { categories, txs, accounts, balances, floor ->
            val summary = BudgetEngine.summarize(categories, txs)
            val isCurrent = m == YearMonth.now()
            val reserve = if (accounts.isEmpty() || !isCurrent) {
                null
            } else {
                ReserveCalculator.status(
                    liquidMinor = ReserveCalculator.liquidTotal(accounts, balances),
                    floorMinor = floor,
                    remainingPlannedOutflowMinor = summary.remainingPlannedOutflowMinor,
                )
            }
            BudgetUiState(m, true, summary, reserve, txs, categories, accounts)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetUiState())

    fun previousMonth() {
        month.value = month.value.minusMonths(1)
    }

    fun nextMonth() {
        month.value = month.value.plusMonths(1)
    }

    fun assign(tx: Transaction, categoryId: Long, learn: Boolean) = viewModelScope.launch {
        val pattern = finance.assignCategory(tx.id, categoryId, learn)
        if (pattern != null) _messages.tryEmit("Learned: \"$pattern\" will be classified automatically")
    }

    fun setBudget(categoryId: Long, minor: Long) = viewModelScope.launch { finance.setBudget(categoryId, minor) }

    fun delete(tx: Transaction) = viewModelScope.launch {
        finance.deleteTransaction(tx.id)
        _messages.tryEmit("Transaction deleted")
    }

    fun addManual(amountMinor: Long, isIncome: Boolean, merchant: String, categoryId: Long?, accountId: Long?, dayMillis: Long?) =
        viewModelScope.launch {
            val signed = if (isIncome) kotlin.math.abs(amountMinor) else -kotlin.math.abs(amountMinor)
            finance.addTransaction(
                Transaction(
                    timestampMillis = dayMillis ?: System.currentTimeMillis(),
                    amountMinor = signed,
                    amountCzkMinor = signed,
                    merchant = merchant.trim().ifBlank { if (isIncome) "Income" else "Expense" },
                    accountId = accountId,
                    categoryId = categoryId,
                    source = TransactionSource.MANUAL,
                    note = "added manually",
                ),
            )
            _messages.tryEmit("Transaction added")
        }

    /** Millis for "today at now" if the shown month is current, else the 15th of the shown month. */
    fun defaultDateFor(month: YearMonth): Long? =
        if (month == YearMonth.now()) null else month.atDay(15).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
