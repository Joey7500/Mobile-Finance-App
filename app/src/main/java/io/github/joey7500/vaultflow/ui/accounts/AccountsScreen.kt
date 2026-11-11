package io.github.joey7500.vaultflow.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.joey7500.vaultflow.data.repository.FinanceRepository
import io.github.joey7500.vaultflow.domain.model.Account
import io.github.joey7500.vaultflow.domain.model.Category
import io.github.joey7500.vaultflow.domain.model.FlowKind
import io.github.joey7500.vaultflow.domain.model.Transaction
import io.github.joey7500.vaultflow.ui.Detail
import io.github.joey7500.vaultflow.ui.LocalNavigator
import io.github.joey7500.vaultflow.ui.LocalSnackbar
import io.github.joey7500.vaultflow.ui.appContainer
import io.github.joey7500.vaultflow.ui.common.TransactionDialog
import io.github.joey7500.vaultflow.ui.common.TransactionRow
import io.github.joey7500.vaultflow.ui.common.categoryName
import io.github.joey7500.vaultflow.ui.components.BalanceCheckLine
import io.github.joey7500.vaultflow.ui.components.GlassCard
import io.github.joey7500.vaultflow.ui.components.LabeledRow
import io.github.joey7500.vaultflow.ui.components.LineChart
import io.github.joey7500.vaultflow.ui.components.MoneyField
import io.github.joey7500.vaultflow.ui.components.Pill
import io.github.joey7500.vaultflow.ui.components.SectionLabel
import io.github.joey7500.vaultflow.ui.components.minorToInput
import io.github.joey7500.vaultflow.ui.components.money
import io.github.joey7500.vaultflow.ui.components.moneyColor
import io.github.joey7500.vaultflow.ui.components.parseMoneyInput
import io.github.joey7500.vaultflow.ui.containerViewModel
import io.github.joey7500.vaultflow.ui.theme.VaultColors
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val DAY = 24L * 3600 * 1000

/** Balance at the end of each of the last [days] days, from today's balance minus later movements. */
fun balanceHistory(current: Long, txs: List<Transaction>, days: Int, zone: ZoneId = ZoneId.systemDefault()): List<Long> {
    val today = LocalDate.now(zone)
    return (days - 1 downTo 0).map { back ->
        val endOfDay = today.minusDays(back.toLong()).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        current - txs.filter { it.timestampMillis >= endOfDay }.sumOf { it.amountCzkMinor }
    }
}

data class AccountCard(val account: Account, val balance: Long, val history: List<Long>, val last30In: Long, val last30Out: Long)

class AccountsViewModel(finance: FinanceRepository) : ViewModel() {
    private val since = System.currentTimeMillis() - 31 * DAY
    val cards: StateFlow<List<AccountCard>> = combine(
        finance.accounts,
        finance.accountBalances,
        finance.transactionsBetween(LocalDate.now().minusDays(31), LocalDate.now().plusDays(1)),
    ) { accounts, balances, txs ->
        accounts.map { a ->
            val mine = txs.filter { it.accountId == a.id }
            val bal = balances[a.id] ?: a.anchorBalanceMinor
            AccountCard(
                a, bal, balanceHistory(bal, mine, 30),
                mine.filter { it.timestampMillis >= since && it.amountCzkMinor > 0 }.sumOf { it.amountCzkMinor },
                mine.filter { it.timestampMillis >= since && it.amountCzkMinor < 0 }.sumOf { -it.amountCzkMinor },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@Composable
fun AccountsScreen() {
    val vm = containerViewModel { AccountsViewModel(it.finance) }
    val cards by vm.cards.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val total = cards.sumOf { it.balance }
    val liquid = cards.filter { it.account.isLiquid }.sumOf { it.balance }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Accounts", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            GlassCard {
                Text("ALL ACCOUNTS", style = MaterialTheme.typography.labelSmall, color = VaultColors.Muted)
                Text(money(total), style = MaterialTheme.typography.displaySmall)
                LabeledRow("Liquid (counts to reserve)", money(liquid))
                if (total > 0) {
                    Spacer(Modifier.height(8.dp))
                    ShareBar(cards.map { it.balance.coerceAtLeast(0) to colorFor(cards.indexOf(it)) })
                }
            }
        }
        if (cards.isEmpty()) {
            item { Text("No accounts yet. Add them in Settings → Accounts.", style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted) }
        }
        items(cards, key = { it.account.id }) { c ->
            val color = colorFor(cards.indexOf(c))
            GlassCard(onClick = { navigator.open(Detail.AccountDetail(c.account.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(4.dp).height(36.dp).clip(RoundedCornerShape(2.dp)).background(color))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.account.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            c.account.type.name.lowercase().replaceFirstChar { it.uppercase() } +
                                (if (c.account.cardIdentifiers.isNotEmpty()) " · card ••" + c.account.cardIdentifiers.joinToString(", ••") else ""),
                            style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted,
                        )
                    }
                    Text(money(c.balance), style = MaterialTheme.typography.titleMedium, color = if (c.balance < 0) VaultColors.Rose else VaultColors.Text)
                }
                Spacer(Modifier.height(8.dp))
                LineChart(c.history, Modifier.fillMaxWidth().height(56.dp), color = color)
                Spacer(Modifier.height(4.dp))
                Row {
                    Text("30 days: in ${money(c.last30In)}", style = MaterialTheme.typography.bodySmall, color = VaultColors.Emerald, modifier = Modifier.weight(1f))
                    Text("out ${money(c.last30Out)}", style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted)
                }
                BalanceCheckLine(c.account)
            }
        }
    }
}

