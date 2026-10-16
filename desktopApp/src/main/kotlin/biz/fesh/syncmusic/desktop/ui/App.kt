package biz.fesh.syncmusic.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import biz.fesh.syncmusic.desktop.AppController
import biz.fesh.syncmusic.desktop.AppLog
import biz.fesh.syncmusic.desktop.AppPaths
import biz.fesh.syncmusic.desktop.ApkResource
import biz.fesh.syncmusic.desktop.BuildInfo
import biz.fesh.syncmusic.desktop.LibraryStats
import biz.fesh.syncmusic.desktop.LiveStatus
import biz.fesh.syncmusic.desktop.NetUtil
import biz.fesh.syncmusic.desktop.PairedDevice
import biz.fesh.syncmusic.desktop.ServerState
import biz.fesh.syncmusic.desktop.Settings
import biz.fesh.syncmusic.desktop.WindowsIntegration
import biz.fesh.syncmusic.shared.Format
import biz.fesh.syncmusic.shared.SyncPhase
import biz.fesh.syncmusic.shared.SyncProgress
import kotlinx.coroutines.delay
import java.awt.Desktop
import java.io.File
import javax.swing.JFileChooser

@Composable
fun App(controller: AppController) {
    val settings by controller.settings.state.collectAsState()
    val stats by controller.library.stats.collectAsState()
    val pin by controller.pin.collectAsState()
    val live by controller.live.collectAsState()
    val serverState by controller.serverState.collectAsState()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }
    val addresses = remember(now / 30_000) { NetUtil.localAddresses() }

    SyncMusicTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Header(serverState, addresses, settings.port)
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                    LibraryCard(Modifier.weight(1f), stats, settings, controller)
                    ConnectCard(Modifier.weight(1.25f), pin, addresses, settings.port, controller)
                }
                DevicesSection(settings, live, now, controller)
                SettingsCard(settings, controller)
                Text(
                    "Sync Music ${BuildInfo.version}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Header(serverState: ServerState, addresses: List<NetUtil.LocalAddress>, port: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Sync Music", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Синхронизация музыки с телефоном по Wi-Fi",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (serverState) {
            is ServerState.Running -> {
                val address = addresses.firstOrNull()?.address
                StatusPill(
                    text = if (address != null) "Работает · $address:$port" else "Работает · нет сети",
                    color = if (address != null) Palette.Green else Palette.Amber,
                    background = if (address != null) Palette.GreenSoft else Palette.AmberSoft,
                )
            }
            ServerState.Starting -> StatusPill("Запуск…", Palette.Grey, Palette.GreySoft)
            is ServerState.Failed -> StatusPill(serverState.message, Palette.Red, Palette.RedSoft)
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color, background: Color) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(background).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text, color = color, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun CardTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun Muted(text: String, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

// ---------------------------------------------------------------- library

@Composable
private fun LibraryCard(modifier: Modifier, stats: LibraryStats, settings: Settings, controller: AppController) {
    SectionCard(modifier) {
        CardTitle("Музыка на компьютере")
        Muted(settings.musicFolder ?: "Папка не выбрана", maxLines = 2)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Format.files(stats.files), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(10.dp))
            Text(Format.bytes(stats.bytes), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (stats.scanning) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Muted("Сканирование папки…")
            }
        } else if (stats.scannedAt > 0) {
            Muted("Проверено ${Format.moment(stats.scannedAt)}")
        }
        stats.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                chooseFolder(settings.musicFolder)?.let(controller::setMusicFolder)
            }) { Text("Выбрать папку") }
            OutlinedButton(onClick = controller::rescan) { Text("Обновить") }
        }
        Muted("Синхронизируются аудиофайлы, плейлисты и тексты песен (.lrc), с сохранением структуры папок.")
    }
}

private fun chooseFolder(initial: String?): String? {
    val chooser = JFileChooser(initial ?: System.getProperty("user.home")).apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Выберите папку с музыкой"
        isAcceptAllFileFilterUsed = false
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absolutePath else null
}

// ---------------------------------------------------------------- connect

