package biz.fesh.syncmusic.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import biz.fesh.syncmusic.desktop.AppController
import biz.fesh.syncmusic.desktop.CopyJob
import biz.fesh.syncmusic.desktop.JobState
import biz.fesh.syncmusic.desktop.PairedDevice
import biz.fesh.syncmusic.desktop.PhoneItem
import biz.fesh.syncmusic.desktop.Settings
import biz.fesh.syncmusic.desktop.finished
import biz.fesh.syncmusic.shared.FileTypes
import biz.fesh.syncmusic.shared.FmOp
import biz.fesh.syncmusic.shared.Format
import biz.fesh.syncmusic.shared.PhoneStorage
import biz.fesh.syncmusic.shared.PhoneVolume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/** A file or folder line in one of the two panels. [key] is its absolute path. */
private data class FileRow(
    val key: String,
    val name: String,
    val dir: Boolean,
    val size: Long = 0,
    val modified: Long = 0,
    /** Shown under the name in the start lists ("синхронізується", free space, …). */
    val note: String? = null,
)

private class Listing(val rows: List<FileRow>, val error: String? = null)

/** The file window: the PC on the left, the phone on the right, copying both ways, the copy queue below. */
@Composable
fun FilesScreen(controller: AppController) {
    val settings by controller.settings.state.collectAsState()
    SyncMusicTheme(settings.theme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            FilesContent(controller, settings)
        }
    }
}

@Composable
private fun FilesContent(controller: AppController, settings: Settings) {
    val fm = controller.files
    val online by fm.online.collectAsState()
    val deviceId by fm.phoneDevice.collectAsState()
    val notice by fm.notice.collectAsState()
    val jobs by fm.jobs.collectAsState()
    val refresh by fm.refresh.collectAsState()
    val device = settings.devices.firstOrNull { it.id == deviceId } ?: settings.devices.firstOrNull()
    val phoneOnline = device != null && device.id in online

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        FilesHeader(settings.devices, device, phoneOnline) { id ->
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
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PcPanel(Modifier.weight(1f), controller, device, phoneOnline, refresh)
            PhonePanel(Modifier.weight(1f), controller, device, phoneOnline, refresh)
        }
        if (jobs.isNotEmpty()) CopyQueue(controller, jobs)
    }
}

