package biz.fesh.syncmusic.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import biz.fesh.syncmusic.Notifications
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val resume = inputData.getBoolean(KEY_RESUME, false)
        val outcome = SyncEngine.run(applicationContext, manual, goForeground = {
            try {
                setForeground(getForegroundInfo())
            } catch (t: Throwable) {
                // Android may refuse to start a foreground service from the background;
                // the sync still runs, it just may be cut short and will resume next time.
                Log.w(TAG, "setForeground failed", t)
            }
        }, waitForLock = resume)
        Log.i(TAG, "Sync finished: $outcome")
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        Notifications.foregroundInfo(applicationContext, SyncEngine.state.value.progress)

    companion object {
        private const val TAG = "SyncWorker"
        private const val KEY_MANUAL = "manual"
        private const val KEY_RESUME = "resume"
        private const val PERIODIC = "periodic-sync"
        private const val NOW = "sync-now"

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        }

        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf(KEY_MANUAL to true))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
        }

        /** After a pause: runs once the paused run has ended (it may still be finishing). */
        fun resumeNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf(KEY_MANUAL to true, KEY_RESUME to true))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
            WorkManager.getInstance(context).cancelUniqueWork(NOW)
        }
    }
}