@Composable
private fun ConnectCard(
    modifier: Modifier,
    pin: String,
    addresses: List<NetUtil.LocalAddress>,
    port: Int,
    controller: AppController,
) {
    SectionCard(modifier) {
        CardTitle("Подключить телефон")
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            val address = addresses.firstOrNull()?.address
            if (address != null && ApkResource.available) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    QrCode("http://$address:$port/", 150.dp, Modifier.clip(RoundedCornerShape(8.dp)))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Step("1", "Установите приложение")
                if (address != null && ApkResource.available) {
                    Muted("Наведите камеру телефона на QR-код и скачайте приложение. Телефон должен быть в той же Wi-Fi сети.")
                } else {
                    Muted("Скачайте SyncMusic.apk со страницы релизов и установите на телефон.")
                }
                Spacer(Modifier.height(6.dp))
                Step("2", "Введите код в приложении")
                Muted("Нажмите «Найти компьютер», выберите «${controller.computerName}» и введите код:")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(pin.chunked(3).joinToString(" "), style = PinStyle, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = controller::regeneratePin) { Text("Новый код") }
                }
            }
        }
        if (addresses.isNotEmpty()) {
            Muted(
                "Адрес компьютера для ручного ввода: " + addresses.joinToString(", ") { it.address },
            )
        } else {
            Text("Компьютер не подключён к сети", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun Step(number: String, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(number, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.titleSmall)
    }
}

// ---------------------------------------------------------------- devices

@Composable
private fun DevicesSection(settings: Settings, live: Map<String, LiveStatus>, now: Long, controller: AppController) {
    Text("Телефоны", style = MaterialTheme.typography.titleLarge)
    if (settings.devices.isEmpty()) {
        SectionCard(Modifier.fillMaxWidth()) {
            Text("Пока ни один телефон не подключён", style = MaterialTheme.typography.titleSmall)
            Muted(
                "Установите приложение на телефон и введите код из блока «Подключить телефон». " +
                    "Если телефон не находит компьютер, нажмите «Разрешить в брандмауэре» ниже.",
            )
        }
    } else {
        settings.devices.sortedByDescending { maxOf(it.lastSeen, live[it.id]?.receivedAt ?: 0L) }.forEach { device ->
            DeviceCard(device, live[device.id], now, controller)
        }
    }
}

@Composable
private fun DeviceCard(device: PairedDevice, live: LiveStatus?, now: Long, controller: AppController) {
    var confirmRemove by remember { mutableStateOf(false) }
    val progress: SyncProgress? = live?.progress ?: device.lastProgress
    val lastSeen = maxOf(device.lastSeen, live?.receivedAt ?: 0L)
    val syncing = live != null && live.progress.phase.isActive && now - live.receivedAt < 30_000
    val online = now - lastSeen < 17 * 60_000L

    SectionCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium)
                Muted(statusLine(progress, syncing, lastSeen, now))
            }
            when {
                syncing -> StatusPill("Синхронизация", Palette.Indigo, Palette.IndigoSoft)
                progress?.phase == SyncPhase.ERROR && online -> StatusPill("Ошибка", Palette.Red, Palette.RedSoft)
                online && progress != null && progress.remainingFiles == 0 -> StatusPill("Всё синхронизировано", Palette.Green, Palette.GreenSoft)
                online -> StatusPill("На связи", Palette.Green, Palette.GreenSoft)
                else -> StatusPill("Не в сети", Palette.Grey, Palette.GreySoft)
            }
        }

        if (progress == null || progress.totalFiles == 0 && progress.phase != SyncPhase.DONE) {
            Muted("Телефон ещё не синхронизировался. Синхронизация начнётся автоматически в течение нескольких минут.")
        } else {
            Spacer(Modifier.height(2.dp))
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
                color = if (progress.remainingFiles == 0) Palette.Green else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Stat(
                    Modifier.weight(1f),
                    "Синхронизировано",
                    "${Format.number(progress.syncedFiles.toLong())} из ${Format.filesGenitive(progress.totalFiles)}",
                    "${Format.bytes(progress.syncedBytes)} из ${Format.bytes(progress.totalBytes)} · ${(progress.fraction * 100).toInt()}%",
                )
                Stat(
                    Modifier.weight(1f),
                    "Осталось",
                    if (progress.remainingFiles == 0) "Ничего" else Format.files(progress.remainingFiles),
                    if (progress.remainingFiles == 0) "Телефон в актуальном состоянии" else Format.bytes(progress.remainingBytes) +
                        (if (syncing) Format.eta(progress.remainingBytes, progress.bytesPerSecond)?.let { " · $it" } ?: "" else ""),
                )
                Stat(
                    Modifier.weight(1f),
                    if (syncing) "Скорость" else "Свободно на телефоне",
                    if (syncing) Format.speed(progress.bytesPerSecond) else if (progress.freeSpaceBytes >= 0) Format.bytes(progress.freeSpaceBytes) else "—",
                    if (syncing && progress.freeSpaceBytes >= 0) "Свободно: ${Format.bytes(progress.freeSpaceBytes)}" else "",
                )
            }
            if (syncing && progress.currentFile != null) {
                Muted("Сейчас: ${progress.currentFile}", maxLines = 1)
            }
            val message = progress.message
            if (message != null && (progress.phase == SyncPhase.ERROR || progress.phase == SyncPhase.STOPPED)) {
                Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }

        HorizontalDivider(Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Muted("Подключён ${Format.moment(device.pairedAt, now)}")
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { confirmRemove = true }) { Text("Отключить телефон", color = MaterialTheme.colorScheme.error) }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Отключить «${device.name}»?") },
            text = { Text("Телефон перестанет синхронизироваться с этим компьютером. Музыка на телефоне останется. Подключить снова можно по коду.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    controller.removeDevice(device.id)
                }) { Text("Отключить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Отмена") } },
        )
    }
}

