package biz.fesh.syncmusic.desktop.ui

import biz.fesh.syncmusic.shared.tr
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
    // Right after adding, the dialog also sets which files and the direction, so it is useful without a phone.
    if (folder == null || (devices.isEmpty() && !request.isNew)) {
        LaunchedEffect(Unit) { controller.closePhonePicker() }
        return
    }
    var deviceId by remember(request) {
        mutableStateOf(request.deviceId?.takeIf { id -> devices.any { it.id == id } } ?: devices.firstOrNull()?.id)
    }
    val device = devices.firstOrNull { it.id == deviceId } ?: devices.firstOrNull()
    val source = SyncProgressSource { d -> live[d.id]?.progress ?: d.lastProgress }

    Dialog(onDismissRequest = controller::closePhonePicker, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.width(640.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    if (request.isNew) tr("Нова папка «${folder.name}»", "Новая папка «${folder.name}»") else tr("Папка на телефоні для «${folder.name}»", "Папка на телефоне для «${folder.name}»"),
                    style = MaterialTheme.typography.titleLarge,
                )
                Note(folder.path, maxLines = 1)
                // Inside the dialog, so its confirmation shows on top. A just added folder has nothing on the
                // phone yet: narrowing the file types removes nothing there, no need to ask.
                val setFileTypes = fileTypesSetter(folder, controller, askFirst = !request.isNew)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Які файли синхронізувати", "Какие файлы синхронизировать"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    FileTypesToggle(folder.fileTypes, setFileTypes)
                }
                Note(Format.fileTypesHint(folder.fileTypes))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (folder.direction == Direction.TO_PHONE) tr("Куди класти файли з комп'ютера", "Куда класть файлы с компьютера") else tr("Яку папку телефона надсилати на комп'ютер", "Какую папку телефона отправлять на компьютер"),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    DirectionToggle(folder.direction) { controller.setDirection(folder.id, it) }
                }
                if (device == null) {
                    NoPhoneYet(folder, controller)
                } else {
                    if (devices.size > 1) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            devices.forEach { d -> Chip(d.name, d.id == device.id) { deviceId = d.id } }
                        }
                    }
                    val progress = PhoneFolders.progressOf(device, source, folder.id)
                    val current = PhoneFolders.currentPath(folder.phoneTargets[device.id], progress)
                    // Phone folders of the other PC folders on this phone, to warn about nesting.
                    val others = settings.folders.filter { it.id != folder.id }.mapNotNull { other ->
                        PhoneFolders.currentPath(other.phoneTargets[device.id], PhoneFolders.progressOf(device, source, other.id))?.let { other.name to it }
                    }
                    key(device.id, folder.direction) {
                        PickerBody(folder, device, storages[device.id], current, others, controller, cancelLabel = if (request.isNew) tr("Пізніше", "Позже") else tr("Скасувати", "Отмена"))
                    }
                }
            }
        }
    }
}

