package io.github.joey7500.vaultflow.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.joey7500.vaultflow.BuildConfig
import io.github.joey7500.vaultflow.domain.backup.BackupCrypto
import io.github.joey7500.vaultflow.domain.model.Account
import io.github.joey7500.vaultflow.domain.model.AccountType
import io.github.joey7500.vaultflow.domain.model.FlowKind
import io.github.joey7500.vaultflow.ui.LocalSnackbar
import io.github.joey7500.vaultflow.ui.components.CheckRow
import io.github.joey7500.vaultflow.ui.components.DropdownChooser
import io.github.joey7500.vaultflow.ui.components.GlassCard
import io.github.joey7500.vaultflow.ui.components.LabeledRow
import io.github.joey7500.vaultflow.ui.components.MoneyField
import io.github.joey7500.vaultflow.ui.components.Pill
import io.github.joey7500.vaultflow.ui.components.SectionLabel
import io.github.joey7500.vaultflow.ui.components.formatDateTime
import io.github.joey7500.vaultflow.ui.components.minorToInput
import io.github.joey7500.vaultflow.ui.components.money
import io.github.joey7500.vaultflow.ui.components.parseMoneyInput
import io.github.joey7500.vaultflow.ui.containerViewModel
import io.github.joey7500.vaultflow.ui.theme.VaultColors
import java.time.LocalDate

private fun hasNotificationAccess(context: Context) =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

