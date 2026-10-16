package biz.fesh.syncmusic.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.core.content.ContextCompat
import biz.fesh.syncmusic.shared.Protocol
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

object Storage {
    const val FOLDER_NAME = "Sync Music"

    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    @Suppress("DEPRECATION")
    fun musicRoot(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), FOLDER_NAME)

    fun freeBytes(dir: File): Long = try {
        var d: File? = dir
        while (d != null && !d.exists()) d = d.parentFile
        StatFs((d ?: Environment.getExternalStorageDirectory()).path).availableBytes
    } catch (_: Throwable) {
        -1
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
}

@Serializable
data class SyncedRecord(val s: Long, val m: Long)

/** Remembers which files this app put on the phone (path -> size/mtime as on the PC). */
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

        fun load(context: Context, serverId: String): SyncedDb {
            val file = File(context.filesDir, "synced-$serverId.json")
            val data = try {
                if (file.isFile) Protocol.json.decodeFromString(serializer, file.readText()) else emptyMap()
            } catch (_: Throwable) {
                emptyMap()
            }
            return SyncedDb(file, data)
        }
    }
}
