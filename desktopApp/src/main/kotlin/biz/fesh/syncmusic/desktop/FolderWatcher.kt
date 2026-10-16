package biz.fesh.syncmusic.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

/**
 * Watches the music folder and calls [onChange] once things settle (1.5 s without new events),
 * so deleted or added files show up on screen and for the phone right away.
 * On Windows a single recursive (FILE_TREE) watch covers the whole folder.
 */
class FolderWatcher(
    private val scope: CoroutineScope,
    private val onChange: suspend () -> Unit,
) {
    private var job: Job? = null

    @Volatile
    private var service: WatchService? = null

    @Synchronized
    fun watch(path: String?) {
        stop()
        if (path == null) return
        val root = try { Paths.get(path) } catch (_: Throwable) { return }
        if (!Files.isDirectory(root)) return
        job = scope.launch(Dispatchers.IO) { run(root) }
    }

    @Synchronized
    fun stop() {
        try {
            service?.close()
        } catch (_: Throwable) {
        }
        service = null
        job?.cancel()
        job = null
    }

    private val kinds: Array<WatchEvent.Kind<*>> = arrayOf(
        StandardWatchEventKinds.ENTRY_CREATE,
        StandardWatchEventKinds.ENTRY_DELETE,
        StandardWatchEventKinds.ENTRY_MODIFY,
    )

    private fun treeModifier(): WatchEvent.Modifier? =
        if (WindowsIntegration.isWindows) {
            try {
                com.sun.nio.file.ExtendedWatchEventModifier.FILE_TREE
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }

    private suspend fun run(root: Path) {
        try {
            val ws = root.fileSystem.newWatchService()
            service = ws
            val keys = HashMap<WatchKey, Path>()
            val tree = treeModifier()
            val recursive = if (tree != null) {
                try {
                    keys[root.register(ws, kinds, tree)] = root
                    true
                } catch (_: Throwable) {
                    false
                }
            } else {
                false
            }
            if (!recursive) registerAll(root, ws, keys)
            AppLog.i("Watching $root (${if (recursive) "recursive" else "${keys.size} folders"})")

            var pending = false
            while (scope.isActive) {
                val key: WatchKey? = if (pending) ws.poll(1_500, TimeUnit.MILLISECONDS) else ws.take()
                if (key == null) {
                    pending = false
                    onChange()
                    continue
                }
                val dir = keys[key]
                for (event in key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue
                    if (!recursive && dir != null && event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                        val child = dir.resolve(event.context() as Path)
                        if (Files.isDirectory(child)) registerAll(child, ws, keys)
                    }
                }
                pending = true
                if (!key.reset()) keys.remove(key)
            }
        } catch (_: ClosedWatchServiceException) {
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            AppLog.w("Folder watcher stopped", t)
        }
    }

    private fun registerAll(start: Path, ws: WatchService, keys: MutableMap<WatchKey, Path>) {
        try {
            Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    try {
                        keys[dir.register(ws, kinds)] = dir
                    } catch (_: Throwable) {
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        } catch (_: Throwable) {
        }
    }
}
