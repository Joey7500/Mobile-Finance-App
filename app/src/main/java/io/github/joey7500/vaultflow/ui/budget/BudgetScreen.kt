package io.github.joey7500.vaultflow.ui.budget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.joey7500.vaultflow.domain.budget.CategoryLine
import io.github.joey7500.vaultflow.domain.budget.ReserveLevel
import io.github.joey7500.vaultflow.domain.budget.ReserveStatus
import io.github.joey7500.vaultflow.domain.model.FlowKind
import io.github.joey7500.vaultflow.domain.model.Transaction
import io.github.joey7500.vaultflow.domain.model.TransactionStatus
import io.github.joey7500.vaultflow.ui.Detail
import io.github.joey7500.vaultflow.ui.KindFilter
import io.github.joey7500.vaultflow.ui.LocalNavigator
import io.github.joey7500.vaultflow.ui.LocalSnackbar
import io.github.joey7500.vaultflow.ui.TxFilter
import io.github.joey7500.vaultflow.ui.common.TransactionDialog
import io.github.joey7500.vaultflow.ui.common.TransactionRow
import io.github.joey7500.vaultflow.ui.components.BudgetBar
import io.github.joey7500.vaultflow.ui.components.CategoryList
import io.github.joey7500.vaultflow.ui.components.CheckRow
import io.github.joey7500.vaultflow.ui.components.DropdownChooser
import io.github.joey7500.vaultflow.ui.components.GlassCard
import io.github.joey7500.vaultflow.ui.components.LabeledRow
import io.github.joey7500.vaultflow.ui.components.MoneyField
import io.github.joey7500.vaultflow.ui.components.Pill
import io.github.joey7500.vaultflow.ui.components.SectionLabel
import io.github.joey7500.vaultflow.ui.components.StatTile
import io.github.joey7500.vaultflow.ui.components.evidenceLabel
import io.github.joey7500.vaultflow.ui.components.formatDateTime
import io.github.joey7500.vaultflow.ui.components.minorToInput
import io.github.joey7500.vaultflow.ui.components.money
import io.github.joey7500.vaultflow.ui.components.moneyColor
import io.github.joey7500.vaultflow.ui.components.parseMoneyInput
import io.github.joey7500.vaultflow.ui.containerViewModel
import io.github.joey7500.vaultflow.ui.theme.VaultColors
import java.time.format.DateTimeFormatter


@Composable
fun BudgetScreen() {
    val vm = containerViewModel { BudgetViewModel(it.finance) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    var editTx by remember { mutableStateOf<Transaction?>(null) }
    val navigator = LocalNavigator.current
    fun openCategory(c: io.github.joey7500.vaultflow.domain.model.Category) =
        navigator.open(Detail.Transactions(TxFilter.Category(c.id, c.name, c.isUncertain), vm.periodOffset))
    fun openKind(k: KindFilter) = navigator.open(Detail.Transactions(TxFilter.Kind(k), vm.periodOffset))
    var adding by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Budget", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = vm::previousMonth) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous period") }
                    Text(state.period.label(), style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = vm::nextMonth) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next period") }
                }
            }

            if (state.isCurrentPeriod) item { ReserveCard(state.reserve) }

            state.summary?.let { s ->
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile("INCOME ›", money(s.incomeMinor), VaultColors.Emerald, Modifier.weight(1f).clickable { openKind(KindFilter.INCOME) })
                        StatTile("SPENT ›", money(s.expensesMinor), VaultColors.Text, Modifier.weight(1f).clickable { openKind(KindFilter.SPENT) })
                        StatTile("INVESTED ›", money(s.investmentsMinor), VaultColors.Sky, Modifier.weight(1f).clickable { openKind(KindFilter.INVESTED) })
                    }
                    Spacer(Modifier.height(6.dp))
                    LabeledRow("Net cash flow this period", money(s.netCashFlowMinor), moneyColor(s.netCashFlowMinor))
                    val confirmed = state.transactions.count { it.evidence.contains(io.github.joey7500.vaultflow.domain.model.TransactionSource.EMAIL) }
                    if (confirmed > 0) {
                        LabeledRow("Confirmed by bank e-mail", "$confirmed of ${state.transactions.size}")
                    }
                }
            }

            val uncertain = state.uncertain
            if (uncertain.isNotEmpty()) {
                item {
                    GlassCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Needs a category", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Pill("${uncertain.size}", VaultColors.Amber)
                        }
                        Text("Tap to assign. Tick \"always\" and VaultFlow learns the merchant.", style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted)
                        Spacer(Modifier.height(6.dp))
                        uncertain.take(6).forEach { tx -> TransactionRow(tx, "Uncertain") { editTx = tx } }
                        state.categories.firstOrNull { it.isUncertain }?.let { u ->
                            TextButton(onClick = { openCategory(u) }) { Text(if (uncertain.size > 6) "See all ${uncertain.size} ›" else "Open list ›") }
                        }
                    }
                }
            }

            state.summary?.let { s ->
                val groups = s.lines
                    .filter { it.category.kind != FlowKind.TRANSFER && !it.category.isUncertain }
                    .groupBy { it.category.group }
                groups.forEach { (group, lines) ->
                    item(key = "group-$group") {
                        GlassCard {
                            Text(group, style = MaterialTheme.typography.titleMedium)
                            lines.forEach { line -> CategoryLineRow(line) { openCategory(line.category) } }
                        }
                    }
                }
            }

            item { SectionLabel("Transactions (${state.transactions.size})") }
            if (state.loaded && state.transactions.isEmpty()) {
                item {
                    Text(
                        "No transactions in this period yet. Add one with + or enable automatic capture in Settings.",
                        style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted,
                    )
                }
            }
            items(state.transactions, key = { it.id }) { tx ->
                TransactionRow(tx, state.categoryName(tx.categoryId)) { editTx = tx }
            }
        }

        FloatingActionButton(
            onClick = { adding = true },
            containerColor = VaultColors.Emerald,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Filled.Add, "Add transaction") }
    }

    editTx?.let { tx ->
        TransactionDialog(
            tx = tx,
            categories = state.categories,
            accounts = state.accounts,
            onAssign = { catId, learn -> vm.assign(tx, catId, learn); editTx = null },
            onDelete = { vm.delete(tx); editTx = null },
            onDismiss = { editTx = null },
        )
    }
    if (adding) {
        AddTransactionDialog(
            state = state,
            onAdd = { amount, income, merchant, cat, acc ->
                vm.addManual(amount, income, merchant, cat, acc, vm.defaultDateFor(state))
                adding = false
            },
            onDismiss = { adding = false },
        )
    }
}

