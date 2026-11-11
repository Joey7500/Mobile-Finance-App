package io.github.joey7500.vaultflow.ingestion

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.joey7500.vaultflow.VaultFlowApp
import io.github.joey7500.vaultflow.domain.ingest.AmountParser
import io.github.joey7500.vaultflow.domain.ingest.Channel
import io.github.joey7500.vaultflow.domain.ingest.IncomingMessage
import io.github.joey7500.vaultflow.email.EmailSyncWorker
import io.github.joey7500.vaultflow.widget.SafeToSpendWidget
import kotlinx.coroutines.launch

/**
 * Receives every posted notification once the user grants Notification access.
 *
 * Privacy: anything that does not contain a money amount is ignored
 * immediately and never stored. Money notifications from apps the user has not
 * enabled only bump a counter for that package name, so the user can decide to
 * enable them in Settings → Automatic capture.
 */
class MoneyNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (sbn.isOngoing) return

        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()
        if (!AmountParser.containsMoney(listOfNotNull(title, text).joinToString(" "))) return

        val container = (application as VaultFlowApp).container
        if (container.isDemo) return // never mix real notifications into demo data

        val postedAt = n.`when`.takeIf { it > 0 } ?: sbn.postTime
        // Key + "when" distinguishes a re-post of the same notification from a new
        // purchase that reuses the bank app's notification id.
        val key = "${sbn.key}|${n.`when`}"

        container.scope.launch {
            runCatching {
                container.initialize()
                val handled = container.finance.ingestMessage(
                    IncomingMessage(Channel.NOTIFICATION, sbn.packageName, key, postedAt, title, text),
                )
                // The bank e-mail follows ~6 min later: check the inbox soon to confirm it.
                if (handled) {
                    EmailSyncWorker.scheduleSoon(applicationContext)
                    SafeToSpendWidget.refresh(applicationContext)
                }
            }
        }
    }
}