private fun statusLine(progress: SyncProgress?, syncing: Boolean, lastSeen: Long, now: Long): String {
    if (syncing && progress != null) {
        return when (progress.phase) {
            SyncPhase.CONNECTING -> "Подключается…"
            SyncPhase.COMPARING -> "Сравнивает файлы…"
            SyncPhase.DOWNLOADING -> "Загружает музыку · загружено за сеанс: ${Format.files(progress.downloadedThisRun)}"
            SyncPhase.DELETING -> "Удаляет треки, удалённые на компьютере…"
            else -> "Синхронизация…"
        }
    }
    val seen = "последняя связь ${Format.moment(lastSeen, now)}"
    return when (progress?.phase) {
        SyncPhase.ERROR -> "Ошибка синхронизации · $seen"
        SyncPhase.STOPPED -> "Синхронизация прервана, продолжится автоматически · $seen"
        else -> seen.replaceFirstChar { it.uppercase() }
    }
}

@Composable
private fun Stat(modifier: Modifier, label: String, value: String, sub: String) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge)
        if (sub.isNotEmpty()) Muted(sub, maxLines = 1)
    }
}

// ---------------------------------------------------------------- settings

@Composable
private fun SettingsCard(settings: Settings, controller: AppController) {
    var firewallStatus by remember { mutableStateOf<String?>(null) }
    SectionCard(Modifier.fillMaxWidth()) {
        CardTitle("Настройки")
        CheckRow(
            checked = settings.autostart,
            enabled = WindowsIntegration.canIntegrate,
            text = "Запускать вместе с Windows (в трее, без окна)",
            onChange = controller::setAutostart,
        )
        CheckRow(
            checked = settings.closeToTray,
            enabled = true,
            text = "При закрытии окна продолжать работу в трее",
            onChange = controller::setCloseToTray,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                enabled = WindowsIntegration.isWindows,
                onClick = {
                    firewallStatus = "Подтвердите запрос Windows…"
                    controller.openFirewall { ok ->
                        firewallStatus = if (ok) "Готово: доступ в брандмауэре открыт" else "Не удалось — запрос отклонён"
                    }
                },
            ) { Text("Разрешить в брандмауэре") }
            OutlinedButton(onClick = { openLog() }) { Text("Журнал") }
            firewallStatus?.let { Muted(it) }
        }
        Muted("Кнопка «Разрешить в брандмауэре» нужна, если телефон не находит компьютер. Windows попросит права администратора.")
    }
}

@Composable
private fun CheckRow(checked: Boolean, enabled: Boolean, text: String, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun openLog() {
    try {
        val file = File(AppPaths.dataDir, "syncmusic.log")
        if (!file.exists()) file.writeText("")
        Desktop.getDesktop().open(file)
    } catch (t: Throwable) {
        AppLog.w("Cannot open log", t)
    }
}
