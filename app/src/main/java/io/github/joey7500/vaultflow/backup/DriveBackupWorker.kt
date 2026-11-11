package io.github.joey7500.vaultflow.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.joey7500.vaultflow.VaultFlowApp
import java.util.concurrent.TimeUnit

/** Daily backup to Google Drive while on Wi-Fi/mobile data and not low on battery. */
class DriveBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as VaultFlowApp).container
        if (container.isDemo) return Result.success()
        container.initialize()
        val drive = container.driveBackup
        if (!drive.isConnected() || !drive.hasPassword) return Result.success()
        return runCatching { drive.backupNow() }.fold(
            onSuccess = { Result.success() },
            // Not connected / no password needs the user; anything else (network) is retried.
            onFailure = { e -> if (e is DriveException && e.code == 0) Result.success() else Result.retry() },
        )
    }

    companion object {
        private const val NAME = "drive-backup-daily"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<DriveBackupWorker>(1, TimeUnit.DAYS).setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(NAME)
    }
}
