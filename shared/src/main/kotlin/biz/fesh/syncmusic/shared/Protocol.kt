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
 *  - Sync: the phone pulls the [Manifest], downloads missing files (with HTTP Range
 *    for resume) and reports [SyncProgress] back so the PC can show it.
 */
object Protocol {
    const val VERSION = 1
    const val DISCOVERY_PORT = 45455
    const val HTTP_PORT = 45456
    const val DISCOVERY_MAGIC = "SYNCMUSIC_DISCOVER_V1"

    /** File types that are synced to the phone. */
    val SYNC_EXTENSIONS: Set<String> = setOf(
        // audio
        "mp3", "flac", "m4a", "m4b", "aac", "ogg", "oga", "opus", "wav", "wma",
        "alac", "aif", "aiff", "ape", "wv", "mka", "dsf", "dff", "mpc", "amr",
        // playlists and lyrics
        "m3u", "m3u8", "pls", "lrc",
    )

    fun isSyncable(fileName: String): Boolean {
        if (fileName.startsWith(".")) return false
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0 || dot == fileName.length - 1) return false
        return fileName.substring(dot + 1).lowercase() in SYNC_EXTENSIONS
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
data class ManifestEntry(
    /** Path relative to the music folder, always with '/' separators. */
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
    DELETING,
    DONE,
    ERROR,
    STOPPED,
    ;

    val isActive: Boolean get() = this == CONNECTING || this == COMPARING || this == DOWNLOADING || this == DELETING
}

@Serializable
data class SyncProgress(
    val phase: SyncPhase,
    /** Whole library on the PC. */
    val totalFiles: Int = 0,
    val totalBytes: Long = 0,
    /** Already on the phone and up to date (bytes include the partially downloaded current file). */
    val syncedFiles: Int = 0,
    val syncedBytes: Long = 0,
    val currentFile: String? = null,
    val bytesPerSecond: Long = 0,
    val downloadedThisRun: Int = 0,
    val deletedThisRun: Int = 0,
    val message: String? = null,
    val freeSpaceBytes: Long = -1,
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
