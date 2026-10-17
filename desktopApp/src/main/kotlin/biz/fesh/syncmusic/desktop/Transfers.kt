package biz.fesh.syncmusic.desktop

import biz.fesh.syncmusic.shared.Protocol
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

sealed interface UploadOutcome {
    data object Done : UploadOutcome
    data class Partial(val received: Long) : UploadOutcome
    data class Conflict(val have: Long) : UploadOutcome
    data object NoSpace : UploadOutcome
    data class Rejected(val message: String) : UploadOutcome
}

/**
 * Receiving files from the phone (phone → PC folders).
 *
 * Rule: nothing that is already on the PC is ever deleted or overwritten. A file that arrives
 * under a name that is taken by a different file is saved next to it as "name (з телефона).ext".
 */
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
                val dest = destinationFor(target, part)
                if (dest == null) {
                    part.delete() // the same file is already on the PC; only our temporary copy goes
                } else {
                    // No REPLACE_EXISTING: if the name got taken meanwhile the move fails instead of overwriting.
                    Files.move(part.toPath(), dest.toPath())
                    if (modified > 0) dest.setLastModified(modified)
                    if (dest != target) AppLog.i("Kept the existing $relative, saved the phone's version as ${dest.name}")
                }
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
     * Where a fully received file goes without touching anything on the PC:
     * the target itself when the name is free, null when an identical file is already there
     * (under the name or as an earlier "(з телефона)" copy), otherwise the first free copy name.
     */
    private fun destinationFor(target: File, received: File): File? {
        if (!target.exists()) return target
        if (sameContent(target, received)) return null
        val dot = target.name.lastIndexOf('.')
        val base = if (dot > 0) target.name.substring(0, dot) else target.name
        val ext = if (dot > 0) target.name.substring(dot) else ""
        var n = 1
        while (true) {
            val label = if (n == 1) "з телефона" else "з телефона $n"
            val candidate = File(target.parentFile, "$base ($label)$ext")
            if (!candidate.exists()) return candidate
            if (sameContent(candidate, received)) return null
            n++
        }
    }

    private fun sameContent(a: File, b: File): Boolean {
        if (!a.isFile || !b.isFile || a.length() != b.length()) return false
        a.inputStream().buffered(256 * 1024).use { x ->
            b.inputStream().buffered(256 * 1024).use { y ->
                val bx = ByteArray(64 * 1024)
                val by = ByteArray(64 * 1024)
                while (true) {
                    val nx = x.readNBytes(bx, 0, bx.size)
                    val ny = y.readNBytes(by, 0, by.size)
                    if (nx != ny || !java.util.Arrays.equals(bx, 0, nx, by, 0, ny)) return false
                    if (nx == 0) return true
                }
            }
        }
    }
}
