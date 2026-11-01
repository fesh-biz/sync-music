package biz.fesh.syncmusic.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import biz.fesh.syncmusic.desktop.AppController
import biz.fesh.syncmusic.desktop.CopyJob
import biz.fesh.syncmusic.desktop.FileManager
import biz.fesh.syncmusic.desktop.JobState
import biz.fesh.syncmusic.desktop.PairedDevice
import biz.fesh.syncmusic.desktop.PcFiles
import biz.fesh.syncmusic.desktop.PhoneItem
import biz.fesh.syncmusic.desktop.Settings
import biz.fesh.syncmusic.desktop.finished
import biz.fesh.syncmusic.shared.FileTypes
import biz.fesh.syncmusic.shared.FmOp
import biz.fesh.syncmusic.shared.Format
import biz.fesh.syncmusic.shared.PhoneStorage
import biz.fesh.syncmusic.shared.PhoneVolume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.awt.event.KeyEvent as Awt

/*
 * The file window, made to work like Total Commander: the PC on the left, the phone on the right,
 * a cursor in the active panel, whole-row marking, ".." at the top of every folder, columns with
 * sorting, quick search by typing, F3–F8 at the bottom and keys the user can change (Hotkeys.kt).
 */

private const val UP_KEY = "\u0000.."

/** A file or folder line in one of the two panels. [key] is its absolute path ("..": [UP_KEY]). */
private data class FileRow(
    val key: String,
    val name: String,
    val dir: Boolean,
    val size: Long = 0,
    val modified: Long = 0,
    /** Shown instead of the columns in the start lists ("синхронізується", free space, …). */
    val note: String? = null,
) {
    val up: Boolean get() = key == UP_KEY

    /** Extension of a file ("mp3"), "" for folders. */
    val ext: String
        get() {
            if (dir) return ""
            val dot = name.lastIndexOf('.')
            return if (dot > 0) name.substring(dot + 1) else ""
        }

    /** Name without the extension (the "Ім'я" column of files). */
    val base: String get() = if (ext.isEmpty()) name else name.substring(0, name.length - ext.length - 1)
}

private val upRow = FileRow(UP_KEY, "..", dir = true)

private class Listing(val rows: List<FileRow>, val error: String? = null)

private enum class Side { PC, PHONE }

private enum class SortBy { NAME, EXT, SIZE, TIME }

/** One panel: what it shows, the cursor, the marked rows, sorting and the quick search. */
@Stable
private class PanelState(val side: Side, val pathFlow: MutableStateFlow<String?>) {
    var listing by mutableStateOf<Listing?>(null)

    /** Folder the listing belongs to (null: the start list — disks, or the phone's storages). */
    var listingFor by mutableStateOf<String?>(null)
    var loaded by mutableStateOf(false)
    var selected by mutableStateOf(setOf<String>())
    var cursor by mutableStateOf<String?>(null)
    var sort by mutableStateOf(SortBy.NAME)
    var desc by mutableStateOf(false)
    var reload by mutableStateOf(0)
    var search by mutableStateOf<String?>(null)
    var volumes by mutableStateOf<List<PhoneVolume>>(emptyList())

    /** Where the cursor goes once the next listing arrives (the folder we came up from, a new folder…). */
    var pendingCursor: String? = null
    val list = LazyListState()
    var lastClickKey: String? = null
    var lastClickAt = 0L

    val rows: List<FileRow> by derivedStateOf {
        val l = listing ?: return@derivedStateOf emptyList()
        if (listingFor == null) return@derivedStateOf l.rows
        listOf(upRow) + sortRows(l.rows, sort, desc)
    }

    /** Inside a folder (not a start list): files can be marked, copied, deleted… */
    val inFolder: Boolean get() = listingFor != null && loaded

    val cursorRow: FileRow? get() = rows.firstOrNull { it.key == cursor }

    /** Marked rows, or the one under the cursor when nothing is marked (as in Total Commander). */
    fun targets(): List<FileRow> {
        if (!inFolder) return emptyList()
        val marked = rows.filter { !it.up && it.key in selected }
        if (marked.isNotEmpty()) return marked
        return listOfNotNull(cursorRow?.takeIf { !it.up })
    }

    fun go(path: String?, cursorTo: String? = null) {
        pendingCursor = cursorTo
        search = null
        if (pathFlow.value == path) reload++ else pathFlow.value = path
    }
}

private fun sortRows(rows: List<FileRow>, sort: SortBy, desc: Boolean): List<FileRow> {
    val byName = compareBy<FileRow> { it.name.lowercase() }
    val inner: Comparator<FileRow> = when (sort) {
        SortBy.NAME -> byName
        SortBy.EXT -> compareBy<FileRow> { it.ext.lowercase() }.then(byName)
        SortBy.SIZE -> compareBy<FileRow> { if (it.dir) 0 else it.size }.then(byName)
        SortBy.TIME -> compareBy<FileRow> { it.modified }.then(byName)
    }
    val ordered = if (desc) inner.reversed() else inner
    // Folders always stay above files; folders are sorted by name unless sorting by date.
    val dirs = rows.filter { it.dir }.sortedWith(if (sort == SortBy.TIME) ordered else if (desc && sort == SortBy.NAME) byName.reversed() else byName)
    val files = rows.filter { !it.dir }.sortedWith(ordered)
    return dirs + files
}

private sealed interface FmDialog {
    data class Transfer(val from: Side, val move: Boolean, val items: List<FileRow>) : FmDialog
    data class Delete(val side: Side, val items: List<FileRow>) : FmDialog

    /** New folder in [dir] ([rename] == null) or renaming [rename]. */
    data class Name(val side: Side, val dir: String, val rename: FileRow?) : FmDialog
    data class Mask(val side: Side, val select: Boolean) : FmDialog
    data object Keys : FmDialog
}

/** Everything the file window does on keys and clicks. */
private class FilesUi(val controller: AppController, val scope: CoroutineScope) {
    val fm: FileManager get() = controller.files
    val pc = PanelState(Side.PC, controller.files.pcPath)
    val phone = PanelState(Side.PHONE, controller.files.phonePath)
    var active by mutableStateOf(Side.PC)
    var dialog by mutableStateOf<FmDialog?>(null)
    var dialogText by mutableStateOf(TextFieldValue(""))
    var dialogError by mutableStateOf<String?>(null)
    var dialogBusy by mutableStateOf(false)

    /** Hotkeys dialog: the command waiting for its new key, and a note about the last change. */
    var capturing by mutableStateOf<FmAction?>(null)
    var keysNote by mutableStateOf<String?>(null)

    var device: PairedDevice? = null
    var phoneOnline = false

