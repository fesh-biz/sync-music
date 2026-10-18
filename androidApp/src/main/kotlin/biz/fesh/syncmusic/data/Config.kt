package biz.fesh.syncmusic.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import biz.fesh.syncmusic.shared.Protocol
import biz.fesh.syncmusic.shared.SyncPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import biz.fesh.syncmusic.shared.FolderInfo
import biz.fesh.syncmusic.shared.FolderProgress
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class PairedServer(
    val serverId: String,
    val serverName: String,
    val host: String,
    val port: Int,
    val token: String,
    /** The PC removed this phone; it must be paired again. */
    val revoked: Boolean = false,
    /** versionCode of the APK the PC offers, for the "update available" banner. */
    val apkVersionCode: Int = 0,
)

/** Where a PC folder lives on the phone. */
@Serializable
data class FolderMapping(
    /** Absolute path on the phone; null = not chosen yet (phone → PC folders start like that). */
    val localPath: String? = null,
    val enabled: Boolean = true,
    /** The last folder choice made on the PC that was applied here (PC clock, [biz.fesh.syncmusic.shared.FolderInfo.phonePathAt]). */
    val pcChoiceAt: Long = 0,
)

@Serializable
data class LastSync(
    val at: Long,
    val phase: SyncPhase,
    val totalFiles: Int = 0,
    val totalBytes: Long = 0,
    val syncedFiles: Int = 0,
    val syncedBytes: Long = 0,
    val downloaded: Int = 0,
    val deleted: Int = 0,
    val uploaded: Int = 0,
    val message: String? = null,
    val folders: List<FolderProgress> = emptyList(),
)

/** App settings backed by SharedPreferences, exposed as StateFlows for the UI. */
class Config private constructor(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("syncmusic", Context.MODE_PRIVATE)

    val deviceId: String = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString(KEY_DEVICE_ID, it).apply()
    }

    val deviceName: String = run {
        val manufacturer = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model".trim()
    }

    private val _server = MutableStateFlow(readJson(KEY_SERVER, PairedServer.serializer()))
    val server: StateFlow<PairedServer?> = _server.asStateFlow()

    private val _autoSync = MutableStateFlow(prefs.getBoolean(KEY_AUTO_SYNC, true))
    val autoSync: StateFlow<Boolean> = _autoSync.asStateFlow()

    private val _paused = MutableStateFlow(prefs.getBoolean(KEY_PAUSED, false))
    /** Paused by the user: no sync runs (automatic or on opening the app) until resumed. */
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    private val _deleteRemoved = MutableStateFlow(prefs.getBoolean(KEY_DELETE_REMOVED, true))
    val deleteRemoved: StateFlow<Boolean> = _deleteRemoved.asStateFlow()

    private val _storageId = MutableStateFlow(prefs.getString(KEY_STORAGE, null) ?: "primary")
    /** Where music is kept: "primary" (phone memory) or an SD card volume id. */
    val storageId: StateFlow<String> = _storageId.asStateFlow()

    private val _folders = MutableStateFlow(readJson(KEY_FOLDERS, ListSerializer(FolderInfo.serializer())).orEmpty())
    /** Folders the PC shares (cached from the last connection). */
    val folders: StateFlow<List<FolderInfo>> = _folders.asStateFlow()

    private val _mappings = MutableStateFlow(
        readJson(KEY_MAPPINGS, MapSerializer(String.serializer(), FolderMapping.serializer())).orEmpty(),
    )
    val mappings: StateFlow<Map<String, FolderMapping>> = _mappings.asStateFlow()

    private val _lastSync = MutableStateFlow(readJson(KEY_LAST_SYNC, LastSync.serializer()))
    val lastSync: StateFlow<LastSync?> = _lastSync.asStateFlow()

    @Synchronized
    fun setServer(value: PairedServer?) {
        _server.value = value
        writeJson(KEY_SERVER, value, PairedServer.serializer())
        if (value == null) {
            setPaused(false)
            setLastSync(null)
            setFolders(emptyList())
            _mappings.value = emptyMap()
            prefs.edit().remove(KEY_MAPPINGS).apply()
        }
    }

    @Synchronized
    fun updateServer(transform: (PairedServer) -> PairedServer) {
        val current = _server.value ?: return
        setServer(transform(current))
    }

    fun setAutoSync(value: Boolean) {
        _autoSync.value = value
        prefs.edit().putBoolean(KEY_AUTO_SYNC, value).apply()
    }

    fun setPaused(value: Boolean) {
        _paused.value = value
        prefs.edit().putBoolean(KEY_PAUSED, value).commit()
    }

    fun setDeleteRemoved(value: Boolean) {
        _deleteRemoved.value = value
        prefs.edit().putBoolean(KEY_DELETE_REMOVED, value).apply()
    }

    fun setStorageId(value: String) {
        _storageId.value = value
        prefs.edit().putString(KEY_STORAGE, value).apply()
        setLastSync(null)
    }

    @Synchronized
    fun setFolders(value: List<FolderInfo>) {
        if (value == _folders.value) return
        _folders.value = value
        prefs.edit().putString(KEY_FOLDERS, Protocol.json.encodeToString(ListSerializer(FolderInfo.serializer()), value)).apply()
    }

    @Synchronized
    fun setMapping(folderId: String, mapping: FolderMapping) {
        val next = _mappings.value + (folderId to mapping)
        _mappings.value = next
        prefs.edit()
            .putString(KEY_MAPPINGS, Protocol.json.encodeToString(MapSerializer(String.serializer(), FolderMapping.serializer()), next))
            .apply()
    }

    fun setLastSync(value: LastSync?) {
        _lastSync.value = value
        writeJson(KEY_LAST_SYNC, value, LastSync.serializer())
    }

    private fun <T> readJson(key: String, serializer: KSerializer<T>): T? = try {
        prefs.getString(key, null)?.let { Protocol.json.decodeFromString(serializer, it) }
    } catch (_: Throwable) {
        null
    }

    private fun <T : Any> writeJson(key: String, value: T?, serializer: KSerializer<T>) {
        val editor = prefs.edit()
        if (value == null) editor.remove(key) else editor.putString(key, Protocol.json.encodeToString(serializer, value))
        editor.apply()
    }

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_SERVER = "server"
        private const val KEY_AUTO_SYNC = "auto_sync"
        private const val KEY_PAUSED = "paused"
        private const val KEY_DELETE_REMOVED = "delete_removed"
        private const val KEY_LAST_SYNC = "last_sync"
        private const val KEY_STORAGE = "storage_id"
        private const val KEY_FOLDERS = "folders"
        private const val KEY_MAPPINGS = "mappings"

        @Volatile
        private var instance: Config? = null

        fun get(context: Context): Config =
            instance ?: synchronized(this) {
                instance ?: Config(context.applicationContext).also { instance = it }
            }
    }
}
