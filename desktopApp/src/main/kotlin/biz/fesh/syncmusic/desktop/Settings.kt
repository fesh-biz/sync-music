package biz.fesh.syncmusic.desktop

import biz.fesh.syncmusic.shared.Direction
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
data class SyncFolder(
    val id: String,
    val name: String,
    /** Folder on the PC. */
    val path: String,
    val direction: Direction = Direction.TO_PHONE,
    /** Delete on the receiving side what disappeared on the sending side. */
    val deleteMissing: Boolean = true,
    /** Phone folder chosen here in the PC app, per phone (device id). */
    val phoneTargets: Map<String, PhoneTarget> = emptyMap(),
)

/** A phone folder chosen in the PC app; the phone applies it on its next sync. */
@Serializable
data class PhoneTarget(
    /** Absolute path on the phone. */
    val path: String,
    /** When it was chosen; the phone applies every choice once. */
    val at: Long,
    /** PC → phone: if the files cannot be moved there, delete the copies in the old phone folder. */
    val deleteOld: Boolean = false,
)

@Serializable
data class Settings(
    val serverId: String = UUID.randomUUID().toString(),
    /** Version 1 had a single music folder; migrated into [folders]. */
    val musicFolder: String? = null,
    val folders: List<SyncFolder> = emptyList(),
    /** Set once the folder list was created (from [musicFolder] or the user's Music folder). */
    val foldersInitialized: Boolean = false,
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
