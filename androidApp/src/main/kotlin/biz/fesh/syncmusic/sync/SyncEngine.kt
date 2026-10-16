package biz.fesh.syncmusic.sync

import android.content.Context
import biz.fesh.syncmusic.BuildConfig
import biz.fesh.syncmusic.Notifications
import biz.fesh.syncmusic.data.Config
import biz.fesh.syncmusic.data.LastSync
import biz.fesh.syncmusic.data.PairedServer
import biz.fesh.syncmusic.net.Api
import biz.fesh.syncmusic.net.Discovery
import biz.fesh.syncmusic.net.FileGoneException
import biz.fesh.syncmusic.net.Http
import biz.fesh.syncmusic.net.StoppedException
import biz.fesh.syncmusic.net.UnauthorizedException
import biz.fesh.syncmusic.net.WifiNet
import biz.fesh.syncmusic.shared.Manifest
import biz.fesh.syncmusic.shared.ManifestEntry
import biz.fesh.syncmusic.shared.ServerInfo
import biz.fesh.syncmusic.shared.SyncPhase
import biz.fesh.syncmusic.shared.SyncProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/** What the UI shows while (or right after) a sync runs in this process. */
data class EngineState(
    val running: Boolean = false,
    val progress: SyncProgress? = null,
    /** Short status such as "Компьютер не найден"; null when there is nothing to say. */
    val note: String? = null,
)

enum class Outcome { NOT_PAIRED, NO_ACCESS, NO_WIFI, NOT_FOUND, UNAUTHORIZED, DONE, FAILED, STOPPED, BUSY }

object SyncEngine {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    internal fun publish(state: EngineState) {
        _state.value = state
    }

    suspend fun run(context: Context, manual: Boolean, goForeground: suspend () -> Unit): Outcome {
        if (!mutex.tryLock()) return Outcome.BUSY
        try {
            _state.value = EngineState(running = true, note = "Поиск компьютера…")
            return Session(context.applicationContext, manual, goForeground).run()
        } finally {
            _state.update { it.copy(running = false) }
            mutex.unlock()
        }
    }
}