@Composable
fun SettingsScreen(onRestartRequested: () -> Unit) {
    val vm = containerViewModel { SettingsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val hasT212 by vm.hasT212.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    // Re-check notification access whenever the user comes back from Android settings.
    var access by remember { mutableStateOf(hasNotificationAccess(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) access = hasNotificationAccess(context) }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    var editAccount by remember { mutableStateOf<Account?>(null) }
    var addingAccount by remember { mutableStateOf(false) }
    var addingCategory by remember { mutableStateOf(false) }
    var confirmDemo by remember { mutableStateOf(false) }

    // Backup flow: password first, then the system file picker.
    var exportPassword by remember { mutableStateOf<CharArray?>(null) }
    var askExportPassword by remember { mutableStateOf(false) }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pwd = exportPassword
        if (uri != null && pwd != null) vm.export(context.contentResolver, uri, pwd)
        exportPassword = null
    }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> importUri = uri }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        // ───────── Vault ─────────
        SectionLabel("Vault · API keys")
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Trading 212", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Pill(if (hasT212) "Stored ✓" else "Not set", if (hasT212) VaultColors.Emerald else VaultColors.Muted)
            }
            Text(
                "Trading 212 app → Settings → API (Beta) → Generate key. Enable only 'Account data' (read-only). " +
                    "Keys are encrypted with this phone's hardware Keystore and never leave it.",
                style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted,
            )
            var key by remember { mutableStateOf("") }
            var secret by remember { mutableStateOf("") }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(key, { key = it }, label = { Text(if (hasT212) "New API key (leave empty to keep)" else "API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(secret, { secret = it }, label = { Text("API secret") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(enabled = key.isNotBlank(), onClick = { vm.saveT212(key, secret); key = ""; secret = "" }) { Text("Save") }
                OutlinedButton(enabled = key.isNotBlank() || hasT212, onClick = { vm.testT212(key, secret) }) { Text("Test") }
                if (hasT212) TextButton(onClick = vm::clearT212) { Text("Remove", color = VaultColors.Rose) }
            }
        }

        // ───────── Reserve ─────────
        SectionLabel("Reserve floor")
        GlassCard {
            var floor by remember(state.floorMinor) { mutableStateOf(minorToInput(state.floorMinor)) }
            Text("Cash that must stay on your liquid accounts at all times.", style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MoneyField(floor, { floor = it }, "Floor", Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                val parsed = parseMoneyInput(floor)
                Button(enabled = parsed != null && parsed != state.floorMinor, onClick = { parsed?.let(vm::setFloor) }) { Text("Save") }
            }
        }

        // ───────── Accounts ─────────
        SectionLabel("Accounts")
        GlassCard {
            if (state.accounts.isEmpty()) {
                Text("Add each bank account with the balance your bank app shows today.", style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted)
            }
            state.accounts.forEach { a ->
                Row(Modifier.fillMaxWidth().clickable { editAccount = a }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            a.type.name.lowercase().replaceFirstChar { it.uppercase() } + (if (a.isLiquid) " · counts to reserve" else "") +
                                (if (a.ownIdentifiers.isNotEmpty()) " · ${a.ownIdentifiers.size} transfer id(s)" else ""),
                            style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted,
                        )
                    }
                    Text(money(state.balances[a.id] ?: a.anchorBalanceMinor), style = MaterialTheme.typography.bodyMedium)
                }
            }
            TextButton(onClick = { addingAccount = true }) { Text("+ Add account") }
        }

        // ───────── Automatic capture ─────────
        SectionLabel("Automatic capture")
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Notification access", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Pill(if (access) "On" else "Off", if (access) VaultColors.Emerald else VaultColors.Amber)
            }
            Text(
                "VaultFlow reads payment notifications from Google Wallet and the bank apps you enable below. " +
                    "Everything is processed on this phone.",
                style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted,
            )
            if (!access && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Android 13+: if the switch is greyed out ('Restricted setting'), open App info → ⋮ (top right) → " +
                        "'Allow restricted settings', then come back and enable access.",
                    style = MaterialTheme.typography.bodySmall, color = VaultColors.Amber,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Notification access") }
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }) { Text("App info") }
            }
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) {
                Text("Battery: set VaultFlow to 'Unrestricted' so capture keeps running")
            }

            SectionLabel("Apps that sent money notifications")
            if (state.senders.isEmpty()) {
                Text("Nothing detected yet. Make a payment, then come back.", style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted)
            }
            state.senders.forEach { s ->
                Column(Modifier.padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(friendlyPackage(s.packageName), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${s.packageName} · seen ${s.seenCount}×" + if (s.lastSeenMillis > 0) " · last ${formatDateTime(s.lastSeenMillis)}" else "",
                                style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Switch(checked = s.enabled, onCheckedChange = { vm.updateSender(s.packageName, it, s.accountId) })
                    }
                    if (s.enabled && state.accounts.isNotEmpty()) {
                        DropdownChooser(
                            "Book to account",
                            listOf<Pair<Long?, String>>(null to "No account (budget only)") + state.accounts.map { it.id to it.name },
                            s.accountId,
                            { vm.updateSender(s.packageName, true, it) },
                        )
                    }
                }
            }
            Text(
                "Tip: if you get both a Wallet and a bank notification for one payment, VaultFlow merges them automatically.",
                style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted,
            )
        }

        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Capture log", style = MaterialTheme.typography.titleMedium)
                    Text("What happened to each captured notification (kept 7 days, encrypted).", style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted)
                }
                Switch(checked = state.captureLogEnabled, onCheckedChange = vm::setCaptureLog)
            }
            state.log.take(25).forEach { e ->
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text("${formatDateTime(e.postedAtMillis)} · ${friendlyPackage(e.packageName)}", style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted)
                    Text(listOf(e.title, e.text).filter { it.isNotBlank() }.joinToString(" — "), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("→ ${e.outcome}", style = MaterialTheme.typography.bodySmall, color = if (e.outcome.startsWith("skipped")) VaultColors.Amber else VaultColors.Emerald)
                }
            }
            if (state.log.isNotEmpty()) TextButton(onClick = vm::clearLog) { Text("Clear log") }
        }

        // ───────── Rules & categories ─────────
        SectionLabel("Learned rules")
        GlassCard {
            if (state.rules.isEmpty()) {
                Text("When you categorise a transaction with \"always\" ticked, the rule appears here.", style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted)
            }
            state.rules.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("\"${r.pattern}\"", style = MaterialTheme.typography.bodyMedium)
                        Text("→ " + (state.categories.firstOrNull { it.id == r.categoryId }?.name ?: "deleted category"), style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted)
                    }
                    TextButton(onClick = { vm.deleteRule(r) }) { Text("Delete", color = VaultColors.Rose) }
                }
            }
        }

        SectionLabel("Categories")
        GlassCard {
            Text("Built-in categories can't be removed; your own can.", style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted)
            state.categories.filter { it.key == null }.forEach { c ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${c.group} · ${c.name}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.archiveCategory(c) }) { Text("Remove", color = VaultColors.Rose) }
                }
            }
            TextButton(onClick = { addingCategory = true }) { Text("+ Add category") }
        }

        // ───────── Backup ─────────
        SectionLabel("Backup")
        GlassCard {
            Text(
                "Uninstalling the app or resetting the phone destroys the encryption key, so your data would be lost. " +
                    "Export an encrypted backup regularly (e.g. to Google Drive). API keys are not included.",
                style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(enabled = !isDemoOrBusy(vm.isDemo, busy), onClick = { askExportPassword = true }) { Text("Export") }
                OutlinedButton(enabled = !isDemoOrBusy(vm.isDemo, busy), onClick = { openDoc.launch(arrayOf("*/*")) }) { Text("Restore") }
            }
            if (vm.isDemo) Text("Not available in Demo Mode.", style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted)
        }

        // ───────── Demo ─────────
        SectionLabel("Demo mode")
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Show synthetic data", style = MaterialTheme.typography.titleMedium)
                    Text("For screenshots and trying things out. Your real vault is kept separately and untouched.", style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted)
                }
                Switch(checked = vm.isDemo, onCheckedChange = { confirmDemo = true })
            }
        }

        LabeledRow("Version", BuildConfig.VERSION_NAME)
        Spacer(Modifier.height(24.dp))
    }

    // ───────── dialogs ─────────
    if (addingAccount || editAccount != null) {
        AccountDialog(
            existing = editAccount,
            currentBalance = editAccount?.let { state.balances[it.id] },
            onSave = { name, type, bal, liquid, ids -> vm.saveAccount(editAccount, name, type, bal, liquid, ids); editAccount = null; addingAccount = false },
            onDelete = { editAccount?.let(vm::deleteAccount); editAccount = null },
            onDismiss = { editAccount = null; addingAccount = false },
        )
    }
    if (addingCategory) {
        CategoryDialog(onSave = { n, g, k -> vm.addCategory(n, g, k); addingCategory = false }, onDismiss = { addingCategory = false })
    }
    if (confirmDemo) {
        AlertDialog(
            onDismissRequest = { confirmDemo = false },
            title = { Text(if (vm.isDemo) "Leave Demo Mode?" else "Switch to Demo Mode?") },
            text = { Text(if (vm.isDemo) "VaultFlow will reopen your real encrypted vault." else "VaultFlow will restart with synthetic data. Automatic capture pauses while in Demo Mode.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDemo = false
                    vm.setDemoMode(!vm.isDemo)
                    onRestartRequested()
                }) { Text("Switch") }
            },
            dismissButton = { TextButton(onClick = { confirmDemo = false }) { Text("Cancel") } },
        )
    }
    if (askExportPassword) {
        PasswordDialog(
            title = "Backup password",
            confirm = true,
            onDone = { pwd ->
                askExportPassword = false
                exportPassword = pwd
                createDoc.launch("vaultflow-${LocalDate.now()}.vfbackup")
            },
            onDismiss = { askExportPassword = false },
        )
    }
    importUri?.let { uri ->
        PasswordDialog(
            title = "Restore backup",
            confirm = false,
            warning = "This REPLACES all data currently in the app.",
            onDone = { pwd ->
                importUri = null
                vm.import(context.contentResolver, uri, pwd) { onRestartRequested() }
            },
            onDismiss = { importUri = null },
        )
    }
}

