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
import biz.fesh.syncmusic.shared.Direction
import biz.fesh.syncmusic.shared.FolderInfo
import biz.fesh.syncmusic.shared.Protocol
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/** A storage volume of the phone: internal memory or an SD card. */
data class Volume(
    val id: String,
    val label: String,
    val root: File,
    val removable: Boolean,
    /** Android 8–10: the SD card is writable only inside the app's own folder (removed with the app). */
    val appPrivate: Boolean,
    val freeBytes: Long,
    val totalBytes: Long,
)

/** A file found in a phone folder that is sent to the PC. */
data class LocalFile(val path: String, val file: File, val size: Long, val modified: Long)

object Storage {
    const val FOLDER_NAME = "Sync Music"
    const val PRIMARY = "primary"

    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    @Suppress("DEPRECATION")
    fun primaryRoot(): File = Environment.getExternalStorageDirectory()

    /** All volumes available right now; internal memory always comes first. */
    fun volumes(context: Context): List<Volume> {
        val result = mutableListOf<Volume>()
        val primary = primaryRoot()
        result += Volume(PRIMARY, "Пам'ять телефона", primary, false, false, free(primary), total(primary))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val sm = context.getSystemService(StorageManager::class.java)
                for (volume in sm?.storageVolumes.orEmpty()) {
                    if (volume.isPrimary || volume.state != Environment.MEDIA_MOUNTED) continue
                    val dir = volume.directory ?: continue
                    val uuid = volume.uuid ?: continue
                    val description = try { volume.getDescription(context) } catch (_: Throwable) { null }
                    val kind = if (volume.isRemovable) "SD-карта" else "Додаткова пам'ять"
                    val label = if (description.isNullOrBlank() || description.equals(kind, ignoreCase = true)) kind else "$kind ($description)"
                    result += Volume(uuid, label, dir, volume.isRemovable, false, free(dir), total(dir))
                }
            } catch (_: Throwable) {
            }
        } else {
            // Before Android 11 an app may write to the SD card only inside its own folder there.
            val dirs = try { context.getExternalFilesDirs(null) } catch (_: Throwable) { emptyArray() }
            for (dir in dirs.drop(1)) {
                if (dir == null) continue
                val mounted = try { Environment.getExternalStorageState(dir) == Environment.MEDIA_MOUNTED } catch (_: Throwable) { false }
                if (!mounted) continue
                val id = dir.absolutePath.removePrefix("/storage/").substringBefore('/')
                result += Volume(id, "SD-карта (папка застосунку)", dir, true, true, free(dir), total(dir))
            }
        }
        return result
    }

    /** The volume that contains [path] (longest matching root). */
    fun volumeOf(volumes: List<Volume>, path: String): Volume? =
        volumes.filter { path == it.root.path || path.startsWith(it.root.path.trimEnd('/') + "/") }
            .maxByOrNull { it.root.path.length }

    /** "Пам'ять телефона › Music/Sync Music" */
    fun describe(volumes: List<Volume>, path: String?): String? {
        if (path == null) return null
        val volume = volumeOf(volumes, path) ?: return path
        val relative = path.removePrefix(volume.root.path).trim('/')
        return if (relative.isEmpty()) volume.label else "${volume.label} › $relative"
    }

    /** Where version 1 kept the music for the old "Де зберігати музику" setting. */
    fun legacyMusicRoot(context: Context, storageId: String): String {
        if (storageId == PRIMARY) return File(primaryRoot(), "${Environment.DIRECTORY_MUSIC}/$FOLDER_NAME").path
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return "/storage/$storageId/${Environment.DIRECTORY_MUSIC}/$FOLDER_NAME"
        val dirs = try { context.getExternalFilesDirs(Environment.DIRECTORY_MUSIC) } catch (_: Throwable) { emptyArray() }
        val dir = dirs.filterNotNull().firstOrNull { it.path.startsWith("/storage/$storageId/") }
        return File(dir ?: File("/storage/$storageId/Android/data/${context.packageName}/files/Music"), FOLDER_NAME).path
    }

    /** Suggested phone folder for a PC → phone folder that has no folder chosen yet. */
    fun defaultFolderPath(context: Context, folder: FolderInfo, legacyStorageId: String, taken: Collection<String>): String {
        if (folder.id == Protocol.LEGACY_FOLDER_ID) return legacyMusicRoot(context, legacyStorageId)
        val safeName = folder.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "Папка" }
        val base = File(primaryRoot(), "$FOLDER_NAME/$safeName").path
        var candidate = base
        var n = 2
        while (taken.any { it.equals(candidate, ignoreCase = true) }) candidate = "$base ($n)".also { n++ }
        return candidate
    }

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

    /** All files under [root] that are sent to the PC (hidden files and system junk are skipped). */
    fun listLocal(root: File): List<LocalFile> {
        val out = ArrayList<LocalFile>()
        fun walk(dir: File, prefix: String) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                val name = child.name
                if (child.isDirectory) {
                    if (Protocol.isSyncableDir(name)) walk(child, "$prefix$name/")
                } else if (Protocol.isSyncable(name)) {
                    out += LocalFile("$prefix$name", child, child.length(), child.lastModified())
                }
            }
        }
        walk(root, "")
        return out
    }

    /**
     * Removes the given folders (and then their parents) while they are empty, deepest first,
     * never touching [root] itself. rmdir fails on a non-empty folder, so no listing is needed.
     */
    fun pruneDirs(root: File, dirs: Collection<File>) {
        val rootPrefix = root.absolutePath.trimEnd('/') + "/"
        val tried = HashSet<String>()
        var current: List<File> = dirs.toList()
        while (current.isNotEmpty()) {
            val next = HashSet<File>()
            for (dir in current.sortedByDescending { it.absolutePath.length }) {
                val path = dir.absolutePath
                if (!path.startsWith(rootPrefix) || !tried.add(path)) continue
                if (dir.delete()) dir.parentFile?.let(next::add)
            }
            current = next.toList()
        }
    }

    /** Deletes the files this app downloaded into [db]'s folder and forgets them. Returns how many. */
    fun wipe(context: Context, db: SyncedDb): Int {
        val root = File(db.root)
        val removed = ArrayList<String>()
        val parents = HashSet<File>()
        for (path in db.paths) {
            val file = resolve(root, path) ?: continue
            if (file.delete()) {
                removed += file.absolutePath
                file.parentFile?.let(parents::add)
            }
        }
        pruneDirs(root, parents)
        File(root, Protocol.TEMP_DIR).deleteRecursively()
        if (root.isDirectory && root.list()?.isEmpty() == true) root.delete()
        db.clear()
        scan(context, removed)
        return removed.size
    }
}

