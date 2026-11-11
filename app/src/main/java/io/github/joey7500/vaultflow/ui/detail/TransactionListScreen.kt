package io.github.joey7500.vaultflow.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.joey7500.vaultflow.data.repository.FinanceRepository
import io.github.joey7500.vaultflow.domain.budget.BudgetPeriod
import io.github.joey7500.vaultflow.domain.model.Account
import io.github.joey7500.vaultflow.domain.model.Category
import io.github.joey7500.vaultflow.domain.model.FlowKind
import io.github.joey7500.vaultflow.domain.model.Transaction
import io.github.joey7500.vaultflow.ui.Detail
import io.github.joey7500.vaultflow.ui.KindFilter
import io.github.joey7500.vaultflow.ui.LocalNavigator
import io.github.joey7500.vaultflow.ui.LocalSnackbar
import io.github.joey7500.vaultflow.ui.TxFilter
import io.github.joey7500.vaultflow.ui.appContainer
import io.github.joey7500.vaultflow.ui.common.TransactionDialog
import io.github.joey7500.vaultflow.ui.common.TransactionRow
import io.github.joey7500.vaultflow.ui.common.categoryName
import io.github.joey7500.vaultflow.ui.components.BudgetBar
import io.github.joey7500.vaultflow.ui.components.GlassCard
import io.github.joey7500.vaultflow.ui.components.MoneyField
import io.github.joey7500.vaultflow.ui.components.SectionLabel
import io.github.joey7500.vaultflow.ui.components.minorToInput
import io.github.joey7500.vaultflow.ui.components.money
import io.github.joey7500.vaultflow.ui.components.parseMoneyInput
import io.github.joey7500.vaultflow.ui.theme.VaultColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ListState(
    val period: BudgetPeriod? = null,
    val transactions: List<Transaction> = emptyList(),
    val categories: List<Category> = emptyList(),
    val accounts: List<Account> = emptyList(),
    /** Positive number shown as the total (spent, received or invested). */
    val totalMinor: Long = 0,
    val category: Category? = null,
    /** For income/spent/invested: (category, total, count) breakdown. */
    val breakdown: List<Triple<Category?, Long, Int>> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionListViewModel(
    private val finance: FinanceRepository,
    private val filter: TxFilter,
    initialOffset: Int,
) : ViewModel() {
    val offset = MutableStateFlow(initialOffset)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    val state: StateFlow<ListState> = offset.flatMapLatest { o ->
        combine(finance.periodView(o), finance.accounts) { view, accounts ->
            val byId = view.categories.associateBy { it.id }
            fun cat(t: Transaction) = t.categoryId?.let(byId::get)
            val txs = view.transactions.filter { t -> matches(cat(t), t) }
            val incomeLike = when (filter) {
                is TxFilter.Kind -> filter.kind == KindFilter.INCOME
                is TxFilter.Category -> byId[filter.categoryId]?.kind == FlowKind.INCOME
            }
            val total = txs.sumOf { it.amountCzkMinor }.let { if (incomeLike) it else -it }
            val breakdown = if (filter is TxFilter.Kind) {
                txs.groupBy { cat(it) }.map { (c, list) ->
                    Triple(c, list.sumOf { it.amountCzkMinor }.let { if (incomeLike) it else -it }, list.size)
                }.sortedByDescending { it.second }
            } else {
                emptyList()
            }
            ListState(view.period, txs, view.categories, accounts, total, (filter as? TxFilter.Category)?.let { byId[it.categoryId] }, breakdown)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListState())

    private fun matches(c: Category?, t: Transaction): Boolean = when (filter) {
        is TxFilter.Category -> if (filter.isUncertain) c == null || c.isUncertain else t.categoryId == filter.categoryId
        is TxFilter.Kind -> when (filter.kind) {
            KindFilter.INCOME -> c?.kind == FlowKind.INCOME
            KindFilter.SPENT -> c == null || c.kind == FlowKind.EXPENSE
            KindFilter.INVESTED -> c?.kind == FlowKind.INVESTMENT
        }
    }

    fun assign(tx: Transaction, categoryId: Long, learn: Boolean) = viewModelScope.launch {
        val pattern = finance.assignCategory(tx.id, categoryId, learn)
        if (pattern != null) messages.tryEmit("Learned: \"$pattern\" will be classified automatically")
    }

    fun delete(tx: Transaction) = viewModelScope.launch {
        finance.deleteTransaction(tx.id)
        messages.tryEmit("Deleted ${tx.merchant}")
    }

    fun setBudget(categoryId: Long, minor: Long) = viewModelScope.launch { finance.setBudget(categoryId, minor) }
}

private val dayHeader = DateTimeFormatter.ofPattern("EEEE d MMMM")

@Composable
fun TransactionListScreen(detail: Detail.Transactions, onBack: () -> Unit) {
    val container = appContainer()
    val vm: TransactionListViewModel = viewModel(
        key = "txlist-${detail.filter}-${detail.periodOffset}",
        factory = viewModelFactory { initializer { TransactionListViewModel(container.finance, detail.filter, detail.periodOffset) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val offset by vm.offset.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val navigator = LocalNavigator.current
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    var editTx by remember { mutableStateOf<Transaction?>(null) }
    var editBudget by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text(detail.filter.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = { vm.offset.value = offset - 1 }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous period") }
                Text(state.period?.label().orEmpty(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.offset.value = offset + 1 }, enabled = offset < 0) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next period") }
            }
        }

        item {
            GlassCard {
                Text("TOTAL · ${state.transactions.size} PAYMENTS", style = MaterialTheme.typography.labelSmall, color = VaultColors.Muted)
                Text(money(state.totalMinor, decimals = true), style = MaterialTheme.typography.displaySmall)
                state.category?.let { c ->
                    if (c.kind != FlowKind.TRANSFER && !c.isUncertain) {
                        Spacer(Modifier.height(8.dp))
                        val budget = c.monthlyBudgetMinor
                        if (budget > 0) {
                            BudgetBar(state.totalMinor.toFloat() / budget, c.kind != FlowKind.INCOME && state.totalMinor > budget)
                            Spacer(Modifier.height(4.dp))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (budget > 0) "Budget ${money(budget)} · ${if (state.totalMinor <= budget) "left " + money(budget - state.totalMinor) else "over by " + money(state.totalMinor - budget)}" else "No budget set",
                                style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted, modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { editBudget = true }) { Text("Edit budget") }
                        }
                    }
                }
            }
        }

        if (state.breakdown.isNotEmpty()) {
            item {
                GlassCard {
                    Text("By category", style = MaterialTheme.typography.titleMedium)
                    state.breakdown.forEach { (c, sum, n) ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                navigator.open(
                                    Detail.Transactions(
                                        TxFilter.Category(c?.id ?: -1, c?.name ?: "Uncertain", isUncertain = c == null || c.isUncertain),
                                        offset,
                                    ),
                                )
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${c?.name ?: "Uncertain"} ($n)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(money(sum), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        if (state.transactions.isEmpty()) {
            item { Text("No payments in this period.", style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted, modifier = Modifier.padding(top = 12.dp)) }
        }
        val byDay = state.transactions.groupBy { Instant.ofEpochMilli(it.timestampMillis).atZone(zone).toLocalDate() }
        byDay.forEach { (day, list) ->
            item(key = "day-$day") {
                SectionLabel(day.format(dayHeader) + " · " + money(-list.sumOf { it.amountCzkMinor }))
            }
            items(list, key = { it.id }) { tx ->
                TransactionRow(tx, categoryName(state.categories, tx.categoryId) + (state.accounts.firstOrNull { it.id == tx.accountId }?.let { " · ${it.name}" } ?: "")) { editTx = tx }
            }
        }
        item { Spacer(Modifier.height(48.dp)) }
    }

    editTx?.let { tx ->
        TransactionDialog(
            tx = tx, categories = state.categories, accounts = state.accounts,
            onAssign = { id, learn -> vm.assign(tx, id, learn); editTx = null },
            onDelete = { vm.delete(tx); editTx = null },
            onDismiss = { editTx = null },
        )
    }
    if (editBudget) {
        state.category?.let { c ->
            var text by remember { mutableStateOf(minorToInput(c.monthlyBudgetMinor)) }
            val parsed = parseMoneyInput(text)
            AlertDialog(
                onDismissRequest = { editBudget = false },
                title = { Text(c.name) },
                text = { Column { MoneyField(text, { text = it }, if (c.kind == FlowKind.INCOME) "Expected per period" else "Budget per period") } },
                confirmButton = { TextButton(enabled = parsed != null, onClick = { parsed?.let { vm.setBudget(c.id, it) }; editBudget = false }) { Text("Save") } },
                dismissButton = { TextButton(onClick = { editBudget = false }) { Text("Cancel") } },
            )
        }
    }
}
