package biz.fesh.syncmusic.sync

import android.content.Context
import android.util.Log
import biz.fesh.syncmusic.data.Config
import biz.fesh.syncmusic.data.FolderMapping
import biz.fesh.syncmusic.shared.Direction
import biz.fesh.syncmusic.shared.FolderInfo
import java.io.File

/** Pointing a PC folder at another phone folder: chosen in the phone app or in the PC app. */
object FolderMove {
    private const val TAG = "FolderMove"

    private fun inside(path: String, root: String) = path == root || path.startsWith(root.trimEnd('/') + "/")

    /**
     * The PC → phone files in [from] can be moved to [to] in place (a folder rename, instant and
     * without downloading anything again): same storage, [to] missing or empty, neither inside the other.
     */
    fun canMove(volumes: List<Volume>, from: String?, to: String): Boolean {
        if (from == null || from == to) return false
        if (!File(from).isDirectory) return false
        val source = Storage.volumeOf(volumes, from) ?: return false
        val target = Storage.volumeOf(volumes, to) ?: return false
        if (source.id != target.id || source.appPrivate != target.appPrivate) return false
        if (inside(to, from) || inside(from, to)) return false
        if (source.root.path == from) return false
        val dest = File(to)
        return !dest.exists() || (dest.isDirectory && dest.list()?.isEmpty() == true)
    }

    /**
     * Sets the phone folder of [folder] to [newPath].
     * PC → phone: the files are moved when [canMove]; otherwise they are downloaded again and the
     * copies in the old folder are deleted when [deleteOld] (only files Sync Music put there).
     * Returns true when the files were moved.
     */
    fun change(
        context: Context,
        config: Config,
        serverId: String,
        folder: FolderInfo,
        mapping: FolderMapping,
        newPath: String,
        deleteOld: Boolean,
        pcChoiceAt: Long = mapping.pcChoiceAt,
    ): Boolean {
        val oldPath = mapping.localPath
        var moved = false
        if (folder.direction == Direction.TO_PHONE && oldPath != null && oldPath != newPath) {
            if (canMove(Storage.volumes(context), oldPath, newPath) && rename(oldPath, newPath)) {
                SyncedDb.rebase(context, serverId, folder.id, oldPath, newPath, config.storageId.value)
                Storage.scan(context, listOf(oldPath, newPath))
                moved = true
                Log.i(TAG, "${folder.name}: moved $oldPath -> $newPath")
            } else if (deleteOld) {
                val db = SyncedDb.load(context, serverId, folder.id, Direction.TO_PHONE, oldPath, config.storageId.value)
                val n = Storage.wipe(context, db)
                Log.i(TAG, "${folder.name}: $oldPath -> $newPath, deleted $n old files")
            } else {
                Log.i(TAG, "${folder.name}: $oldPath -> $newPath, old files kept")
            }
        }
        config.setMapping(folder.id, mapping.copy(localPath = newPath, pcChoiceAt = pcChoiceAt))
        return moved
    }

    private fun rename(from: String, to: String): Boolean {
        val dest = File(to)
        dest.parentFile?.mkdirs()
        if (dest.isDirectory) dest.delete() // empty, checked by canMove
        val ok = try {
            File(from).renameTo(dest)
        } catch (_: Throwable) {
            false
        }
        if (!ok) Log.w(TAG, "rename $from -> $to failed")
        return ok
    }

    /**
     * Applies folder choices made in the PC app (each one once, so the phone can still change it later).
     * Returns problems per folder id, to show next to the folder.
     */
    fun applyPcChoices(context: Context, config: Config, serverId: String, folders: List<FolderInfo>): Map<String, String> {
        val problems = HashMap<String, String>()
        val volumes = Storage.volumes(context)
        for (folder in folders) {
            val target = folder.phonePath?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: continue
            val mapping = config.mappings.value[folder.id] ?: FolderMapping()
            if (folder.phonePathAt <= mapping.pcChoiceAt) continue
            if (target == mapping.localPath) {
                config.setMapping(folder.id, mapping.copy(pcChoiceAt = folder.phonePathAt))
                continue
            }
            val volume = Storage.volumeOf(volumes, target)
            if (volume == null) {
                // E.g. the SD card is out right now: try again on the next sync.
                problems[folder.id] = "Папку, вибрану на комп'ютері, не знайдено на телефоні (SD-карту вийнято?)"
                continue
            }
            val dir = File(target)
            val problem = when {
                target.split('/').any { it == ".." || it == "." } -> "Недопустима папка, вибрана на комп'ютері"
                folder.direction == Direction.TO_PC && !dir.isDirectory -> "Папки «${Storage.describe(volumes, target)}», вибраної на комп'ютері, на телефоні немає"
                else -> null
            }
            if (problem != null) {
                problems[folder.id] = problem
                config.setMapping(folder.id, mapping.copy(pcChoiceAt = folder.phonePathAt))
                continue
            }
            change(context, config, serverId, folder, mapping, target, folder.phoneDeleteOld, folder.phonePathAt)
            if (folder.direction == Direction.TO_PHONE && !dir.isDirectory && !dir.mkdirs()) {
                problems[folder.id] = "Не вдалося створити папку «${Storage.describe(volumes, target)}»"
            }
            Log.i(TAG, "${folder.name}: folder chosen on the PC: $target")
        }
        return problems
    }
}
