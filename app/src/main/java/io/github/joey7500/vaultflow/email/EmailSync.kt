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

data class RecheckResult(val checkedMails: Int, val updated: Int, val error: String?)

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
                processedKey = key,
            )
            if (!handled) finance.markProcessed(key, handled = false)
            if (handled) processed++
        }
        finance.purgeProcessed()
        finance.finalizeAwaitingEmail()
        settings.putLong(SettingsStore.EMAIL_LAST_SYNC_AT, now)
        settings.put(SettingsStore.EMAIL_LAST_RESULT, "OK · ${mails.size} e-mails checked · $processed processed")
        EmailSyncResult(mails.size, processed, null)
    }

    /**
     * Settings → "Re-check names from e-mails" (and once after the update to 1.3.0): re-reads the
     * bank e-mails of the last [days] days and enriches the records that already contain them
     * (name, payment message, counterparty account, category unless you picked it). Read-only on
     * Gmail; it never creates records. The capture log's stored copies are used first, offline.
     */
    suspend fun recheck(days: Int = 60): RecheckResult = lock.withLock {
        val updated = finance.reenrichFromCaptureLog().toMutableSet()
        val address = vault.get(SecretVault.GMAIL_ADDRESS)
        val password = vault.get(SecretVault.GMAIL_APP_PASSWORD)
        if (address.isNullOrBlank() || password.isNullOrBlank()) {
            finance.logRecheck(0, updated.size)
            return@withLock RecheckResult(0, updated.size, "Gmail is not set up")
        }
        val mails = try {
            withContext(Dispatchers.IO) {
                ImapMailFetcher.fetch(address, password, System.currentTimeMillis() - days * DAY, max = 600)
            }
        } catch (e: Exception) {
            finance.logRecheck(0, updated.size)
            return@withLock RecheckResult(0, updated.size, e.message ?: e.javaClass.simpleName)
        }
        val incoming = mails
            .filter { AmountParser.containsMoney(it.subject + "\n" + it.text) }
            .map { IncomingMessage(Channel.EMAIL, "mail:" + it.from, it.messageId, it.receivedAtMillis, it.subject, it.text) }
        updated += finance.reenrichFromEmails(incoming)
        finance.logRecheck(incoming.size, updated.size)
        RecheckResult(incoming.size, updated.size, null)
    }

    private companion object {
        const val DAY = 24L * 3600 * 1000
        val lock = Mutex()
    }
}
