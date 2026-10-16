package biz.fesh.syncmusic.desktop

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.text.SimpleDateFormat
import java.util.Date

object AppPaths {
    val dataDir: File by lazy {
        val base = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
            ?: (System.getProperty("user.home") + File.separator + ".config")
        File(base, "SyncMusic").apply { mkdirs() }
    }

    fun defaultMusicFolder(): String? {
        val home = System.getProperty("user.home") ?: return null
        val candidates = listOf(File(home, "Music"), File(home, "Музика"), File(home, "Музыка"))
        return candidates.firstOrNull { it.isDirectory }?.absolutePath
    }
}

/** Tiny file logger: %APPDATA%\SyncMusic\syncmusic.log (rotated at 1 MB). */
object AppLog {
    private val file by lazy { File(AppPaths.dataDir, "syncmusic.log") }
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

    @Synchronized
    fun write(level: String, message: String, error: Throwable? = null) {
        try {
            if (file.length() > 1_000_000) {
                val old = File(file.parentFile, "syncmusic.old.log")
                old.delete()
                file.renameTo(old)
            }
            val line = buildString {
                append(fmt.format(Date())).append(' ').append(level).append(' ').append(message)
                if (error != null) append(" :: ").append(error.toString())
                append('\n')
            }
            file.appendText(line)
        } catch (_: Throwable) {
        }
    }

    fun i(message: String) = write("INFO", message)
    fun w(message: String, error: Throwable? = null) = write("WARN", message, error)
    fun e(message: String, error: Throwable? = null) = write("ERROR", message, error)
}

/** Ensures only one copy of the app runs; a second launch asks the first one to show its window. */
object SingleInstance {
    private var channel: FileChannel? = null
    private var lock: FileLock? = null

    fun acquire(): Boolean = try {
        val raf = RandomAccessFile(File(AppPaths.dataDir, "app.lock"), "rw")
        val ch = raf.channel
        val l = ch.tryLock()
        if (l == null) {
            ch.close()
            false
        } else {
            channel = ch
            lock = l
            true
        }
    } catch (t: Throwable) {
        AppLog.w("Single-instance lock failed", t)
        true
    }
}

object NetUtil {
    private val virtualMarkers = listOf(
        "virtual", "vmware", "vbox", "virtualbox", "hyper-v", "vethernet", "wsl", "docker",
        "loopback", "bluetooth", "tap-", "tunnel", "teredo", "zerotier", "tailscale", "wireguard", "npcap",
    )

    data class LocalAddress(val address: String, val interfaceName: String)

    /** IPv4 addresses the phone can reach, best candidates first. */
    fun localAddresses(): List<LocalAddress> {
        val all = mutableListOf<Pair<LocalAddress, Boolean>>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (ni in ifaces) {
                val up = try { ni.isUp && !ni.isLoopback } catch (_: Throwable) { false }
                if (!up) continue
                val name = (ni.displayName ?: ni.name ?: "").lowercase()
                val flaggedVirtual = try { ni.isVirtual } catch (_: Throwable) { false }
                val virtual = flaggedVirtual || virtualMarkers.any { name.contains(it) }
                for (addr in ni.inetAddresses.toList()) {
                    if (addr !is Inet4Address || addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                    all += LocalAddress(addr.hostAddress, ni.displayName ?: ni.name) to virtual
                }
            }
        } catch (t: Throwable) {
            AppLog.w("Cannot enumerate network interfaces", t)
        }
        val real = all.filter { !it.second }.map { it.first }
        val list = real.ifEmpty { all.map { it.first } }
        return list.sortedBy { addr ->
            when {
                addr.address.startsWith("192.168.") -> 0
                addr.address.startsWith("10.") -> 1
                addr.address.startsWith("172.") -> 2
                else -> 3
            }
        }
    }
}

/** The Android APK bundled into the PC app at build time, served at /app.apk. */
object ApkResource {
    val versionCode: Int by lazy {
        try {
            javaClass.getResourceAsStream("/syncmusic-apk.txt")?.use { it.bufferedReader().readText().trim().toIntOrNull() } ?: 0
        } catch (_: Throwable) {
            0
        }
    }

    val bytes: ByteArray? by lazy {
        try {
            javaClass.getResourceAsStream("/syncmusic.apk")?.use(InputStream::readBytes)
        } catch (_: Throwable) {
            null
        }
    }

    val available: Boolean get() = javaClass.getResource("/syncmusic.apk") != null
}

object BuildInfo {
    val version: String by lazy {
        try {
            javaClass.getResourceAsStream("/syncmusic-version.txt")?.use { it.bufferedReader().readText().trim() } ?: "dev"
        } catch (_: Throwable) {
            "dev"
        }
    }
}
