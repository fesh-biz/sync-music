package biz.fesh.syncmusic.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire protocol shared by the PC server and the Android client.
 *
 * Transport: plain HTTP inside the local network.
 *  - Discovery: the phone broadcasts [Protocol.DISCOVERY_MAGIC] over UDP to port
 *    [Protocol.DISCOVERY_PORT]; every running PC answers with a [ServerInfo] JSON.
 *  - Pairing: the phone sends the 6-digit code shown on the PC and gets a token.
 *  - Folders: the PC shares several folders ([FolderInfo]); each one is synced either
 *    PC → phone ([Direction.TO_PHONE]) or phone → PC ([Direction.TO_PC]).
 *  - PC → phone: the phone pulls the [Manifest] and downloads files (HTTP Range for resume).
 *  - Phone → PC: the phone uploads files (PUT with an offset for resume). Nothing on the PC is
 *    ever deleted or overwritten: a different file under a taken name is saved as a copy.
 *  - The phone reports [SyncProgress] back so the PC can show it.
 *  - The phone reports its storages and folders ([PhoneStorage]) once per sync, so the PC app can
 *    choose the phone folder for a PC folder ([FolderInfo.phonePath]); the phone applies it on its next sync.
 */
object Protocol {
    const val VERSION = 2
    const val DISCOVERY_PORT = 45455
    const val HTTP_PORT = 45456
    const val DISCOVERY_MAGIC = "SYNCMUSIC_DISCOVER_V1"

    /** Id of the folder created from the music folder of version 1 (kept for upgrades). */
    const val LEGACY_FOLDER_ID = "music"

    /** Name of the hidden folder with partial files inside every synced folder. */
    const val TEMP_DIR = ".syncmusic"

    private val junk = setOf("thumbs.db", "desktop.ini", "ehthumbs.db")

    /** All files are synced except hidden ones and system junk (Thumbs.db, desktop.ini, Office lock files). */
    fun isSyncable(fileName: String): Boolean {
        if (fileName.isEmpty() || fileName.startsWith(".") || fileName.startsWith("~$")) return false
        return fileName.lowercase() !in junk
    }

    /** [isSyncable] and of the kind the folder syncs ([FileTypes]). */
    fun isSyncable(fileName: String, types: FileTypes): Boolean = isSyncable(fileName) && types.matches(fileName)

    /** Folder names that are never descended into. */
    fun isSyncableDir(dirName: String): Boolean =
        !dirName.startsWith(".") && !dirName.equals("\$RECYCLE.BIN", ignoreCase = true) &&
            !dirName.equals("System Volume Information", ignoreCase = true)

    /**
     * Validates a relative path coming over the network ('/'-separated).
     * Rejects absolute paths, '..', empty segments and characters Windows cannot store.
     */
    fun isSafeRelativePath(path: String): Boolean {
        if (path.isBlank() || path.length > 1024) return false
        val parts = path.split('/')
        return parts.all { part ->
            part.isNotEmpty() && part != "." && part != ".." && !part.endsWith(" ") && !part.endsWith(".") &&
                part.none { it in "<>:\"\\|?*" || it.code < 32 }
        }
    }

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
}

@Serializable
data class ServerInfo(
    val serverId: String,
    val serverName: String,
    val port: Int,
    val protocolVersion: Int = Protocol.VERSION,
    val appVersion: String = "",
    /** versionCode of the Android app bundled into the PC app (0 = none). */
    val apkVersionCode: Int = 0,
)

@Serializable
data class PairRequest(
    val pin: String,
    val deviceId: String,
    val deviceName: String,
)

@Serializable
data class PairResponse(
    val token: String,
    val serverId: String,
    val serverName: String,
)

@Serializable
data class ErrorResponse(val error: String)

@Serializable
enum class Direction {
    /** The phone downloads files from the PC. */
    TO_PHONE,

    /** The PC receives files from the phone. */
    TO_PC,
}

/** Which files of a folder are synced. */
@Serializable
enum class FileTypes {
    ALL,

    /** Audio files, playlists, cue sheets and lyrics. */
    MUSIC,

    /** Video files and subtitles. */
    MOVIES,
    ;

    fun matches(fileName: String): Boolean {
        if (this == ALL) return true
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (this) {
            MUSIC -> ext in MUSIC_EXTENSIONS
            MOVIES -> ext in MOVIE_EXTENSIONS
            ALL -> true
        }
    }

    companion object {
        val MUSIC_EXTENSIONS = setOf(
            "mp3", "flac", "m4a", "m4b", "aac", "ogg", "oga", "opus", "wav", "wma", "aif", "aiff", "aifc", "alac",
            "ape", "wv", "mpc", "tta", "mka", "dsf", "dff", "mid", "midi", "amr",
            "m3u", "m3u8", "pls", "cue", "lrc",
        )
        val MOVIE_EXTENSIONS = setOf(
            "mp4", "m4v", "mkv", "avi", "mov", "wmv", "webm", "flv", "mpg", "mpeg", "mpe", "m2v", "ts", "m2ts", "mts",
            "vob", "3gp", "3g2", "ogv", "divx", "rm", "rmvb", "asf",
            "srt", "ass", "ssa", "sub", "idx", "vtt", "smi", "sup",
        )
    }
}

@Serializable
data class FolderInfo(
    val id: String,
    val name: String,
    val direction: Direction = Direction.TO_PHONE,
    /** PC → phone only: delete files on the phone when they disappear on the PC. Always false for phone → PC. */
    val deleteMissing: Boolean = true,
    /** Files currently in the folder on the PC. */
    val files: Int = 0,
    val bytes: Long = 0,
    val available: Boolean = true,
    /** Folder on the requesting phone chosen in the PC app (absolute path); null = chosen on the phone. */
    val phonePath: String? = null,
    /** When [phonePath] was chosen (PC clock). The phone applies every choice once, so it can still change it later. */
    val phonePathAt: Long = 0,
    /** PC → phone: if the files cannot be moved to [phonePath] (another storage), delete the copies in the old phone folder. */
    val phoneDeleteOld: Boolean = false,
    /** Which files are synced (older PCs: all). The PC lists only these; the phone uploads only these. */
    val fileTypes: FileTypes = FileTypes.ALL,
)