    val settings: Settings get() = controller.settings.value

    fun panel(side: Side) = if (side == Side.PC) pc else phone
    fun other(side: Side) = if (side == Side.PC) phone else pc
    val current: PanelState get() = panel(active)

    // ---------------------------------------------------------------- keys

    fun onKey(k: KeyPress): Boolean {
        dialog?.let { return dialogKey(it, k) }
        if (device == null) return false
        val p = current
        val combo = k.combo
        val text = p.search
        if (text != null) {
            if (k.code == Awt.VK_ESCAPE) {
                p.search = null
                return true
            }
            if (k.code == Awt.VK_BACK_SPACE && k.plain && !k.shift) {
                p.search = text.dropLast(1).ifEmpty { null }
                return true
            }
            if (k.plain && k.char != '\u0000') {
                quickSearch(p, text + k.char)
                return true
            }
            // Any other key ends the search and does what it does (Enter opens, arrows move…).
            p.search = null
        }
        when (combo) {
            "Up", "Down", "PageUp", "PageDown", "Home", "End",
            "Shift+Up", "Shift+Down", "Shift+PageUp", "Shift+PageDown", "Shift+Home", "Shift+End",
            -> {
                moveCursor(p, combo!!.removePrefix("Shift+"), combo.startsWith("Shift+"))
                return true
            }
            "Esc" -> return false
        }
        val action = combo?.let { Hotkeys.table(settings)[it] }
        if (action != null) {
            perform(action)
            return true
        }
        if (k.plain && k.char != '\u0000' && !k.char.isWhitespace()) {
            quickSearch(p, k.char.toString())
            return true
        }
        return false
    }

    private fun dialogKey(d: FmDialog, k: KeyPress): Boolean {
        if (d is FmDialog.Keys) {
            val action = capturing
            if (action != null) {
                if (k.code == Awt.VK_ESCAPE && !k.ctrl && !k.alt && !k.shift) {
                    capturing = null
                    return true
                }
                val combo = k.combo ?: return true // a modifier alone: wait for the key
                if (combo in Hotkeys.fixed) {
                    keysNote = "«$combo» зайнята: нею рухається курсор у списку"
                    return true
                }
                val before = Hotkeys.owner(settings, combo)
                controller.settings.update { it.copy(fmHotkeys = Hotkeys.assign(it, action, combo)) }
                keysNote = if (before != null && before != action) "«$combo» тепер для «${action.title}», а не для «${before.title}»" else null
                capturing = null
                return true
            }
            if (k.code == Awt.VK_ESCAPE || k.code == Awt.VK_ENTER) {
                closeDialog()
                return true
            }
            return false
        }
        if (k.code == Awt.VK_ESCAPE) {
            closeDialog()
            return true
        }
        if (k.code == Awt.VK_ENTER && !k.ctrl && !k.alt) {
            confirm()
            return true
        }
        return false
    }

    private fun moveCursor(p: PanelState, key: String, shift: Boolean) {
        val rows = p.rows
        if (rows.isEmpty()) return
        val last = rows.size - 1
        val idx = rows.indexOfFirst { it.key == p.cursor }.coerceAtLeast(0)
        val page = (p.list.layoutInfo.visibleItemsInfo.size - 1).coerceAtLeast(1)
        val target = when (key) {
            "Up" -> idx - 1
            "Down" -> idx + 1
            "PageUp" -> idx - page
            "PageDown" -> idx + page
            "Home" -> 0
            else -> last
        }.coerceIn(0, last)
        if (shift && p.inFolder) {
            // Total Commander: Shift+arrow marks the row it leaves; Shift+PageDown/End… mark the whole stretch.
            val marked = if (key == "Up" || key == "Down") {
                listOf(rows[idx])
            } else {
                rows.subList(minOf(idx, target), maxOf(idx, target) + 1)
            }
            p.selected = p.selected + marked.filter { !it.up }.map { it.key }
        }
        p.cursor = rows[target].key
    }

    private fun quickSearch(p: PanelState, text: String) {
        val match = p.rows.firstOrNull { !it.up && it.name.startsWith(text, ignoreCase = true) }
        if (match != null) {
            p.search = text
            p.cursor = match.key
        }
    }

    // ---------------------------------------------------------------- commands

    fun perform(action: FmAction) {
        if (device == null) return
        val p = current
        when (action) {
            FmAction.SWITCH_PANEL -> {
                p.search = null
                active = if (active == Side.PC) Side.PHONE else Side.PC
            }
            FmAction.OPEN -> p.cursorRow?.let { open(p, it, edit = false) }
            FmAction.UP -> goUp(p)
            FmAction.ROOT -> goRoot(p)
            FmAction.LEFT_DRIVES -> {
                active = Side.PC
                pc.go(null, pc.listingFor)
            }
            FmAction.RIGHT_DRIVES -> {
                active = Side.PHONE
                phone.go(null, phone.volumes.firstOrNull { v -> phone.listingFor?.let { PhoneStorage.inside(it, v.root) } == true }?.root)
            }
            FmAction.REFRESH -> p.reload++
            FmAction.SELECT -> {
                val row = p.cursorRow
                if (row != null && !row.up && p.inFolder) p.selected = if (row.key in p.selected) p.selected - row.key else p.selected + row.key
                moveCursor(p, "Down", false)
            }
            FmAction.SELECT_ALL -> if (p.inFolder) p.selected = p.rows.filter { !it.up }.map { it.key }.toSet()
            FmAction.DESELECT_ALL -> p.selected = emptySet()
            FmAction.SELECT_GROUP, FmAction.DESELECT_GROUP -> if (p.inFolder) {
                openDialog(FmDialog.Mask(p.side, action == FmAction.SELECT_GROUP), "*.*")
            }
            FmAction.INVERT -> if (p.inFolder) {
                p.selected = p.rows.filter { !it.up && it.key !in p.selected }.map { it.key }.toSet()
            }
            FmAction.VIEW, FmAction.EDIT -> p.cursorRow?.let { open(p, it, edit = action == FmAction.EDIT) }
            FmAction.COPY, FmAction.MOVE -> startTransfer(p, action == FmAction.MOVE)
            FmAction.RENAME -> {
                val row = p.cursorRow?.takeIf { !it.up && p.inFolder } ?: return
                if (!phoneReady(p)) return
                val start = TextFieldValue(row.name, TextRange(0, if (row.dir || row.ext.isEmpty()) row.name.length else row.base.length))
                openDialog(FmDialog.Name(p.side, p.listingFor!!, row), start)
            }
            FmAction.MKDIR -> {
                val dir = p.listingFor ?: return
                if (!p.loaded || !phoneReady(p)) return
                openDialog(FmDialog.Name(p.side, dir, null), "Нова папка")
            }
            FmAction.DELETE -> {
                val items = p.targets()
                if (items.isEmpty() || !phoneReady(p)) return
                openDialog(FmDialog.Delete(p.side, items), "")
            }
            FmAction.SORT_NAME -> sortBy(p, SortBy.NAME)
            FmAction.SORT_EXT -> sortBy(p, SortBy.EXT)
            FmAction.SORT_TIME -> sortBy(p, SortBy.TIME)
            FmAction.SORT_SIZE -> sortBy(p, SortBy.SIZE)
            FmAction.HOTKEYS -> {
                keysNote = null
                capturing = null
                openDialog(FmDialog.Keys, "")
            }
        }
    }