@Serializable
data class SyncedRecord(val s: Long, val m: Long)

@Serializable
private data class DbFile(val root: String, val files: Map<String, SyncedRecord>)

/**
 * Remembers which files were synced for one folder (path -> size/mtime): for PC → phone folders
 * the files this app downloaded, for phone → PC folders the files it uploaded.
 * The record is tied to the phone folder ([root]); choosing another folder starts from scratch.
 */
class SyncedDb private constructor(private val file: File, val root: String, initial: Map<String, SyncedRecord>) {
    private val map = HashMap(initial)
    private var dirty = false

    operator fun get(path: String): SyncedRecord? = map[path]
    val paths: Set<String> get() = map.keys.toSet()
    val size: Int get() = map.size

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
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(Protocol.json.encodeToString(DbFile.serializer(), DbFile(root, map)))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
            dirty = false
        } catch (_: Throwable) {
        }
    }

    companion object {
        private val legacySerializer = MapSerializer(String.serializer(), SyncedRecord.serializer())

        private fun safe(text: String) = text.replace(Regex("[^A-Za-z0-9_-]"), "_")

        fun load(
            context: Context,
            serverId: String,
            folderId: String,
            direction: Direction,
            root: String,
            legacyStorageId: String,
        ): SyncedDb {
            val file = File(context.filesDir, "sync/${safe(serverId)}/${safe(folderId)}-${direction.name}.json")
            if (file.isFile) {
                val stored = try {
                    Protocol.json.decodeFromString(DbFile.serializer(), file.readText())
                } catch (_: Throwable) {
                    null
                }
                return SyncedDb(file, root, if (stored != null && stored.root == root) stored.files else emptyMap())
            }
            // Version 1 kept one list for the music folder: take it over so nothing is downloaded again.
            if (folderId == Protocol.LEGACY_FOLDER_ID && direction == Direction.TO_PHONE &&
                root == Storage.legacyMusicRoot(context, legacyStorageId)
            ) {
                val suffix = if (legacyStorageId == Storage.PRIMARY) "" else "-" + safe(legacyStorageId)
                val legacy = File(context.filesDir, "synced-$serverId$suffix.json")
                val data = try {
                    if (legacy.isFile) Protocol.json.decodeFromString(legacySerializer, legacy.readText()) else emptyMap()
                } catch (_: Throwable) {
                    emptyMap()
                }
                return SyncedDb(file, root, data).also { if (data.isNotEmpty()) it.dirty = true }
            }
            return SyncedDb(file, root, emptyMap())
        }
    }
}
