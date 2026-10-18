package biz.fesh.syncmusic.desktop

import biz.fesh.syncmusic.shared.Direction
import biz.fesh.syncmusic.shared.FileTypes
import biz.fesh.syncmusic.shared.FolderInfo
import biz.fesh.syncmusic.shared.PairRequest
import biz.fesh.syncmusic.shared.PhoneStorage
import biz.fesh.syncmusic.shared.Protocol
import biz.fesh.syncmusic.shared.ServerInfo
import biz.fesh.syncmusic.shared.SyncProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom

sealed interface ServerState {
    data object Starting : ServerState
    data class Running(val port: Int) : ServerState
    data class Failed(val message: String) : ServerState
}

/** Live (not persisted) status of a phone. */
data class LiveStatus(
    val progress: SyncProgress,
    val receivedAt: Long,
)

sealed interface PairResult {
    data class Ok(val token: String) : PairResult
    data object WrongPin : PairResult
}

/**
 * The PC app asks to choose a phone folder for [folderId] ([deviceId] null = the first phone).
 * [isNew]: right after "Додати папку" — the dialog also offers which files to sync and works without a phone.
 */
data class PhonePickerRequest(val folderId: String, val deviceId: String? = null, val isNew: Boolean = false)

/** A synced folder with its scanner and change watcher. */
class FolderRuntime(val folder: SyncFolder, val library: Library, val watcher: FolderWatcher)

class AppController {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(File(AppPaths.dataDir, "settings.json"))

    private val runtimes = java.util.concurrent.ConcurrentHashMap<String, FolderRuntime>()
    private val _runtimeVersion = MutableStateFlow(0)
    /** Changes whenever folder scanners are created or removed (the UI re-reads them). */
    val runtimeVersion: StateFlow<Int> = _runtimeVersion.asStateFlow()

    private val random = SecureRandom()

    private val _pin = MutableStateFlow(newPin())
    val pin: StateFlow<String> = _pin.asStateFlow()
    private var failedPinAttempts = 0

    private val _live = MutableStateFlow<Map<String, LiveStatus>>(emptyMap())
    val live: StateFlow<Map<String, LiveStatus>> = _live.asStateFlow()

    private val _serverState = MutableStateFlow<ServerState>(ServerState.Starting)
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    /** null until the first (background) lookup finishes. */
    private val phoneStorageFile = File(AppPaths.dataDir, "phones.json")
    private val _phoneStorage = MutableStateFlow(loadPhoneStorage())
    /** Storages and folders each phone reported (device id -> storage), for the phone folder picker. */
    val phoneStorage: StateFlow<Map<String, PhoneStorage>> = _phoneStorage.asStateFlow()

    private val _phonePicker = MutableStateFlow<PhonePickerRequest?>(null)
    val phonePicker: StateFlow<PhonePickerRequest?> = _phonePicker.asStateFlow()

    private val _addresses = MutableStateFlow<List<NetUtil.LocalAddress>?>(null)
    val addresses: StateFlow<List<NetUtil.LocalAddress>?> = _addresses.asStateFlow()

    private val _showWindow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val showWindow: SharedFlow<Unit> = _showWindow.asSharedFlow()

    private val _hideWindow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Used by automated tests to simulate closing the window to the tray. */
    val hideWindow: SharedFlow<Unit> = _hideWindow.asSharedFlow()

    val computerName: String by lazy {
        val env = System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }
        val host = if (env == null) {
            try {
                InetAddress.getLocalHost().hostName
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }
        env ?: host ?: "Комп'ютер"
    }

    private val server = SyncServer(this)
    private val discovery = DiscoveryResponder { serverInfo() }
    private val lastPersist = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun start() {
        AppLog.i("Starting Sync Music ${BuildInfo.version}")
        migrateFolders()
        if (!settings.value.firstRunDone) {
            settings.update { it.copy(firstRunDone = true) }
            if (settings.value.autostart) scope.launch(Dispatchers.IO) { WindowsIntegration.setAutostart(true) }
        } else if (settings.value.autostart) {
            // Re-apply so the path stays correct after updates.
            scope.launch(Dispatchers.IO) { WindowsIntegration.setAutostart(true) }
        }
        syncRuntimes()
        startServer()
        discovery.start()
        // Network adapters change rarely; enumerating them can be slow on Windows, so do it off the UI thread.
        scope.launch(Dispatchers.IO) {
            while (true) {
                val started = System.currentTimeMillis()
                val list = NetUtil.localAddresses()
                if (_addresses.value == null) AppLog.i("Network addresses $list in ${System.currentTimeMillis() - started} ms")
                _addresses.value = list
                delay(30_000)
            }
        }
        // Safety net in case a change notification was missed.
        scope.launch {
            while (true) {
                delay(120_000)
                runtimes.values.forEach { it.library.rescan() }
            }
        }
    }