    fun sortBy(p: PanelState, sort: SortBy, toggle: Boolean = false) {
        if (p.sort == sort) {
            if (toggle) p.desc = !p.desc
        } else {
            p.sort = sort
            p.desc = sort == SortBy.TIME || sort == SortBy.SIZE // newest / biggest first, as in Total Commander
        }
    }

    private fun phoneReady(p: PanelState): Boolean {
        if (p.side == Side.PC) return true
        if (!phoneOnline) {
            fm.showNotice(FileManager.OFFLINE)
            return false
        }
        return true
    }

    fun open(p: PanelState, row: FileRow, edit: Boolean) {
        val dev = device ?: return
        when {
            row.up -> goUp(p)
            row.dir -> p.go(row.key)
            p.side == Side.PC -> scope.launch(Dispatchers.IO) { PcFiles.open(File(row.key), edit)?.let(fm::showNotice) }
            phoneReady(p) -> fm.viewPhoneFile(dev.id, PhoneItem(row.key, row.name, false, row.size, row.modified))
        }
    }

    fun goUp(p: PanelState) {
        val path = p.listingFor ?: return
        val parent = if (p.side == Side.PC) File(path).parentFile?.path else phoneParent(path, p.volumes)
        p.go(parent, path)
    }

    private fun goRoot(p: PanelState) {
        val path = p.listingFor ?: return
        val root = if (p.side == Side.PC) {
            File(path).toPath().root?.toString()
        } else {
            p.volumes.filter { PhoneStorage.inside(path, it.root) }.maxByOrNull { it.root.length }?.root
        }
        if (root != null && root != path) p.go(root)
    }

    private fun startTransfer(p: PanelState, move: Boolean) {
        val items = p.targets()
        if (items.isEmpty()) return
        if (!phoneOnline) {
            fm.showNotice(FileManager.OFFLINE)
            return
        }
        val dev = device ?: return
        if (move && p.side == Side.PHONE && !fm.supports(dev.id, FmOp.DELETE)) {
            fm.showNotice(FileManager.OLD_PHONE)
            return
        }
        val target = other(p.side).listingFor.orEmpty()
        openDialog(FmDialog.Transfer(p.side, move, items), target)
    }

    // ---------------------------------------------------------------- mouse

    fun click(p: PanelState, row: FileRow, ctrl: Boolean, shift: Boolean, secondary: Boolean) {
        active = p.side
        p.search = null
        val markable = !row.up && p.inFolder
        when {
            secondary || ctrl -> if (markable) p.selected = if (row.key in p.selected) p.selected - row.key else p.selected + row.key
            shift && p.inFolder -> {
                val rows = p.rows
                val a = rows.indexOfFirst { it.key == p.cursor }.coerceAtLeast(0)
                val b = rows.indexOfFirst { it.key == row.key }
                if (b >= 0) p.selected = p.selected + rows.subList(minOf(a, b), maxOf(a, b) + 1).filter { !it.up }.map { it.key }
            }
            else -> {
                val now = System.currentTimeMillis()
                if (p.lastClickKey == row.key && now - p.lastClickAt < 450) {
                    p.lastClickKey = null
                    p.cursor = row.key
                    open(p, row, edit = false)
                    return
                }
                p.lastClickKey = row.key
                p.lastClickAt = now
            }
        }
        p.cursor = row.key
    }

    // ---------------------------------------------------------------- dialogs

    fun openDialog(d: FmDialog, text: String) = openDialog(d, TextFieldValue(text, TextRange(0, text.length)))

    fun openDialog(d: FmDialog, text: TextFieldValue) {
        current.search = null
        dialogText = text
        dialogError = null
        dialogBusy = false
        dialog = d
    }

    fun closeDialog() {
        dialog = null
        capturing = null
        dialogBusy = false
    }

    fun confirm() {
        val d = dialog ?: return
        if (dialogBusy) return
        val dev = device ?: return
        val text = dialogText.text.trim()
        when (d) {
            is FmDialog.Transfer -> {
                if (text.isEmpty()) {
                    dialogError = if (d.from == Side.PC) "Вкажіть папку на телефоні" else "Вкажіть папку на комп'ютері"
                    return
                }
                val source = panel(d.from)
                if (d.from == Side.PC) {
                    if (!text.startsWith("/")) {
                        dialogError = "Шлях на телефоні починається з «/», наприклад /storage/emulated/0/Music"
                        return
                    }
                    fm.copyToPhone(dev.id, d.items.map { File(it.key) }, text, d.move)
                } else {
                    val dir = File(text)
                    if (!dir.isAbsolute) {
                        dialogError = "Вкажіть повний шлях, наприклад C:\\Users\\Музика"
                        return
                    }
                    fm.copyToPc(dev.id, d.items.map { PhoneItem(it.key, it.name, it.dir, it.size, it.modified) }, dir, d.move)
                }
                source.selected = emptySet()
                closeDialog()
            }
            is FmDialog.Delete -> {
                val source = panel(d.side)
                source.selected = emptySet()
                // The cursor stays near where it was: on the row below the deleted one(s).
                val rows = source.rows
                val gone = d.items.map { it.key }.toSet()
                val idx = rows.indexOfFirst { it.key == source.cursor }
                source.pendingCursor = (rows.drop(idx.coerceAtLeast(0)) + rows.take(idx.coerceAtLeast(0)).reversed())
                    .firstOrNull { it.key !in gone }?.key
                closeDialog()
                scope.launch {
                    val error = if (d.side == Side.PC) fm.deletePc(d.items.map { File(it.key) }) else fm.deletePhone(dev.id, d.items.map { it.key })
                    error?.let(fm::showNotice)
                }
            }
            is FmDialog.Name -> name(d, dev, text)
            is FmDialog.Mask -> {
                val p = panel(d.side)
                val regex = maskRegex(text.ifEmpty { "*.*" })
                val hits = p.rows.filter { !it.up && !it.dir && regex.matches(it.name) }.map { it.key }
                p.selected = if (d.select) p.selected + hits else p.selected - hits.toSet()
                closeDialog()
            }
            FmDialog.Keys -> closeDialog()
        }
    }

