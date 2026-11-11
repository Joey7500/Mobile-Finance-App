package io.github.joey7500.vaultflow.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.joey7500.vaultflow.domain.model.Account
import io.github.joey7500.vaultflow.domain.model.Category
import io.github.joey7500.vaultflow.domain.model.Transaction
import io.github.joey7500.vaultflow.domain.model.TransactionStatus
import io.github.joey7500.vaultflow.ui.components.CategoryList
import io.github.joey7500.vaultflow.ui.components.CheckRow
import io.github.joey7500.vaultflow.ui.components.evidenceLabel
import io.github.joey7500.vaultflow.ui.components.formatDateTime
import io.github.joey7500.vaultflow.ui.components.money
import io.github.joey7500.vaultflow.ui.components.moneyColor
import io.github.joey7500.vaultflow.ui.theme.VaultColors

/** One transaction line: merchant, time · category · sources, amount. */
@Composable
fun TransactionRow(tx: Transaction, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(tx.merchant, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                formatDateTime(tx.timestampMillis) + " · " + subtitle + (if (tx.status == TransactionStatus.PENDING) " · pending" else "") +
                    evidenceLabel(tx.evidence).let { if (it.isBlank()) "" else " · $it" },
                style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(money(tx.amountCzkMinor, decimals = true), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = moneyColor(tx.amountCzkMinor))
            if (tx.currency != "CZK") Text(money(tx.amountMinor, true).replace("Kč", tx.currency), style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted)
        }
    }
}

fun categoryName(categories: List<Category>, id: Long?): String = categories.firstOrNull { it.id == id }?.name ?: "Uncertain"

/** Details of one transaction: change its category (optionally learn), or delete it after confirmation. */
@Composable
fun TransactionDialog(
    tx: Transaction,
    categories: List<Category>,
    accounts: List<Account>,
    onAssign: (Long, Boolean) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var learn by remember { mutableStateOf(tx.categoryId == null) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this transaction?") },
            text = { Text("${tx.merchant} · ${money(tx.amountCzkMinor, true)} · ${formatDateTime(tx.timestampMillis)}\n\nUse this for duplicates or mistakes. It cannot be undone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete", color = VaultColors.Rose) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tx.merchant, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Text(money(tx.amountCzkMinor, true) + " · " + formatDateTime(tx.timestampMillis), color = moneyColor(tx.amountCzkMinor))
                Text(
                    "Account: " + (accounts.firstOrNull { it.id == tx.accountId }?.name ?: "none") +
                        evidenceLabel(tx.evidence).let { if (it.isBlank()) "" else " · $it" },
                    style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted,
                )
                if (tx.note.isNotBlank()) Text(tx.note, style = MaterialTheme.typography.bodySmall, color = VaultColors.Muted)
                Spacer(Modifier.height(8.dp))
                CheckRow(learn, { learn = it }, "Always classify this merchant like this")
                CategoryList(categories, tx.categoryId) { onAssign(it.id, learn) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = VaultColors.Rose) } },
    )
}