private class Session(
    private val ctx: Context,
    private val manual: Boolean,
    private val goForeground: suspend () -> Unit,
) {
    private val config = Config.get(ctx)

    // Live counters, read by the reporter once a second.
    @Volatile private var phase = SyncPhase.CONNECTING
    @Volatile private var totalFiles = 0
    @Volatile private var totalBytes = 0L
    @Volatile private var syncedFiles = 0
    @Volatile private var syncedBytes = 0L
    @Volatile private var currentFile: String? = null
    @Volatile private var currentBytes = 0L
    @Volatile private var downloaded = 0
    @Volatile private var deleted = 0
    @Volatile private var message: String? = null
    @Volatile private var speed = 0L
    @Volatile private var freeSpace = -1L
    private val transferred = AtomicLong(0)
    private var foregroundRequested = false

    private fun snapshot(): SyncProgress = SyncProgress(
        phase = phase,
        totalFiles = totalFiles,
        totalBytes = totalBytes,
        syncedFiles = syncedFiles,
        syncedBytes = syncedBytes + currentBytes,
        currentFile = currentFile,
        bytesPerSecond = speed,
        downloadedThisRun = downloaded,
        deletedThisRun = deleted,
        message = message,
        freeSpaceBytes = freeSpace,
        appVersionCode = BuildConfig.VERSION_CODE,
        timestamp = System.currentTimeMillis(),
    )

    private fun note(outcome: Outcome, text: String?): Outcome {
        SyncEngine.publish(EngineState(running = false, progress = null, note = text))
        return outcome
    }

    private suspend fun foreground() {
        if (foregroundRequested) return
        foregroundRequested = true
        goForeground()
    }

    suspend fun run(): Outcome {
        val server = config.server.value ?: return note(Outcome.NOT_PAIRED, null)
        if (server.revoked) return note(Outcome.UNAUTHORIZED, "Компьютер отключил этот телефон")
        if (!Storage.hasAccess(ctx)) return note(Outcome.NO_ACCESS, "Нет доступа к памяти телефона — откройте приложение и разрешите доступ")
        val network = WifiNet.find(ctx) ?: return note(Outcome.NO_WIFI, "Нет подключения к Wi-Fi")
        val http = Http.client(network)

        val api = resolve(server, http, network)
            ?: return note(Outcome.NOT_FOUND, "Компьютер «${server.serverName}» не найден. Запустите Sync Music на ПК.")

        phase = SyncPhase.CONNECTING
        SyncEngine.publish(EngineState(running = true, progress = snapshot()))

        val manifest: Manifest = try {
            withContext(Dispatchers.IO) { api.manifest() }
        } catch (e: UnauthorizedException) {
            config.updateServer { it.copy(revoked = true) }
            return note(Outcome.UNAUTHORIZED, "Компьютер отключил этот телефон. Подключитесь заново.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return finish(api, SyncPhase.ERROR, "Не удалось получить список файлов: ${e.message}", Outcome.FAILED)
        }

        return sync(api, server, manifest)
    }

    private suspend fun resolve(server: PairedServer, http: OkHttpClient, network: android.net.Network): Api? =
        withContext(Dispatchers.IO) {
            fun probe(host: String, port: Int): ServerInfo? = try {
                Api(http, host, port).info().takeIf { it.serverId == server.serverId }
            } catch (_: Throwable) {
                null
            }

            var host = server.host
            var port = server.port
            var info = probe(host, port)
            if (info == null) {
                val found = Discovery.discover(ctx, network).firstOrNull { it.info.serverId == server.serverId }
                if (found != null) {
                    host = found.host
                    port = found.info.port
                    info = found.info
                }
            }
            val resolved = info ?: return@withContext null
            if (host != server.host || port != server.port ||
                resolved.apkVersionCode != server.apkVersionCode || resolved.serverName != server.serverName
            ) {
                config.updateServer {
                    it.copy(host = host, port = port, apkVersionCode = resolved.apkVersionCode, serverName = resolved.serverName)
                }
            }
            Api(http, host, port, server.token)
        }

    private suspend fun sync(api: Api, server: PairedServer, manifest: Manifest): Outcome {
        val root = Storage.musicRoot()
        if (!root.isDirectory && !root.mkdirs()) {
            return finish(api, SyncPhase.ERROR, "Не удалось создать папку Music/${Storage.FOLDER_NAME}", Outcome.FAILED)
        }
        val tmpDir = File(root, ".syncmusic")
        tmpDir.mkdirs()
        try {
            File(tmpDir, ".nomedia").let { if (!it.exists()) it.createNewFile() }
        } catch (_: Throwable) {
        }

        val db = SyncedDb.load(ctx, server.serverId)
        val scanBatch = ArrayList<String>()

        try {
            return coroutineScope {
                val reporter = launch(Dispatchers.IO) { reportLoop(api) }
                try {
                    // ---- compare
                    phase = SyncPhase.COMPARING
                    val pending = ArrayList<Pair<ManifestEntry, File>>()
                    val serverPaths = HashSet<String>(manifest.files.size * 2)
                    withContext(Dispatchers.IO) {
                        totalFiles = manifest.files.size
                        totalBytes = manifest.files.sumOf { it.size }
                        var okFiles = 0
                        var okBytes = 0L
                        for (entry in manifest.files) {
                            val dest = Storage.resolve(root, entry.path) ?: continue
                            serverPaths += entry.path
                            val record = db[entry.path]
                            val upToDate = dest.isFile && dest.length() == entry.size &&
                                (record == null || (record.s == entry.size && record.m == entry.modified))
                            if (upToDate) {
                                if (record == null) db.put(entry.path, SyncedRecord(entry.size, entry.modified))
                                okFiles++
                                okBytes += entry.size
                            } else {
                                pending += entry to dest
                            }
                        }
                        syncedFiles = okFiles
                        syncedBytes = okBytes
                        freeSpace = Storage.freeBytes(root)
                    }

                    // ---- delete what disappeared on the PC
                    val stale = db.paths.filter { it !in serverPaths }
                    if (stale.isNotEmpty()) {
                        if (manifest.files.isEmpty()) {
                            // Safety net: never wipe the phone because the PC reported an empty library.
                            message = "На компьютере нет музыки — удаление пропущено"
                        } else if (config.deleteRemoved.value) {
                            foreground()
                            phase = SyncPhase.DELETING
                            withContext(Dispatchers.IO) {
                                val removed = ArrayList<String>()
                                for (path in stale) {
                                    val file = Storage.resolve(root, path)
                                    if (file != null && file.isFile && file.delete()) {
                                        deleted++
                                        removed += file.absolutePath
                                        Storage.pruneEmptyParents(root, file)
                                    }
                                    db.remove(path)
                                }
                                db.save()
                                Storage.scan(ctx, removed)
                            }
                        } else {
                            stale.forEach(db::remove)
                            db.save()
                        }
                    }

                    // ---- download
                    if (pending.isNotEmpty()) {
                        foreground()
                        phase = SyncPhase.DOWNLOADING
                        pending.sortBy { it.first.path.lowercase() }
                        val outcome = withContext(Dispatchers.IO) { downloadAll(api, root, tmpDir, pending, db, scanBatch) }
                        if (outcome != null) return@coroutineScope outcome
                    }

                    reporter.cancel()
                    val done = if (message != null && syncedFiles < totalFiles) SyncPhase.ERROR else SyncPhase.DONE
                    finish(api, done, message, Outcome.DONE)
                } finally {
                    reporter.cancel()
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                finish(api, SyncPhase.STOPPED, "Синхронизация прервана, продолжится позже", Outcome.STOPPED)
            }
            throw e
        } catch (e: UnauthorizedException) {
            config.updateServer { it.copy(revoked = true) }
            return finish(api, SyncPhase.ERROR, "Компьютер отключил этот телефон", Outcome.UNAUTHORIZED, report = false)
        } catch (e: Throwable) {
            return finish(api, SyncPhase.ERROR, "Связь с компьютером потеряна: ${e.message}", Outcome.FAILED)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                db.save()
                Storage.scan(ctx, scanBatch.toList())
            }
        }
    }

    /** Returns a final [Outcome] if the run has to stop early, null when everything was processed. */
    private suspend fun downloadAll(
        api: Api,
        root: File,
        tmpDir: File,
        pending: List<Pair<ManifestEntry, File>>,
        db: SyncedDb,
        scanBatch: MutableList<String>,
    ): Outcome? {
        val job = kotlin.coroutines.coroutineContext[Job]
        val isStopped = { job?.isActive == false }

        // Drop leftovers of files that are no longer wanted.
        val wantedParts = pending.mapTo(HashSet()) { partName(it.first.path) }
        tmpDir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".part") && f.name !in wantedParts) f.delete()
        }

        var skipped = 0
        for ((entry, dest) in pending) {
            if (isStopped()) throw CancellationException("stopped")
            val part = File(tmpDir, partName(entry.path))
            val have = if (part.isFile) part.length() else 0L
            val free = Storage.freeBytes(root)
            freeSpace = free
            if (free >= 0 && free < entry.size - have + RESERVE_BYTES) {
                message = "Недостаточно места на телефоне"
                return null
            }
            currentFile = entry.path
            currentBytes = have.coerceAtMost(entry.size)

            var attempt = 0
            var gone = false
            while (true) {
                try {
                    api.download(
                        entry,
                        part,
                        onProgress = { got ->
                            val delta = got - currentBytes
                            if (delta > 0) transferred.addAndGet(delta)
                            currentBytes = got
                        },
                        isStopped = isStopped,
                    )
                    if (part.length() != entry.size) {
                        part.delete()
                        throw IOException("размер файла не совпал")
                    }
                    break
                } catch (e: FileGoneException) {
                    part.delete()
                    gone = true
                    break
                } catch (e: StoppedException) {
                    throw CancellationException("stopped")
                } catch (e: UnauthorizedException) {
                    throw e
                } catch (e: IOException) {
                    attempt++
                    if (attempt >= 3 || isStopped()) throw e
                    delay(2_000L * attempt)
                }
            }
            if (gone) {
                skipped++
                currentBytes = 0
                continue
            }

            dest.parentFile?.mkdirs()
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
            dest.setLastModified(entry.modified)
            db.put(entry.path, SyncedRecord(entry.size, entry.modified))

            currentBytes = 0
            syncedBytes += entry.size
            syncedFiles++
            downloaded++
            scanBatch += dest.absolutePath
            if (scanBatch.size >= 25) {
                Storage.scan(ctx, scanBatch.toList())
                scanBatch.clear()
            }
            if (downloaded % 10 == 0) db.save()
        }
        currentFile = null
        if (skipped > 0 && message == null) message = "Пропущено файлов: $skipped (изменились на компьютере)"
        return null
    }

    private suspend fun reportLoop(api: Api) {
        var lastTotal = transferred.get()
        var lastTime = System.currentTimeMillis()
        while (kotlin.coroutines.coroutineContext.isActive) {
            delay(1_000)
            val now = System.currentTimeMillis()
            val total = transferred.get()
            val dt = (now - lastTime).coerceAtLeast(1)
            val instant = (total - lastTotal) * 1000 / dt
            speed = if (speed == 0L) instant else (speed * 0.7 + instant * 0.3).toLong()
            lastTotal = total
            lastTime = now

            val progress = snapshot()
            SyncEngine.publish(EngineState(running = true, progress = progress))
            if (foregroundRequested) Notifications.showProgress(ctx, progress)
            try {
                api.sendProgress(progress)
            } catch (_: Throwable) {
            }
        }
    }

    private suspend fun finish(
        api: Api,
        finalPhase: SyncPhase,
        text: String?,
        outcome: Outcome,
        report: Boolean = true,
    ): Outcome {
        phase = finalPhase
        message = text
        currentFile = null
        speed = 0
        val progress = snapshot()
        SyncEngine.publish(EngineState(running = false, progress = progress))
        config.setLastSync(
            LastSync(
                at = System.currentTimeMillis(),
                phase = finalPhase,
                totalFiles = progress.totalFiles,
                totalBytes = progress.totalBytes,
                syncedFiles = progress.syncedFiles,
                syncedBytes = progress.syncedBytes,
                downloaded = downloaded,
                deleted = deleted,
                message = text,
            ),
        )
        if (report) {
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(5_000) {
                    try {
                        api.sendProgress(progress)
                    } catch (_: Throwable) {
                    }
                }
            }
        }
        Notifications.cancelProgress(ctx)
        if (downloaded > 0 || deleted > 0) Notifications.showDone(ctx, downloaded, deleted, progress)
        return outcome
    }

    companion object {
        private const val RESERVE_BYTES = 200L * 1024 * 1024

        private fun partName(path: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(path.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) } + ".part"
        }
    }
}