    private fun name(d: FmDialog.Name, dev: PairedDevice, name: String) {
        if (!PhoneStorage.isValidName(name)) {
            dialogError = "Назва не може бути порожньою, починатися чи закінчуватися крапкою й містити / \\ : * ? \" < > |"
            return
        }
        val p = panel(d.side)
        val rename = d.rename
        if (rename != null && name == rename.name) {
            closeDialog()
            return
        }
        if (d.side == Side.PC) {
            val error = if (rename == null) {
                val dir = File(d.dir, name)
                when {
                    dir.exists() -> "Така назва вже є"
                    !dir.mkdir() -> "Не вдалося створити папку"
                    else -> null
                }
            } else {
                val src = File(rename.key)
                val dst = File(src.parentFile, name)
                when {
                    dst.exists() && !dst.path.equals(src.path, ignoreCase = true) -> "Така назва вже є"
                    !src.renameTo(dst) -> "Не вдалося перейменувати (файл може бути відкритий в іншій програмі)"
                    else -> null
                }
            }
            if (error != null) {
                dialogError = error
                return
            }
            p.pendingCursor = File(d.dir, name).path
            p.reload++
            closeDialog()
            return
        }
        dialogBusy = true
        scope.launch {
            val reply = if (rename == null) {
                fm.request(dev.id, FmOp.MKDIR, d.dir.trimEnd('/') + "/" + name)
            } else {
                fm.request(dev.id, FmOp.RENAME, rename.key, name = name)
            }
            dialogBusy = false
            if (reply.ok) {
                p.pendingCursor = d.dir.trimEnd('/') + "/" + name
                p.reload++
                closeDialog()
            } else {
                dialogError = reply.error ?: if (rename == null) "Не вдалося створити папку" else "Не вдалося перейменувати"
            }
        }
    }
}

/** "*.mp3;*.flac" or "*.mp3 *.flac" → a regex (case-insensitive); "*.*" matches every file. */
private fun maskRegex(mask: String): Regex {
    val parts = mask.split(';', ' ', ',').map { it.trim() }.filter { it.isNotEmpty() }
    val alternatives = parts.map { part ->
        if (part == "*.*") ".*" else part.map { c ->
            when (c) {
                '*' -> ".*"
                '?' -> "."
                else -> Regex.escape(c.toString())
            }
        }.joinToString("")
    }
    return Regex(alternatives.joinToString("|") { "(?:$it)" }, RegexOption.IGNORE_CASE)
}

/** Folder above [path] on the phone; null at the root of a storage (back to the storage list). */
private fun phoneParent(path: String, volumes: List<PhoneVolume>): String? {
    val clean = path.trimEnd('/')
    if (volumes.any { it.root.trimEnd('/') == clean }) return null
    val parent = clean.substringBeforeLast('/', "")
    if (parent.isEmpty()) return null
    val known = volumes.isNotEmpty()
    if (known && volumes.none { PhoneStorage.inside(parent, it.root) }) return null
    if (!known && (parent == "/storage" || parent == "/storage/emulated")) return null
    return parent
}

// ==================================================================== screen

/** The file window: the PC on the left, the phone on the right, copying both ways, the copy queue below. */
@Composable
fun FilesScreen(controller: AppController, keys: FilesKeys) {
    val settings by controller.settings.state.collectAsState()
    SyncMusicTheme(settings.theme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            FilesContent(controller, settings, keys)
        }
    }
}

@Composable
private fun FilesContent(controller: AppController, settings: Settings, keys: FilesKeys) {
    val fm = controller.files
    val scope = rememberCoroutineScope()
    val ui = remember { FilesUi(controller, scope) }
    val online by fm.online.collectAsState()
    val deviceId by fm.phoneDevice.collectAsState()
    val notice by fm.notice.collectAsState()
    val jobs by fm.jobs.collectAsState()
    val device = settings.devices.firstOrNull { it.id == deviceId } ?: settings.devices.firstOrNull()
    val phoneOnline = device != null && device.id in online
    ui.device = device
    ui.phoneOnline = phoneOnline

    DisposableEffect(keys) {
        keys.handler = ui::onKey
        onDispose { keys.handler = null }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FilesHeader(settings, device, phoneOnline) { id ->
                fm.phoneDevice.value = id
                fm.phonePath.value = null
            }
            if (device == null) {
                Muted("Спершу підключіть телефон у головному вікні Sync Music.")
                return@Column
            }
            if (!phoneOnline) WaitingBanner(device.name)
            notice?.let { text ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.AmberSoft).padding(start = 14.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text, color = Palette.Amber, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = fm::clearNotice) { Text("Зрозуміло") }
                }
            }
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PcPanel(Modifier.weight(1f), ui)
                PhonePanel(Modifier.weight(1f), ui, device, phoneOnline)
            }
            if (jobs.isNotEmpty()) CopyQueue(controller, jobs)
            FunctionBar(ui, settings)
        }
        ui.dialog?.let { DialogOverlay(ui, it, settings) }
    }
}

@Composable
private fun FilesHeader(settings: Settings, device: PairedDevice?, online: Boolean, onDevice: (String) -> Unit) {
    val look = LocalLook.current
    val devices = settings.devices
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(if (look.bright) HeaderBrush else Brush.linearGradient(listOf(Palette.Indigo, Palette.Indigo))),
            contentAlignment = Alignment.Center,
        ) { ScalesLogo(30.dp, Color.White) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Файли комп'ютера і телефона", style = MaterialTheme.typography.titleLarge)
            fun k(action: FmAction) = Hotkeys.label(settings, action)
            Muted(
                "${k(FmAction.SWITCH_PANEL)} — інша панель · ${k(FmAction.SELECT)} — позначити · ${k(FmAction.OPEN)} — відкрити · " +
                    "${k(FmAction.UP)} — вгору · ${k(FmAction.COPY)} — копіювати · ${k(FmAction.MOVE)} — перемістити · " +
                    "${k(FmAction.DELETE)} — видалити · ${k(FmAction.HOTKEYS)} — клавіші",
                maxLines = 2,
            )
        }
        Spacer(Modifier.width(12.dp))
        if (devices.size > 1 && device != null) {
            Segmented(devices.map { it.id to it.name }, device.id, onDevice)
            Spacer(Modifier.width(10.dp))
        }
        if (device != null) {
            if (online) {
                StatusPill("${device.name} на зв'язку", Palette.Green, Palette.GreenSoft)
            } else {
                StatusPill("Чекаємо на телефон", Palette.Amber, Palette.AmberSoft)
            }
        }
    }
}