private fun isDemoOrBusy(demo: Boolean, busy: Boolean) = demo || busy

private fun friendlyPackage(pkg: String): String = when (pkg) {
    "com.google.android.apps.walletnfcrel" -> "Google Wallet"
    else -> pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
}

@Composable
private fun AccountDialog(
    existing: Account?,
    currentBalance: Long?,
    onSave: (String, AccountType, Long, Boolean, String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var type by remember { mutableStateOf(existing?.type ?: AccountType.BANK) }
    var balance by remember { mutableStateOf((currentBalance ?: existing?.anchorBalanceMinor)?.let { minorToInput(it) } ?: "") }
    var liquid by remember { mutableStateOf(existing?.isLiquid ?: true) }
    var ids by remember { mutableStateOf(existing?.ownIdentifiers?.joinToString("\n") ?: "") }
    val parsed = parseMoneyInput(balance)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add account" else "Edit account") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name (e.g. Main – Air Bank)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AccountType.entries.forEach { t ->
                        FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.name.lowercase().replaceFirstChar { it.uppercase() }) })
                    }
                }
                MoneyField(balance, { balance = it }, "Balance right now")
                CheckRow(liquid, { liquid = it }, "Counts towards the reserve floor (liquid cash)")
                OutlinedTextField(
                    ids, { ids = it },
                    label = { Text("Your account number digits (optional)") },
                    supportingText = { Text("One per line, e.g. the last 6 digits. Payments mentioning them are treated as internal transfers. Stored only in the encrypted database.") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(enabled = parsed != null, onClick = { parsed?.let { onSave(name, type, it, liquid, ids) } }) { Text("Save") } },
        dismissButton = {
            Row {
                if (existing != null) TextButton(onClick = onDelete) { Text("Delete", color = VaultColors.Rose) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun CategoryDialog(onSave: (String, String, FlowKind) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("Lifestyle") }
    var kind by remember { mutableStateOf(FlowKind.EXPENSE) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add category") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(group, { group = it }, label = { Text("Group") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                DropdownChooser(
                    "Type",
                    listOf(FlowKind.EXPENSE to "Expense", FlowKind.INCOME to "Income", FlowKind.INVESTMENT to "Investment"),
                    kind,
                    { kind = it },
                )
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name, group, kind) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PasswordDialog(title: String, confirm: Boolean, warning: String? = null, onDone: (CharArray) -> Unit, onDismiss: () -> Unit) {
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    val longEnough = p1.length >= BackupCrypto.MIN_PASSWORD_LENGTH
    val ok = if (confirm) longEnough && p1 == p2 else p1.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                warning?.let { Text(it, color = VaultColors.Amber, style = MaterialTheme.typography.bodySmall) }
                if (confirm) {
                    Text(
                        "At least ${BackupCrypto.MIN_PASSWORD_LENGTH} characters. Store it in your password manager: without it the backup cannot be opened.",
                        style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted,
                    )
                }
                OutlinedTextField(p1, { p1 = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                if (confirm) OutlinedTextField(p2, { p2 = it }, label = { Text("Repeat password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onDone(p1.toCharArray()) }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
