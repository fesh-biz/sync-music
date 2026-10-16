package biz.fesh.syncmusic.desktop

import biz.fesh.syncmusic.shared.Protocol
import biz.fesh.syncmusic.shared.SyncProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

@Serializable
data class PairedDevice(
    val id: String,
    val name: String,
    val token: String,
    val pairedAt: Long,
    val lastSeen: Long = 0,
    val lastProgress: SyncProgress? = null,
)

@Serializable
data class Settings(
    val serverId: String = UUID.randomUUID().toString(),
    val musicFolder: String? = null,
    val port: Int = Protocol.HTTP_PORT,
    val devices: List<PairedDevice> = emptyList(),
    val autostart: Boolean = true,
    val closeToTray: Boolean = true,
    val trayHintShown: Boolean = false,
    val firstRunDone: Boolean = false,
)

class SettingsStore(private val file: File) {
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Settings> = _state.asStateFlow()
    val value: Settings get() = _state.value

    init {
        // Make sure a freshly generated serverId is persisted right away.
        save(_state.value)
    }

    @Synchronized
    fun update(transform: (Settings) -> Settings): Settings {
        val next = transform(_state.value)
        if (next != _state.value) {
            _state.value = next
            save(next)
        }
        return next
    }

    private fun load(): Settings = try {
        if (file.isFile) Protocol.json.decodeFromString(Settings.serializer(), file.readText()) else Settings()
    } catch (t: Throwable) {
        AppLog.e("Settings are corrupted, starting fresh", t)
        Settings()
    }

    private fun save(settings: Settings) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(Protocol.json.encodeToString(Settings.serializer(), settings))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (t: Throwable) {
            try {
                file.writeText(Protocol.json.encodeToString(Settings.serializer(), settings))
            } catch (t2: Throwable) {
                AppLog.e("Cannot save settings", t2)
            }
        }
    }
}