@Composable
private fun WaitingBanner(name: String) {
    val look = LocalLook.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(look.toPcSoft).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), color = look.toPc, strokeWidth = 2.dp)
        Spacer(Modifier.width(14.dp))
        Column {
            Text("Відкрийте Sync Music на телефоні «$name»", style = MaterialTheme.typography.titleSmall)
            Muted(
                "Телефон підключиться за кілька секунд і показуватиме свої файли, поки відкрите це вікно. " +
                    "Телефон і комп'ютер мають бути в одній Wi-Fi мережі.",
            )
        }
    }
}

// ---------------------------------------------------------------- PC panel

@Composable
private fun PcPanel(modifier: Modifier, ui: FilesUi) {
    val p = ui.pc
    val path by p.pathFlow.collectAsState()
    val refresh by ui.fm.refresh.collectAsState()

    LaunchedEffect(path, refresh, p.reload) {
        val same = path == p.listingFor && p.loaded
        if (!same) {
            p.loaded = false
            p.selected = emptySet()
        }
        val listing = withContext(Dispatchers.IO) { listPc(path, ui.settings) }
        applyListing(p, path, listing, same)
    }

    Panel(
        modifier = modifier,
        ui = ui,
        p = p,
        title = "Комп'ютер",
        location = path ?: "Швидкий доступ і диски",
        accent = LocalLook.current.toPhoneBrush,
        startLabel = "Диски",
        empty = null,
    )
}

private fun applyListing(p: PanelState, path: String?, listing: Listing, same: Boolean) {
    p.listing = listing
    p.listingFor = path
    p.loaded = true
    val keys = p.rows.map { it.key }
    val wanted = p.pendingCursor
    p.cursor = when {
        wanted != null && wanted in keys -> wanted
        same && p.cursor in keys -> p.cursor
        else -> keys.firstOrNull()
    }
    p.pendingCursor = null
    if (same) p.selected = p.selected.filter { it in keys }.toSet()
}

private fun listPc(path: String?, settings: Settings): Listing {
    if (path == null) {
        val rows = ArrayList<FileRow>()
        for (folder in settings.folders) {
            if (File(folder.path).isDirectory) rows += FileRow(folder.path, folder.name, true, note = "синхронізується · ${folder.path}")
        }
        val home = System.getProperty("user.home")
        if (home != null) {
            rows += FileRow(home, "Домашня папка", true, note = home)
            for ((name, label) in listOf("Music" to "Музика", "Videos" to "Відео", "Downloads" to "Завантаження", "Desktop" to "Робочий стіл", "Documents" to "Документи")) {
                val dir = File(home, name)
                if (dir.isDirectory) rows += FileRow(dir.path, label, true, note = dir.path)
            }
        }
        for (root in File.listRoots().orEmpty()) {
            val total = root.totalSpace
            val note = if (total > 0) "вільно ${Format.bytes(root.usableSpace)} з ${Format.bytes(total)}" else null
            rows += FileRow(root.path, "Диск ${root.path.trimEnd('\\', '/')}".takeIf { root.path != "/" } ?: "Корінь /", true, note = note)
        }
        return Listing(rows.distinctBy { it.key })
    }
    val files = File(path).listFiles() ?: return Listing(emptyList(), "Немає доступу до папки")
    val rows = files
        .filter { !it.name.startsWith(".") && !it.isHidden }
        .map { FileRow(it.path, it.name, it.isDirectory, if (it.isFile) it.length() else 0, it.lastModified()) }
    return Listing(rows)
}

// ---------------------------------------------------------------- phone panel

@Composable
private fun PhonePanel(modifier: Modifier, ui: FilesUi, device: PairedDevice, phoneOnline: Boolean) {
    val p = ui.phone
    val fm = ui.fm
    val path by p.pathFlow.collectAsState()
    val refresh by fm.refresh.collectAsState()

    LaunchedEffect(device.id) {
        p.volumes = ui.controller.phoneStorage.value[device.id]?.volumes.orEmpty()
    }
    LaunchedEffect(path, refresh, p.reload, phoneOnline, device.id) {
        if (!phoneOnline) {
            p.listing = Listing(emptyList())
            p.listingFor = path
            p.loaded = false
            p.selected = emptySet()
            return@LaunchedEffect
        }
        val same = path == p.listingFor && p.loaded
        if (!same) {
            p.loaded = false
            p.selected = emptySet()
            p.listing = null
        }
        val current = path
        val reply = fm.request(device.id, FmOp.LIST, current.orEmpty())
        val listing = when {
            !reply.ok -> Listing(emptyList(), reply.error)
            current == null -> {
                p.volumes = reply.volumes
                Listing(
                    reply.volumes.map { v ->
                        val free = if (v.freeBytes >= 0) "вільно ${Format.bytes(v.freeBytes)}" + (if (v.totalBytes > 0) " з ${Format.bytes(v.totalBytes)}" else "") else null
                        FileRow(v.root, v.label, true, note = free)
                    },
                )
            }
            else -> Listing(reply.entries.map { FileRow(current.trimEnd('/') + "/" + it.name, it.name, it.dir, it.size, it.modified) })
        }
        applyListing(p, current, listing, same)
    }

    val volume = path?.let { cur -> p.volumes.filter { PhoneStorage.inside(cur, it.root) }.maxByOrNull { it.root.length } }
    Panel(
        modifier = modifier,
        ui = ui,
        p = p,
        title = "Телефон",
        location = when {
            path == null -> "Пам'ять телефона і SD-карта"
            volume != null -> volume.label + path!!.removePrefix(volume.root).trim('/').let { if (it.isEmpty()) "" else " › " + it.replace("/", " › ") }
            else -> path!!
        },
        accent = LocalLook.current.toPcBrush,
        startLabel = "Носії",
        empty = if (!phoneOnline) "Файли з'являться, щойно телефон підключиться" else null,
    )
}

