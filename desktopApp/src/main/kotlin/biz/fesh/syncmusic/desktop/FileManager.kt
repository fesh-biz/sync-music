package biz.fesh.syncmusic.desktop

import biz.fesh.syncmusic.shared.FileNames
import biz.fesh.syncmusic.shared.FmCommand
import biz.fesh.syncmusic.shared.FmOp
import biz.fesh.syncmusic.shared.FmReply
import biz.fesh.syncmusic.shared.Format
import biz.fesh.syncmusic.shared.Protocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class JobState { WAITING, RUNNING, DONE, SKIPPED, FAILED, CANCELLED }

val JobState.finished: Boolean get() = this != JobState.WAITING && this != JobState.RUNNING

/** Copying one file between the PC and a phone (started in the file window). */
data class CopyJob(
    val id: String,
    val toPhone: Boolean,
    val deviceId: String,
    /** PC → phone: the PC file; phone → PC: the phone file (absolute path). */
    val source: String,
    /** PC → phone: the phone folder; phone → PC: the PC folder. */
    val targetDir: String,
    val name: String,
    val size: Long,
    val modified: Long,
    val state: JobState = JobState.WAITING,
    /** Bytes transferred so far. */
    val done: Long = 0,
    val error: String? = null,
    /** The name the file got (differs when the name was taken by a different file). */
    val savedAs: String? = null,
)

/** A phone file or folder picked in the file window. */
data class PhoneItem(val path: String, val name: String, val dir: Boolean, val size: Long, val modified: Long)

private class QueuedCommand(val command: FmCommand, val at: Long)

/** The phone's side of the file manager: commands wait here until its next long poll picks them up. */
private class PhoneLink {
    val queue = Channel<QueuedCommand>(Channel.UNLIMITED)
    @Volatile var lastPoll = 0L
    val polling = AtomicInteger(0)
    val replies = ConcurrentHashMap<String, CompletableDeferred<FmReply>>()
}

/**
 * File manager on the PC side: lists phone folders and copies files both ways, one file at a time.
 * The phone is reached only through its long poll (see Files.kt in the shared module).
 */
class FileManager(private val app: AppController) {
    private val links = ConcurrentHashMap<String, PhoneLink>()

    private val _open = MutableStateFlow(false)
    /** The file window is open. */
    val open: StateFlow<Boolean> = _open.asStateFlow()

    private val _online = MutableStateFlow<Set<String>>(emptySet())
    /** Phones whose file access is on right now (device ids). */
    val online: StateFlow<Set<String>> = _online.asStateFlow()

    private val _jobs = MutableStateFlow<List<CopyJob>>(emptyList())
    val jobs: StateFlow<List<CopyJob>> = _jobs.asStateFlow()

    private val _speed = MutableStateFlow(0L)
    /** Bytes per second of the running copy. */
    val speed: StateFlow<Long> = _speed.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    /** Last problem worth showing (e.g. a phone folder that could not be read). */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** Folders shown in the two panels: null = "Цей комп'ютер" / the phone's storages. */
    val pcPath = MutableStateFlow<String?>(null)
    val phonePath = MutableStateFlow<String?>(null)
    val phoneDevice = MutableStateFlow<String?>(null)

    private val _refresh = MutableStateFlow(0)
    /** Changes when the panels should re-read their folders (after copying). */
    val refresh: StateFlow<Int> = _refresh.asStateFlow()

