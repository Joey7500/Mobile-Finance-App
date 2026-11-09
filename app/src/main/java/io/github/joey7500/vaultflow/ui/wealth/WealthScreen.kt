package io.github.joey7500.vaultflow.ui.wealth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.joey7500.vaultflow.data.repository.WealthState
import io.github.joey7500.vaultflow.domain.Money
import io.github.joey7500.vaultflow.domain.ingest.AmountParser
import io.github.joey7500.vaultflow.domain.model.Account
import io.github.joey7500.vaultflow.domain.model.ManualAsset
import io.github.joey7500.vaultflow.domain.model.ManualAssetKind
import io.github.joey7500.vaultflow.ui.LocalSnackbar
import io.github.joey7500.vaultflow.ui.components.DonutChart
import io.github.joey7500.vaultflow.ui.components.Dot
import io.github.joey7500.vaultflow.ui.components.GlassCard
import io.github.joey7500.vaultflow.ui.components.LabeledRow
import io.github.joey7500.vaultflow.ui.components.LineChart
import io.github.joey7500.vaultflow.ui.components.MoneyField
import io.github.joey7500.vaultflow.ui.components.Pill
import io.github.joey7500.vaultflow.ui.components.formatDateTime
import io.github.joey7500.vaultflow.ui.components.formatDay
import io.github.joey7500.vaultflow.ui.components.minorToInput
import io.github.joey7500.vaultflow.ui.components.money
import io.github.joey7500.vaultflow.ui.components.moneyColor
import io.github.joey7500.vaultflow.ui.components.parseMoneyInput
import io.github.joey7500.vaultflow.ui.containerViewModel
import io.github.joey7500.vaultflow.ui.theme.VaultColors
import java.util.Locale

