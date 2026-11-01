package biz.fesh.syncmusic.files

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import biz.fesh.syncmusic.R
import biz.fesh.syncmusic.data.Config
import biz.fesh.syncmusic.net.Api
import biz.fesh.syncmusic.net.Discovery
import biz.fesh.syncmusic.net.Http
import biz.fesh.syncmusic.net.UnauthorizedException
import biz.fesh.syncmusic.net.WifiNet
import biz.fesh.syncmusic.shared.FileNames
import biz.fesh.syncmusic.shared.FmCommand
import biz.fesh.syncmusic.shared.FmEntry
import biz.fesh.syncmusic.shared.FmOp
import biz.fesh.syncmusic.shared.FmReply
import biz.fesh.syncmusic.shared.PhoneStorage
import biz.fesh.syncmusic.shared.PhoneVolume
import biz.fesh.syncmusic.shared.Protocol
import biz.fesh.syncmusic.sync.Storage
import biz.fesh.syncmusic.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * Lets the PC app's file window browse this phone and copy files both ways.
 *
 * Runs as a foreground service while the PC wants it: started when the app is opened (or by a sync
 * when the PC's file window is open), it keeps a long poll open to the PC and carries out its commands.
 * It stops by itself a few minutes after the PC's file window is closed.
 */
class FileAccessService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private var transferJob: Job? = null
    private val transfers = Channel<Pair<Api, FmCommand>>(Channel.UNLIMITED)

    @Volatile private var lastWanted = 0L
    @Volatile private var everWanted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Turned off from the notification")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            createChannel(this)
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot run in the foreground", t)
            stopSelf()
            return START_NOT_STICKY
        }
        lastWanted = maxOf(lastWanted, System.currentTimeMillis())
        if (transferJob == null) transferJob = scope.launch { transferLoop() }
        if (loop?.isActive != true) {
            Log.i(TAG, "File access on")
            loop = scope.launch { pollLoop() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "File access off")
        scope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int) {
        // Android 14+: the daily limit of data sync services is used up.
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    private fun idle(): Boolean {
        val limit = if (everWanted) IDLE_AFTER_USE_MS else IDLE_UNUSED_MS
        return System.currentTimeMillis() - lastWanted > limit
    }

    private suspend fun pollLoop() {
        val config = Config.get(this)
        var failures = 0
        while (scope.isActive && !idle()) {
            val server = config.server.value
            if (server == null || server.revoked) break
            val network = WifiNet.find(this)
            if (network == null) {
                delay(5_000)
                continue
            }
            val api = Api(Http.client(network), server.host, server.port, server.token)
            try {
                val poll = api.fmPoll(POLL_SECONDS)
                failures = 0
                if (poll.wanted || poll.commands.isNotEmpty()) {
                    lastWanted = System.currentTimeMillis()
                    everWanted = true
                }
                for (command in poll.commands) {
                    when (command.op) {
                        FmOp.PULL, FmOp.PUSH -> transfers.send(api to command)
                        else -> scope.launch { answer(api, quick(command)) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnauthorizedException) {
                config.updateServer { it.copy(revoked = true) }
                break
            } catch (e: Throwable) {
                failures++
                Log.i(TAG, "Poll failed ($failures): ${e.message}")
                if (failures % 3 == 0) {
                    // The PC may have got another address.
                    val found = try {
                        Discovery.discover(this, network).firstOrNull { it.info.serverId == server.serverId }
                    } catch (_: Throwable) {
                        null
                    }
                    if (found != null && (found.host != server.host || found.info.port != server.port)) {
                        config.updateServer { it.copy(host = found.host, port = found.info.port) }
                    }
                }
                delay(minOf(2_000L * failures, 15_000L))
            }
        }
        stopSelf()
    }

    private suspend fun transferLoop() {
        for ((api, command) in transfers) {
            val reply = try {
                if (command.op == FmOp.PULL) pull(api, command) else push(api, command)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "${command.op} ${command.path} failed", e)
                FmReply(command.id, ok = false, error = e.message ?: "помилка")
            }
            lastWanted = System.currentTimeMillis()
            answer(api, reply)
        }
    }

    private suspend fun answer(api: Api, reply: FmReply) {
        repeat(3) { attempt ->
            try {
                api.fmReply(reply)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.i(TAG, "Reply failed: ${e.message}")
                delay(1_000L * (attempt + 1))
            }
        }
    }

    // ---------------------------------------------------------------- commands

    /** Only folders inside the phone's storages are reachable, never the app's or the system's files. */
    private fun allowed(path: String): Boolean {
        if (!path.startsWith("/") || path.split('/').any { it == ".." || it == "." }) return false
        return Storage.volumes(this).any { PhoneStorage.inside(path.trimEnd('/'), it.root.path) }
    }

    private fun quick(command: FmCommand): FmReply = try {
        when (command.op) {
            FmOp.LIST -> list(command)
            FmOp.TREE -> tree(command)
            FmOp.MKDIR -> mkdir(command)
            FmOp.DELETE -> delete(command)
            FmOp.RENAME -> rename(command)
            FmOp.PRUNE -> prune(command)
            else -> FmReply(command.id, ok = false, error = "unsupported")
        }
    } catch (e: Throwable) {
        FmReply(command.id, ok = false, error = e.message ?: "помилка")
    }

    private fun list(command: FmCommand): FmReply {
        if (command.path.isEmpty()) {
            val volumes = Storage.volumes(this).map {
                PhoneVolume(
                    id = it.id,
                    label = it.label,
                    root = it.root.path,
                    removable = it.removable,
                    appPrivate = it.appPrivate,
                    freeBytes = it.freeBytes,
                    totalBytes = it.totalBytes,
                )
            }
            return FmReply(command.id, volumes = volumes)
        }
        if (!allowed(command.path)) return FmReply(command.id, ok = false, error = "Ця папка недоступна")
        val files = File(command.path).listFiles() ?: return FmReply(command.id, ok = false, error = "Немає доступу до папки")
        val entries = files
            .filter { !it.name.startsWith(".") }
            .map { FmEntry(it.name, it.isDirectory, if (it.isFile) it.length() else 0, it.lastModified()) }
        return FmReply(command.id, entries = entries)
    }

    private fun tree(command: FmCommand): FmReply {
        if (!allowed(command.path)) return FmReply(command.id, ok = false, error = "Ця папка недоступна")
        val root = File(command.path)
        if (!root.isDirectory) return FmReply(command.id, ok = false, error = "Папку не знайдено")
        val entries = ArrayList<FmEntry>()
        var truncated = false
        fun walk(dir: File, prefix: String) {
            val children = dir.listFiles() ?: return
            for (child in children.sortedBy { it.name.lowercase() }) {
                if (child.name.startsWith(".")) continue
                val rel = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                if (child.isDirectory) {
                    walk(child, rel)
                } else {
                    if (entries.size >= MAX_TREE) {
                        truncated = true
                        return
                    }
                    entries += FmEntry(rel, false, child.length(), child.lastModified())
                }
            }
        }
        walk(root, "")
        return FmReply(command.id, entries = entries, truncated = truncated)
    }

    private fun mkdir(command: FmCommand): FmReply {
        val name = command.path.trimEnd('/').substringAfterLast('/')
        if (!allowed(command.path) || !PhoneStorage.isValidName(name)) return FmReply(command.id, ok = false, error = "Недопустима назва папки")
        val dir = File(command.path)
        if (dir.exists()) return FmReply(command.id, ok = false, error = "Така назва вже є")
        return if (dir.mkdirs()) FmReply(command.id) else FmReply(command.id, ok = false, error = "Не вдалося створити папку")
    }

    /** A storage's own root is never deleted, renamed or pruned. */
    private fun isVolumeRoot(path: String): Boolean =
        Storage.volumes(this).any { it.root.path.trimEnd('/') == path.trimEnd('/') }

    private fun delete(command: FmCommand): FmReply {
        if (!allowed(command.path) || isVolumeRoot(command.path)) return FmReply(command.id, ok = false, error = "Цю папку не можна видалити")
        val target = File(command.path)
        if (!target.exists()) return FmReply(command.id)
        val removed = ArrayList<String>()
        val ok = if (target.isDirectory) {
            var all = true
            target.walkBottomUp().forEach { f ->
                if (f.isFile) {
                    if (f.delete()) removed += f.path else all = false
                } else if (!f.delete()) {
                    all = false
                }
            }
            all
        } else {
            target.delete().also { if (it) removed += target.path }
        }
        Storage.scan(this, removed.take(MAX_SCAN))
        Log.i(TAG, "Deleted ${command.path} (${removed.size} files)")
        return if (ok) FmReply(command.id) else FmReply(command.id, ok = false, error = "Не все вдалося видалити")
    }

    private fun rename(command: FmCommand): FmReply {
        val name = command.name
        if (!allowed(command.path) || isVolumeRoot(command.path) || !PhoneStorage.isValidName(name)) {
            return FmReply(command.id, ok = false, error = "Недопустима назва")
        }
        val source = File(command.path)
        if (!source.exists()) return FmReply(command.id, ok = false, error = "Файл не знайдено на телефоні")
        val target = File(source.parentFile, name)
        if (target.exists() && !target.path.equals(source.path, ignoreCase = true)) return FmReply(command.id, ok = false, error = "Така назва вже є")
        if (!source.renameTo(target)) return FmReply(command.id, ok = false, error = "Не вдалося перейменувати")
        if (target.isFile) Storage.scan(this, listOf(source.path, target.path))
        return FmReply(command.id, savedAs = name)
    }

    private fun prune(command: FmCommand): FmReply {
        if (!allowed(command.path)) return FmReply(command.id, ok = false, error = "Ця папка недоступна")
        val root = File(command.path)
        if (!root.isDirectory) return FmReply(command.id)
        val keepRoot = isVolumeRoot(command.path)
        root.walkBottomUp().forEach { dir ->
            if (dir.isDirectory && (dir != root || !keepRoot)) {
                // Only folders left empty (or holding just the leftovers of copying) go.
                File(dir, Protocol.TEMP_DIR).takeIf { it.isDirectory && it.list().isNullOrEmpty() }?.delete()
                if (dir.list().isNullOrEmpty()) dir.delete()
            }
        }
        return FmReply(command.id)
    }

    /** PC → phone. Nothing is overwritten: a different file under a taken name is saved as "name (з комп'ютера).ext". */
    private fun pull(api: Api, command: FmCommand): FmReply {
        val name = command.name
        if (!allowed(command.path) || name.isEmpty() || name.contains('/') || name == "." || name == "..") {
            return FmReply(command.id, ok = false, error = "Ця папка телефона недоступна")
        }
        val dir = File(command.path)
        dir.mkdirs()
        if (!dir.isDirectory) return FmReply(command.id, ok = false, error = "Не вдалося створити папку на телефоні")
        val target = File(dir, name)
        if (target.isFile && FileNames.sameFile(target.length(), target.lastModified(), command.size, command.modified)) {
            return FmReply(command.id, skipped = true, savedAs = name)
        }
        val free = Storage.freeBytes(dir)
        if (free in 0 until command.size + RESERVE_BYTES) return FmReply(command.id, ok = false, error = "На телефоні недостатньо місця")
        val tempDir = File(dir, Protocol.TEMP_DIR)
        val part = File(tempDir, "fm-${command.id}.part")
        try {
            tempDir.mkdirs()
            api.fmDownload(command.id, part)
            if (part.length() != command.size) throw IOException("Файл отримано не повністю")
            val dest = destination(target, part) ?: return FmReply(command.id, skipped = true, savedAs = name)
            if (!part.renameTo(dest)) {
                part.copyTo(dest)
            }
            if (command.modified > 0) dest.setLastModified(command.modified)
            Storage.scan(this, listOf(dest.path))
            return FmReply(command.id, savedAs = dest.name)
        } finally {
            part.delete()
            tempDir.delete() // only when empty
        }
    }

    private fun destination(target: File, received: File): File? {
        if (!target.exists()) return target
        if (sameContent(target, received)) return null
        var n = 1
        while (true) {
            val candidate = File(target.parentFile, FileNames.copyName(target.name, "з комп'ютера", n))
            if (!candidate.exists()) return candidate
            if (sameContent(candidate, received)) return null
            n++
        }
    }

    private fun sameContent(a: File, b: File): Boolean {
        if (!a.isFile || !b.isFile || a.length() != b.length()) return false
        a.inputStream().buffered(256 * 1024).use { x ->
            b.inputStream().buffered(256 * 1024).use { y ->
                val bx = ByteArray(64 * 1024)
                val by = ByteArray(64 * 1024)
                while (true) {
                    val nx = x.readNBytesCompat(bx)
                    val ny = y.readNBytesCompat(by)
                    if (nx != ny) return false
                    for (i in 0 until nx) if (bx[i] != by[i]) return false
                    if (nx == 0) return true
                }
            }
        }
    }

    private fun java.io.InputStream.readNBytesCompat(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    /** Phone → PC. */
    private fun push(api: Api, command: FmCommand): FmReply {
        val file = File(command.path)
        if (!allowed(command.path) || !file.isFile) return FmReply(command.id, ok = false, error = "Файл не знайдено на телефоні")
        api.fmUpload(command.id, file)
        return FmReply(command.id)
    }

    // ---------------------------------------------------------------- notification

    private fun notification(): Notification {
        val server = Config.get(this).server.value?.serverName ?: "комп'ютер"
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, FileAccessService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle("Доступ до файлів з комп'ютера")
            .setContentText("«$server» може переглядати й копіювати файли телефона")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_stat_sync, "Вимкнути", stop)
            .build()
    }

    companion object {
        private const val TAG = "FileAccess"
        private const val CHANNEL = "file_access"
        private const val NOTIFICATION_ID = 1004
        private const val ACTION_STOP = "biz.fesh.syncmusic.FILE_ACCESS_STOP"
        private const val POLL_SECONDS = 20
        private const val MAX_TREE = 50_000
        private const val MAX_SCAN = 2_000
        private const val RESERVE_BYTES = 20L * 1024 * 1024

        /** Opened without the PC asking: stays on for a while, in case the file window is opened next. */
        private const val IDLE_UNUSED_MS = 3 * 60_000L

        /** After the PC's file window is closed. */
        private const val IDLE_AFTER_USE_MS = 5 * 60_000L

        private fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Доступ до файлів з комп'ютера", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Показується, поки комп'ютер може переглядати й копіювати файли телефона"
                    setShowBadge(false)
                },
            )
        }

        /** Asks the PC whether its file window wants this phone (false when the PC cannot be reached). */
        suspend fun pcWants(context: Context): Boolean = kotlinx.coroutines.withContext(Dispatchers.IO) {
            val server = Config.get(context).server.value ?: return@withContext false
            if (server.revoked) return@withContext false
            val network = WifiNet.find(context) ?: return@withContext false
            try {
                Api(Http.client(network), server.host, server.port, server.token).fmState()?.wanted == true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                false
            }
        }

        /** Turns file access on (or keeps it on). Android may refuse while the app is in the background. */
        fun start(context: Context) {
            val config = Config.get(context)
            val server = config.server.value ?: return
            if (server.revoked || !Storage.hasAccess(context)) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, FileAccessService::class.java))
            } catch (t: Throwable) {
                Log.i(TAG, "Cannot start file access now: ${t.message}")
            }
        }
    }
}
