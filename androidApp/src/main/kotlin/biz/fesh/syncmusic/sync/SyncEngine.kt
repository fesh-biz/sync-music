package biz.fesh.syncmusic.sync

import android.content.Context
import android.util.Log
import biz.fesh.syncmusic.BuildConfig
import biz.fesh.syncmusic.Notifications
import biz.fesh.syncmusic.data.Config
import biz.fesh.syncmusic.data.FolderMapping
import biz.fesh.syncmusic.data.LastSync
import biz.fesh.syncmusic.data.PairedServer
import biz.fesh.syncmusic.net.Api
import biz.fesh.syncmusic.net.Discovery
import biz.fesh.syncmusic.net.FileGoneException
import biz.fesh.syncmusic.net.FolderUnavailableException
import biz.fesh.syncmusic.net.Http
import biz.fesh.syncmusic.net.StoppedException
import biz.fesh.syncmusic.net.UnauthorizedException
import biz.fesh.syncmusic.net.UploadResult
import biz.fesh.syncmusic.net.WifiNet
import biz.fesh.syncmusic.shared.Direction
import biz.fesh.syncmusic.shared.FolderInfo
import biz.fesh.syncmusic.shared.FolderProgress
import biz.fesh.syncmusic.shared.ManifestEntry
import biz.fesh.syncmusic.shared.Protocol
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
    /** Short status such as "Комп'ютер не знайдено"; null when there is nothing to say. */
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
            _state.value = EngineState(running = true, note = "Пошук комп'ютера…")
            return Session(context.applicationContext, goForeground).run()
        } finally {
            _state.update { it.copy(running = false) }
            mutex.unlock()
        }
    }
}

/** Gives every PC folder a phone folder: PC → phone ones get a default, phone → PC ones wait for the user. */
object FolderMappings {
    fun ensure(context: Context, config: Config, folders: List<FolderInfo>) {
        val taken = config.mappings.value.values.mapNotNull { it.localPath }.toMutableSet()
        for (folder in folders) {
            val existing = config.mappings.value[folder.id]
            if (existing != null && (existing.localPath != null || folder.direction == Direction.TO_PC)) continue
            if (folder.direction == Direction.TO_PHONE) {
                val path = Storage.defaultFolderPath(context, folder, config.storageId.value, taken)
                taken += path
                config.setMapping(folder.id, FolderMapping(path, existing?.enabled ?: true))
            } else {
                config.setMapping(folder.id, FolderMapping(null, true))
            }
        }
    }
}

/** Live counters of one folder, read by the reporter once a second. */
private class FolderState(val info: FolderInfo, val mapping: FolderMapping, val display: String?) {
    @Volatile var totalFiles = 0
    @Volatile var totalBytes = 0L
    @Volatile var syncedFiles = 0
    @Volatile var syncedBytes = 0L
    @Volatile var message: String? = null

    fun progress() = FolderProgress(
        id = info.id,
        name = info.name,
        direction = info.direction,
        totalFiles = totalFiles,
        totalBytes = totalBytes,
        syncedFiles = syncedFiles,
        syncedBytes = syncedBytes,
        localPath = display,
        enabled = mapping.enabled,
        message = message,
    )
}

private class DownPlan(
    val state: FolderState,
    val root: File,
    val db: SyncedDb,
    val downloads: List<Pair<ManifestEntry, File>>,
    val stale: List<String>,
    val pcEmpty: Boolean,
)

private class UpPlan(
    val state: FolderState,
    val root: File,
    val db: SyncedDb,
    val uploads: List<LocalFile>,
)

