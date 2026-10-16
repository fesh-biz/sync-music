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
            NotificationChannel(CHANNEL_PROGRESS, "Ход синхронизации", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Показывается, пока музыка загружается с компьютера"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Синхронизация завершена", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Сколько треков добавлено или удалено"
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
            .setContentTitle("Синхронизация музыки")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openApp(context))
        if (progress == null || progress.totalFiles == 0) {
            builder.setContentText("Подключение к компьютеру…").setProgress(0, 0, true)
        } else {
            val text = when (progress.phase) {
                SyncPhase.DELETING -> "Удаление треков, удалённых на компьютере"
                else -> "${Format.number(progress.syncedFiles.toLong())} из ${Format.filesGenitive(progress.totalFiles)} · " +
                    "осталось ${Format.bytes(progress.remainingBytes)}"
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
        if (downloaded > 0) parts += "добавлено ${Format.files(downloaded)}"
        if (deleted > 0) parts += "удалено ${Format.files(deleted)}"
        val title = if (progress.phase == SyncPhase.DONE) "Музыка синхронизирована" else "Синхронизация остановлена"
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
