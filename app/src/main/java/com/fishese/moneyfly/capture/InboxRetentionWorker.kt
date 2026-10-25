package com.fishese.moneyfly.capture

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fishese.moneyfly.MoneyFlyApplication
import com.fishese.moneyfly.ledger.LedgerCommand
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** Disk pruning also runs on capture/refresh/open; Android may defer periodic work. */
class InboxRetentionWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val store = (applicationContext as MoneyFlyApplication).ledgerStore
        if (store.execute(LedgerCommand.PruneInbox()).applied) Result.success() else Result.retry()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { Result.retry() }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "moneyfly-inbox-retention",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<InboxRetentionWorker>(12, TimeUnit.HOURS).build(),
            )
        }
    }
}
