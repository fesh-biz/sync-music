package biz.fesh.syncmusic

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import biz.fesh.syncmusic.shared.Format
import biz.fesh.syncmusic.shared.SyncPhase
import biz.fesh.syncmusic.shared.SyncProgress
import biz.fesh.syncmusic.ui.MainActivity

object Notifications {
    private const val CHANNEL_PROGRESS = "sync_progress"
    private const val CHANNEL_DONE = "sync_done"
    const val ID_PROGRESS = 1001
    private const val ID_DONE = 1002

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Перебіг синхронізації", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Показується, поки музика завантажується з комп'ютера"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Синхронізацію завершено", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Скільки треків додано або видалено"
            },
        )
    }

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun buildProgress(context: Context, progress: SyncProgress?): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle("Синхронізація музики")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openApp(context))
        if (progress == null || progress.totalFiles == 0) {
            builder.setContentText("Підключення до комп'ютера…").setProgress(0, 0, true)
        } else {
            val text = when (progress.phase) {
                SyncPhase.DELETING -> "Видалення треків, видалених на комп'ютері"
                else -> "${Format.number(progress.syncedFiles.toLong())} з ${Format.filesGenitive(progress.totalFiles)} · " +
                    "залишилось ${Format.bytes(progress.remainingBytes)}"
            }
            builder.setContentText(text)
                .setProgress(1000, (progress.fraction * 1000).toInt(), false)
            progress.currentFile?.let { builder.setStyle(NotificationCompat.BigTextStyle().bigText("$text\n${it.substringAfterLast('/')}")) }
        }
        return builder.build()
    }

    fun foregroundInfo(context: Context, progress: SyncProgress?): ForegroundInfo {
        val notification = buildProgress(context, progress)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(ID_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(ID_PROGRESS, notification)
        }
    }

    @SuppressLint("MissingPermission")
    fun showProgress(context: Context, progress: SyncProgress) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        try {
            nm.notify(ID_PROGRESS, buildProgress(context, progress))
        } catch (_: Throwable) {
        }
    }

    fun cancelProgress(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(ID_PROGRESS)
        } catch (_: Throwable) {
        }
    }

    @SuppressLint("MissingPermission")
    fun showDone(context: Context, downloaded: Int, deleted: Int, progress: SyncProgress) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val parts = mutableListOf<String>()
        if (downloaded > 0) parts += "додано ${Format.files(downloaded)}"
        if (deleted > 0) parts += "видалено ${Format.files(deleted)}"
        val title = if (progress.phase == SyncPhase.DONE) "Музику синхронізовано" else "Синхронізацію зупинено"
        val text = parts.joinToString(", ").replaceFirstChar { it.uppercase() }
        val notification = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        try {
            nm.notify(ID_DONE, notification)
        } catch (_: Throwable) {
        }
    }
}
