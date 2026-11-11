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

    val state: StateFlow<BudgetUiState> = combine(finance.periodSettings, offset) { s, o -> s to o }
        .flatMapLatest { (settings, o) ->
            val current = BudgetPeriods.containing(LocalDate.now(), settings)
            val period = BudgetPeriods.shift(current, o, settings)
            val (from, to) = BudgetPeriods.queryRange(period, settings)
            val zone = ZoneId.systemDefault()
            combine(
                finance.categories,
                finance.transactionsBetween(from, to),
                finance.accounts,
                finance.accountBalances,
                finance.reserveFloorMinor,
            ) { categories, loaded, accounts, balances, floor ->
                val incomeIds = categories.filter { it.kind == FlowKind.INCOME }.map { it.id }.toSet()
                // Salary paid a few days early belongs to the period it is meant for.
                val txs = loaded.filter { t ->
                    val date = Instant.ofEpochMilli(t.timestampMillis).atZone(zone).toLocalDate()
                    val isIncome = t.amountCzkMinor > 0 && t.categoryId in incomeIds
                    BudgetPeriods.belongsTo(date, isIncome, period, settings)
                }
                val summary = BudgetEngine.summarize(categories, txs)
                val isCurrent = o == 0
                val reserve = if (accounts.isEmpty() || !isCurrent) {
                    null
                } else {
                    ReserveCalculator.status(
                        liquidMinor = ReserveCalculator.liquidTotal(accounts, balances),
                        floorMinor = floor,
                        remainingPlannedOutflowMinor = summary.remainingPlannedOutflowMinor,
                    )
                }
                BudgetUiState(period, isCurrent, true, summary, reserve, txs, categories, accounts)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetUiState())

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
