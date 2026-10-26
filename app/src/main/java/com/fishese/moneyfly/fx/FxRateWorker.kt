package com.fishese.moneyfly.fx

import android.content.Context
import androidx.work.*
import com.fishese.moneyfly.MoneyFlyApplication
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class FxRateWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val app = applicationContext as MoneyFlyApplication
        val entries = app.ledgerStore.entries.first()
        app.fxRepository.initialize(entries)
        if (app.fxRepository.refresh(entries)) Result.success() else Result.retry()
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (_: Exception) { Result.retry() }
    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("moneyfly-daily-exchange-rates", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<FxRateWorker>(1, TimeUnit.DAYS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
    }
}
