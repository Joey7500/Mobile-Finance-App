package io.github.joey7500.vaultflow.email

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.joey7500.vaultflow.VaultFlowApp
import io.github.joey7500.vaultflow.widget.SafeToSpendWidget
import java.util.concurrent.TimeUnit

/**
 * Background inbox check: every 15 minutes, plus follow-ups 7, 15 and 30 minutes after each
 * captured payment (the bank e-mail usually arrives ~6 minutes after the push). The later
 * follow-ups only connect to Gmail while some push is still waiting for its e-mail.
 */
class EmailSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as VaultFlowApp).container
        if (container.isDemo) return Result.success()
        if (!container.emailSync.isConfigured) return Result.success()
        container.initialize()
        if (inputData.getBoolean(KEY_ONLY_IF_WAITING, false) && !container.finance.hasAwaitingEmail()) {
            container.finance.finalizeAwaitingEmail()
            return Result.success()
        }
        val result = container.emailSync.run()
        if (result.processed > 0) SafeToSpendWidget.refresh(applicationContext)
        // Authentication problems need the user; network hiccups are retried.
        return if (result.error == null || result.error.contains("App Password")) Result.success() else Result.retry()
    }

    companion object {
        private const val PERIODIC = "email-sync-periodic"
        private const val SOON = "email-sync-soon"
        private const val KEY_ONLY_IF_WAITING = "only_if_waiting"

        /** Minutes after a payment; each slot is debounced (a burst of payments = one check per slot). */
        private val FOLLOW_UPS = listOf(7L, 15L, 30L)

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<EmailSyncWorker>(15, TimeUnit.MINUTES).setConstraints(network).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /**
         * After a captured payment: check at +7 min (always, like before), and at +15 / +30 min
         * only while a push still waits for its bank e-mail. Network required, no wake-ups otherwise.
         */
        fun scheduleFollowUps(context: Context) {
            val wm = WorkManager.getInstance(context)
            FOLLOW_UPS.forEachIndexed { i, minutes ->
                val request = OneTimeWorkRequestBuilder<EmailSyncWorker>()
                    .setInitialDelay(minutes, TimeUnit.MINUTES)
                    .setConstraints(network)
                    .setInputData(workDataOf(KEY_ONLY_IF_WAITING to (i > 0)))
                    .build()
                wm.enqueueUniqueWork("$SOON-$minutes", ExistingWorkPolicy.REPLACE, request)
            }
        }

        fun cancelAll(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(PERIODIC)
            wm.cancelUniqueWork(SOON)
            FOLLOW_UPS.forEach { wm.cancelUniqueWork("$SOON-$it") }
        }
    }
}
