package biz.fesh.syncmusic.desktop

import biz.fesh.syncmusic.shared.PairRequest
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

class AppController {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(File(AppPaths.dataDir, "settings.json"))
    val library = Library()

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

    private val _showWindow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val showWindow: SharedFlow<Unit> = _showWindow.asSharedFlow()

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
        env ?: host ?: "Компьютер"
    }

    private val server = SyncServer(this)
    private val discovery = DiscoveryResponder { serverInfo() }
    private val lastPersist = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun start() {
        AppLog.i("Starting Sync Music ${BuildInfo.version}")
        if (settings.value.musicFolder == null) {
            AppPaths.defaultMusicFolder()?.let { folder -> settings.update { it.copy(musicFolder = folder) } }
        }
        if (!settings.value.firstRunDone) {
            settings.update { it.copy(firstRunDone = true) }
            if (settings.value.autostart) scope.launch(Dispatchers.IO) { WindowsIntegration.setAutostart(true) }
        } else if (settings.value.autostart) {
            // Re-apply so the path stays correct after updates.
            scope.launch(Dispatchers.IO) { WindowsIntegration.setAutostart(true) }
        }
        library.setRoot(settings.value.musicFolder)
        scope.launch { library.rescan() }
        startServer()
        discovery.start()
        // Periodic rescan keeps the numbers on screen fresh.
        scope.launch {
            while (true) {
                delay(120_000)
                library.rescan()
            }
        }
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
                ServerState.Failed("Порт $port занят другой программой")
            }
        }
    }

    fun shutdown() {
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

    // ---------- library ----------

    fun setMusicFolder(path: String) {
        settings.update { it.copy(musicFolder = path) }
        library.setRoot(path)
        scope.launch { library.rescan() }
    }

    fun rescan() {
        scope.launch { library.rescan() }
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
        _events.tryEmit("Телефон «${request.deviceName}» подключён")
        return PairResult.Ok(token)
    }

    fun deviceByToken(token: String): PairedDevice? {
        val bytes = token.toByteArray()
        return settings.value.devices.firstOrNull { MessageDigest.isEqual(it.token.toByteArray(), bytes) }
    }

    fun removeDevice(id: String) {
        settings.update { s -> s.copy(devices = s.devices.filter { it.id != id }) }
        _live.update { it - id }
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