private fun colorFor(i: Int): Color = listOf(VaultColors.Sky, VaultColors.Emerald, VaultColors.Violet, VaultColors.Amber, VaultColors.Rose)[i.coerceAtLeast(0) % 5]

@Composable
private fun ShareBar(parts: List<Pair<Long, Color>>) {
    val total = parts.sumOf { it.first }.coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
        parts.filter { it.first > 0 }.forEach { (v, c) ->
            Box(Modifier.weight(v.toFloat() / total).height(10.dp).background(c))
        }
    }
}

// ───────────────────────────── Account detail ─────────────────────────────

data class AccountDetailState(
    val account: Account? = null,
    val balance: Long = 0,
    val history: List<Long> = emptyList(),
    val transactions: List<Transaction> = emptyList(),
    val categories: List<Category> = emptyList(),
    val accounts: List<Account> = emptyList(),
)

class AccountDetailViewModel(private val finance: FinanceRepository, accountId: Long) : ViewModel() {
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val state: StateFlow<AccountDetailState> = combine(
        finance.accounts,
        finance.accountBalances,
        finance.transactionsForAccount(accountId, System.currentTimeMillis() - 366 * DAY),
        finance.categories,
    ) { accounts, balances, txs, cats ->
        val a = accounts.firstOrNull { it.id == accountId }
        val bal = a?.let { balances[it.id] ?: it.anchorBalanceMinor } ?: 0L
        AccountDetailState(a, bal, balanceHistory(bal, txs, 90), txs, cats, accounts)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountDetailState())

    fun assign(tx: Transaction, categoryId: Long, learn: Boolean) = viewModelScope.launch { finance.assignCategory(tx.id, categoryId, learn) }
    fun delete(tx: Transaction) = viewModelScope.launch {
        finance.deleteTransaction(tx.id)
        messages.tryEmit("Deleted ${tx.merchant}")
    }
    fun setBalance(account: Account, minor: Long) = viewModelScope.launch {
        val e = finance.accountEntities().firstOrNull { it.id == account.id } ?: return@launch
        finance.saveAccount(e.copy(anchorBalanceMinor = minor, anchorDateMillis = System.currentTimeMillis()))
        messages.tryEmit("Balance set")
    }
}

private val monthHeader = DateTimeFormatter.ofPattern("LLLL yyyy")

@Composable
fun AccountDetailScreen(accountId: Long, onBack: () -> Unit) {
    val container = appContainer()
    val vm: AccountDetailViewModel = viewModel(
        key = "account-$accountId",
        factory = viewModelFactory { initializer { AccountDetailViewModel(container.finance, accountId) } },
    )
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    var editTx by remember { mutableStateOf<Transaction?>(null) }
    var editBalance by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text(s.account?.name.orEmpty(), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("BALANCE", style = MaterialTheme.typography.labelSmall, color = VaultColors.Muted, modifier = Modifier.weight(1f))
                    if (s.account?.isLiquid == true) Pill("Counts to reserve", VaultColors.Emerald)
                }
                Text(money(s.balance, decimals = true), style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(8.dp))
                LineChart(s.history, Modifier.fillMaxWidth().height(120.dp))
                Text("Last 90 days", style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted)
                s.account?.let { BalanceCheckLine(it) }
                TextButton(onClick = { editBalance = true }) { Text("Correct the balance") }
            }
        }
        val byMonth = s.transactions.groupBy { YearMonth.from(Instant.ofEpochMilli(it.timestampMillis).atZone(zone)) }
        byMonth.forEach { (month, list) ->
            item(key = "m-$month") {
                val inflow = list.filter { it.amountCzkMinor > 0 }.sumOf { it.amountCzkMinor }
                val outflow = list.filter { it.amountCzkMinor < 0 }.sumOf { -it.amountCzkMinor }
                SectionLabel(month.format(monthHeader) + " · in ${money(inflow)} · out ${money(outflow)}")
            }
            items(list, key = { it.id }) { tx ->
                TransactionRow(tx, categoryName(s.categories, tx.categoryId)) { editTx = tx }
            }
        }
        if (s.transactions.isEmpty()) {
            item { Text("No transactions on this account in the last year.", style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted) }
        }
        item { Spacer(Modifier.height(48.dp)) }
    }

    editTx?.let { tx ->
        TransactionDialog(
            tx = tx, categories = s.categories, accounts = s.accounts,
            onAssign = { id, learn -> vm.assign(tx, id, learn); editTx = null },
            onDelete = { vm.delete(tx); editTx = null },
            onDismiss = { editTx = null },
        )
    }
    if (editBalance) {
        s.account?.let { a ->
            var text by remember { mutableStateOf(minorToInput(s.balance)) }
            val parsed = parseMoneyInput(text.removePrefix("-").removePrefix("−"))?.let { if (text.trim().startsWith("-") || text.trim().startsWith("−")) -it else it }
            AlertDialog(
                onDismissRequest = { editBalance = false },
                title = { Text(a.name) },
                text = {
                    Column {
                        Text("Enter the balance your bank app shows right now.", style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted)
                        Spacer(Modifier.height(8.dp))
                        MoneyField(text, { text = it }, "Balance")
                    }
                },
                confirmButton = { TextButton(enabled = parsed != null, onClick = { parsed?.let { vm.setBalance(a, it) }; editBalance = false }) { Text("Save") } },
                dismissButton = { TextButton(onClick = { editBalance = false }) { Text("Cancel") } },
            )
        }
    }
}