@Composable
private fun FilesHeader(devices: List<PairedDevice>, device: PairedDevice?, online: Boolean, onDevice: (String) -> Unit) {
    val look = LocalLook.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(if (look.bright) HeaderBrush else Brush.linearGradient(listOf(Palette.Indigo, Palette.Indigo))),
            contentAlignment = Alignment.Center,
        ) { ScalesLogo(32.dp, Color.White) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Файли комп'ютера і телефона", style = MaterialTheme.typography.titleLarge)
            Muted("Позначте файли або папки й натисніть «Копіювати». Двічі клацніть папку, щоб відкрити її.", maxLines = 2)
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
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(look.toPcSoft).padding(horizontal = 16.dp, vertical = 12.dp),
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
private fun PcPanel(modifier: Modifier, controller: AppController, device: PairedDevice, phoneOnline: Boolean, refresh: Int) {
    val fm = controller.files
    val look = LocalLook.current
    val path by fm.pcPath.collectAsState()
    val phonePath by fm.phonePath.collectAsState()
    var listing by remember { mutableStateOf<Listing?>(null) }
    var selected by remember(path) { mutableStateOf(setOf<String>()) }
    var reload by remember { mutableStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(path, refresh, reload) {
        listing = withContext(Dispatchers.IO) { listPc(path, controller.settings.value) }
    }

    val current = path
    PanelFrame(
        modifier = modifier,
        title = "Комп'ютер",
        location = current ?: "Швидкий доступ і диски",
        accent = look.toPhoneBrush,
        onUp = current?.let { p -> { fm.pcPath.value = File(p).parentFile?.path } },
        onRefresh = { reload++ },
        onNewFolder = if (current != null) ({ creating = true }) else null,
        actions = {
            val target = phonePath
            CopyButton(
                text = "Копіювати на телефон",
                brush = look.toPhoneBrush,
                enabled = selected.isNotEmpty() && target != null && phoneOnline,
            ) {
                fm.copyToPhone(device.id, selected.map(::File), target!!)
                selected = emptySet()
            }
        },
        hint = when {
            selected.isEmpty() -> null
            phonePath == null -> "Праворуч відкрийте папку телефона, куди копіювати"
            else -> "Вибрано: ${selected.size}"
        },
    ) {
        FileList(
            listing = listing,
            selectable = current != null,
            selected = selected,
            onToggle = { key -> selected = if (key in selected) selected - key else selected + key },
            onOpen = { row -> fm.pcPath.value = row.key },
        )
    }

    if (creating && current != null) {
        NameDialog(
            title = "Нова папка на комп'ютері",
            error = createError,
            onDismiss = {
                creating = false
                createError = null
            },
        ) { name ->
            val dir = File(current, name)
            if (dir.exists()) {
                createError = "Така назва вже є"
            } else if (dir.mkdir()) {
                creating = false
                createError = null
                reload++
            } else {
                createError = "Не вдалося створити папку"
            }
        }
    }
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
        .sortedWith(compareBy({ !it.dir }, { it.name.lowercase() }))
    return Listing(rows)
}

// ---------------------------------------------------------------- phone panel

@Composable
private fun PhonePanel(modifier: Modifier, controller: AppController, device: PairedDevice, phoneOnline: Boolean, refresh: Int) {
    val fm = controller.files
    val look = LocalLook.current
    val scope = rememberCoroutineScope()
    val path by fm.phonePath.collectAsState()
    val pcPath by fm.pcPath.collectAsState()
    var listing by remember { mutableStateOf<Listing?>(null) }
    var volumes by remember(device.id) { mutableStateOf(controller.phoneStorage.value[device.id]?.volumes.orEmpty()) }
    var selected by remember(path) { mutableStateOf(setOf<String>()) }
    var reload by remember { mutableStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(path, refresh, reload, phoneOnline, device.id) {
        if (!phoneOnline) {
            listing = Listing(emptyList())
            return@LaunchedEffect
        }
        listing = null
        val current = path
        val reply = fm.request(device.id, FmOp.LIST, current.orEmpty())
        listing = when {
            !reply.ok -> Listing(emptyList(), reply.error)
            current == null -> {
                volumes = reply.volumes
                Listing(
                    reply.volumes.map { v ->
                        val free = if (v.freeBytes >= 0) "вільно ${Format.bytes(v.freeBytes)}" + (if (v.totalBytes > 0) " з ${Format.bytes(v.totalBytes)}" else "") else null
                        FileRow(v.root, v.label, true, note = free)
                    },
                )
            }
            else -> Listing(
                reply.entries
                    .map { FileRow(current.trimEnd('/') + "/" + it.name, it.name, it.dir, it.size, it.modified) }
                    .sortedWith(compareBy({ !it.dir }, { it.name.lowercase() })),
            )
        }
    }

    val current = path
    val volume = current?.let { p -> volumes.filter { PhoneStorage.inside(p, it.root) }.maxByOrNull { it.root.length } }
    PanelFrame(
        modifier = modifier,
        title = "Телефон",
        location = when {
            current == null -> "Пам'ять телефона і SD-карта"
            volume != null -> volume.label + current.removePrefix(volume.root).trim('/').let { if (it.isEmpty()) "" else " › " + it.replace("/", " › ") }
            else -> current
        },
        accent = look.toPcBrush,
        onUp = current?.let { p -> { fm.phonePath.value = phoneParent(p, volumes) } },
        onRefresh = { reload++ },
        onNewFolder = if (current != null && phoneOnline) ({ creating = true }) else null,
        actions = {
            val target = pcPath
            CopyButton(
                text = "Копіювати на комп'ютер",
                brush = look.toPcBrush,
                enabled = selected.isNotEmpty() && target != null && phoneOnline,
            ) {
                val rows = listing?.rows.orEmpty().filter { it.key in selected }
                fm.copyToPc(device.id, rows.map { PhoneItem(it.key, it.name, it.dir, it.size, it.modified) }, File(target!!))
                selected = emptySet()
            }
        },
        hint = when {
            selected.isEmpty() -> null
            pcPath == null -> "Ліворуч відкрийте папку комп'ютера, куди копіювати"
            else -> "Вибрано: ${selected.size}"
        },
    ) {
        if (!phoneOnline) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Muted("Файли з'являться, щойно телефон підключиться")
            }
        } else {
            FileList(
                listing = listing,
                selectable = current != null,
                selected = selected,
                onToggle = { key -> selected = if (key in selected) selected - key else selected + key },
                onOpen = { row -> fm.phonePath.value = row.key },
            )
        }
    }

    if (creating && current != null) {
        NameDialog(
            title = "Нова папка на телефоні",
            error = createError,
            onDismiss = {
                creating = false
                createError = null
            },
        ) { name ->
            scope.launch {
                val reply = fm.request(device.id, FmOp.MKDIR, current.trimEnd('/') + "/" + name)
                if (reply.ok) {
                    creating = false
                    createError = null
                    reload++
                } else {
                    createError = reply.error ?: "Не вдалося створити папку"
                }
            }
        }
    }
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

// ---------------------------------------------------------------- shared pieces

@Composable
private fun PanelFrame(
    modifier: Modifier,
    title: String,
    location: String,
    accent: Brush,
    onUp: (() -> Unit)?,
    onRefresh: () -> Unit,
    onNewFolder: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
    hint: String?,
    content: @Composable () -> Unit,
) {
    Column(
        modifier.fillMaxSize().clip(RoundedCornerShape(18.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Box(Modifier.fillMaxWidth().height(5.dp).background(accent))
        Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                if (onNewFolder != null) TextButton(onClick = onNewFolder) { Text("Нова папка") }
                TextButton(onClick = onRefresh) { Text("Оновити") }
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(start = 4.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onUp?.invoke() }, enabled = onUp != null) { Text("↑ Вгору") }
                Text(
                    location,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { hint?.let { Muted(it, maxLines = 2) } }
                Spacer(Modifier.width(8.dp))
                actions()
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

@Composable
private fun CopyButton(text: String, brush: Brush, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .then(if (enabled) Modifier.background(brush) else Modifier.background(MaterialTheme.colorScheme.surfaceVariant))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileList(
    listing: Listing?,
    selectable: Boolean,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onOpen: (FileRow) -> Unit,
) {
    when {
        listing == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        }
        listing.error != null -> Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
            Text(listing.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        listing.rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Muted("Порожня папка") }
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(listing.rows, key = { it.key }) { row ->
                val isSelected = row.key in selected
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .combinedClickable(
                            onClick = { if (selectable) onToggle(row.key) else if (row.dir) onOpen(row) },
                            onDoubleClick = { if (row.dir) onOpen(row) },
                        )
                        .padding(start = 6.dp, end = 10.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selectable) {
                        Checkbox(checked = isSelected, onCheckedChange = { onToggle(row.key) })
                    } else {
                        Spacer(Modifier.width(12.dp))
                    }
                    FileGlyph(row)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        Text(
                            row.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (row.dir) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        row.note?.let { Muted(it, maxLines = 1) }
                    }
                    if (!row.dir) {
                        Text(
                            Format.bytes(row.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(84.dp),
                        )
                        Text(
                            if (row.modified > 0) dateFormat.format(Date(row.modified)) else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(112.dp),
                        )
                    } else {
                        TextButton(onClick = { onOpen(row) }) { Text("Відкрити") }
                    }
                }
            }
        }
    }
}

private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm")

/** Small drawn icons: a folder, or a page tinted by kind (music, video, other). */
@Composable
private fun FileGlyph(row: FileRow) {
    val look = LocalLook.current
    val color = when {
        row.dir -> if (look.bright) Palette.Violet else Palette.Indigo
        FileTypes.MUSIC.matches(row.name) -> if (look.bright) Palette.Rose else Palette.Indigo
        FileTypes.MOVIES.matches(row.name) -> if (look.bright) Palette.Teal else Palette.Sky
        else -> Palette.Grey
    }
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        if (row.dir) {
            drawRoundRect(color.copy(alpha = 0.55f), Offset(w * 0.06f, h * 0.18f), Size(w * 0.4f, h * 0.2f), CornerRadius(w * 0.06f))
            drawRoundRect(color, Offset(w * 0.06f, h * 0.28f), Size(w * 0.88f, h * 0.56f), CornerRadius(w * 0.1f))
        } else {
            drawRoundRect(color.copy(alpha = 0.15f), Offset(w * 0.18f, h * 0.08f), Size(w * 0.64f, h * 0.84f), CornerRadius(w * 0.08f))
            drawRoundRect(color, Offset(w * 0.18f, h * 0.08f), Size(w * 0.64f, h * 0.84f), CornerRadius(w * 0.08f), style = Stroke(w * 0.07f))
            drawLine(color, Offset(w * 0.32f, h * 0.42f), Offset(w * 0.68f, h * 0.42f), w * 0.06f)
            drawLine(color, Offset(w * 0.32f, h * 0.6f), Offset(w * 0.6f, h * 0.6f), w * 0.06f)
        }
    }
}

@Composable
private fun NameDialog(title: String, error: String?, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("Нова папка") }
    val valid = PhoneStorage.isValidName(name.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    isError = !valid || error != null,
                    label = { Text("Назва папки") },
                )
                when {
                    !valid -> Text("Назва не може містити / \\ : * ? \" < > |", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    error != null -> Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onConfirm(name.trim()) }) { Text("Створити") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } },
    )
}

// ---------------------------------------------------------------- copy queue

@Composable
private fun CopyQueue(controller: AppController, jobs: List<CopyJob>) {
    val fm = controller.files
    val look = LocalLook.current
    val speed by fm.speed.collectAsState()
    val active = jobs.any { !it.state.finished }
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
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        active -> "Копіювання: ${Format.number(finished.toLong())} з ${Format.filesGenitive(counted.size)}"
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
        GradientBar(fraction, if (!active && failed.isEmpty()) look.done else look.progress, 10.dp)
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
