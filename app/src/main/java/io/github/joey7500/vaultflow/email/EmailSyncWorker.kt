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
import io.github.joey7500.vaultflow.VaultFlowApp
import java.util.concurrent.TimeUnit

/** Background inbox check: every 15 minutes, plus ~8 minutes after each captured payment. */
class EmailSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as VaultFlowApp).container
        if (container.isDemo) return Result.success()
        if (!container.emailSync.isConfigured) return Result.success()
        container.initialize()
        val result = container.emailSync.run()
        // Authentication problems need the user; network hiccups are retried.
        return if (result.error == null || result.error.contains("App Password")) Result.success() else Result.retry()
    }

    companion object {
        private const val PERIODIC = "email-sync-periodic"
        private const val SOON = "email-sync-soon"

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<EmailSyncWorker>(15, TimeUnit.MINUTES).setConstraints(network).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Debounced: a burst of payments leads to one check ~8 min after the last one. */
        fun scheduleSoon(context: Context) {
            val request = OneTimeWorkRequestBuilder<EmailSyncWorker>().setInitialDelay(8, TimeUnit.MINUTES).setConstraints(network).build()
            WorkManager.getInstance(context).enqueueUniqueWork(SOON, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
            WorkManager.getInstance(context).cancelUniqueWork(SOON)
        }
    }
}
