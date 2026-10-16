package biz.fesh.syncmusic

import android.app.Application
import biz.fesh.syncmusic.data.Config
import biz.fesh.syncmusic.sync.SyncWorker

class SyncApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        val config = Config.get(this)
        val server = config.server.value
        if (server != null && !server.revoked && config.autoSync.value) {
            SyncWorker.schedulePeriodic(this)
        }
    }
}
