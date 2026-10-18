package biz.fesh.syncmusic

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import biz.fesh.syncmusic.sync.SyncEngine

/** "Пауза" / "Продовжити" buttons in the notifications. */
class SyncActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PAUSE -> SyncEngine.pause(context.applicationContext)
            ACTION_RESUME -> SyncEngine.resume(context.applicationContext)
        }
    }

    companion object {
        const val ACTION_PAUSE = "biz.fesh.syncmusic.action.PAUSE"
        const val ACTION_RESUME = "biz.fesh.syncmusic.action.RESUME"

        fun intent(context: Context, action: String): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                action.hashCode(),
                Intent(context, SyncActionReceiver::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
