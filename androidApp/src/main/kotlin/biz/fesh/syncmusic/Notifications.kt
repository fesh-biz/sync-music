package biz.fesh.syncmusic

import biz.fesh.syncmusic.shared.tr
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
    private const val ID_PAUSED = 1003

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, tr("Перебіг синхронізації", "Ход синхронизации"), NotificationManager.IMPORTANCE_LOW).apply {
                description = tr("Показується, поки файли передаються між телефоном і комп'ютером", "Показывается, пока файлы передаются между телефоном и компьютером")
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, tr("Синхронізацію завершено", "Синхронизация завершена"), NotificationManager.IMPORTANCE_LOW).apply {
                description = tr("Скільки файлів отримано, надіслано або видалено", "Сколько файлов получено, отправлено или удалено")
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
            .setContentTitle(tr("Синхронізація файлів", "Синхронизация файлов"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openApp(context))
            .addAction(R.drawable.ic_stat_sync, tr("Пауза", "Пауза"), SyncActionReceiver.intent(context, SyncActionReceiver.ACTION_PAUSE))
        if (progress == null || progress.totalFiles == 0) {
            builder.setContentText(tr("Підключення до комп'ютера…", "Подключение к компьютеру…")).setProgress(0, 0, true)
        } else {
            val text = when (progress.phase) {
                SyncPhase.DELETING -> tr("Видалення файлів, видалених у джерелі", "Удаление файлов, удалённых в источнике")
                SyncPhase.UPLOADING -> tr("Надсилання на комп'ютер · ${Format.number(progress.syncedFiles.toLong())} з ${Format.filesGenitive(progress.totalFiles)}", "Отправка на компьютер · ${Format.number(progress.syncedFiles.toLong())} из ${Format.filesGenitive(progress.totalFiles)}")
                else -> tr("${Format.number(progress.syncedFiles.toLong())} з ${Format.filesGenitive(progress.totalFiles)} · ", "${Format.number(progress.syncedFiles.toLong())} из ${Format.filesGenitive(progress.totalFiles)} · ") +
                    tr("залишилось ${Format.bytes(progress.remainingBytes)}", "осталось ${Format.bytes(progress.remainingBytes)}")
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

    /** "Синхронізацію призупинено" with a "Продовжити" button; stays until resumed. */
    @SuppressLint("MissingPermission")
    fun showPaused(context: Context, progress: SyncProgress) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val text = if (progress.totalFiles > 0) {
            tr("${Format.number(progress.syncedFiles.toLong())} з ${Format.filesGenitive(progress.totalFiles)} · ", "${Format.number(progress.syncedFiles.toLong())} из ${Format.filesGenitive(progress.totalFiles)} · ") +
                tr("залишилось ${Format.bytes(progress.remainingBytes)}", "осталось ${Format.bytes(progress.remainingBytes)}")
        } else {
            tr("Не синхронізується, доки ви не продовжите", "Не синхронизируется, пока вы не продолжите")
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle(tr("Синхронізацію призупинено", "Синхронизация приостановлена"))
            .setContentText(text)
            .setSilent(true)
            .setContentIntent(openApp(context))
            .addAction(R.drawable.ic_stat_sync, tr("Продовжити", "Продолжить"), SyncActionReceiver.intent(context, SyncActionReceiver.ACTION_RESUME))
            .build()
        try {
            nm.notify(ID_PAUSED, notification)
        } catch (_: Throwable) {
        }
    }

    fun cancelPaused(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(ID_PAUSED)
        } catch (_: Throwable) {
        }
    }

    @SuppressLint("MissingPermission")
    fun showDone(context: Context, progress: SyncProgress) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val parts = mutableListOf<String>()
        if (progress.downloadedThisRun > 0) parts += tr("отримано ${Format.files(progress.downloadedThisRun)}", "получено ${Format.files(progress.downloadedThisRun)}")
        if (progress.uploadedThisRun > 0) parts += tr("надіслано на ПК ${Format.files(progress.uploadedThisRun)}", "отправлено на ПК ${Format.files(progress.uploadedThisRun)}")
        if (progress.deletedThisRun > 0) parts += tr("видалено ${Format.files(progress.deletedThisRun)}", "удалено ${Format.files(progress.deletedThisRun)}")
        val title = if (progress.phase == SyncPhase.DONE) tr("Файли синхронізовано", "Файлы синхронизированы") else tr("Синхронізацію зупинено", "Синхронизация остановлена")
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
