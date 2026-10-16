package biz.fesh.syncmusic.desktop

import biz.fesh.syncmusic.shared.Manifest
import biz.fesh.syncmusic.shared.ManifestEntry
import biz.fesh.syncmusic.shared.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

data class LibraryStats(
    val root: String? = null,
    val files: Int = 0,
    val bytes: Long = 0,
    val scanning: Boolean = false,
    val scannedAt: Long = 0,
    val error: String? = null,
)

/** Scans the music folder and serves the manifest + file lookups. */
class Library {
    private class Snapshot(
        val root: String,
        val entries: List<ManifestEntry>,
        val byPath: Map<String, File>,
        val scannedAt: Long,
    )

    private val mutex = Mutex()

    @Volatile
    private var snapshot: Snapshot? = null

    @Volatile
    private var root: String? = null

    private val _stats = MutableStateFlow(LibraryStats())
    val stats: StateFlow<LibraryStats> = _stats.asStateFlow()

    fun setRoot(path: String?) {
        root = path
        snapshot = null
        _stats.value = LibraryStats(root = path)
    }

    fun fileFor(path: String): File? = snapshot?.byPath?.get(path)

    suspend fun manifest(serverId: String, maxAgeMs: Long = 5_000): Manifest {
        val snap = ensureFresh(maxAgeMs)
        return Manifest(serverId = serverId, generatedAt = System.currentTimeMillis(), files = snap?.entries.orEmpty())
    }

    suspend fun rescan() {
        ensureFresh(0)
    }

    suspend fun refreshIfOlderThan(maxAgeMs: Long) {
        ensureFresh(maxAgeMs)
    }

    private suspend fun ensureFresh(maxAgeMs: Long): Snapshot? = mutex.withLock {
        val currentRoot = root ?: return@withLock null
        val existing = snapshot
        if (existing != null && existing.root == currentRoot &&
            System.currentTimeMillis() - existing.scannedAt <= maxAgeMs
        ) {
            return@withLock existing
        }
        _stats.value = _stats.value.copy(root = currentRoot, scanning = true)
        val started = System.currentTimeMillis()
        val fresh = withContext(Dispatchers.IO) { scan(currentRoot) }
        val previous = existing?.entries?.size
        if (previous != fresh.first.entries.size) {
            AppLog.i("Scanned ${fresh.first.entries.size} files in ${System.currentTimeMillis() - started} ms (was $previous)")
        }
        if (root == currentRoot) {
            snapshot = fresh.first
            _stats.value = LibraryStats(
                root = currentRoot,
                files = fresh.first.entries.size,
                bytes = fresh.first.entries.sumOf { it.size },
                scanning = false,
                scannedAt = fresh.first.scannedAt,
                error = fresh.second,
            )
        }
        fresh.first
    }

    private fun scan(rootPath: String): Pair<Snapshot, String?> {
        val rootDir = File(rootPath)
        if (!rootDir.isDirectory) {
            return Snapshot(rootPath, emptyList(), emptyMap(), System.currentTimeMillis()) to "Папку не знайдено"
        }
        val rootNio = rootDir.toPath()
        val entries = ArrayList<ManifestEntry>()
        val byPath = HashMap<String, File>()
        try {
            Files.walkFileTree(rootNio, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = dir.fileName?.toString() ?: return FileVisitResult.CONTINUE
                    if (dir != rootNio && (name.startsWith(".") || name.equals("\$RECYCLE.BIN", true))) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                    val name = file.fileName.toString()
                    if (!Protocol.isSyncable(name)) return FileVisitResult.CONTINUE
                    val rel = rootNio.relativize(file).joinToString("/") { it.toString() }
                    entries += ManifestEntry(rel, attrs.size(), attrs.lastModifiedTime().toMillis())
                    byPath[rel] = file.toFile()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        } catch (t: Throwable) {
            AppLog.e("Scan failed for $rootPath", t)
            return Snapshot(rootPath, entries, byPath, System.currentTimeMillis()) to "Помилка читання папки: ${t.message}"
        }
        entries.sortBy { it.path.lowercase() }
        return Snapshot(rootPath, entries, byPath, System.currentTimeMillis()) to null
    }
}
