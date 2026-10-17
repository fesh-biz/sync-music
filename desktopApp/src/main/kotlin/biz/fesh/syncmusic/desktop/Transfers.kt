package biz.fesh.syncmusic.desktop

import biz.fesh.syncmusic.shared.Protocol
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

sealed interface UploadOutcome {
    data object Done : UploadOutcome
    data class Partial(val received: Long) : UploadOutcome
    data class Conflict(val have: Long) : UploadOutcome
    data object NoSpace : UploadOutcome
    data class Rejected(val message: String) : UploadOutcome
}

/** Receiving files from the phone (phone → PC folders) and deleting them on request. */
object Transfers {
    private const val RESERVE_BYTES = 50L * 1024 * 1024
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun lockFor(key: String): Mutex = locks.computeIfAbsent(key) { Mutex() }

    /** File for a phone-supplied relative path inside [root], or null if the path is unsafe. */
    fun resolve(root: File, relative: String): File? {
        if (!Protocol.isSafeRelativePath(relative)) return null
        val file = File(root, relative.replace('/', File.separatorChar))
        return try {
            val rootPath = root.canonicalPath.trimEnd(File.separatorChar) + File.separator
            if (file.canonicalPath.startsWith(rootPath)) file else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun tempDir(root: File): File {
        val dir = File(root, Protocol.TEMP_DIR)
        if (!dir.isDirectory) {
            dir.mkdirs()
            if (WindowsIntegration.isWindows) {
                try {
                    Files.setAttribute(dir.toPath(), "dos:hidden", true)
                } catch (_: Throwable) {
                }
            }
        }
        return dir
    }

    private fun partFile(root: File, relative: String) = File(tempDir(root), sha1(relative) + ".part")

    /** How many bytes of this file were already received (resume point). */
    fun offset(root: File, relative: String, size: Long): Long {
        val part = partFile(root, relative)
        if (!part.isFile) return 0
        val length = part.length()
        if (length > size) {
            part.delete()
            return 0
        }
        return length
    }

    fun receive(root: File, relative: String, size: Long, modified: Long, offset: Long, input: InputStream): UploadOutcome {
        val target = resolve(root, relative) ?: return UploadOutcome.Rejected("Недопустима назва файлу для Windows: $relative")
        val part = partFile(root, relative)
        val have = if (part.isFile) part.length() else 0L
        if (have != offset) return UploadOutcome.Conflict(have)
        val free = root.usableSpace
        if (free > 0 && free < size - offset + RESERVE_BYTES) return UploadOutcome.NoSpace
        FileOutputStream(part, offset > 0).use { out -> input.copyTo(out, 256 * 1024) }
        val length = part.length()
        return when {
            length == size -> {
                target.parentFile?.mkdirs()
                try {
                    Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                if (modified > 0) target.setLastModified(modified)
                UploadOutcome.Done
            }
            length > size -> {
                part.delete()
                UploadOutcome.Rejected("Отримано більше даних, ніж очікувалося")
            }
            else -> UploadOutcome.Partial(length)
        }
    }

    /**
     * Deletes a file the phone uploaded earlier. [expectedSize] protects files changed on the PC
     * in the meantime: they are left alone. Returns false when the file was kept.
     */
    fun delete(root: File, relative: String, expectedSize: Long?): Boolean {
        val file = resolve(root, relative) ?: return false
        if (!file.isFile) return true
        if (expectedSize != null && file.length() != expectedSize) return false
        if (!file.delete()) return false
        var dir = file.parentFile
        val rootPath = root.canonicalPath
        while (dir != null && dir.canonicalPath != rootPath && dir.canonicalPath.startsWith(rootPath)) {
            if (!dir.delete()) break // fails when not empty
            dir = dir.parentFile
        }
        return true
    }
}
