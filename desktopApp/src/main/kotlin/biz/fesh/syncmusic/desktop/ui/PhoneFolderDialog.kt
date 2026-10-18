package biz.fesh.syncmusic.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import biz.fesh.syncmusic.desktop.AppController
import biz.fesh.syncmusic.desktop.PairedDevice
import biz.fesh.syncmusic.desktop.PhonePickerRequest
import biz.fesh.syncmusic.desktop.PhoneTarget
import biz.fesh.syncmusic.desktop.SyncFolder
import biz.fesh.syncmusic.shared.Direction
import biz.fesh.syncmusic.shared.FolderProgress
import biz.fesh.syncmusic.shared.Format
import biz.fesh.syncmusic.shared.PhoneStorage
import biz.fesh.syncmusic.shared.PhoneVolume

/** Where a PC folder lives on a phone, combining what the phone reported and what was chosen here. */
object PhoneFolders {
    fun progressOf(device: PairedDevice, live: SyncProgressSource, folderId: String): FolderProgress? =
        live.progress(device)?.folders?.firstOrNull { it.id == folderId }

    /** Chosen here, but the phone has not applied it yet. */
    fun pending(target: PhoneTarget?, progress: FolderProgress?): Boolean =
        target != null && progress?.phonePath != target.path && (progress?.pcChoiceAt ?: 0L) < target.at

    /** The phone folder to show: the pending choice, otherwise what the phone uses. */
    fun currentPath(target: PhoneTarget?, progress: FolderProgress?): String? =
        if (pending(target, progress)) target?.path else progress?.phonePath ?: target?.path

    fun describe(storage: PhoneStorage?, path: String): String = (storage ?: PhoneStorage()).describe(path)

    fun inside(path: String, root: String) = PhoneStorage.inside(path, root)
}

/** Latest progress of a phone (live if it is syncing right now). */
fun interface SyncProgressSource {
    fun progress(device: PairedDevice): biz.fesh.syncmusic.shared.SyncProgress?
}

@Composable
fun PhoneFolderDialog(request: PhonePickerRequest, controller: AppController) {
    val settings by controller.settings.state.collectAsState()
    val storages by controller.phoneStorage.collectAsState()
    val live by controller.live.collectAsState()
    val folder = settings.folders.firstOrNull { it.id == request.folderId }
    val devices = settings.devices.sortedByDescending { it.lastSeen }
    if (folder == null || devices.isEmpty()) {
        LaunchedEffect(Unit) { controller.closePhonePicker() }
        return
    }
    var deviceId by remember(request) {
        mutableStateOf(request.deviceId?.takeIf { id -> devices.any { it.id == id } } ?: devices.first().id)
    }
    val device = devices.firstOrNull { it.id == deviceId } ?: devices.first()
    val source = SyncProgressSource { d -> live[d.id]?.progress ?: d.lastProgress }
    val storage = storages[device.id]
    val progress = PhoneFolders.progressOf(device, source, folder.id)
    val target = folder.phoneTargets[device.id]
    val current = PhoneFolders.currentPath(target, progress)
    // Phone folders of the other PC folders on this phone, to warn about nesting.
    val others = settings.folders.filter { it.id != folder.id }.mapNotNull { other ->
        PhoneFolders.currentPath(other.phoneTargets[device.id], PhoneFolders.progressOf(device, source, other.id))?.let { other.name to it }
    }

    Dialog(onDismissRequest = controller::closePhonePicker, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.width(640.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Папка на телефоні для «${folder.name}»", style = MaterialTheme.typography.titleLarge)
                Note(folder.path, maxLines = 1)
                if (devices.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        devices.forEach { d -> Chip(d.name, d.id == device.id) { deviceId = d.id } }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (folder.direction == Direction.TO_PHONE) "Куди класти файли з комп'ютера" else "Яку папку телефона надсилати на комп'ютер",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    DirectionToggle(folder.direction) { controller.setDirection(folder.id, it) }
                }
                key(device.id, folder.direction) {
                    PickerBody(folder, device, storage, current, others, controller)
                }
            }
        }
    }
}

