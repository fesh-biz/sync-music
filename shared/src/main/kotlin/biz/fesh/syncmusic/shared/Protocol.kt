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
)

@Serializable
data class FolderList(val folders: List<FolderInfo>)

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
