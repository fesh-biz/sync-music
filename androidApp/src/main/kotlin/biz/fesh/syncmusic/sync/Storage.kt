package biz.fesh.syncmusic.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.core.content.ContextCompat
import biz.fesh.syncmusic.shared.Protocol
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/** A place on the phone where music can be kept: internal memory or an SD card. */
data class StorageOption(
    /** [Storage.PRIMARY] or the volume UUID (e.g. "1A2B-3C4D"). */
    val id: String,
    /** Name shown to the user, e.g. "Пам'ять телефона" or "SD-карта SanDisk". */
    val label: String,
    /** Short form for messages and the PC screen. */
    val shortLabel: String,
    val musicRoot: File,
    /** Path shown to the user, relative to the volume. */
    val displayPath: String,
    val removable: Boolean,
    /** Android 8–10: SD card is writable only inside the app's own folder, removed with the app. */
    val appPrivate: Boolean,
    val freeBytes: Long,
    val totalBytes: Long,
)

object Storage {
    const val FOLDER_NAME = "Sync Music"
    const val PRIMARY = "primary"

    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    /** All places available right now; internal memory always comes first. */
    fun options(context: Context): List<StorageOption> {
        val result = mutableListOf<StorageOption>()

        @Suppress("DEPRECATION")
        val primaryRoot = Environment.getExternalStorageDirectory()
        result += StorageOption(
            id = PRIMARY,
            label = "Пам'ять телефона",
            shortLabel = "пам'ять телефона",
            musicRoot = File(primaryRoot, "${Environment.DIRECTORY_MUSIC}/$FOLDER_NAME"),
            displayPath = "Music/$FOLDER_NAME",
            removable = false,
            appPrivate = false,
            freeBytes = free(primaryRoot),
            totalBytes = total(primaryRoot),
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val sm = context.getSystemService(StorageManager::class.java)
                for (volume in sm?.storageVolumes.orEmpty()) {
                    if (volume.isPrimary) continue
                    if (volume.state != Environment.MEDIA_MOUNTED) continue
                    val dir = volume.directory ?: continue
                    val uuid = volume.uuid ?: continue
                    val description = try { volume.getDescription(context) } catch (_: Throwable) { null }
                    val kind = if (volume.isRemovable) "SD-карта" else "Додаткова пам'ять"
                    val label = if (description.isNullOrBlank() || description.equals(kind, ignoreCase = true)) kind else "$kind ($description)"
                    result += StorageOption(
                        id = uuid,
                        label = label,
                        shortLabel = kind,
                        musicRoot = File(dir, "${Environment.DIRECTORY_MUSIC}/$FOLDER_NAME"),
                        displayPath = "Music/$FOLDER_NAME",
                        removable = volume.isRemovable,
                        appPrivate = false,
                        freeBytes = free(dir),
                        totalBytes = total(dir),
                    )
                }
            } catch (_: Throwable) {
            }
        } else {
            // Before Android 11 an app may write to the SD card only inside its own folder there.
            val dirs = try { context.getExternalFilesDirs(Environment.DIRECTORY_MUSIC) } catch (_: Throwable) { emptyArray() }
            for (dir in dirs.drop(1)) {
                if (dir == null) continue
                val mounted = try { Environment.getExternalStorageState(dir) == Environment.MEDIA_MOUNTED } catch (_: Throwable) { false }
                if (!mounted) continue
                val volumeName = dir.absolutePath.removePrefix("/storage/").substringBefore('/')
                result += StorageOption(
                    id = volumeName,
                    label = "SD-карта",
                    shortLabel = "SD-карта",
                    musicRoot = File(dir, FOLDER_NAME),
                    displayPath = "Android/data/${context.packageName}/files/Music/$FOLDER_NAME",
                    removable = true,
                    appPrivate = true,
                    freeBytes = free(dir),
                    totalBytes = total(dir),
                )
            }
        }
        return result
    }

    /** The option with [id], or null if that card is not inserted right now. */
    fun find(context: Context, id: String): StorageOption? = options(context).firstOrNull { it.id == id }

    private fun free(dir: File): Long = try { StatFs(dir.path).availableBytes } catch (_: Throwable) { -1 }
    private fun total(dir: File): Long = try { StatFs(dir.path).totalBytes } catch (_: Throwable) { -1 }

    fun freeBytes(dir: File): Long {
        var d: File? = dir
        while (d != null && !d.exists()) d = d.parentFile
        return if (d == null) -1 else free(d)
    }

    fun scan(context: Context, paths: Collection<String>) {
        if (paths.isEmpty()) return
        try {
            MediaScannerConnection.scanFile(context.applicationContext, paths.toTypedArray(), null, null)
        } catch (_: Throwable) {
        }
    }

    /** Resolves a manifest path under [root], refusing anything that escapes it. */
    fun resolve(root: File, relative: String): File? {
        if (relative.isBlank() || relative.contains('\u0000')) return null
        val parts = relative.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        return File(root, parts.joinToString(File.separator))
    }

    /** Removes empty folders between [file]'s parent and [root]. */
    fun pruneEmptyParents(root: File, file: File) {
        var dir = file.parentFile
        val rootPath = root.absolutePath
        while (dir != null && dir.absolutePath.startsWith(rootPath) && dir.absolutePath != rootPath) {
            val children = dir.list()
            if (children == null || children.isNotEmpty()) return
            if (!dir.delete()) return
            dir = dir.parentFile
        }
    }

    /**
     * Deletes the music this app put into [option] (only files it downloaded itself)
     * and forgets them. Returns the number of deleted files.
     */
    fun wipe(context: Context, serverId: String, option: StorageOption): Int {
        val db = SyncedDb.load(context, serverId, option.id)
        val root = option.musicRoot
        val removed = ArrayList<String>()
        for (path in db.paths) {
            val file = resolve(root, path) ?: continue
            if (file.isFile && file.delete()) {
                removed += file.absolutePath
                pruneEmptyParents(root, file)
            }
        }
        File(root, ".syncmusic").deleteRecursively()
        if (root.isDirectory && root.list()?.isEmpty() == true) root.delete()
        db.clear()
        scan(context, removed)
        return removed.size
    }
}

@Serializable
data class SyncedRecord(val s: Long, val m: Long)

/** Remembers which files this app put on the phone (path -> size/mtime as on the PC), per storage. */
class SyncedDb private constructor(private val file: File, initial: Map<String, SyncedRecord>) {
    private val map = HashMap(initial)
    private var dirty = false

    operator fun get(path: String): SyncedRecord? = map[path]
    val paths: Set<String> get() = map.keys.toSet()

    fun put(path: String, record: SyncedRecord) {
        map[path] = record
        dirty = true
    }

    fun remove(path: String) {
        if (map.remove(path) != null) dirty = true
    }

    fun clear() {
        map.clear()
        dirty = false
        file.delete()
    }

    fun save() {
        if (!dirty) return
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(Protocol.json.encodeToString(serializer, map))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
            dirty = false
        } catch (_: Throwable) {
        }
    }

    companion object {
        private val serializer = MapSerializer(String.serializer(), SyncedRecord.serializer())

        fun load(context: Context, serverId: String, storageId: String): SyncedDb {
            val suffix = if (storageId == Storage.PRIMARY) "" else "-" + storageId.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val file = File(context.filesDir, "synced-$serverId$suffix.json")
            val data = try {
                if (file.isFile) Protocol.json.decodeFromString(serializer, file.readText()) else emptyMap()
            } catch (_: Throwable) {
                emptyMap()
            }
            return SyncedDb(file, data)
        }
    }
}
