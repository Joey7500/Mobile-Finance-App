package io.github.joey7500.vaultflow.wealth

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.joey7500.vaultflow.VaultFlowApp
import io.github.joey7500.vaultflow.core.security.SecretVault
import io.github.joey7500.vaultflow.widget.SafeToSpendWidget
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Refreshes the net worth (Trading 212, gold spot, ČNB rates) twice a day in the
 * background. The manual refresh on the Wealth tab stays.
 *
 * Never throws: a failing API is retried a couple of times and then left for
 * the next 12-hour run, so a broken API can never crash the app.
 */
class WealthRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as VaultFlowApp).container
        if (container.isDemo || !container.vault.has(SecretVault.T212_API_KEY)) return Result.success()
        return try {
            container.initialize()
            val problems = container.wealth.refresh()
            SafeToSpendWidget.refresh(applicationContext)
            if (problems.isEmpty() || runAttemptCount >= MAX_RETRIES) Result.success() else Result.retry()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount >= MAX_RETRIES) Result.success() else Result.retry()
        }
    }

    companion object {
        private const val NAME = "wealth-refresh-12h"
        private const val MAX_RETRIES = 2

        /** Idempotent: keeps an already scheduled job (and its timing). */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<WealthRefreshWorker>(12, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