@Composable
fun WealthScreen() {
    val vm = containerViewModel { WealthViewModel(it.wealth, it.finance) }
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    var editGold by remember { mutableStateOf(false) }
    var editT212 by remember { mutableStateOf(false) }
    var editAccount by remember { mutableStateOf<Pair<Account, Long>?>(null) }
    var editAsset by remember { mutableStateOf<ManualAsset?>(null) }
    var addingAsset by remember { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Net worth", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        state?.lastRefreshAt?.let { "Updated " + formatDateTime(it) } ?: "Not refreshed yet",
                        style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted,
                    )
                }
                if (refreshing) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, "Refresh live values") }
                }
            }
        }

        val s = state ?: return@LazyColumn
        item { TotalCard(s) }
        if (s.history.size >= 2) item { HistoryCard(s) }

        item {
            GlassCard(onClick = { editT212 = true }) {
                PillarHeader("Trading 212", VaultColors.Pillars[0], s.trading212.valueCzkMinor)
                val t = s.trading212
                when {
                    !t.hasCredentials && !t.isManual -> Text(
                        "Add your read-only API key in Settings → Vault for live value, or tap to enter it manually.",
                        style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted,
                    )
                    t.isManual -> Text("Manual value · tap to edit", style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted)
                    else -> {
                        if (t.currency != null && t.currency != "CZK" && t.nativeValue != null) {
                            LabeledRow("In account currency", String.format(Locale.ROOT, "%,.2f %s", t.nativeValue, t.currency))
                        }
                        t.profitLossNative?.let { LabeledRow("Unrealised P/L", String.format(Locale.ROOT, "%+,.2f %s", it, t.currency ?: ""), if (it >= 0) VaultColors.Emerald else VaultColors.Rose) }
                        t.updatedAt?.let { Text("Live · " + formatDateTime(it), style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted) }
                    }
                }
            }
        }

        item {
            GlassCard(onClick = { editGold = true }) {
                PillarHeader("Physical gold", VaultColors.Pillars[1], s.gold.valueMinor)
                LabeledRow("Holding", String.format(Locale.ROOT, "%.2f g (%.3f oz)", s.gold.grams, s.gold.grams / 31.1034768))
                s.gold.czkPerGram?.let {
                    LabeledRow(if (s.gold.isManualPrice) "Price (manual)" else "Spot price", Money.format((it * 100).toLong()) + " / g")
                }
                if (s.gold.grams == 0.0) Text("Tap to enter how much gold you own.", style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted)
            }
        }

        item {
            GlassCard {
                PillarHeader("Bank accounts", VaultColors.Pillars[2], s.accounts.sumOf { it.second })
                if (s.accounts.isEmpty()) {
                    Text("Add accounts in Settings → Accounts.", style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted)
                }
                s.accounts.forEach { pair ->
                    Row(
                        Modifier.fillMaxWidth().clickable { editAccount = pair }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(pair.first.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(money(pair.second), style = MaterialTheme.typography.bodyMedium, color = moneyColor(pair.second))
                    }
                }
                if (s.accounts.isNotEmpty()) Text("Tap an account to correct its balance.", style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted)
            }
        }

        item {
            GlassCard {
                PillarHeader("Building savings & other", VaultColors.Pillars[3], s.manualAssets.sumOf { it.valueMinor })
                s.manualAssets.forEach { a ->
                    Row(
                        Modifier.fillMaxWidth().clickable { editAsset = a }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(a.name, style = MaterialTheme.typography.bodyMedium)
                            Text("Updated " + formatDay(a.updatedAtMillis), style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted)
                        }
                        Text(money(a.valueMinor), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                TextButton(onClick = { addingAsset = true }) { Text("+ Add asset (e.g. stavební spoření)") }
            }
        }
    }

    val s = state
    if (editGold && s != null) GoldDialog(s, vm, onDismiss = { editGold = false })
    if (editT212 && s != null) {
        AmountDialog(
            title = "Trading 212 manual value",
            hint = "Only used when no API key is stored. Leave empty to clear.",
            initial = s.trading212.valueCzkMinor,
            allowEmpty = true,
            onSave = { vm.setManualTrading212(it); editT212 = false },
            onDismiss = { editT212 = false },
        )
    }
    editAccount?.let { (acc, bal) ->
        AmountDialog(
            title = acc.name,
            hint = "Enter the balance your bank app shows right now. Captured transactions are added on top of it.",
            initial = bal,
            allowEmpty = false,
            onSave = { v -> v?.let { vm.setAccountBalance(acc, it) }; editAccount = null },
            onDismiss = { editAccount = null },
        )
    }
    if (addingAsset || editAsset != null) {
        AssetDialog(
            existing = editAsset,
            onSave = { name, kind, value -> vm.saveAsset(editAsset, name, kind, value); editAsset = null; addingAsset = false },
            onDelete = { editAsset?.let(vm::deleteAsset); editAsset = null },
            onDismiss = { editAsset = null; addingAsset = false },
        )
    }
}

@Composable
private fun PillarHeader(title: String, color: androidx.compose.ui.graphics.Color, valueMinor: Long?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(color)
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(valueMinor?.let { money(it) } ?: "—", style = MaterialTheme.typography.titleMedium)
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun TotalCard(s: WealthState) {
    GlassCard {
        Text("TOTAL", style = MaterialTheme.typography.labelSmall, color = VaultColors.Muted)
        Text(money(s.netWorth.totalMinor), style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
                DonutChart(
                    s.netWorth.pillars.mapIndexed { i, p -> p.valueMinor.coerceAtLeast(0).toFloat() to VaultColors.Pillars[i % VaultColors.Pillars.size] },
                    Modifier.size(132.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                s.netWorth.pillars.forEachIndexed { i, p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(VaultColors.Pillars[i % VaultColors.Pillars.size], 8.dp)
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(p.pillar.label, style = MaterialTheme.typography.bodySmall)
                            Text(
                                String.format(Locale.ROOT, "%.0f %%", s.netWorth.share(p.pillar) * 100),
                                style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(s: WealthState) {
    val values = s.history.map { it.totalMinor }
    val change = values.last() - values.first()
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("History", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Pill((if (change >= 0) "+" else "") + money(change), if (change >= 0) VaultColors.Emerald else VaultColors.Rose)
        }
        Spacer(Modifier.height(8.dp))
        LineChart(values, Modifier.fillMaxWidth().height(120.dp))
        Text(
            "Since " + java.time.LocalDate.ofEpochDay(s.history.first().epochDay).toString() + " · one point per refresh day",
            style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted,
        )
    }
}

@Composable
private fun GoldDialog(s: WealthState, vm: WealthViewModel, onDismiss: () -> Unit) {
    var grams by remember { mutableStateOf(if (s.gold.grams == 0.0) "" else s.gold.grams.toString()) }
    var manual by remember { mutableStateOf(s.gold.isManualPrice) }
    var price by remember { mutableStateOf(s.gold.czkPerGram?.let { String.format(Locale.ROOT, "%.0f", it) } ?: "") }
    val gramsValue = grams.replace(',', '.').toDoubleOrNull()
    val priceValue = AmountParser.parseNumber(price)?.let { it / 100.0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Physical gold") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(grams, { grams = it }, label = { Text("Total weight") }, suffix = { Text("g") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("1 troy ounce = 31.10 g. Live price = XAU/USD spot × ČNB USD/CZK.", style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !manual, onClick = { manual = false }, label = { Text("Live price") })
                    FilterChip(selected = manual, onClick = { manual = true }, label = { Text("Manual price") })
                }
                if (manual) OutlinedTextField(price, { price = it }, label = { Text("Price per gram") }, suffix = { Text("Kč") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                enabled = gramsValue != null && (!manual || priceValue != null),
                onClick = {
                    vm.setManualGoldPrice(if (manual) priceValue else null)
                    gramsValue?.let { vm.setGoldGrams(it) }
                    onDismiss()
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AmountDialog(title: String, hint: String, initial: Long?, allowEmpty: Boolean, onSave: (Long?) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial?.let { minorToInput(it) } ?: "") }
    val parsed = parseMoneyInput(text.replace("−", "-").replace("-", ""))?.let { if (text.trim().startsWith("-") || text.trim().startsWith("−")) -it else it }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted)
                Spacer(Modifier.height(8.dp))
                MoneyField(text, { text = it }, "Amount")
            }
        },
        confirmButton = {
            TextButton(enabled = parsed != null || (allowEmpty && text.isBlank()), onClick = { onSave(parsed) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AssetDialog(existing: ManualAsset?, onSave: (String, ManualAssetKind, Long) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "Building savings") }
    var kind by remember { mutableStateOf(existing?.kind ?: ManualAssetKind.BUILDING_SAVINGS) }
    var value by remember { mutableStateOf(existing?.let { minorToInput(it.valueMinor) } ?: "") }
    val parsed = parseMoneyInput(value)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add asset" else "Edit asset") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = kind == ManualAssetKind.BUILDING_SAVINGS, onClick = { kind = ManualAssetKind.BUILDING_SAVINGS }, label = { Text("Building savings") })
                    FilterChip(selected = kind == ManualAssetKind.OTHER, onClick = { kind = ManualAssetKind.OTHER }, label = { Text("Other") })
                }
                MoneyField(value, { value = it }, "Current value")
                Text("Update it when the yearly statement arrives.", style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted)
            }
        },
        confirmButton = { TextButton(enabled = parsed != null, onClick = { parsed?.let { onSave(name, kind, it) } }) { Text("Save") } },
        dismissButton = {
            Row {
                if (existing != null) TextButton(onClick = onDelete) { Text("Delete", color = VaultColors.Rose) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