    // ---------- folders ----------

    /** Version 1 synced one music folder; it becomes the first folder (same id, so phones keep their files). */
    private fun migrateFolders() {
        val current = settings.value
        if (current.foldersInitialized) return
        val path = current.musicFolder ?: AppPaths.defaultMusicFolder()
        settings.update { s ->
            val folders = if (s.folders.isEmpty() && path != null) {
                listOf(SyncFolder(Protocol.LEGACY_FOLDER_ID, File(path).name.ifBlank { "Музика" }, path, Direction.TO_PHONE, true))
            } else {
                s.folders
            }
            s.copy(folders = folders, musicFolder = null, foldersInitialized = true)
        }
        AppLog.i("Folders: ${settings.value.folders.map { it.path }}")
    }

    /** Creates/removes scanners and watchers to match the folder list. */
    @Synchronized
    private fun syncRuntimes() {
        val folders = settings.value.folders
        val ids = folders.map { it.id }.toSet()
        for ((id, runtime) in runtimes) {
            if (id !in ids) {
                runtime.watcher.stop()
                runtimes.remove(id)
            }
        }
        for (folder in folders) {
            val existing = runtimes[folder.id]
            if (existing != null && existing.folder.path == folder.path) {
                runtimes[folder.id] = FolderRuntime(folder, existing.library, existing.watcher)
                if (existing.library.fileTypes != folder.fileTypes) {
                    existing.library.setFileTypes(folder.fileTypes)
                    scope.launch { existing.library.rescan() }
                }
                continue
            }
            existing?.watcher?.stop()
            val library = Library()
            library.setFileTypes(folder.fileTypes)
            library.setRoot(folder.path)
            val watcher = FolderWatcher(scope) {
                AppLog.i("Folder ${folder.name} changed, rescanning")
                library.rescan()
            }
            watcher.watch(folder.path)
            runtimes[folder.id] = FolderRuntime(folder, library, watcher)
            scope.launch { library.rescan() }
        }
        _runtimeVersion.value++
    }

    fun runtime(id: String): FolderRuntime? = runtimes[id]

    /** Folder by id; without an id (version 1 phones) the first PC → phone folder. */
    fun folderRuntime(id: String?): FolderRuntime? {
        if (id != null) return runtimes[id]
        val folder = settings.value.folders.firstOrNull { it.direction == Direction.TO_PHONE } ?: return null
        return runtimes[folder.id]
    }

    /** Folders as the phone [deviceId] sees them (with the phone folder chosen here for it). */
    fun folderInfos(deviceId: String? = null): List<FolderInfo> = settings.value.folders.map { folder ->
        val stats = runtimes[folder.id]?.library?.stats?.value
        val target = deviceId?.let { folder.phoneTargets[it] }
        FolderInfo(
            id = folder.id,
            name = folder.name,
            direction = folder.direction,
            // Phone → PC folders never delete anything on the PC.
            deleteMissing = folder.direction == Direction.TO_PHONE && folder.deleteMissing,
            files = stats?.files ?: 0,
            bytes = stats?.bytes ?: 0,
            available = File(folder.path).isDirectory,
            phonePath = target?.path,
            phonePathAt = target?.at ?: 0,
            phoneDeleteOld = folder.direction == Direction.TO_PHONE && target?.deleteOld == true,
            fileTypes = folder.fileTypes,
        )
    }

    // ---------- phone folders ----------

    /** Chooses the phone folder of [folderId] on the phone [deviceId]; the phone applies it on its next sync. */
    fun setPhoneFolder(folderId: String, deviceId: String, path: String, deleteOld: Boolean) {
        val clean = path.trimEnd('/').ifEmpty { "/" }
        settings.update { s ->
            s.copy(
                folders = s.folders.map {
                    if (it.id == folderId) {
                        // Strictly increasing, so a quick second choice is never taken for an already applied one.
                        val at = maxOf(System.currentTimeMillis(), (it.phoneTargets[deviceId]?.at ?: 0) + 1)
                        it.copy(phoneTargets = it.phoneTargets + (deviceId to PhoneTarget(clean, at, deleteOld)))
                    } else {
                        it
                    }
                },
            )
        }
        syncRuntimes()
        AppLog.i("Phone folder for $folderId on $deviceId: $clean (deleteOld=$deleteOld)")
    }