/** A folder added before any phone was connected: the phone folder is chosen later (or on the phone). */
@Composable
private fun NoPhoneYet(folder: SyncFolder, controller: AppController) {
    Note(
        if (folder.direction == Direction.TO_PHONE) {
            tr("Телефон ще не підключено. Папку на телефоні можна буде вибрати після підключення — тут або в застосунку на телефоні. ", "Телефон ещё не подключён. Папку на телефоне можно будет выбрать после подключения — здесь или в приложении на телефоне. ") +
                tr("Без вибору телефон покладе файли в «Sync Music/${folder.name}».", "Без выбора телефон положит файлы в «Sync Music/${folder.name}».")
        } else {
            tr("Телефон ще не підключено. Яку папку телефона надсилати, можна буде вибрати після підключення — ", "Телефон ещё не подключён. Какую папку телефона отправлять, можно будет выбрать после подключения — ") +
                tr("тут або в застосунку на телефоні.", "здесь или в приложении на телефоне.")
        },
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        Button(onClick = controller::closePhonePicker) { Text(tr("Готово", "Готово")) }
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
    cancelLabel: String,
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
            tr("Телефон «${device.name}» ще не надіслав список своїх папок — це станеться під час його наступної синхронізації ", "Телефон «${device.name}» ещё не прислал список своих папок — это произойдёт во время его следующей синхронизации ") +
                tr("(потрібна нова версія застосунку на телефоні). Поки що папку можна вказати вручну кнопкою «Нова папка».", "(нужна новая версия приложения на телефоне). Пока что папку можно указать вручную кнопкой «Новая папка»."),
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
    if (volume.freeBytes >= 0) Note(tr("Вільно ${Format.bytes(volume.freeBytes)} з ${Format.bytes(volume.totalBytes)}", "Свободно ${Format.bytes(volume.freeBytes)} из ${Format.bytes(volume.totalBytes)}"))
    if (volume.appPrivate) {
        Text(
            tr("На цій версії Android на SD-карту можна писати лише в папку застосунку. Вона видаляється разом із застосунком.", "В этой версии Android на SD-карту можно записывать только в папку приложения. Она удаляется вместе с приложением."),
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Amber,
        )
    }
    Text(describe(selected), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

    Surface(
        Modifier.fillMaxWidth().height(220.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        LazyColumn(Modifier.padding(6.dp)) {
            if (rel.isNotEmpty()) {
                item { FolderLine(tr("‹  На рівень вище", "‹  На уровень выше"), folder = false) { rel = parentOf(rel) } }
            }
            items(children, key = { it }) { dir ->
                val path = volume.root.trimEnd('/') + "/" + dir
                val mark = when {
                    path == current -> tr("  · зараз тут", "  · сейчас здесь")
                    else -> others.firstOrNull { it.second == path }?.let { "  · «${it.first}»" } ?: ""
                }
                FolderLine(dir.substringAfterLast('/') + mark, folder = true) { rel = dir }
            }
            if (children.isEmpty()) {
                item {
                    Note(
                        if (volume.truncated && volume.dirs.isNotEmpty()) tr("Вкладених папок не показано. Можна створити або ввести назву кнопкою «Нова папка».", "Вложенные папки не показаны. Можно создать или ввести название кнопкой «Новая папка».")
                        else tr("Тут немає вкладених папок", "Здесь нет вложенных папок"),
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
                placeholder = { Text(tr("Назва папки", "Название папки")) },
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
            }) { Text(tr("Додати", "Добавить")) }
            TextButton(onClick = { creating = false }) { Text(tr("Скасувати", "Отмена")) }
        }
        if (!toPhone) Note(tr("Папку телефона, якої там немає, надсилати нема чого — виберіть наявну папку.", "Папку телефона, которой там нет, отправлять нечего — выберите существующую папку."))
    }

    // What happens with the files already on the phone.
    if (toPhone && current != null && current != selected) {
        val oldVolume = volumes.filter { PhoneFolders.inside(current, it.root) }.maxByOrNull { it.root.length }
        if (oldVolume != null && oldVolume.id == volume.id) {
            Note(tr("Файли з «${describe(current)}» буде перенесено на телефоні без повторного завантаження (якщо нова папка порожня).", "Файлы из «${describe(current)}» будут перенесены на телефоне без повторной загрузки (если новая папка пуста)."))
        } else {
            Note(tr("Файли буде заново завантажено в нову папку на іншому носії.", "Файлы будут заново загружены в новую папку на другом носителе."))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = deleteOld, onCheckedChange = { deleteOld = it })
                Text(tr("Видалити з телефона старі копії в «${describe(current)}»", "Удалить с телефона старые копии в «${describe(current)}»"), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    val nested = others.firstOrNull { (_, path) -> path != selected && (PhoneFolders.inside(path, selected) || PhoneFolders.inside(selected, path)) }
    if (nested != null) {
        Text(
            tr("Ця папка вкладена в папку «${nested.first}» на телефоні (або містить її). Краще вибрати окрему папку.", "Эта папка вложена в папку «${nested.first}» на телефоне (или содержит её). Лучше выбрать отдельную папку."),
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Amber,
        )
    }
    val sameAsOther = others.firstOrNull { it.second == selected }
    Note(tr("Телефон застосує вибір під час наступної синхронізації: протягом 15 хвилин або одразу, коли відкрити застосунок.", "Телефон применит выбор при следующей синхронизации: в течение 15 минут или сразу, когда открыть приложение."))

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = controller::closePhonePicker) { Text(cancelLabel) }
        Spacer(Modifier.weight(1f))
        if (toPhone && !creating) {
            OutlinedButton(onClick = { creating = true }) { Text(tr("Нова папка", "Новая папка")) }
            Spacer(Modifier.width(8.dp))
        } else if (!creating) {
            OutlinedButton(onClick = { creating = true }) { Text(tr("Ввести назву", "Ввести название")) }
            Spacer(Modifier.width(8.dp))
        }
        val canPick = !(toPhone && rel.isEmpty()) && sameAsOther == null
        Button(
            enabled = canPick,
            onClick = {
                controller.setPhoneFolder(folder.id, device.id, selected, deleteOld)
                controller.closePhonePicker()
            },
        ) { Text(tr("Вибрати цю папку", "Выбрать эту папку")) }
    }
    if (toPhone && rel.isEmpty()) Note(tr("Виберіть або створіть папку — не корінь пам'яті.", "Выберите или создайте папку — не корень памяти."))
    if (sameAsOther != null) Note(tr("Цю папку вже використовує «${sameAsOther.first}».", "Эту папку уже использует «${sameAsOther.first}»."))
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
