package biz.fesh.syncmusic.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import biz.fesh.syncmusic.BuildConfig
import biz.fesh.syncmusic.data.Config
import biz.fesh.syncmusic.data.LastSync
import biz.fesh.syncmusic.data.PairedServer
import biz.fesh.syncmusic.net.Api
import biz.fesh.syncmusic.net.Discovery
import biz.fesh.syncmusic.net.FoundServer
import biz.fesh.syncmusic.net.Http
import biz.fesh.syncmusic.net.PairingException
import biz.fesh.syncmusic.net.WifiNet
import biz.fesh.syncmusic.shared.Format
import biz.fesh.syncmusic.shared.Protocol
import biz.fesh.syncmusic.shared.SyncPhase
import biz.fesh.syncmusic.shared.SyncProgress
import biz.fesh.syncmusic.sync.EngineState
import biz.fesh.syncmusic.sync.Storage
import biz.fesh.syncmusic.sync.SyncEngine
import biz.fesh.syncmusic.sync.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Perms(val storage: Boolean, val notifications: Boolean, val battery: Boolean) {
    companion object {
        fun read(context: Context): Perms {
            val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
            val power = context.getSystemService(PowerManager::class.java)
            val battery = power?.isIgnoringBatteryOptimizations(context.packageName) ?: true
            return Perms(Storage.hasAccess(context), notifications, battery)
        }
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val config = remember { Config.get(context) }
    val server by config.server.collectAsState()
    val engine by SyncEngine.state.collectAsState()
    val lastSync by config.lastSync.collectAsState()
    val autoSync by config.autoSync.collectAsState()
    val deleteRemoved by config.deleteRemoved.collectAsState()
    var perms by remember { mutableStateOf(Perms.read(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) perms = Perms.read(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        perms = Perms.read(context)
    }

    // Opening the app (or granting storage access) kicks off a sync right away.
    val current = server
    LaunchedEffect(perms.storage, current?.serverId, current?.revoked) {
        if (perms.storage && current != null && !current.revoked) SyncWorker.syncNow(context)
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { Header() }

            if (!perms.storage) {
                item {
                    NoticeCard(
                        title = "Разрешите доступ к памяти",
                        text = "Музыка сохраняется в папку Music/${Storage.FOLDER_NAME}, где её видят все плееры. " +
                            "Для этого Android требует разрешение «Доступ ко всем файлам».",
                        button = "Разрешить",
                    ) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            openStorageSettings(context)
                        } else {
                            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        }
                    }
                }
            }
            if (!perms.battery) {
                item {
                    NoticeCard(
                        title = "Разрешите работу в фоне",
                        text = "Без этого Android может не запускать синхронизацию, пока приложение закрыто.",
                        button = "Разрешить",
                    ) { openBatterySettings(context) }
                }
            }
            if (!perms.notifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                item {
                    NoticeCard(
                        title = "Уведомления",
                        text = "Чтобы видеть ход загрузки и итог синхронизации.",
                        button = "Включить",
                    ) { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                }
            }

            when {
                current == null -> item { PairingCard(config) }
                current.revoked -> item { RevokedCard(current, config) }
                else -> {
                    if (current.apkVersionCode > BuildConfig.VERSION_CODE) {
                        item { UpdateCard(current) }
                    }
                    item { StatusCard(current, engine, lastSync, perms.storage) }
                    item {
                        SettingsCard(
                            autoSync = autoSync,
                            deleteRemoved = deleteRemoved,
                            onAutoSync = { enabled ->
                                config.setAutoSync(enabled)
                                if (enabled) SyncWorker.schedulePeriodic(context) else SyncWorker.cancelPeriodic(context)
                            },
                            onDeleteRemoved = config::setDeleteRemoved,
                            onUnpair = {
                                SyncWorker.cancelAll(context)
                                config.setServer(null)
                            },
                        )
                    }
                }
            }

            item {
                Text(
                    "Sync Music ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private fun openStorageSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    try {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")),
        )
    } catch (_: Throwable) {
        try {
            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        } catch (_: Throwable) {
        }
    }
}

@SuppressLint("BatteryLife")
private fun openBatterySettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
        )
    } catch (_: Throwable) {
        try {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Throwable) {
        }
    }
}

@Composable
private fun Header() {
    Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
        Text("Sync Music", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Музыка с компьютера на телефоне — по Wi-Fi",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
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

@Composable
private fun NoticeCard(title: String, text: String, button: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Button(onClick = onClick) { Text(button) }
        }
    }
}

// ------------------------------------------------------------------ pairing