    fun onPhoneStorage(deviceId: String, storage: PhoneStorage) {
        val stamped = storage.copy(at = System.currentTimeMillis())
        _phoneStorage.update { it + (deviceId to stamped) }
        savePhoneStorage()
    }

    fun openPhonePicker(folderId: String, deviceId: String? = null, isNew: Boolean = false) {
        _phonePicker.value = PhonePickerRequest(folderId, deviceId, isNew)
    }

    fun closePhonePicker() {
        _phonePicker.value = null
    }

    private fun loadPhoneStorage(): Map<String, PhoneStorage> = try {
        if (phoneStorageFile.isFile) {
            Protocol.json.decodeFromString(MapSerializer(String.serializer(), PhoneStorage.serializer()), phoneStorageFile.readText())
        } else {
            emptyMap()
        }
    } catch (t: Throwable) {
        AppLog.w("phones.json is corrupted", t)
        emptyMap()
    }

    @Synchronized
    private fun savePhoneStorage() {
        try {
            val text = Protocol.json.encodeToString(MapSerializer(String.serializer(), PhoneStorage.serializer()), _phoneStorage.value)
            val tmp = File(phoneStorageFile.parentFile, phoneStorageFile.name + ".tmp")
            tmp.writeText(text)
            java.nio.file.Files.move(tmp.toPath(), phoneStorageFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (t: Throwable) {
            AppLog.w("Cannot save phones.json", t)
        }
    }

    /** Adds a PC folder (PC → phone by default). Returns an error text or null. */
    fun addFolder(path: String, direction: Direction = Direction.TO_PHONE, deleteMissing: Boolean? = null): String? {
        val dir = File(path)
        if (!dir.isDirectory) return "Папку не знайдено"
        val canonical = try { dir.canonicalPath } catch (_: Throwable) { dir.absolutePath }
        if (settings.value.folders.any { sameFile(it.path, canonical) }) return "Ця папка вже синхронізується"
        val baseName = dir.name.ifBlank { canonical }
        var name = baseName
        var n = 2
        while (settings.value.folders.any { it.name.equals(name, ignoreCase = true) }) name = "$baseName (${n++})"
        val folder = SyncFolder(
            id = java.util.UUID.randomUUID().toString(),
            name = name,
            path = canonical,
            direction = direction,
            deleteMissing = direction == Direction.TO_PHONE && (deleteMissing ?: true),
        )
        settings.update { it.copy(folders = it.folders + folder) }
        syncRuntimes()
        AppLog.i("Added folder $name ($canonical, $direction)")
        return null
    }

    fun removeFolder(id: String) {
        settings.update { s -> s.copy(folders = s.folders.filter { it.id != id }) }
        syncRuntimes()
    }

    fun setDirection(id: String, direction: Direction) {
        settings.update { s ->
            s.copy(
                folders = s.folders.map {
                    if (it.id == id && it.direction != direction) {
                        // Safe default for the new direction: mirror to the phone, keep everything on the PC.
                        it.copy(direction = direction, deleteMissing = direction == Direction.TO_PHONE)
                    } else {
                        it
                    }
                },
            )
        }
        syncRuntimes()
    }

    /** Only for PC → phone folders (deleting on the phone); nothing is ever deleted on the PC. */
    fun setDeleteMissing(id: String, value: Boolean) {
        settings.update { s ->
            s.copy(folders = s.folders.map { if (it.id == id && it.direction == Direction.TO_PHONE) it.copy(deleteMissing = value) else it })
        }
        syncRuntimes()
    }

    /** Music, movies or all files. PC → phone: with deleting on, the phone drops the files that no longer fit. */
    fun setFileTypes(id: String, types: FileTypes) {
        settings.update { s -> s.copy(folders = s.folders.map { if (it.id == id) it.copy(fileTypes = types) else it }) }
        syncRuntimes()
        AppLog.i("File types of $id: $types")
    }

    private fun sameFile(a: String, b: String): Boolean = try {
        File(a).canonicalPath.equals(File(b).canonicalPath, ignoreCase = WindowsIntegration.isWindows)
    } catch (_: Throwable) {
        a == b
    }

    private fun startServer() {
        val port = settings.value.port
        _serverState.value = ServerState.Starting
        scope.launch(Dispatchers.IO) {
            _serverState.value = try {
                server.start(port)
                AppLog.i("HTTP server on port $port")
                ServerState.Running(port)
            } catch (t: Throwable) {
                AppLog.e("Cannot start server on $port", t)
                ServerState.Failed("Порт $port зайнятий іншою програмою")
            }
        }
    }

    fun shutdown() {
        runtimes.values.forEach { it.watcher.stop() }
        discovery.stop()
        server.stop()
    }

    fun serverInfo() = ServerInfo(
        serverId = settings.value.serverId,
        serverName = computerName,
        port = settings.value.port,
        appVersion = BuildInfo.version,
        apkVersionCode = ApkResource.versionCode,
    )

    fun requestShowWindow() {
        _showWindow.tryEmit(Unit)
    }

    fun requestHideWindow() {
        _hideWindow.tryEmit(Unit)
    }

    /** Called when the window becomes visible: make sure the numbers are current. */
    fun onWindowShown() {
        scope.launch { runtimes.values.forEach { it.library.refreshIfOlderThan(10_000) } }
    }

    fun rescan() {
        scope.launch { runtimes.values.forEach { it.library.rescan() } }
    }

    // ---------- pairing ----------

    private fun newPin(): String = (0 until 6).joinToString("") { random.nextInt(10).toString() }

    fun regeneratePin() {
        _pin.value = newPin()
        failedPinAttempts = 0
    }

    @Synchronized
    fun pair(request: PairRequest): PairResult {
        val expected = _pin.value
        val ok = MessageDigest.isEqual(expected.toByteArray(), request.pin.trim().toByteArray())
        if (!ok) {
            failedPinAttempts++
            if (failedPinAttempts >= 5) regeneratePin()
            return PairResult.WrongPin
        }
        val bytes = ByteArray(32).also(random::nextBytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        val now = System.currentTimeMillis()
        settings.update { s ->
            val others = s.devices.filter { it.id != request.deviceId }
            s.copy(devices = others + PairedDevice(request.deviceId, request.deviceName.take(60), token, now, lastSeen = now))
        }
        regeneratePin()
        AppLog.i("Paired device ${request.deviceName} (${request.deviceId})")
        _events.tryEmit("Телефон «${request.deviceName}» підключено")
        return PairResult.Ok(token)
    }

    fun deviceByToken(token: String): PairedDevice? {
        val bytes = token.toByteArray()
        return settings.value.devices.firstOrNull { MessageDigest.isEqual(it.token.toByteArray(), bytes) }
    }

    fun removeDevice(id: String) {
        settings.update { s -> s.copy(devices = s.devices.filter { it.id != id }) }
        _live.update { it - id }
        if (id in _phoneStorage.value) {
            _phoneStorage.update { it - id }
            savePhoneStorage()
        }
    }

    // ---------- progress ----------

    fun touchDevice(id: String) {
        val now = System.currentTimeMillis()
        val last = lastPersist[id] ?: 0L
        if (now - last > 60_000) {
            lastPersist[id] = now
            settings.update { s -> s.copy(devices = s.devices.map { if (it.id == id) it.copy(lastSeen = now) else it }) }
        }
    }

    fun onProgress(deviceId: String, progress: SyncProgress) {
        val now = System.currentTimeMillis()
        val stamped = progress.copy(timestamp = now)
        _live.update { it + (deviceId to LiveStatus(stamped, now)) }
        val last = lastPersist[deviceId] ?: 0L
        if (!progress.phase.isActive || now - last > 30_000) {
            lastPersist[deviceId] = now
            settings.update { s ->
                s.copy(devices = s.devices.map { if (it.id == deviceId) it.copy(lastSeen = now, lastProgress = stamped) else it })
            }
        }
    }

    // ---------- preferences ----------

    fun setAutostart(enabled: Boolean) {
        settings.update { it.copy(autostart = enabled) }
        scope.launch(Dispatchers.IO) { WindowsIntegration.setAutostart(enabled) }
    }

    fun setCloseToTray(enabled: Boolean) {
        settings.update { it.copy(closeToTray = enabled) }
    }

    fun markTrayHintShown() {
        settings.update { it.copy(trayHintShown = true) }
    }

    fun openFirewall(onDone: (Boolean) -> Unit) {
        scope.launch(Dispatchers.IO) { onDone(WindowsIntegration.openFirewall(settings.value.port)) }
    }
}