@Serializable
data class FolderList(val folders: List<FolderInfo>)

/** A storage of the phone (internal memory or an SD card) with its folders, so the PC can offer them. */
@Serializable
data class PhoneVolume(
    val id: String,
    val label: String,
    /** Absolute path of the volume on the phone, e.g. /storage/emulated/0. */
    val root: String,
    val removable: Boolean = false,
    /** Android 8–10 SD card: only the app's own folder is writable (it is removed with the app). */
    val appPrivate: Boolean = false,
    val freeBytes: Long = -1,
    val totalBytes: Long = -1,
    /** Folders inside the volume, relative to [root] with '/' separators, a few levels deep. */
    val dirs: List<String> = emptyList(),
    /** Not all folders fit into [dirs]. */
    val truncated: Boolean = false,
)

@Serializable
data class PhoneStorage(
    val volumes: List<PhoneVolume> = emptyList(),
    val at: Long = 0,
) {
    fun volumeOf(path: String): PhoneVolume? = volumes.filter { inside(path, it.root) }.maxByOrNull { it.root.length }

    /** "Пам'ять телефона › Music/Sync Music" */
    fun describe(path: String): String {
        val volume = volumeOf(path)
        if (volume == null) {
            if (inside(path, PRIMARY_ROOT)) return label(PRIMARY_LABEL, path.removePrefix(PRIMARY_ROOT))
            return path
        }
        return label(volume.label, path.removePrefix(volume.root))
    }

    companion object {
        /** Internal memory on practically every Android phone (used before the phone reported its storage). */
        const val PRIMARY_ROOT = "/storage/emulated/0"
        const val PRIMARY_LABEL = "Пам'ять телефона"

        fun inside(path: String, root: String): Boolean = path == root || path.startsWith(root.trimEnd('/') + "/")

        private fun label(volume: String, relative: String): String {
            val rel = relative.trim('/')
            return if (rel.isEmpty()) volume else "$volume › $rel"
        }

        /** A folder name the phone and Windows both accept. */
        fun isValidName(name: String): Boolean =
            name.isNotBlank() && name == name.trim() && !name.startsWith(".") && !name.endsWith(".") &&
                name.none { it in "/\\<>:\"|?*" || it.code < 32 }
    }
}

@Serializable
data class UploadOffset(
    /** Bytes of this file the PC already has (partial upload). */
    val offset: Long,
    val complete: Boolean = false,
)

@Serializable
data class ManifestEntry(
    /** Path relative to the synced folder, always with '/' separators. */
    val path: String,
    val size: Long,
    /** Last-modified time, epoch millis. */
    val modified: Long,
)

@Serializable
data class Manifest(
    val serverId: String,
    val generatedAt: Long,
    val files: List<ManifestEntry>,
)

@Serializable
enum class SyncPhase {
    CONNECTING,
    COMPARING,
    DOWNLOADING,
    UPLOADING,
    DELETING,
    DONE,
    ERROR,
    STOPPED,
    ;

    val isActive: Boolean
        get() = this == CONNECTING || this == COMPARING || this == DOWNLOADING || this == UPLOADING || this == DELETING
}

@Serializable
data class FolderProgress(
    val id: String,
    val name: String,
    val direction: Direction,
    val totalFiles: Int = 0,
    val totalBytes: Long = 0,
    val syncedFiles: Int = 0,
    val syncedBytes: Long = 0,
    /** Folder on the phone, shown to the user (null = not chosen yet). */
    val localPath: String? = null,
    val enabled: Boolean = true,
    val message: String? = null,
    /** Absolute path of the phone folder (null = not chosen yet). */
    val phonePath: String? = null,
    /** The last folder choice made on the PC that the phone has applied ([FolderInfo.phonePathAt]). */
    val pcChoiceAt: Long = 0,
) {
    val remainingFiles: Int get() = (totalFiles - syncedFiles).coerceAtLeast(0)
    val remainingBytes: Long get() = (totalBytes - syncedBytes).coerceAtLeast(0)
}

@Serializable
data class SyncProgress(
    val phase: SyncPhase,
    /** All files of all synced folders. */
    val totalFiles: Int = 0,
    val totalBytes: Long = 0,
    /** Already on the receiving side and up to date (bytes include the partially transferred current file). */
    val syncedFiles: Int = 0,
    val syncedBytes: Long = 0,
    val currentFile: String? = null,
    val bytesPerSecond: Long = 0,
    val downloadedThisRun: Int = 0,
    val deletedThisRun: Int = 0,
    val message: String? = null,
    val freeSpaceBytes: Long = -1,
    /** Where the phone keeps the files (version 1: "SD-карта" / "пам'ять телефона"). */
    val storage: String? = null,
    val uploadedThisRun: Int = 0,
    val folders: List<FolderProgress> = emptyList(),
    val appVersionCode: Int = 0,
    val timestamp: Long = 0,
) {
    val remainingFiles: Int get() = (totalFiles - syncedFiles).coerceAtLeast(0)
    val remainingBytes: Long get() = (totalBytes - syncedBytes).coerceAtLeast(0)
    val fraction: Float
        get() = when {
            totalBytes > 0 -> (syncedBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
            totalFiles > 0 -> (syncedFiles.toFloat() / totalFiles).coerceIn(0f, 1f)
            else -> 1f
        }
}