@Composable
private fun PickerBody(
    folder: SyncFolder,
    device: PairedDevice,
    storage: PhoneStorage?,
    current: String?,
    others: List<Pair<String, String>>,
    controller: AppController,
) {
    val toPhone = folder.direction == Direction.TO_PHONE
    val volumes = storage?.volumes?.takeIf { it.isNotEmpty() }
        ?: listOf(PhoneVolume("primary", PhoneStorage.PRIMARY_LABEL, PhoneStorage.PRIMARY_ROOT))
    val startVolume = current?.let { path -> volumes.filter { PhoneFolders.inside(path, it.root) }.maxByOrNull { it.root.length } }
        ?: volumes.first()
    val startRel = current?.takeIf { PhoneFolders.inside(it, startVolume.root) }?.removePrefix(startVolume.root)?.trim('/').orEmpty()

    var volumeId by remember { mutableStateOf(startVolume.id) }
    var rel by remember { mutableStateOf(startRel) }
    // Folders typed here that the phone did not report (new ones, or deeper than the phone lists).
    var extras by remember { mutableStateOf(mapOf(startVolume.id to withParents(startRel))) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var deleteOld by remember { mutableStateOf(false) }

    val volume = volumes.firstOrNull { it.id == volumeId } ?: volumes.first()
    val known = remember(volume, extras) { (volume.dirs + extras[volume.id].orEmpty()).toSet() }
    val children = known.filter { parentOf(it) == rel }.sortedBy { it.lowercase() }
    val selected = if (rel.isEmpty()) volume.root else volume.root.trimEnd('/') + "/" + rel
    val describe = { path: String -> PhoneFolders.describe(storage, path) }

    if (storage == null) {
        Text(
            "Телефон «${device.name}» ще не надіслав список своїх папок — це станеться під час його наступної синхронізації " +
                "(потрібна нова версія застосунку на телефоні). Поки що папку можна вказати вручну кнопкою «Нова папка».",
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.Amber,
        )
    }
    if (volumes.size > 1) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            volumes.forEach { v ->
                Chip(v.label, v.id == volume.id) {
                    volumeId = v.id
                    rel = ""
                    creating = false
                }
            }
        }
    }
    if (volume.freeBytes >= 0) Note("Вільно ${Format.bytes(volume.freeBytes)} з ${Format.bytes(volume.totalBytes)}")
    if (volume.appPrivate) {
        Text(
            "На цій версії Android на SD-карту можна писати лише в папку застосунку. Вона видаляється разом із застосунком.",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Amber,
        )
    }
    Text(describe(selected), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

    Surface(
        Modifier.fillMaxWidth().height(250.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        LazyColumn(Modifier.padding(6.dp)) {
            if (rel.isNotEmpty()) {
                item { FolderLine("‹  На рівень вище", folder = false) { rel = parentOf(rel) } }
            }
            items(children, key = { it }) { dir ->
                val path = volume.root.trimEnd('/') + "/" + dir
                val mark = when {
                    path == current -> "  · зараз тут"
                    else -> others.firstOrNull { it.second == path }?.let { "  · «${it.first}»" } ?: ""
                }
                FolderLine(dir.substringAfterLast('/') + mark, folder = true) { rel = dir }
            }
            if (children.isEmpty()) {
                item {
                    Note(
                        if (volume.truncated && volume.dirs.isNotEmpty()) "Вкладених папок не показано. Можна створити або ввести назву кнопкою «Нова папка»."
                        else "Тут немає вкладених папок",
                    )
                }
            }
        }
    }

    if (creating) {
        val valid = PhoneStorage.isValidName(newName)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                singleLine = true,
                placeholder = { Text("Назва папки") },
                modifier = Modifier.weight(1f),
                isError = newName.isNotEmpty() && !valid,
            )
            Spacer(Modifier.width(8.dp))
            Button(enabled = valid, onClick = {
                val dir = if (rel.isEmpty()) newName else "$rel/$newName"
                extras = extras + (volume.id to (extras[volume.id].orEmpty() + dir))
                rel = dir
                newName = ""
                creating = false
            }) { Text("Додати") }
            TextButton(onClick = { creating = false }) { Text("Скасувати") }
        }
        if (!toPhone) Note("Папку телефона, якої там немає, надсилати нема чого — виберіть наявну папку.")
    }

    // What happens with the files already on the phone.
    if (toPhone && current != null && current != selected) {
        val oldVolume = volumes.filter { PhoneFolders.inside(current, it.root) }.maxByOrNull { it.root.length }
        if (oldVolume != null && oldVolume.id == volume.id) {
            Note("Файли з «${describe(current)}» буде перенесено на телефоні без повторного завантаження (якщо нова папка порожня).")
        } else {
            Note("Файли буде заново завантажено в нову папку на іншому носії.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = deleteOld, onCheckedChange = { deleteOld = it })
                Text("Видалити з телефона старі копії в «${describe(current)}»", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    val nested = others.firstOrNull { (_, path) -> path != selected && (PhoneFolders.inside(path, selected) || PhoneFolders.inside(selected, path)) }
    if (nested != null) {
        Text(
            "Ця папка вкладена в папку «${nested.first}» на телефоні (або містить її). Краще вибрати окрему папку.",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Amber,
        )
    }
    val sameAsOther = others.firstOrNull { it.second == selected }
    Note("Телефон застосує вибір під час наступної синхронізації: протягом 15 хвилин або одразу, коли відкрити застосунок.")

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = controller::closePhonePicker) { Text("Скасувати") }
        Spacer(Modifier.weight(1f))
        if (toPhone && !creating) {
            OutlinedButton(onClick = { creating = true }) { Text("Нова папка") }
            Spacer(Modifier.width(8.dp))
        } else if (!creating) {
            OutlinedButton(onClick = { creating = true }) { Text("Ввести назву") }
            Spacer(Modifier.width(8.dp))
        }
        val canPick = !(toPhone && rel.isEmpty()) && sameAsOther == null
        Button(
            enabled = canPick,
            onClick = {
                controller.setPhoneFolder(folder.id, device.id, selected, deleteOld)
                controller.closePhonePicker()
            },
        ) { Text("Вибрати цю папку") }
    }
    if (toPhone && rel.isEmpty()) Note("Виберіть або створіть папку — не корінь пам'яті.")
    if (sameAsOther != null) Note("Цю папку вже використовує «${sameAsOther.first}».")
}

private fun parentOf(path: String) = path.substringBeforeLast('/', "")

/** "a/b/c" -> {"a", "a/b", "a/b/c"} */
private fun withParents(rel: String): Set<String> {
    if (rel.isEmpty()) return emptySet()
    val parts = rel.split('/')
    return (1..parts.size).mapTo(HashSet()) { parts.take(it).joinToString("/") }
}

@Composable
private fun Note(text: String, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = !selected, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FolderLine(name: String, folder: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(width = 18.dp, height = 13.dp).clip(RoundedCornerShape(3.dp))
                .background(if (folder) MaterialTheme.colorScheme.primaryContainer else Color.Transparent),
        )
        Spacer(Modifier.width(12.dp))
        Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (folder) FontWeight.Normal else FontWeight.Medium)
    }
}