@Composable
private fun ReserveCard(reserve: ReserveStatus?) {
    GlassCard {
        if (reserve == null) {
            Text("Reserve watchdog", style = MaterialTheme.typography.titleMedium)
            Text(
                "Add your bank accounts with their current balance in Settings → Accounts to track your reserve floor.",
                style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted,
            )
            return@GlassCard
        }
        val (label, color) = when (reserve.level) {
            ReserveLevel.HEALTHY -> "Healthy" to VaultColors.Emerald
            ReserveLevel.AT_RISK -> "At risk" to VaultColors.Amber
            ReserveLevel.BREACHED -> "Below floor" to VaultColors.Rose
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SAFE TO SPEND OR INVEST", style = MaterialTheme.typography.labelSmall, color = VaultColors.Muted, modifier = Modifier.weight(1f))
            Pill(label, color)
        }
        Text(money(reserve.safeToSpendMinor), style = MaterialTheme.typography.displaySmall, color = if (reserve.safeToSpendMinor >= 0) VaultColors.Text else VaultColors.Rose)
        Spacer(Modifier.height(4.dp))
        LabeledRow("Liquid cash now", money(reserve.liquidMinor))
        LabeledRow("Reserve floor", "− " + money(reserve.floorMinor))
        LabeledRow("Still budgeted this period", "− " + money(reserve.remainingPlannedOutflowMinor))
        Text("Income not received yet is not counted.", style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted)
    }
}

@Composable
private fun CategoryLineRow(line: CategoryLine, onClick: () -> Unit) {
    val isIncome = line.category.kind == FlowKind.INCOME
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(line.category.name + " ›", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                money(line.actualMinor) + if (line.budgetMinor > 0) " / " + money(line.budgetMinor) else "",
                style = MaterialTheme.typography.bodySmall,
                color = if (line.isOverBudget) VaultColors.Rose else VaultColors.Muted,
            )
        }
        Spacer(Modifier.height(6.dp))
        BudgetBar(line.progress, line.isOverBudget, color = if (isIncome) VaultColors.Emerald else if (line.category.kind == FlowKind.INVESTMENT) VaultColors.Sky else VaultColors.Violet)
    }
}

@Composable
private fun AddTransactionDialog(
    state: BudgetUiState,
    onAdd: (Long, Boolean, String, Long?, Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    var amount by remember { mutableStateOf("") }
    var income by remember { mutableStateOf(false) }
    var merchant by remember { mutableStateOf("") }
    var categoryId by remember { mutableStateOf<Long?>(null) }
    var accountId by remember { mutableStateOf(state.accounts.firstOrNull()?.id) }
    val parsed = parseMoneyInput(amount)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add transaction") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !income, onClick = { income = false }, label = { Text("Expense") })
                    FilterChip(selected = income, onClick = { income = true }, label = { Text("Income") })
                }
                MoneyField(amount, { amount = it }, "Amount")
                OutlinedTextField(merchant, { merchant = it }, label = { Text("Merchant / description") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                DropdownChooser(
                    "Category",
                    listOf<Pair<Long?, String>>(null to "Uncertain (decide later)") + state.categories.map { it.id to "${it.group} · ${it.name}" },
                    categoryId,
                    { categoryId = it },
                )
                if (state.accounts.isNotEmpty()) {
                    DropdownChooser(
                        "Account",
                        listOf<Pair<Long?, String>>(null to "No account") + state.accounts.map { it.id to it.name },
                        accountId,
                        { accountId = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = parsed != null && parsed > 0, onClick = { parsed?.let { onAdd(it, income, merchant, categoryId, accountId) } }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