@Composable
private fun PairingCard(config: Config) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<FoundServer>>(emptyList()) }
    var selected by remember { mutableStateOf<FoundServer?>(null) }
    var pin by remember { mutableStateOf("") }
    var manualHost by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun search() {
        if (searching) return
        scope.launch {
            searching = true
            error = null
            val network = WifiNet.find(context)
            if (network == null) {
                error = "Подключитесь к той же Wi-Fi сети, что и компьютер."
            } else {
                found = Discovery.discover(context, network, 3_000)
                if (found.size == 1) selected = found.first()
            }
            searched = true
            searching = false
        }
    }

    fun checkManual() {
        val raw = manualHost.trim().removePrefix("http://").trimEnd('/')
        if (raw.isEmpty()) return
        val host = raw.substringBefore(':')
        val port = raw.substringAfter(':', "").toIntOrNull() ?: Protocol.HTTP_PORT
        scope.launch {
            busy = true
            error = null
            try {
                val network = WifiNet.find(context)
                val info = withContext(Dispatchers.IO) { Api(Http.client(network), host, port).info() }
                selected = FoundServer(host, info)
            } catch (e: Throwable) {
                error = "Не удалось подключиться к $host. Проверьте адрес и что Sync Music запущен на компьютере."
            } finally {
                busy = false
            }
        }
    }

    fun pair(target: FoundServer) {
        scope.launch {
            busy = true
            error = null
            try {
                val network = WifiNet.find(context)
                val response = withContext(Dispatchers.IO) {
                    Api(Http.client(network), target.host, target.info.port).pair(pin, config.deviceId, config.deviceName)
                }
                config.setServer(
                    PairedServer(
                        serverId = response.serverId,
                        serverName = response.serverName,
                        host = target.host,
                        port = target.info.port,
                        token = response.token,
                        apkVersionCode = target.info.apkVersionCode,
                    ),
                )
                if (config.autoSync.value) SyncWorker.schedulePeriodic(context)
                SyncWorker.syncNow(context)
            } catch (e: PairingException) {
                error = (e.message ?: "Неверный код") + ". Проверьте код на экране компьютера."
                pin = ""
            } catch (e: Throwable) {
                error = "Не удалось подключиться: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) { search() }

    SectionCard {
        val target = selected
        if (target == null) {
            Text("Подключение к компьютеру", style = MaterialTheme.typography.titleMedium)
            Muted("Запустите Sync Music на компьютере. Телефон и компьютер должны быть в одной Wi-Fi сети.")

            if (searching) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Muted("Ищем компьютер…")
                }
            }
            found.forEach { server ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable {
                            selected = server
                            pin = ""
                            error = null
                        }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Dot(Green)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(server.info.serverName, style = MaterialTheme.typography.titleSmall)
                        Muted(server.host)
                    }
                    Text("Выбрать", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                }
            }
            if (searched && !searching && found.isEmpty() && error == null) {
                Muted("Компьютер не найден. Проверьте, что Sync Music открыт на ПК, и нажмите «Искать снова». Если не помогло — в программе на ПК нажмите «Разрешить в брандмауэре».")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Button(onClick = { search() }, enabled = !searching) {
                Text(if (searched) "Искать снова" else "Найти компьютер")
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Muted("Или введите адрес, показанный в программе на компьютере:")
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = manualHost,
                    onValueChange = { manualHost = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("192.168.1.10") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { checkManual() }, enabled = !busy && manualHost.isNotBlank()) { Text("ОК") }
            }
        } else {
            Text("Компьютер «${target.info.serverName}»", style = MaterialTheme.typography.titleMedium)
            Muted("Введите 6-значный код из окна Sync Music на компьютере.")
            OutlinedTextField(
                value = pin,
                onValueChange = { value -> pin = value.filter(Char::isDigit).take(6) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("000000") },
                textStyle = TextStyle(fontSize = 28.sp, letterSpacing = 8.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    selected = null
                    error = null
                }) { Text("Назад") }
                Spacer(Modifier.weight(1f))
                if (busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                }
                Button(onClick = { pair(target) }, enabled = pin.length == 6 && !busy) { Text("Подключить") }
            }
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}

@Composable
private fun RevokedCard(server: PairedServer, config: Config) {
    SectionCard {
        Text("Компьютер «${server.serverName}» отключил этот телефон", style = MaterialTheme.typography.titleMedium)
        Muted("Музыка на телефоне осталась. Чтобы снова синхронизироваться, подключитесь по коду.")
        Button(onClick = { config.setServer(null) }) { Text("Подключиться заново") }
    }
}

@Composable
private fun UpdateCard(server: PairedServer) {
    val context = LocalContext.current
    NoticeCard(
        title = "Доступна новая версия",
        text = "На компьютере установлена более новая версия Sync Music. Обновите приложение на телефоне.",
        button = "Скачать обновление",
    ) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://${server.host}:${server.port}/app.apk")))
        } catch (_: Throwable) {
        }
    }
}

// ------------------------------------------------------------------ status