// ---------------------------------------------------------------- panel

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Panel(
    modifier: Modifier,
    ui: FilesUi,
    p: PanelState,
    title: String,
    location: String,
    accent: Brush,
    startLabel: String,
    empty: String?,
) {
    val isActive = ui.active == p.side
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxSize().clip(RoundedCornerShape(14.dp))
            .border(if (isActive) 2.dp else 1.dp, if (isActive) scheme.primary else scheme.outline, RoundedCornerShape(14.dp))
            .background(scheme.surface)
            .onPointerEvent(PointerEventType.Press) { ui.active = p.side },
    ) {
        Box(Modifier.fillMaxWidth().height(if (isActive) 5.dp else 3.dp).background(accent))
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                ui.active = p.side
                ui.perform(if (p.side == Side.PC) FmAction.LEFT_DRIVES else FmAction.RIGHT_DRIVES)
            }) { Text(startLabel) }
            TextButton(onClick = { p.reload++ }) { Text("Оновити") }
        }
        // The path line, as in Total Commander: highlighted in the active panel.
        Text(
            location,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (isActive) scheme.onPrimary else scheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(6.dp))
                .background(if (isActive) scheme.primary else scheme.surfaceVariant)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        ColumnHeaders(ui, p)
        HorizontalDivider(color = scheme.outlineVariant)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val listing = p.listing
            when {
                empty != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Muted(empty) }
                listing == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                }
                else -> FileList(ui, p, isActive)
            }
            if (empty == null) {
                listing?.error?.let { error ->
                    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                        Text(error, color = scheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (listing != null && listing.error == null && listing.rows.isEmpty() && p.listingFor != null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Muted("Порожня папка") }
                }
            }
            p.search?.let { text ->
                Row(
                    Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(8.dp))
                        .background(scheme.inverseSurface).padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text("Пошук: ", color = scheme.inverseOnSurface, style = MaterialTheme.typography.bodyMedium)
                    Text(text, color = scheme.inverseOnSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        HorizontalDivider(color = scheme.outlineVariant)
        Text(
            statusLine(p),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

private fun statusLine(p: PanelState): String {
    if (!p.inFolder) return if (p.side == Side.PC) "Двічі клацніть диск або папку (чи Enter), щоб відкрити" else "Виберіть пам'ять телефона або SD-карту"
    val rows = p.rows.filter { !it.up }
    val files = rows.filter { !it.dir }
    val dirs = rows.filter { it.dir }
    val selFiles = files.filter { it.key in p.selected }
    val selDirs = dirs.count { it.key in p.selected }
    return "Позначено ${Format.bytes(selFiles.sumOf { it.size })} з ${Format.bytes(files.sumOf { it.size })} · " +
        "файлів ${selFiles.size} з ${files.size} · папок $selDirs з ${dirs.size}"
}

private val ExtWidth = 64.dp
private val SizeWidth = 92.dp
private val DateWidth = 118.dp

@Composable
private fun ColumnHeaders(ui: FilesUi, p: PanelState) {
    if (p.listingFor == null) return
    Row(Modifier.fillMaxWidth().padding(start = 34.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        SortHeader(ui, p, "Ім'я", SortBy.NAME, Modifier.weight(1f))
        SortHeader(ui, p, "Тип", SortBy.EXT, Modifier.width(ExtWidth))
        SortHeader(ui, p, "Розмір", SortBy.SIZE, Modifier.width(SizeWidth).padding(end = 10.dp), TextAlign.End)
        SortHeader(ui, p, "Дата", SortBy.TIME, Modifier.width(DateWidth))
    }
}

@Composable
private fun SortHeader(ui: FilesUi, p: PanelState, text: String, sort: SortBy, modifier: Modifier, align: TextAlign = TextAlign.Start) {
    val scheme = MaterialTheme.colorScheme
    val mark = if (p.sort == sort) (if (p.desc) " ▼" else " ▲") else ""
    Text(
        text + mark,
        style = MaterialTheme.typography.labelMedium,
        color = if (p.sort == sort) scheme.primary else scheme.onSurfaceVariant,
        textAlign = align,
        maxLines = 1,
        modifier = modifier.clickable {
            ui.active = p.side
            ui.sortBy(p, sort, toggle = true)
        }.padding(vertical = 5.dp),
    )
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
private fun FileList(ui: FilesUi, p: PanelState, isActive: Boolean) {
    val rows = p.rows
    val cursor = p.cursor
    val scheme = MaterialTheme.colorScheme

    // Keep the cursor in view (keys move it).
    LaunchedEffect(cursor, rows) {
        val idx = rows.indexOfFirst { it.key == cursor }
        if (idx < 0) return@LaunchedEffect
        val info = p.list.layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty()) {
            p.list.scrollToItem(idx)
            return@LaunchedEffect
        }
        val first = visible.first().index
        val lastFull = visible.lastOrNull { it.offset + it.size <= info.viewportEndOffset }?.index ?: visible.last().index
        if (idx < first) {
            p.list.scrollToItem(idx)
        } else if (idx > lastFull) {
            p.list.scrollToItem((idx - (lastFull - first)).coerceAtLeast(0))
        }
    }

    LazyColumn(Modifier.fillMaxSize(), state = p.list) {
        itemsIndexed(rows, key = { _, row -> row.key }) { _, row ->
            val marked = row.key in p.selected
            val atCursor = row.key == cursor
            val background = when {
                atCursor && isActive && marked -> Palette.Red
                atCursor && isActive -> scheme.primary
                marked -> Palette.RedSoft
                else -> Color.Transparent
            }
            val onCursor = atCursor && isActive
            val textColor = when {
                onCursor -> Color.White
                marked -> Palette.Red
                else -> scheme.onSurface
            }
            val muted = if (onCursor) Color.White.copy(alpha = 0.85f) else if (marked) Palette.Red else scheme.onSurfaceVariant
            Row(
                Modifier.fillMaxWidth().heightIn(min = 26.dp)
                    .background(background)
                    .then(if (atCursor && !isActive) Modifier.border(1.dp, scheme.primary) else Modifier)
                    .onPointerEvent(PointerEventType.Press) { e ->
                        val primary = e.buttons.isPrimaryPressed
                        val secondary = e.buttons.isSecondaryPressed
                        if (primary || secondary) {
                            ui.click(p, row, e.keyboardModifiers.isCtrlPressed, e.keyboardModifiers.isShiftPressed, secondary && !primary)
                        }
                    }
                    .padding(start = 10.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FileGlyph(row, if (onCursor) Color.White else null)
                Spacer(Modifier.width(8.dp))
                val bold = if (row.dir || marked) FontWeight.SemiBold else FontWeight.Normal
                if (row.note != null) {
                    Text(row.name, color = textColor, fontWeight = bold, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.4f))
                    Text(row.note, color = muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.6f))
                } else {
                    Text(
                        if (row.dir) row.name else row.base,
                        color = textColor,
                        fontWeight = bold,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(vertical = 3.dp),
                    )
                    Text(row.ext, color = muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(ExtWidth))
                    Text(
                        if (row.dir) "<Папка>" else Format.bytes(row.size),
                        color = muted,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        modifier = Modifier.width(SizeWidth).padding(end = 10.dp),
                    )
                    Text(
                        if (row.modified > 0) dateFormat.format(Date(row.modified)) else "",
                        color = muted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        modifier = Modifier.width(DateWidth),
                    )
                }
            }
        }
    }
}

private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm")

/** Small drawn icons: a folder, "..", or a page tinted by kind (music, video, other). */
@Composable
private fun FileGlyph(row: FileRow, tint: Color?) {
    val look = LocalLook.current
    val color = tint ?: when {
        row.dir -> if (look.bright) Palette.Violet else Palette.Indigo
        FileTypes.MUSIC.matches(row.name) -> if (look.bright) Palette.Rose else Palette.Indigo
        FileTypes.MOVIES.matches(row.name) -> if (look.bright) Palette.Teal else Palette.Sky
        else -> Palette.Grey
    }
    Canvas(Modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        when {
            row.up -> {
                // An arrow pointing up and back.
                drawLine(color, Offset(w * 0.25f, h * 0.15f), Offset(w * 0.25f, h * 0.75f), w * 0.12f)
                drawLine(color, Offset(w * 0.25f, h * 0.75f), Offset(w * 0.85f, h * 0.75f), w * 0.12f)
                drawLine(color, Offset(w * 0.25f, h * 0.12f), Offset(w * 0.05f, h * 0.4f), w * 0.12f)
                drawLine(color, Offset(w * 0.25f, h * 0.12f), Offset(w * 0.45f, h * 0.4f), w * 0.12f)
            }
            row.dir -> {
                drawRoundRect(color.copy(alpha = 0.55f), Offset(w * 0.04f, h * 0.14f), Size(w * 0.42f, h * 0.22f), CornerRadius(w * 0.06f))
                drawRoundRect(color, Offset(w * 0.04f, h * 0.26f), Size(w * 0.92f, h * 0.6f), CornerRadius(w * 0.1f))
            }
            else -> {
                drawRoundRect(color.copy(alpha = 0.15f), Offset(w * 0.16f, h * 0.06f), Size(w * 0.68f, h * 0.88f), CornerRadius(w * 0.08f))
                drawRoundRect(color, Offset(w * 0.16f, h * 0.06f), Size(w * 0.68f, h * 0.88f), CornerRadius(w * 0.08f), style = Stroke(w * 0.09f))
                drawLine(color, Offset(w * 0.32f, h * 0.42f), Offset(w * 0.68f, h * 0.42f), w * 0.08f)
                drawLine(color, Offset(w * 0.32f, h * 0.62f), Offset(w * 0.6f, h * 0.62f), w * 0.08f)
            }
        }
    }
}

// ---------------------------------------------------------------- function keys

@Composable
private fun FunctionBar(ui: FilesUi, settings: Settings) {
    val scheme = MaterialTheme.colorScheme
    val buttons = listOf(
        FmAction.VIEW to "Перегляд",
        FmAction.EDIT to "Правка",
        FmAction.COPY to "Копіювати",
        FmAction.MOVE to "Перемістити",
        FmAction.MKDIR to "Нова папка",
        FmAction.DELETE to "Видалити",
        FmAction.HOTKEYS to "Клавіші",
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((action, text) in buttons) {
            val key = Hotkeys.label(settings, action)
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(scheme.surface)
                    .border(1.dp, scheme.outline, RoundedCornerShape(8.dp))
                    .clickable { ui.perform(action) }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (key.isNotEmpty()) {
                    Text(key, style = MaterialTheme.typography.labelLarge, color = scheme.primary, fontWeight = FontWeight.Bold, maxLines = 1)
                    Spacer(Modifier.width(6.dp))
                }
                Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------------------------------------------------------- dialogs

/** Ukrainian "1 об'єкт", "3 об'єкти", "12 об'єктів". */
private fun countItems(n: Int) = "${Format.number(n.toLong())} ${Format.plural(n.toLong(), "об'єкт", "об'єкти", "об'єктів")}"

private fun describe(items: List<FileRow>): String =
    if (items.size == 1) "«${items[0].name}»" else countItems(items.size) + " (файли й папки з усім вмістом)"

@Composable
private fun DialogOverlay(ui: FilesUi, d: FmDialog, settings: Settings) {
    // In-window dialog: the window's own keys handler sends Enter/Esc to it and the rest to its field.
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { ui.closeDialog() },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            Modifier.width(if (d is FmDialog.Keys) 720.dp else 560.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 12.dp,
        ) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (d) {
                    is FmDialog.Transfer -> TransferDialog(ui, d)
                    is FmDialog.Delete -> DeleteDialog(ui, d)
                    is FmDialog.Name -> NameDialog(ui, d)
                    is FmDialog.Mask -> MaskDialog(ui, d)
                    FmDialog.Keys -> KeysDialog(ui, settings)
                }
            }
        }
    }
}

@Composable
private fun DialogField(ui: FilesUi, label: String) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedTextField(
        value = ui.dialogText,
        onValueChange = {
            ui.dialogText = it
            ui.dialogError = null
        },
        singleLine = true,
        isError = ui.dialogError != null,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    ui.dialogError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun DialogButtons(ui: FilesUi, confirm: String, danger: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (ui.dialogBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = ui::closeDialog) { Text("Скасувати (Esc)") }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = ui::confirm,
            enabled = !ui.dialogBusy,
            colors = if (danger) androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Palette.Red) else androidx.compose.material3.ButtonDefaults.buttonColors(),
        ) { Text("$confirm (Enter)") }
    }
}

@Composable
private fun ColumnScope.TransferDialog(ui: FilesUi, d: FmDialog.Transfer) {
    val verb = if (d.move) "Переміщення" else "Копіювання"
    val where = if (d.from == Side.PC) "на телефон" else "на комп'ютер"
    Text("$verb $where", style = MaterialTheme.typography.titleLarge)
    Text(
        (if (d.move) "Перемістити " else "Копіювати ") + describe(d.items) + " у папку:",
        style = MaterialTheme.typography.bodyMedium,
    )
    DialogField(ui, if (d.from == Side.PC) "Папка на телефоні" else "Папка на комп'ютері")
    Muted(
        buildString {
            append("Файли з такими самими назвами не перезаписуються: однакові пропускаються, інші зберігаються поруч як копія.")
            if (d.move) {
                append(if (d.from == Side.PC) " Після копіювання файли комп'ютера перейдуть у кошик Windows." else " Після копіювання файли буде видалено з телефона.")
            }
        },
    )
    DialogButtons(ui, if (d.move) "Перемістити" else "Копіювати")
}

@Composable
private fun ColumnScope.DeleteDialog(ui: FilesUi, d: FmDialog.Delete) {
    Text("Видалення", style = MaterialTheme.typography.titleLarge)
    Text("Видалити ${describe(d.items)}?", style = MaterialTheme.typography.bodyLarge)
    if (d.side == Side.PC) {
        Muted(if (PcFiles.canRecycle) "Вони потраплять у кошик Windows — їх можна буде відновити." else "Кошик Windows недоступний — нічого не буде видалено.")
    } else {
        Text("На телефоні немає кошика: файли буде видалено назавжди.", color = Palette.Red, style = MaterialTheme.typography.bodyMedium)
    }
    DialogButtons(ui, "Видалити", danger = d.side == Side.PHONE)
}

@Composable
private fun ColumnScope.NameDialog(ui: FilesUi, d: FmDialog.Name) {
    val where = if (d.side == Side.PC) "на комп'ютері" else "на телефоні"
    Text(if (d.rename == null) "Нова папка $where" else "Перейменування $where", style = MaterialTheme.typography.titleLarge)
    DialogField(ui, if (d.rename == null) "Назва папки" else "Нова назва")
    DialogButtons(ui, if (d.rename == null) "Створити" else "Перейменувати")
}

@Composable
private fun ColumnScope.MaskDialog(ui: FilesUi, d: FmDialog.Mask) {
    Text(if (d.select) "Позначити файли" else "Зняти позначки", style = MaterialTheme.typography.titleLarge)
    DialogField(ui, "Маска, наприклад *.mp3;*.flac")
    Muted("* — будь-які символи, ? — один символ. Кілька масок — через «;».")
    DialogButtons(ui, if (d.select) "Позначити" else "Зняти")
}

@Composable
private fun ColumnScope.KeysDialog(ui: FilesUi, settings: Settings) {
    val scheme = MaterialTheme.colorScheme
    Text("Клавіші файлового вікна", style = MaterialTheme.typography.titleLarge)
    Muted("Типово — як у Total Commander. Натисніть «+», а потім потрібне сполучення; × прибирає клавішу. Стрілки, PageUp/PageDown, Home/End і Esc працюють завжди.")
    val capturing = ui.capturing
    if (capturing != null) {
        Text(
            "Натисніть сполучення для «${capturing.title}»… (Esc — скасувати)",
            color = scheme.onPrimary,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(scheme.primary).padding(12.dp),
        )
    }
    ui.keysNote?.let { Text(it, color = Palette.Amber, style = MaterialTheme.typography.bodyMedium) }
    Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
        var group = ""
        for (action in FmAction.entries) {
            if (action.group != group) {
                group = action.group
                Text(group, style = MaterialTheme.typography.labelLarge, color = scheme.primary, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(if (capturing == action) scheme.primaryContainer else Color.Transparent)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(action.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                for (combo in Hotkeys.keysOf(settings, action)) {
                    Row(
                        Modifier.padding(start = 6.dp).clip(RoundedCornerShape(6.dp)).background(scheme.surfaceVariant)
                            .border(1.dp, scheme.outline, RoundedCornerShape(6.dp))
                            .padding(start = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(combo, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            "×",
                            style = MaterialTheme.typography.labelLarge,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.clickable {
                                ui.controller.settings.update { it.copy(fmHotkeys = Hotkeys.remove(it, action, combo)) }
                            }.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
                TextButton(onClick = {
                    ui.keysNote = null
                    ui.capturing = action
                }) { Text("+") }
            }
        }
    }
    HorizontalDivider(color = scheme.outlineVariant)
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = {
            ui.controller.settings.update { it.copy(fmHotkeys = emptyMap()) }
            ui.keysNote = "Повернуто типові клавіші Total Commander"
            ui.capturing = null
        }) { Text("Типові клавіші") }
        Spacer(Modifier.weight(1f))
        Button(onClick = ui::closeDialog) { Text("Готово") }
    }
}

// ---------------------------------------------------------------- copy queue

@Composable
private fun CopyQueue(controller: AppController, jobs: List<CopyJob>) {
    val fm = controller.files
    val look = LocalLook.current
    val speed by fm.speed.collectAsState()
    val active = jobs.any { !it.state.finished }
    val moving = jobs.any { !it.state.finished && it.move }
    val counted = jobs.filter { it.state != JobState.CANCELLED }
    val copied = jobs.count { it.state == JobState.DONE }
    val skipped = jobs.count { it.state == JobState.SKIPPED }
    val failed = jobs.filter { it.state == JobState.FAILED }
    val finished = counted.count { it.state.finished }
    val totalBytes = counted.sumOf { it.size }
    val doneBytes = counted.sumOf { if (it.state == JobState.DONE || it.state == JobState.SKIPPED) it.size else it.done.coerceAtMost(it.size) }
    val running = jobs.firstOrNull { it.state == JobState.RUNNING }
    val fraction = if (totalBytes > 0) (doneBytes.toDouble() / totalBytes).toFloat() else if (counted.isNotEmpty()) finished.toFloat() / counted.size else 1f

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        active -> (if (moving) "Переміщення" else "Копіювання") + ": ${Format.number(finished.toLong())} з ${Format.filesGenitive(counted.size)}"
                        failed.isNotEmpty() -> "Скопійовано ${Format.files(copied)}, не вдалося ${Format.files(failed.size)}"
                        else -> "Готово: скопійовано ${Format.files(copied)}"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                val details = buildList {
                    add("${Format.bytes(doneBytes)} з ${Format.bytes(totalBytes)}")
                    if (active && speed > 0) {
                        add(Format.speed(speed))
                        Format.eta(totalBytes - doneBytes, speed)?.let { add(it) }
                    }
                    if (skipped > 0) add("вже були: ${Format.files(skipped)}")
                }
                Muted(details.joinToString(" · "), maxLines = 1)
            }
            if (active) {
                OutlinedButton(onClick = fm::cancelAll) { Text("Скасувати") }
            } else {
                if (failed.isNotEmpty()) {
                    OutlinedButton(onClick = fm::retryFailed) { Text("Повторити невдалі") }
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(onClick = fm::clearFinished) { Text("Сховати") }
            }
        }
        GradientBar(fraction, if (!active && failed.isEmpty()) look.done else look.progress, 8.dp)
        running?.let { job ->
            val where = if (job.toPhone) "на телефон" else "на комп'ютер"
            Muted("Зараз $where: ${job.name}", maxLines = 1)
        }
        failed.take(3).forEach { job ->
            Text(
                "${job.name}: ${job.error ?: "помилка"}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (failed.size > 3) Muted("і ще ${Format.files(failed.size - 3)} з помилками")
    }
}