    /** For automated tests: the last listing made with [testList]. */
    @Volatile var lastTestList: FmReply? = null

    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val pcFiles = ConcurrentHashMap<String, File>()
    private val cancelled: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        app.scope.launch { worker() }
        app.scope.launch {
            var lastBytes = 0L
            var lastAt = System.currentTimeMillis()
            while (true) {
                delay(400)
                val now = System.currentTimeMillis()
                _online.value = links.filter { (_, link) -> link.polling.get() > 0 || now - link.lastPoll < 35_000 }.keys.toSet()
                val running = _jobs.value.firstOrNull { it.state == JobState.RUNNING }
                val bytes = running?.let { counters[it.id]?.get() }
                if (running != null && bytes != null) {
                    if (bytes != running.done) update(running.id) { it.copy(done = bytes) }
                    val dt = now - lastAt
                    if (dt >= 1_000) {
                        val delta = (bytes - lastBytes).coerceAtLeast(0)
                        _speed.value = if (_speed.value == 0L) delta * 1000 / dt else (_speed.value + delta * 1000 / dt) / 2
                        lastBytes = bytes
                        lastAt = now
                    }
                } else {
                    _speed.value = 0
                    lastBytes = 0
                    lastAt = now
                }
            }
        }
    }

    // ---------------------------------------------------------------- window

    fun openWindow(deviceId: String? = null) {
        val devices = app.settings.value.devices
        val chosen = deviceId ?: phoneDevice.value?.takeIf { id -> devices.any { it.id == id } }
            ?: devices.maxByOrNull { maxOf(it.lastSeen, links[it.id]?.lastPoll ?: 0) }?.id
        phoneDevice.value = chosen
        if (pcPath.value == null) {
            pcPath.value = app.settings.value.folders.firstOrNull { File(it.path).isDirectory }?.path
                ?: System.getProperty("user.home")
        }
        _open.value = true
    }

    fun closeWindow() {
        _open.value = false
    }

    fun clearNotice() {
        _notice.value = null
    }

    fun refreshPanels() {
        _refresh.update { it + 1 }
    }

    // ---------------------------------------------------------------- link (called by the server)

    private fun link(deviceId: String) = links.computeIfAbsent(deviceId) { PhoneLink() }

    fun isOnline(deviceId: String?): Boolean {
        val link = deviceId?.let { links[it] } ?: return false
        return link.polling.get() > 0 || System.currentTimeMillis() - link.lastPoll < 35_000
    }

    /** The phone keeps its file access on while this is true. */
    fun wanted(deviceId: String): Boolean =
        _open.value || _jobs.value.any { it.deviceId == deviceId && !it.state.finished }

    /** Long poll of the phone: waits up to [waitMs] for commands. */
    suspend fun poll(deviceId: String, waitMs: Long): List<FmCommand> {
        val link = link(deviceId)
        link.polling.incrementAndGet()
        link.lastPoll = System.currentTimeMillis()
        try {
            val result = ArrayList<FmCommand>()
            val first = withTimeoutOrNull(waitMs) { link.queue.receive() }
            var next: QueuedCommand? = first
            while (next != null) {
                // Commands nobody waits for any more (the phone was away) are dropped.
                if (System.currentTimeMillis() - next.at < STALE_MS || link.replies.containsKey(next.command.id)) result += next.command
                next = link.queue.tryReceive().getOrNull()
            }
            return result
        } finally {
            link.lastPoll = System.currentTimeMillis()
            link.polling.decrementAndGet()
        }
    }

    fun onReply(deviceId: String, reply: FmReply) {
        link(deviceId).replies[reply.id]?.complete(reply)
    }

    fun forget(deviceId: String) {
        links.remove(deviceId)
        cancelAll()
        _jobs.update { list -> list.filter { it.deviceId != deviceId } }
    }

    /** Runs a quick command (LIST, TREE, MKDIR) on the phone. */
    suspend fun request(deviceId: String, op: FmOp, path: String, timeoutMs: Long = 20_000): FmReply {
        val id = UUID.randomUUID().toString()
        if (!isOnline(deviceId)) return FmReply(id, ok = false, error = OFFLINE)
        val link = link(deviceId)
        val deferred = CompletableDeferred<FmReply>()
        link.replies[id] = deferred
        try {
            link.queue.send(QueuedCommand(FmCommand(id, op, path), System.currentTimeMillis()))
            return withTimeoutOrNull(timeoutMs) { deferred.await() } ?: FmReply(id, ok = false, error = "Телефон не відповідає")
        } finally {
            link.replies.remove(id)
        }
    }

    suspend fun testList(path: String) {
        val device = phoneDevice.value ?: app.settings.value.devices.firstOrNull()?.id ?: return
        lastTestList = request(device, FmOp.LIST, path)
    }

    // ---------------------------------------------------------------- copying

    /** Copies PC files and folders (with everything inside) into the phone folder [phoneDir]. */
    fun copyToPhone(deviceId: String, files: List<File>, phoneDir: String) {
        app.scope.launch(Dispatchers.IO) {
            val added = ArrayList<CopyJob>()
            for (file in files) {
                if (file.isFile) {
                    added += pcJob(deviceId, file, phoneDir)
                } else if (file.isDirectory) {
                    val base = file.toPath()
                    file.walkTopDown()
                        .onEnter { it == file || Protocol.isSyncableDir(it.name) }
                        .filter { it.isFile && Protocol.isSyncable(it.name) }
                        .take(MAX_JOBS)
                        .forEach { f ->
                            val rel = base.relativize(f.parentFile.toPath()).joinToString("/") { it.toString() }
                            val dir = phoneDir.trimEnd('/') + "/" + file.name + (if (rel.isEmpty()) "" else "/$rel")
                            added += pcJob(deviceId, f, dir)
                        }
                }
            }
            enqueue(added, "з комп'ютера")
        }
    }

    private fun pcJob(deviceId: String, file: File, phoneDir: String) = CopyJob(
        id = UUID.randomUUID().toString(),
        toPhone = true,
        deviceId = deviceId,
        source = file.absolutePath,
        targetDir = phoneDir.trimEnd('/').ifEmpty { "/" },
        name = file.name,
        size = file.length(),
        modified = file.lastModified(),
    )

    /** Copies phone files and folders (with everything inside) into the PC folder [pcDir]. */
    fun copyToPc(deviceId: String, items: List<PhoneItem>, pcDir: File) {
        app.scope.launch {
            val added = ArrayList<CopyJob>()
            for (item in items) {
                if (!item.dir) {
                    added += phoneJob(deviceId, item.path, pcDir, FileNames.safeName(item.name), item.size, item.modified)
                    continue
                }
                val tree = request(deviceId, FmOp.TREE, item.path, 120_000)
                if (!tree.ok) {
                    _notice.value = "Не вдалося прочитати папку «${item.name}» на телефоні: ${tree.error}"
                    continue
                }
                if (tree.truncated) _notice.value = "У папці «${item.name}» забагато файлів — копіюються перші ${Format.number(tree.entries.size.toLong())}"
                val root = File(pcDir, FileNames.safeName(item.name))
                for (entry in tree.entries) {
                    if (entry.dir) continue
                    val parts = entry.name.split('/').filter { it.isNotEmpty() }
                    if (parts.isEmpty() || parts.any { it == "." || it == ".." }) continue
                    val dir = parts.dropLast(1).fold(root) { d, p -> File(d, FileNames.safeName(p)) }
                    added += phoneJob(deviceId, item.path.trimEnd('/') + "/" + entry.name, dir, FileNames.safeName(parts.last()), entry.size, entry.modified)
                }
            }
            enqueue(added, "з телефона")
        }
    }

    private fun phoneJob(deviceId: String, source: String, dir: File, name: String, size: Long, modified: Long) = CopyJob(
        id = UUID.randomUUID().toString(),
        toPhone = false,
        deviceId = deviceId,
        source = source,
        targetDir = dir.absolutePath,
        name = name,
        size = size,
        modified = modified,
    )

    private fun enqueue(added: List<CopyJob>, from: String) {
        if (added.isEmpty()) return
        AppLog.i("File window: copying ${added.size} files $from")
        _jobs.update { list ->
            // Finished jobs of earlier copies make way, so the list does not grow forever.
            val keep = list.filter { !it.state.finished || it.state == JobState.FAILED }.takeLast(MAX_JOBS)
            keep + added
        }
        wake.trySend(Unit)
    }

    /** Stops the running copy and drops the waiting ones. */
    fun cancelAll() {
        val ids = _jobs.value.filter { !it.state.finished }.map { it.id }
        cancelled += ids
        _jobs.update { list -> list.map { if (it.state == JobState.WAITING) it.copy(state = JobState.CANCELLED) else it } }
    }

    fun clearFinished() {
        _jobs.update { list -> list.filter { !it.state.finished } }
    }

    fun retryFailed() {
        _jobs.update { list -> list.map { if (it.state == JobState.FAILED) it.copy(state = JobState.WAITING, done = 0, error = null) else it } }
        wake.trySend(Unit)
    }

    private fun update(id: String, transform: (CopyJob) -> CopyJob) {
        _jobs.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    private fun job(id: String): CopyJob? = _jobs.value.firstOrNull { it.id == id }

    private suspend fun worker() {
        while (true) {
            val job = _jobs.value.firstOrNull { it.state == JobState.WAITING && isOnline(it.deviceId) }
            if (job == null) {
                withTimeoutOrNull(1_000) { wake.receive() }
                continue
            }
            runJob(job)
        }
    }

    private suspend fun runJob(job: CopyJob) {
        update(job.id) { it.copy(state = JobState.RUNNING, done = 0) }
        val counter = AtomicLong(0)
        counters[job.id] = counter
        val reply = try {
            if (job.toPhone) toPhone(job) else toPc(job)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppLog.w("Copying ${job.source} failed", e)
            FmReply(job.id, ok = false, error = e.message ?: "помилка")
        } finally {
            pcFiles.remove(job.id)
        }
        val wasCancelled = cancelled.remove(job.id)
        counters.remove(job.id)
        val state = when {
            reply.ok && reply.skipped -> JobState.SKIPPED
            reply.ok -> JobState.DONE
            wasCancelled -> JobState.CANCELLED
            else -> JobState.FAILED
        }
        update(job.id) {
            it.copy(state = state, done = if (reply.ok) it.size else counter.get(), error = reply.error, savedAs = reply.savedAs)
        }
        if (state == JobState.FAILED) AppLog.w("Copy of ${job.source} failed: ${reply.error}")
        if (_jobs.value.none { !it.state.finished }) refreshPanels()
    }

    private suspend fun toPhone(job: CopyJob): FmReply {
        val file = File(job.source)
        if (!file.isFile) return FmReply(job.id, ok = false, error = "Файл не знайдено на комп'ютері")
        pcFiles[job.id] = file
        return send(job, FmCommand(job.id, FmOp.PULL, job.targetDir, job.name, file.length(), file.lastModified()))
    }

    private suspend fun toPc(job: CopyJob): FmReply {
        val dir = File(job.targetDir)
        val existing = File(dir, job.name)
        val same = withContext(Dispatchers.IO) {
            dir.mkdirs()
            existing.isFile && FileNames.sameFile(existing.length(), existing.lastModified(), job.size, job.modified)
        }
        if (same) return FmReply(job.id, skipped = true, savedAs = job.name)
        if (!dir.isDirectory) return FmReply(job.id, ok = false, error = "Не вдалося створити папку ${dir.path}")
        pcFiles[job.id] = existing
        return send(job, FmCommand(job.id, FmOp.PUSH, job.source, job.name, job.size, job.modified))
    }

    /** Sends a transfer command and waits for its end; gives up when nothing moves for a minute. */
    private suspend fun send(job: CopyJob, command: FmCommand): FmReply {
        val link = link(job.deviceId)
        val deferred = CompletableDeferred<FmReply>()
        link.replies[job.id] = deferred
        try {
            link.queue.send(QueuedCommand(command, System.currentTimeMillis()))
            val counter = counters[job.id]
            var lastBytes = -1L
            var lastChange = System.currentTimeMillis()
            while (true) {
                withTimeoutOrNull(1_000) { deferred.await() }?.let { return it }
                if (job.id in cancelled) return FmReply(job.id, ok = false, error = "Скасовано")
                val bytes = counter?.get() ?: 0
                val now = System.currentTimeMillis()
                if (bytes != lastBytes) {
                    lastBytes = bytes
                    lastChange = now
                }
                if (now - lastChange > STALL_MS) {
                    return FmReply(job.id, ok = false, error = if (isOnline(job.deviceId)) "Телефон не відповідає" else OFFLINE)
                }
            }
        } finally {
            link.replies.remove(job.id)
        }
    }

    // ---------------------------------------------------------------- transfers (called by the server)

    /** The PC file of a running PC → phone copy, or null when [id] is not one. */
    fun pullFile(deviceId: String, id: String): File? {
        val job = job(id) ?: return null
        if (!job.toPhone || job.deviceId != deviceId || job.state != JobState.RUNNING) return null
        return pcFiles[id]
    }

    fun counter(id: String): AtomicLong? = counters[id]

    fun isCancelled(id: String): Boolean = id in cancelled

    /**
     * Receives the phone file of a running phone → PC copy. Nothing on the PC is overwritten:
     * a different file under the same name is saved as "name (з телефона).ext".
     * Returns null on success or the error text.
     */
    fun receiveUpload(deviceId: String, id: String, declaredSize: Long?, input: InputStream): String? {
        val job = job(id)
        val target = pcFiles[id]
        if (job == null || target == null || job.toPhone || job.deviceId != deviceId) return "Копіювання вже не виконується"
        // The phone knows the real size (the listing may be older); the queue shows it from now on.
        val size = declaredSize?.takeIf { it >= 0 } ?: job.size
        if (size != job.size) update(id) { it.copy(size = size) }
        val dir = target.parentFile
        val counter = counters[id]
        val tempDir = Transfers.tempDir(dir)
        val temp = File(tempDir, "fm-$id.part")
        try {
            val free = dir.usableSpace
            if (free > 0 && free < size + RESERVE_BYTES) return fail(deviceId, id, "На комп'ютері недостатньо місця")
            FileOutputStream(temp).use { out ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    if (id in cancelled) throw IOException("Скасовано")
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    counter?.addAndGet(n.toLong())
                }
            }
            if (temp.length() != size) {
                temp.delete()
                return fail(deviceId, id, "Файл отримано не повністю")
            }
            val dest = Transfers.destinationFor(target, temp)
            val reply = if (dest == null) {
                temp.delete()
                FmReply(id, skipped = true, savedAs = target.name)
            } else {
                Files.move(temp.toPath(), dest.toPath())
                if (job.modified > 0) dest.setLastModified(job.modified)
                FmReply(id, savedAs = dest.name)
            }
            link(deviceId).replies[id]?.complete(reply)
            return null
        } catch (e: Throwable) {
            temp.delete()
            return fail(deviceId, id, e.message ?: "помилка запису")
        } finally {
            tempDir.delete() // only when empty
        }
    }

    private fun fail(deviceId: String, id: String, message: String): String {
        link(deviceId).replies[id]?.complete(FmReply(id, ok = false, error = message))
        return message
    }

    companion object {
        const val OFFLINE = "Телефон не на зв'язку"
        private const val STALE_MS = 60_000L
        private const val STALL_MS = 60_000L
        private const val MAX_JOBS = 100_000
        private const val RESERVE_BYTES = 50L * 1024 * 1024
    }
}