private fun LastSync.toProgress() = SyncProgress(
    phase = phase,
    totalFiles = totalFiles,
    totalBytes = totalBytes,
    syncedFiles = syncedFiles,
    syncedBytes = syncedBytes,
    downloadedThisRun = downloaded,
    deletedThisRun = deleted,
    message = message,
    timestamp = at,
)

@Composable
private fun StatusCard(server: PairedServer, engine: EngineState, lastSync: LastSync?, hasStorage: Boolean) {
    val context = LocalContext.current
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(15_000)
            value = System.currentTimeMillis()
        }
    }
    val running = engine.running
    val progress = engine.progress ?: lastSync?.toProgress()

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text("ПК", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(server.serverName, style = MaterialTheme.typography.titleMedium)
                Muted(statusText(running, progress, lastSync, now), maxLines = 2)
            }
        }

        if (progress != null && progress.totalFiles > 0) {
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = if (progress.remainingFiles == 0) Green else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Row(Modifier.fillMaxWidth()) {
                Stat(
                    Modifier.weight(1f),
                    "Синхронизировано",
                    "${Format.number(progress.syncedFiles.toLong())} из ${Format.number(progress.totalFiles.toLong())}",
                    "${Format.bytes(progress.syncedBytes)} из ${Format.bytes(progress.totalBytes)}",
                )
                Stat(
                    Modifier.weight(1f),
                    "Осталось",
                    if (progress.remainingFiles == 0) "0" else Format.files(progress.remainingFiles),
                    if (progress.remainingFiles == 0) "всё на месте" else Format.bytes(progress.remainingBytes),
                )
            }
            if (running && progress.phase == SyncPhase.DOWNLOADING) {
                val file = progress.currentFile?.substringAfterLast('/')
                if (file != null) Muted("Загружается: $file", maxLines = 1)
                val eta = Format.eta(progress.remainingBytes, progress.bytesPerSecond)
                if (progress.bytesPerSecond > 0) Muted(Format.speed(progress.bytesPerSecond) + (eta?.let { " · $it" } ?: ""))
            }
        }

        if (!running) {
            engine.note?.let { Text(it, color = Amber, style = MaterialTheme.typography.bodyMedium) }
        }

        Button(
            onClick = { SyncWorker.syncNow(context) },
            enabled = !running && hasStorage,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (running) "Идёт синхронизация…" else "Синхронизировать сейчас")
        }
        Muted("Папка на телефоне: Music/${Storage.FOLDER_NAME}")
    }
}

private fun statusText(running: Boolean, progress: SyncProgress?, lastSync: LastSync?, now: Long): String {
    if (running) {
        return when (progress?.phase) {
            null, SyncPhase.CONNECTING -> "Подключение к компьютеру…"
            SyncPhase.COMPARING -> "Сравнение файлов…"
            SyncPhase.DOWNLOADING -> "Загрузка музыки…"
            SyncPhase.DELETING -> "Удаление треков, удалённых на ПК…"
            else -> "Синхронизация…"
        }
    }
    if (lastSync == null) return "Ещё не синхронизировалось"
    val moment = Format.moment(lastSync.at, now)
    return when (lastSync.phase) {
        SyncPhase.DONE -> if (lastSync.syncedFiles >= lastSync.totalFiles) "Всё синхронизировано · $moment" else "Синхронизировано · $moment"
        SyncPhase.STOPPED -> "Прервано $moment · продолжится автоматически"
        SyncPhase.ERROR -> (lastSync.message ?: "Ошибка") + " · $moment"
        else -> moment
    }
}

@Composable
private fun Stat(modifier: Modifier, label: String, value: String, sub: String) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Muted(sub, maxLines = 1)
    }
}

// ------------------------------------------------------------------ settings

@Composable
private fun SettingsCard(
    autoSync: Boolean,
    deleteRemoved: Boolean,
    onAutoSync: (Boolean) -> Unit,
    onDeleteRemoved: (Boolean) -> Unit,
    onUnpair: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    SectionCard {
        Text("Настройки", style = MaterialTheme.typography.titleMedium)
        SwitchRow(
            title = "Автоматическая синхронизация",
            subtitle = "Каждые 15 минут, когда телефон в домашней Wi-Fi сети и компьютер включён",
            checked = autoSync,
            onChange = onAutoSync,
        )
        SwitchRow(
            title = "Удалять треки, удалённые на ПК",
            subtitle = "Удаляются только файлы, загруженные этим приложением",
            checked = deleteRemoved,
            onChange = onDeleteRemoved,
        )
        HorizontalDivider()
        TextButton(onClick = { confirm = true }) {
            Text("Отключиться от компьютера", color = MaterialTheme.colorScheme.error)
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Отключиться от компьютера?") },
            text = { Text("Синхронизация прекратится. Музыка на телефоне останется.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onUnpair()
                }) { Text("Отключиться", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Muted(subtitle)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
