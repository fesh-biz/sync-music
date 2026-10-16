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
                "Синхронізація музики з телефоном через Wi-Fi",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (serverState) {
            is ServerState.Running -> {
                val address = addresses.firstOrNull()?.address
                StatusPill(
                    text = if (address != null) "Працює · $address:$port" else "Працює · немає мережі",
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
        CardTitle("Музика на комп'ютері")
        Muted(settings.musicFolder ?: "Папку не вибрано", maxLines = 2)
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
                Muted("Сканування папки…")
            }
        } else if (stats.scannedAt > 0) {
            Muted("Перевірено ${Format.moment(stats.scannedAt)}")
        }
        stats.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                chooseFolder(settings.musicFolder)?.let(controller::setMusicFolder)
            }) { Text("Вибрати папку") }
            OutlinedButton(onClick = controller::rescan) { Text("Оновити") }
        }
        Muted("Синхронізуються аудіофайли, плейлисти й тексти пісень (.lrc) зі збереженням структури папок.")
    }
}

private fun chooseFolder(initial: String?): String? {
    val chooser = JFileChooser(initial ?: System.getProperty("user.home")).apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Виберіть папку з музикою"
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
        CardTitle("Підключити телефон")
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            val address = addresses.firstOrNull()?.address
            if (address != null && ApkResource.available) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    QrCode("http://$address:$port/", 150.dp, Modifier.clip(RoundedCornerShape(8.dp)))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Step("1", "Встановіть застосунок")
                if (address != null && ApkResource.available) {
                    Muted("Наведіть камеру телефона на QR-код і завантажте застосунок. Телефон має бути в тій самій Wi-Fi мережі.")
                } else {
                    Muted("Завантажте SyncMusic.apk зі сторінки релізів і встановіть на телефон.")
                }
                Spacer(Modifier.height(6.dp))
                Step("2", "Введіть код у застосунку")
                Muted("Натисніть «Знайти комп'ютер», виберіть «${controller.computerName}» і введіть код:")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(pin.chunked(3).joinToString(" "), style = PinStyle, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = controller::regeneratePin) { Text("Новий код") }
                }
            }
        }
        if (addresses.isNotEmpty()) {
            Muted(
                "Адреса комп'ютера для ручного введення: " + addresses.joinToString(", ") { it.address },
            )
        } else {
            Text("Комп'ютер не підключений до мережі", color = MaterialTheme.colorScheme.error)
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
    Text("Телефони", style = MaterialTheme.typography.titleLarge)
    if (settings.devices.isEmpty()) {
        SectionCard(Modifier.fillMaxWidth()) {
            Text("Поки що жоден телефон не підключено", style = MaterialTheme.typography.titleSmall)
            Muted(
                "Встановіть застосунок на телефон і введіть код із блоку «Підключити телефон». " +
                    "Якщо телефон не знаходить комп'ютер, натисніть «Дозволити в брандмауері» нижче.",
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
    val freeLabel = progress?.storage?.let { "Вільно ($it)" } ?: "Вільно на телефоні"

    SectionCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium)
                Muted(statusLine(progress, syncing, lastSeen, now))
            }
            when {
                syncing -> StatusPill("Синхронізація", Palette.Indigo, Palette.IndigoSoft)
                progress?.phase == SyncPhase.ERROR && online -> StatusPill("Помилка", Palette.Red, Palette.RedSoft)
                online && progress != null && progress.remainingFiles == 0 -> StatusPill("Усе синхронізовано", Palette.Green, Palette.GreenSoft)
                online -> StatusPill("На зв'язку", Palette.Green, Palette.GreenSoft)
                else -> StatusPill("Не в мережі", Palette.Grey, Palette.GreySoft)
            }
        }

        if (progress == null || progress.totalFiles == 0 && progress.phase != SyncPhase.DONE) {
            Muted("Телефон ще не синхронізувався. Синхронізація почнеться автоматично протягом кількох хвилин.")
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
                    "Синхронізовано",
                    "${Format.number(progress.syncedFiles.toLong())} з ${Format.filesGenitive(progress.totalFiles)}",
                    "${Format.bytes(progress.syncedBytes)} з ${Format.bytes(progress.totalBytes)} · ${(progress.fraction * 100).toInt()}%",
                )
                Stat(
                    Modifier.weight(1f),
                    "Залишилось",
                    if (progress.remainingFiles == 0) "Нічого" else Format.files(progress.remainingFiles),
                    if (progress.remainingFiles == 0) "Телефон в актуальному стані" else Format.bytes(progress.remainingBytes) +
                        (if (syncing) Format.eta(progress.remainingBytes, progress.bytesPerSecond)?.let { " · $it" } ?: "" else ""),
                )
                Stat(
                    Modifier.weight(1f),
                    if (syncing) "Швидкість" else freeLabel,
                    if (syncing) Format.speed(progress.bytesPerSecond) else if (progress.freeSpaceBytes >= 0) Format.bytes(progress.freeSpaceBytes) else "—",
                    if (syncing && progress.freeSpaceBytes >= 0) "$freeLabel: ${Format.bytes(progress.freeSpaceBytes)}" else "",
                )
            }
            if (syncing && progress.currentFile != null) {
                Muted("Зараз: ${progress.currentFile}", maxLines = 1)
            }
            val message = progress.message
            if (message != null && (progress.phase == SyncPhase.ERROR || progress.phase == SyncPhase.STOPPED)) {
                Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }

        HorizontalDivider(Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Muted("Підключено ${Format.moment(device.pairedAt, now)}")
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { confirmRemove = true }) { Text("Відключити телефон", color = MaterialTheme.colorScheme.error) }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Відключити «${device.name}»?") },
            text = { Text("Телефон перестане синхронізуватися з цим комп'ютером. Музика на телефоні залишиться. Підключити знову можна за кодом.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    controller.removeDevice(device.id)
                }) { Text("Відключити", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Скасувати") } },
        )
    }
}

