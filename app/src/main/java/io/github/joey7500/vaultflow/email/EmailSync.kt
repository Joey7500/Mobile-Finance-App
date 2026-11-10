package io.github.joey7500.vaultflow.email

import io.github.joey7500.vaultflow.core.security.SecretVault
import io.github.joey7500.vaultflow.data.repository.FinanceRepository
import io.github.joey7500.vaultflow.data.repository.SettingsStore
import io.github.joey7500.vaultflow.domain.ingest.AmountParser
import io.github.joey7500.vaultflow.domain.ingest.Channel
import io.github.joey7500.vaultflow.domain.ingest.IncomingMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class EmailSyncResult(val checked: Int, val processed: Int, val error: String?)

/**
 * Pulls bank e-mails from the dedicated Gmail inbox and feeds them through the
 * same fusion pipeline as notifications. Every e-mail is processed once.
 */
class EmailSync(
    private val vault: SecretVault,
    private val finance: FinanceRepository,
    private val settings: SettingsStore,
) {
    val isConfigured: Boolean
        get() = vault.has(SecretVault.GMAIL_ADDRESS) && vault.has(SecretVault.GMAIL_APP_PASSWORD)

    suspend fun run(): EmailSyncResult = lock.withLock {
        val address = vault.get(SecretVault.GMAIL_ADDRESS)
        val password = vault.get(SecretVault.GMAIL_APP_PASSWORD)
        if (address.isNullOrBlank() || password.isNullOrBlank()) return@withLock EmailSyncResult(0, 0, "Gmail is not set up")

        val now = System.currentTimeMillis()
        val last = settings.getLong(SettingsStore.EMAIL_LAST_SYNC_AT, 0L)
        // First run: last 3 days. Later: since the last run minus a safety margin.
        val since = if (last == 0L) now - 3L * DAY else maxOf(last - DAY, now - 14L * DAY)

        val mails = try {
            withContext(Dispatchers.IO) { ImapMailFetcher.fetch(address, password, since) }
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            settings.put(SettingsStore.EMAIL_LAST_RESULT, "Error: $msg")
            return@withLock EmailSyncResult(0, 0, msg)
        }

        var processed = 0
        for (m in mails.sortedBy { it.receivedAtMillis }) {
            val key = "mail:" + m.messageId
            val seen = finance.processedMessage(key)
            if (seen?.handled == true) continue
            if (!AmountParser.containsMoney(m.subject + "\n" + m.text)) {
                finance.markProcessed(key, handled = true) // not a money e-mail: never look again
                continue
            }
            if (seen != null) {
                // Seen before while the sender was disabled: only retry, never count twice.
                if (!finance.isSenderEnabled("mail:" + m.from)) continue
            }
            val handled = finance.ingestMessage(
                IncomingMessage(Channel.EMAIL, "mail:" + m.from, m.messageId, m.receivedAtMillis, m.subject, m.text),
            )
            finance.markProcessed(key, handled)
            if (handled) processed++
        }
        finance.purgeProcessed()
        settings.putLong(SettingsStore.EMAIL_LAST_SYNC_AT, now)
        settings.put(SettingsStore.EMAIL_LAST_RESULT, "OK · ${mails.size} e-mails checked · $processed processed")
        EmailSyncResult(mails.size, processed, null)
    }

    private companion object {
        const val DAY = 24L * 3600 * 1000
        val lock = Mutex()
    }
}
