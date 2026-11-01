package biz.fesh.syncmusic.shared

import kotlinx.serialization.Serializable

/*
 * File manager: the PC app shows the phone's files next to the PC's and copies files both ways.
 *
 * The phone never accepts connections. While its file access service runs, it keeps a long poll
 * open to the PC (GET /api/fm/poll), carries out the commands it gets and answers with
 * POST /api/fm/reply:
 *  - LIST / TREE / MKDIR — the answer is an [FmReply];
 *  - PULL — the phone downloads the file the PC prepared (GET /api/fm/file?id=) into a phone folder;
 *  - PUSH — the phone sends one of its files to the PC (PUT /api/fm/upload?id=);
 *  - DELETE / RENAME / PRUNE (phones that poll with ops >= [FmOps.FILE_OPS] only) — an [FmReply].
 * The PC serves and accepts only the files of copy jobs the user started in the PC app (by job id),
 * so the phone can neither read nor write anything else on the PC. Nothing is ever overwritten:
 * a different file under a taken name is saved as a copy next to it.
 */

@Serializable
enum class FmOp {
    /** Contents of a phone folder ([FmCommand.path]); an empty path lists the storages ([FmReply.volumes]). */
    LIST,

    /** All files under a phone folder, names relative to it with '/' (to copy a whole folder to the PC). */
    TREE,

    /** Creates the phone folder [FmCommand.path]. */
    MKDIR,

    /** PC → phone: download the job's file into the phone folder [FmCommand.path] as [FmCommand.name]. */
    PULL,

    /** Phone → PC: send the phone file [FmCommand.path]. */
    PUSH,

    /** Deletes the phone file or folder (with everything inside) [FmCommand.path]. */
    DELETE,

    /** Renames the phone file or folder [FmCommand.path] to [FmCommand.name] (same folder). */
    RENAME,

    /** Removes empty folders under [FmCommand.path] and the folder itself when it ends up empty (after a move). */
    PRUNE,
}

/**
 * What a phone's file access understands; sent as `ops=` with the long poll.
 * Older phones send nothing and never get commands they cannot decode.
 */
object FmOps {
    const val BASIC = 1

    /** DELETE, RENAME and PRUNE. */
    const val FILE_OPS = 2

    const val CURRENT = FILE_OPS

    fun needs(op: FmOp): Int = when (op) {
        FmOp.DELETE, FmOp.RENAME, FmOp.PRUNE -> FILE_OPS
        else -> BASIC
    }
}

@Serializable
data class FmCommand(
    val id: String,
    val op: FmOp,
    /** Absolute phone path (folder or, for PUSH, file). */
    val path: String = "",
    /** PULL: name of the new file; RENAME: the new name. */
    val name: String = "",
    val size: Long = 0,
    /** Last-modified time, epoch millis. */
    val modified: Long = 0,
)

/** Answer to a long poll: commands to run, and whether the PC's file window is open (keep the service running). */
@Serializable
data class FmPoll(
    val commands: List<FmCommand> = emptyList(),
    val wanted: Boolean = false,
)

@Serializable
data class FmState(val wanted: Boolean = false)

@Serializable
data class FmEntry(
    /** File or folder name (TREE: path relative to the listed folder, '/'-separated). */
    val name: String,
    val dir: Boolean = false,
    val size: Long = 0,
    val modified: Long = 0,
)

@Serializable
data class FmReply(
    val id: String,
    val ok: Boolean = true,
    val error: String? = null,
    val entries: List<FmEntry> = emptyList(),
    /** LIST of the empty path: the phone's storages (without [PhoneVolume.dirs]). */
    val volumes: List<PhoneVolume> = emptyList(),
    /** TREE: not all files fit. */
    val truncated: Boolean = false,
    /** PULL: the same file was already there, nothing was copied. */
    val skipped: Boolean = false,
    /** PULL: the name the file was saved under (differs when the name was taken by another file). */
    val savedAs: String? = null,
)

object FileNames {
    /** Same file for the purposes of "already copied": same size and (to FAT's 2 s) the same modification time. */
    fun sameFile(sizeA: Long, modifiedA: Long, sizeB: Long, modifiedB: Long): Boolean =
        sizeA == sizeB && (modifiedA <= 0 || modifiedB <= 0 || kotlin.math.abs(modifiedA - modifiedB) <= 2_000)

    /** A name Windows (and FAT SD cards) can store: forbidden characters become '_', no trailing dots or spaces. */
    fun safeName(name: String): String {
        val replaced = name.map { if (it in "<>:\"/\\|?*" || it.code < 32) '_' else it }.joinToString("")
        val trimmed = replaced.trimEnd('.', ' ')
        val base = trimmed.ifEmpty { "_" }
        val stem = base.substringBefore('.').uppercase()
        return if (stem in RESERVED) "_$base" else base
    }

    /** "name (label).ext", "name (label 2).ext", … */
    fun copyName(name: String, label: String, n: Int): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        val tag = if (n <= 1) label else "$label $n"
        return "$base ($tag)$ext"
    }

    private val RESERVED = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }
}