private fun statusLine(progress: SyncProgress?, syncing: Boolean, lastSeen: Long, now: Long): String {
    if (syncing && progress != null) {
        return when (progress.phase) {
            SyncPhase.CONNECTING -> "Підключається…"
            SyncPhase.COMPARING -> "Порівнює файли…"
            SyncPhase.DOWNLOADING -> "Завантажує музику · за сеанс: ${Format.files(progress.downloadedThisRun)}"
            SyncPhase.DELETING -> "Видаляє треки, видалені на комп'ютері…"
            else -> "Синхронізація…"
        }
    }
    val seen = "останній зв'язок ${Format.moment(lastSeen, now)}"
    return when (progress?.phase) {
        SyncPhase.ERROR -> "Помилка синхронізації · $seen"
        SyncPhase.STOPPED -> "Синхронізацію перервано, продовжиться автоматично · $seen"
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
        CardTitle("Налаштування")
        CheckRow(
            checked = settings.autostart,
            enabled = WindowsIntegration.canIntegrate,
            text = "Запускати разом із Windows (у треї, без вікна)",
            onChange = controller::setAutostart,
        )
        CheckRow(
            checked = settings.closeToTray,
            enabled = true,
            text = "Після закриття вікна працювати далі в треї",
            onChange = controller::setCloseToTray,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                enabled = WindowsIntegration.isWindows,
                onClick = {
                    firewallStatus = "Підтвердіть запит Windows…"
                    controller.openFirewall { ok ->
                        firewallStatus = if (ok) "Готово: доступ у брандмауері відкрито" else "Не вдалося — запит відхилено"
                    }
                },
            ) { Text("Дозволити в брандмауері") }
            OutlinedButton(onClick = { openLog() }) { Text("Журнал") }
            firewallStatus?.let { Muted(it) }
        }
        Muted("Кнопка «Дозволити в брандмауері» потрібна, якщо телефон не знаходить комп'ютер. Windows попросить права адміністратора.")
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