private class Session(
    private val ctx: Context,
    private val goForeground: suspend () -> Unit,
) {
    private val config = Config.get(ctx)

    @Volatile private var phase = SyncPhase.CONNECTING
    @Volatile private var states: List<FolderState> = emptyList()
    @Volatile private var currentFile: String? = null
    @Volatile private var currentBytes = 0L
    @Volatile private var downloaded = 0
    @Volatile private var uploaded = 0
    @Volatile private var deleted = 0
    @Volatile private var message: String? = null
    @Volatile private var speed = 0L
    @Volatile private var freeSpace = -1L
    private val transferred = AtomicLong(0)
    private var foregroundRequested = false
    private val startedAt = System.currentTimeMillis()
    private val dbs = ArrayList<SyncedDb>()
    private val scanBatch = ArrayList<String>()

    private fun elapsed() = System.currentTimeMillis() - startedAt

    private fun snapshot(): SyncProgress {
        val list = states
        val active = list.filter { it.mapping.enabled }
        return SyncProgress(
            phase = phase,
            totalFiles = active.sumOf { it.totalFiles },
            totalBytes = active.sumOf { it.totalBytes },
            syncedFiles = active.sumOf { it.syncedFiles },
            syncedBytes = active.sumOf { it.syncedBytes } + currentBytes,
            currentFile = currentFile,
            bytesPerSecond = speed,
            downloadedThisRun = downloaded,
            uploadedThisRun = uploaded,
            deletedThisRun = deleted,
            message = message,
            freeSpaceBytes = freeSpace,
            folders = list.map { it.progress() },
            appVersionCode = BuildConfig.VERSION_CODE,
            timestamp = System.currentTimeMillis(),
        )
    }

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
        if (server.revoked) return note(Outcome.UNAUTHORIZED, "Комп'ютер відключив цей телефон")
        if (!Storage.hasAccess(ctx)) return note(Outcome.NO_ACCESS, "Немає доступу до пам'яті телефона — відкрийте застосунок і дозвольте доступ")
        val network = WifiNet.find(ctx) ?: return note(Outcome.NO_WIFI, "Немає підключення до Wi-Fi")
        val http = Http.client(network)

        val api = resolve(server, http, network)
            ?: return note(Outcome.NOT_FOUND, "Комп'ютер «${server.serverName}» не знайдено. Запустіть Sync Music на ПК.")

        phase = SyncPhase.CONNECTING
        SyncEngine.publish(EngineState(running = true, progress = snapshot()))

        val folders = try {
            withContext(Dispatchers.IO) { api.folders() }
        } catch (e: UnauthorizedException) {
            config.updateServer { it.copy(revoked = true) }
            return note(Outcome.UNAUTHORIZED, "Комп'ютер відключив цей телефон. Підключіться знову.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return finish(api, SyncPhase.ERROR, "Не вдалося отримати список папок: ${e.message}", Outcome.FAILED)
        }
        config.setFolders(folders)
        FolderMappings.ensure(ctx, config, folders)
        val volumes = Storage.volumes(ctx)
        states = folders.map { info ->
            val mapping = config.mappings.value[info.id] ?: FolderMapping()
            FolderState(info, mapping, Storage.describe(volumes, mapping.localPath))
        }
        Log.i(TAG, "Folders: ${folders.size} in ${elapsed()} ms")
        return sync(api, server)
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

    // ------------------------------------------------------------------ main flow

    private suspend fun sync(api: Api, server: PairedServer): Outcome {
        try {
            return coroutineScope {
                val reporter = launch(Dispatchers.IO) { reportLoop(api) }
                try {
                    phase = SyncPhase.COMPARING
                    val downPlans = ArrayList<DownPlan>()
                    val upPlans = ArrayList<UpPlan>()
                    for (state in states) {
                        if (!state.mapping.enabled) continue
                        val path = state.mapping.localPath
                        if (path == null) {
                            state.message = "Виберіть папку на телефоні"
                            continue
                        }
                        try {
                            withContext(Dispatchers.IO) {
                                when (state.info.direction) {
                                    Direction.TO_PHONE -> planDown(api, server, state, File(path))?.let(downPlans::add)
                                    Direction.TO_PC -> planUp(api, server, state, File(path))?.let(upPlans::add)
                                }
                            }
                        } catch (e: FolderUnavailableException) {
                            state.message = e.message
                        }
                    }
                    Log.i(TAG, "Compared ${states.size} folders after ${elapsed()} ms")

                    withContext(Dispatchers.IO) {
                        for (plan in downPlans) deleteLocal(plan)
                    }

                    for (plan in downPlans) {
                        if (plan.downloads.isEmpty()) continue
                        foreground()
                        phase = SyncPhase.DOWNLOADING
                        val stop = withContext(Dispatchers.IO) { downloadAll(api, plan) }
                        if (stop) break
                    }
                    for (plan in upPlans) {
                        if (plan.uploads.isEmpty()) continue
                        foreground()
                        phase = SyncPhase.UPLOADING
                        withContext(Dispatchers.IO) { uploadAll(api, plan) }
                    }

                    reporter.cancel()
                    val problems = states.any { it.mapping.enabled && it.message != null && it.syncedFiles < it.totalFiles }
                    val done = if (message != null || problems) SyncPhase.ERROR else SyncPhase.DONE
                    finish(api, done, message, Outcome.DONE)
                } finally {
                    reporter.cancel()
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                finish(api, SyncPhase.STOPPED, "Синхронізацію перервано, продовжиться пізніше", Outcome.STOPPED)
            }
            throw e
        } catch (e: UnauthorizedException) {
            config.updateServer { it.copy(revoked = true) }
            return finish(api, SyncPhase.ERROR, "Комп'ютер відключив цей телефон", Outcome.UNAUTHORIZED, report = false)
        } catch (e: Throwable) {
            return finish(api, SyncPhase.ERROR, "Зв'язок із комп'ютером втрачено: ${e.message}", Outcome.FAILED)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                dbs.forEach { it.save() }
                Storage.scan(ctx, scanBatch.toList())
            }
        }
    }

    // ------------------------------------------------------------------ PC → phone

    private fun planDown(api: Api, server: PairedServer, state: FolderState, root: File): DownPlan? {
        if (!root.isDirectory && !root.mkdirs()) {
            state.message = "Не вдалося створити папку на телефоні"
            return null
        }
        val tmpDir = File(root, Protocol.TEMP_DIR)
        tmpDir.mkdirs()
        try {
            File(tmpDir, ".nomedia").let { if (!it.exists()) it.createNewFile() }
        } catch (_: Throwable) {
        }
        val manifest = api.manifest(state.info.id)
        val db = SyncedDb.load(ctx, server.serverId, state.info.id, Direction.TO_PHONE, root.path, config.storageId.value)
        dbs += db

        val pending = ArrayList<Pair<ManifestEntry, File>>()
        val serverPaths = HashSet<String>(manifest.files.size * 2)
        var okFiles = 0
        var okBytes = 0L
        // One directory listing per folder instead of a stat() per file (fast with tens of thousands of files).
        val listings = HashMap<String, Set<String>>()
        fun listed(path: String): Boolean {
            val parent = path.substringBeforeLast('/', "")
            val names = listings.getOrPut(parent) { File(root, parent).list()?.toHashSet() ?: emptySet() }
            return path.substringAfterLast('/') in names
        }
        for (entry in manifest.files) {
            val dest = Storage.resolve(root, entry.path) ?: continue
            serverPaths += entry.path
            val record = db[entry.path]
            val known = record != null && record.s == entry.size && record.m == entry.modified
            val upToDate = if (known && listed(entry.path)) {
                true
            } else {
                dest.isFile && dest.length() == entry.size && (record == null || known)
            }
            if (upToDate) {
                if (record == null) db.put(entry.path, SyncedRecord(entry.size, entry.modified))
                okFiles++
                okBytes += entry.size
            } else {
                pending += entry to dest
            }
        }
        state.totalFiles = manifest.files.size
        state.totalBytes = manifest.files.sumOf { it.size }
        state.syncedFiles = okFiles
        state.syncedBytes = okBytes
        freeSpace = Storage.freeBytes(root)
        pending.sortBy { it.first.path.lowercase() }
        val stale = db.paths.filter { it !in serverPaths }
        Log.i(TAG, "${state.info.name}: ${pending.size} to download, ${stale.size} gone on the PC")
        return DownPlan(state, root, db, pending, stale, manifest.files.isEmpty())
    }

    private fun deleteLocal(plan: DownPlan) {
        if (plan.stale.isEmpty()) return
        if (plan.pcEmpty) {
            // Safety net: never wipe the phone because the PC reported an empty folder.
            plan.state.message = "На комп'ютері папка порожня — видалення пропущено"
            return
        }
        if (!plan.state.info.deleteMissing) {
            plan.stale.forEach(plan.db::remove)
            return
        }
        phase = SyncPhase.DELETING
        val removed = ArrayList<String>()
        val parents = HashSet<File>()
        for (path in plan.stale) {
            val file = Storage.resolve(plan.root, path)
            if (file != null && file.delete()) {
                deleted++
                removed += file.absolutePath
                file.parentFile?.let(parents::add)
            }
            plan.db.remove(path)
        }
        Storage.pruneDirs(plan.root, parents)
        plan.db.save()
        Storage.scan(ctx, removed)
        Log.i(TAG, "${plan.state.info.name}: deleted ${removed.size} files after ${elapsed()} ms")
    }

    /** Returns true when the whole run has to stop (no space on the phone). */
    private suspend fun downloadAll(api: Api, plan: DownPlan): Boolean {
        val job = kotlin.coroutines.coroutineContext[Job]
        val isStopped = { job?.isActive == false }
        val state = plan.state
        val tmpDir = File(plan.root, Protocol.TEMP_DIR)

        // Drop leftovers of files that are no longer wanted.
        val wantedParts = plan.downloads.mapTo(HashSet()) { partName(it.first.path) }
        tmpDir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".part") && f.name !in wantedParts) f.delete()
        }

        var skipped = 0
        for ((entry, dest) in plan.downloads) {
            if (isStopped()) throw CancellationException("stopped")
            val part = File(tmpDir, partName(entry.path))
            val have = if (part.isFile) part.length() else 0L
            val free = Storage.freeBytes(plan.root)
            freeSpace = free
            if (free >= 0 && free < entry.size - have + RESERVE_BYTES) {
                message = "Недостатньо місця на телефоні"
                state.message = message
                return true
            }
            currentFile = "${state.info.name}/${entry.path}"
            currentBytes = have.coerceAtMost(entry.size)

            var attempt = 0
            var gone = false
            while (true) {
                try {
                    api.download(
                        state.info.id,
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
                        throw IOException("розмір файлу не збігся")
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
            plan.db.put(entry.path, SyncedRecord(entry.size, entry.modified))

            currentBytes = 0
            state.syncedBytes += entry.size
            state.syncedFiles++
            downloaded++
            scanBatch += dest.absolutePath
            if (scanBatch.size >= 25) {
                Storage.scan(ctx, scanBatch.toList())
                scanBatch.clear()
            }
            if (downloaded % 10 == 0) plan.db.save()
        }
        currentFile = null
        if (skipped > 0 && state.message == null) state.message = "Пропущено файлів: $skipped (змінилися на комп'ютері)"
        return false
    }

    // ------------------------------------------------------------------ phone → PC

    private fun planUp(api: Api, server: PairedServer, state: FolderState, root: File): UpPlan? {
        if (!root.isDirectory) {
            state.message = "Папку на телефоні не знайдено"
            return null
        }
        val local = Storage.listLocal(root)
        val manifest = api.manifest(state.info.id)
        val onPc = manifest.files.associateBy { it.path }
        val db = SyncedDb.load(ctx, server.serverId, state.info.id, Direction.TO_PC, root.path, config.storageId.value)
        dbs += db

        val uploads = ArrayList<LocalFile>()
        var okFiles = 0
        var okBytes = 0L
        var badNames = 0
        var totalFiles = 0
        var totalBytes = 0L
        for (file in local) {
            if (!Protocol.isSafeRelativePath(file.path)) {
                badNames++
                continue
            }
            totalFiles++
            totalBytes += file.size
            val record = db[file.path]
            val pc = onPc[file.path]
            val known = record != null && record.s == file.size && record.m == file.modified
            // Already sent and unchanged on the phone: done, even if the PC copy was edited since.
            // (The PC never overwrites its files; a changed phone file arrives as a separate copy.)
            if (pc != null && (known || (record == null && pc.size == file.size))) {
                if (record == null) db.put(file.path, SyncedRecord(file.size, file.modified))
                okFiles++
                okBytes += file.size
            } else {
                uploads += file
            }
        }
        state.totalFiles = totalFiles
        state.totalBytes = totalBytes
        state.syncedFiles = okFiles
        state.syncedBytes = okBytes
        if (badNames > 0) state.message = "Пропущено ${badNames} файлів з назвами, недопустимими у Windows"

        // Files deleted on the phone stay on the PC: nothing is ever deleted there.
        val localPaths = local.mapTo(HashSet()) { it.path }
        db.paths.filter { it !in localPaths }.forEach(db::remove)
        uploads.sortBy { it.path.lowercase() }
        Log.i(TAG, "${state.info.name}: ${uploads.size} to upload")
        return UpPlan(state, root, db, uploads)
    }

    private suspend fun uploadAll(api: Api, plan: UpPlan) {
        val job = kotlin.coroutines.coroutineContext[Job]
        val isStopped = { job?.isActive == false }
        val state = plan.state
        val folderId = state.info.id
        for (local in plan.uploads) {
            if (isStopped()) throw CancellationException("stopped")
            val file = local.file
            if (!file.isFile) continue
            val size = file.length()
            val modified = file.lastModified()
            currentFile = "${state.info.name}/${local.path}"
            currentBytes = 0

            var offset = api.uploadOffset(folderId, local.path, size)
            var attempts = 0
            var done = false
            var skip = false
            while (!done && !skip) {
                val result = try {
                    api.upload(
                        folderId, local.path, file, size, modified, offset,
                        onProgress = { sent ->
                            val delta = sent - currentBytes
                            if (delta > 0) transferred.addAndGet(delta)
                            currentBytes = sent
                        },
                        isStopped = isStopped,
                    )
                } catch (e: StoppedException) {
                    throw CancellationException("stopped")
                } catch (e: UnauthorizedException) {
                    throw e
                } catch (e: IOException) {
                    attempts++
                    if (attempts >= 3 || isStopped()) throw e
                    delay(2_000L * attempts)
                    offset = api.uploadOffset(folderId, local.path, size)
                    continue
                }
                when (result) {
                    UploadResult.Complete -> done = true
                    is UploadResult.Partial, is UploadResult.Conflict -> {
                        attempts++
                        if (attempts >= 5) throw IOException("не вдалося надіслати ${local.path}")
                        offset = if (result is UploadResult.Partial) result.offset else (result as UploadResult.Conflict).offset
                    }
                    UploadResult.NoSpace -> {
                        message = "На комп'ютері недостатньо місця"
                        state.message = message
                        currentFile = null
                        currentBytes = 0
                        return
                    }
                    is UploadResult.Rejected -> {
                        state.message = result.message
                        skip = true
                    }
                }
            }
            currentBytes = 0
            if (done) {
                plan.db.put(local.path, SyncedRecord(size, modified))
                state.syncedFiles++
                state.syncedBytes += size
                uploaded++
                if (uploaded % 10 == 0) plan.db.save()
            }
        }
        currentFile = null
    }

    // ------------------------------------------------------------------ reporting

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
        currentBytes = 0
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
                uploaded = uploaded,
                message = text,
                folders = progress.folders,
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
        Log.i(TAG, "Finished $finalPhase after ${elapsed()} ms: down=$downloaded up=$uploaded deleted=$deleted ${text.orEmpty()}")
        Notifications.cancelProgress(ctx)
        if (downloaded > 0 || uploaded > 0 || deleted > 0) Notifications.showDone(ctx, progress)
        return outcome
    }

    companion object {
        private const val TAG = "SyncEngine"
        private const val RESERVE_BYTES = 200L * 1024 * 1024

        private fun partName(path: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(path.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) } + ".part"
        }
    }
}
