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
import io.github.joey7500.vaultflow.domain.budget.BudgetPeriod
import io.github.joey7500.vaultflow.domain.budget.BudgetPeriods
import io.github.joey7500.vaultflow.domain.budget.PeriodSettings
import io.github.joey7500.vaultflow.domain.model.FlowKind
import java.time.Instant
import java.time.LocalDate
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
    val period: BudgetPeriod = BudgetPeriods.containing(LocalDate.now(), PeriodSettings()),
    val isCurrentPeriod: Boolean = true,
    val loaded: Boolean = false,
    val summary: MonthSummary? = null,
    val reserve: ReserveStatus? = null,
    val transactions: List<Transaction> = emptyList(),
    val categories: List<Category> = emptyList(),
    val accounts: List<Account> = emptyList(),
) {
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

    /** 0 = current period, -1 = previous, ... */
    private val offset = MutableStateFlow(0)
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    val state: StateFlow<BudgetUiState> = offset.flatMapLatest { o ->
        combine(
            finance.periodView(o),
            finance.accounts,
            finance.accountBalances,
            finance.reserveFloorMinor,
        ) { view, accounts, balances, floor ->
            val summary = BudgetEngine.summarize(view.categories, view.transactions)
            val reserve = if (accounts.isEmpty() || !view.isCurrent) {
                null
            } else {
                ReserveCalculator.status(
                    liquidMinor = ReserveCalculator.liquidTotal(accounts, balances),
                    floorMinor = floor,
                    remainingPlannedOutflowMinor = summary.remainingPlannedOutflowMinor,
                )
            }
            BudgetUiState(view.period, view.isCurrent, true, summary, reserve, view.transactions, view.categories, accounts)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetUiState())

    /** Shown period relative to the current one (for opening drill-downs on the same period). */
    val periodOffset: Int get() = offset.value

    fun previousMonth() {
        offset.value -= 1
    }

    fun nextMonth() {
        offset.value += 1
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

    /** "Now" in the current period, else the middle of the shown period. */
    fun defaultDateFor(state: BudgetUiState): Long? =
        if (state.isCurrentPeriod) null
        else state.period.start.plusDays(14).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
