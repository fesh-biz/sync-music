package biz.fesh.syncmusic.ui

import biz.fesh.syncmusic.Notifications
import biz.fesh.syncmusic.shared.I18n
import biz.fesh.syncmusic.shared.Lang
import biz.fesh.syncmusic.shared.tr
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
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.lifecycle.repeatOnLifecycle
import biz.fesh.syncmusic.BuildConfig
import biz.fesh.syncmusic.data.Config
import biz.fesh.syncmusic.data.FolderMapping
import biz.fesh.syncmusic.data.LastSync
import biz.fesh.syncmusic.data.PairedServer
import biz.fesh.syncmusic.net.Api
import biz.fesh.syncmusic.net.Discovery
import biz.fesh.syncmusic.net.FoundServer
import biz.fesh.syncmusic.net.Http
import biz.fesh.syncmusic.net.PairingException
import biz.fesh.syncmusic.net.WifiNet
import biz.fesh.syncmusic.files.FileAccessService
import biz.fesh.syncmusic.shared.Direction
import biz.fesh.syncmusic.shared.FileTypes
import biz.fesh.syncmusic.shared.FolderInfo
import biz.fesh.syncmusic.shared.FolderProgress
import biz.fesh.syncmusic.shared.Format
import biz.fesh.syncmusic.shared.Protocol
import biz.fesh.syncmusic.shared.SyncPhase
import biz.fesh.syncmusic.shared.SyncProgress
import biz.fesh.syncmusic.sync.EngineState
import biz.fesh.syncmusic.sync.FolderMove
import biz.fesh.syncmusic.sync.Storage
import biz.fesh.syncmusic.sync.Volume
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

/** Runs [block] every time the screen comes back to the foreground. */
@Composable
private fun OnResume(block: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val current by rememberUpdatedState(block)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) current()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
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
    val paused by config.paused.collectAsState()
    val folders by config.folders.collectAsState()
    val mappings by config.mappings.collectAsState()
    var perms by remember { mutableStateOf(Perms.read(context)) }
    var volumes by remember { mutableStateOf(Storage.volumes(context)) }

    OnResume {
        perms = Perms.read(context)
        volumes = Storage.volumes(context)
    }
    // Notice an SD card being inserted or removed while the screen is open.
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            volumes = withContext(Dispatchers.IO) { Storage.volumes(context) }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        perms = Perms.read(context)
    }

    // Opening the app (or granting storage access) starts a sync right away.
    val current = server
    LaunchedEffect(perms.storage, current?.serverId, current?.revoked) {
        if (perms.storage && current != null && !current.revoked) SyncWorker.syncNow(context)
    }
    // While the app is on screen it checks whether the PC's file window is open; if so, file access turns on
    // (and stays on in the background until that window is closed).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(perms.storage, current?.serverId, current?.revoked) {
        if (!perms.storage || current == null || current.revoked) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                if (FileAccessService.pcWants(context)) FileAccessService.start(context)
                delay(5_000)
            }
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Header { lang ->
                    config.setLanguage(lang)
                    Notifications.createChannels(context) // channel names in the new language
                }
            }

            if (!perms.storage) {
                item {
                    NoticeCard(
                        title = tr("Дозвольте доступ до пам'яті", "Разрешите доступ к памяти"),
                        text = tr("Файли зберігаються в папках, які ви вибираєте, — у пам'яті телефона або на SD-карті. ", "Файлы хранятся в папках, которые вы выбираете, — в памяти телефона или на SD-карте. ") +
                            tr("Для цього Android вимагає дозвіл «Доступ до всіх файлів».", "Для этого Android требует разрешение «Доступ ко всем файлам»."),
                        button = tr("Дозволити", "Разрешить"),
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
                        title = tr("Дозвольте роботу у фоні", "Разрешите работу в фоне"),
                        text = tr("Без цього Android може не запускати синхронізацію, поки застосунок закритий.", "Без этого Android может не запускать синхронизацию, пока приложение закрыто."),
                        button = tr("Дозволити", "Разрешить"),
                    ) { openBatterySettings(context) }
                }
            }
            if (!perms.notifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                item {
                    NoticeCard(
                        title = tr("Сповіщення", "Уведомления"),
                        text = tr("Щоб бачити перебіг завантаження і підсумок синхронізації.", "Чтобы видеть ход загрузки и итог синхронизации."),
                        button = tr("Увімкнути", "Включить"),
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
                    item { StatusCard(current, engine, lastSync, perms.storage, paused) }
                    item {
                        FoldersCard(
                            config = config,
                            server = current,
                            folders = folders,
                            mappings = mappings,
                            volumes = volumes,
                            progress = engine.progress?.folders?.takeIf { it.isNotEmpty() } ?: lastSync?.folders.orEmpty(),
                            running = engine.running,
                        )
                    }
                    item {
                        SettingsCard(
                            autoSync = autoSync,
                            onAutoSync = { enabled ->
                                config.setAutoSync(enabled)
                                if (enabled) SyncWorker.schedulePeriodic(context) else SyncWorker.cancelPeriodic(context)
                            },
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
private fun Header(onLanguage: (Lang) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp).clip(RoundedCornerShape(24.dp))
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Twilight, Indigo, Rose)))
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Sync Music",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            // Language switch: "УКР | РУС", the current one highlighted.
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.18f)).padding(2.dp),
            ) {
                for (lang in Lang.entries) {
                    val on = lang == I18n.lang
                    Text(
                        if (lang == Lang.UK) "УКР" else "РУС",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        color = if (on) Indigo else Color.White,
                        modifier = Modifier.clip(RoundedCornerShape(50))
                            .background(if (on) Color.White else Color.Transparent)
                            .clickable(enabled = !on) { onLanguage(lang) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
        Text(
            tr("Синхронізація файлів з комп'ютером через Wi-Fi", "Синхронизация файлов с компьютером через Wi-Fi"),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.85f),
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
                error = tr("Підключіться до тієї самої Wi-Fi мережі, що й комп'ютер.", "Подключитесь к той же Wi-Fi сети, что и компьютер.")
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
                error = tr("Не вдалося підключитися до $host. Перевірте адресу і що Sync Music запущено на комп'ютері.", "Не удалось подключиться к $host. Проверьте адрес и что Sync Music запущен на компьютере.")
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
                error = (e.message ?: tr("Невірний код", "Неверный код")) + tr(". Перевірте код на екрані комп'ютера.", ". Проверьте код на экране компьютера.")
                pin = ""
            } catch (e: Throwable) {
                error = tr("Не вдалося підключитися: ${e.message}", "Не удалось подключиться: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) { search() }

    SectionCard {
        val target = selected
        if (target == null) {
            Text(tr("Підключення до комп'ютера", "Подключение к компьютеру"), style = MaterialTheme.typography.titleMedium)
            Muted(tr("Запустіть Sync Music на комп'ютері. Телефон і комп'ютер мають бути в одній Wi-Fi мережі.", "Запустите Sync Music на компьютере. Телефон и компьютер должны быть в одной Wi-Fi сети."))

            if (searching) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Muted(tr("Шукаємо комп'ютер…", "Ищем компьютер…"))
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
                    Text(tr("Вибрати", "Выбрать"), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                }
            }
            if (searched && !searching && found.isEmpty() && error == null) {
                Muted(
                    tr("Комп'ютер не знайдено. Перевірте, що Sync Music відкрито на ПК, і натисніть «Шукати знову». ", "Компьютер не найден. Проверьте, что Sync Music открыт на ПК, и нажмите «Искать снова». ") +
                        tr("Якщо не допомогло — у програмі на ПК натисніть «Дозволити в брандмауері».", "Если не помогло — в программе на ПК нажмите «Разрешить в брандмауэре»."),
                )
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Button(onClick = { search() }, enabled = !searching) {
                Text(if (searched) tr("Шукати знову", "Искать снова") else tr("Знайти комп'ютер", "Найти компьютер"))
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Muted(tr("Або введіть адресу, показану в програмі на комп'ютері:", "Или введите адрес, показанный в программе на компьютере:"))
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
                OutlinedButton(onClick = { checkManual() }, enabled = !busy && manualHost.isNotBlank()) { Text(tr("ОК", "ОК")) }
            }
        } else {
            Text(tr("Комп'ютер «${target.info.serverName}»", "Компьютер «${target.info.serverName}»"), style = MaterialTheme.typography.titleMedium)
            Muted(tr("Введіть 6-значний код із вікна Sync Music на комп'ютері.", "Введите 6-значный код из окна Sync Music на компьютере."))
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
                }) { Text(tr("Назад", "Назад")) }
                Spacer(Modifier.weight(1f))
                if (busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                }
                Button(onClick = { pair(target) }, enabled = pin.length == 6 && !busy) { Text(tr("Підключити", "Подключить")) }
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
        Text(tr("Комп'ютер «${server.serverName}» відключив цей телефон", "Компьютер «${server.serverName}» отключил этот телефон"), style = MaterialTheme.typography.titleMedium)
        Muted(tr("Файли на телефоні залишилися. Щоб знову синхронізуватися, підключіться за кодом.", "Файлы на телефоне остались. Чтобы снова синхронизироваться, подключитесь по коду."))
        Button(onClick = { config.setServer(null) }) { Text(tr("Підключитися знову", "Подключиться снова")) }
    }
}

@Composable
private fun UpdateCard(server: PairedServer) {
    val context = LocalContext.current
    NoticeCard(
        title = tr("Доступна нова версія", "Доступна новая версия"),
        text = tr("На комп'ютері встановлено новішу версію Sync Music. Оновіть застосунок на телефоні.", "На компьютере установлена более новая версия Sync Music. Обновите приложение на телефоне."),
        button = tr("Завантажити оновлення", "Скачать обновление"),
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
private fun StatusCard(
    server: PairedServer,
    engine: EngineState,
    lastSync: LastSync?,
    hasStorage: Boolean,
    paused: Boolean,
) {
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
                Text(tr("ПК", "ПК"), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(server.serverName, style = MaterialTheme.typography.titleMedium)
                Muted(statusText(running, paused, progress, lastSync, now), maxLines = 2)
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
                    tr("Синхронізовано", "Синхронизировано"),
                    tr("${Format.number(progress.syncedFiles.toLong())} з ${Format.number(progress.totalFiles.toLong())}", "${Format.number(progress.syncedFiles.toLong())} из ${Format.number(progress.totalFiles.toLong())}"),
                    tr("${Format.bytes(progress.syncedBytes)} з ${Format.bytes(progress.totalBytes)}", "${Format.bytes(progress.syncedBytes)} из ${Format.bytes(progress.totalBytes)}"),
                )
                Stat(
                    Modifier.weight(1f),
                    tr("Залишилось", "Осталось"),
                    if (progress.remainingFiles == 0) "0" else Format.files(progress.remainingFiles),
                    if (progress.remainingFiles == 0) tr("усе на місці", "всё на месте") else Format.bytes(progress.remainingBytes),
                )
            }
            if (running && !paused && (progress.phase == SyncPhase.DOWNLOADING || progress.phase == SyncPhase.UPLOADING)) {
                val file = progress.currentFile?.substringAfterLast('/')
                if (file != null) Muted((if (progress.phase == SyncPhase.UPLOADING) tr("Надсилається: ", "Отправка: ") else tr("Отримується: ", "Получение: ")) + file, maxLines = 1)
                val eta = Format.eta(progress.remainingBytes, progress.bytesPerSecond)
                if (progress.bytesPerSecond > 0) Muted(Format.speed(progress.bytesPerSecond) + (eta?.let { " · $it" } ?: ""))
            }
        }

        if (!running && !paused) {
            engine.note?.let { Text(it, color = Amber, style = MaterialTheme.typography.bodyMedium) }
        }
        if (paused) {
            Muted(tr("Поки синхронізацію призупинено, вона не запускається ні автоматично, ні під час відкриття застосунку. ", "Пока синхронизация приостановлена, она не запускается ни автоматически, ни при открытии приложения. ") +
                tr("Недокачаний файл продовжить завантажуватися з того самого місця.", "Недокачанный файл продолжит загружаться с того же места."))
        }

        when {
            paused -> Button(
                onClick = { SyncEngine.resume(context) },
                enabled = hasStorage,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(tr("Продовжити синхронізацію", "Продолжить синхронизацию")) }
            running -> OutlinedButton(
                onClick = { SyncEngine.pause(context) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(tr("Пауза", "Пауза")) }
            else -> Button(
                onClick = { SyncWorker.syncNow(context) },
                enabled = hasStorage,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(tr("Синхронізувати зараз", "Синхронизировать сейчас")) }
        }
    }
}

private fun statusText(running: Boolean, paused: Boolean, progress: SyncProgress?, lastSync: LastSync?, now: Long): String {
    if (paused) return if (running) tr("Призупинення…", "Приостановка…") else tr("Синхронізацію призупинено", "Синхронизация приостановлена")
    if (running) {
        return when (progress?.phase) {
            null, SyncPhase.CONNECTING -> tr("Підключення до комп'ютера…", "Подключение к компьютеру…")
            SyncPhase.COMPARING -> tr("Порівняння файлів…", "Сравнение файлов…")
            SyncPhase.DOWNLOADING -> tr("Отримання файлів з комп'ютера…", "Получение файлов с компьютера…")
            SyncPhase.UPLOADING -> tr("Надсилання файлів на комп'ютер…", "Отправка файлов на компьютер…")
            SyncPhase.DELETING -> tr("Видалення файлів, видалених у джерелі…", "Удаление файлов, удалённых в источнике…")
            else -> tr("Синхронізація…", "Синхронизация…")
        }
    }
    if (lastSync == null) return tr("Ще не синхронізувалося", "Ещё не синхронизировалось")
    val moment = Format.moment(lastSync.at, now)
    return when (lastSync.phase) {
        SyncPhase.DONE -> if (lastSync.syncedFiles >= lastSync.totalFiles) tr("Усе синхронізовано · $moment", "Всё синхронизировано · $moment") else tr("Синхронізовано · $moment", "Синхронизировано · $moment")
        SyncPhase.STOPPED -> tr("Перервано $moment · продовжиться автоматично", "Прервано $moment · продолжится автоматически")
        SyncPhase.ERROR -> (lastSync.message ?: tr("Є проблеми з деякими папками", "Есть проблемы с некоторыми папками")) + " · $moment"
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

// ------------------------------------------------------------------ folders

@Composable
private fun FoldersCard(
    config: Config,
    server: PairedServer,
    folders: List<FolderInfo>,
    mappings: Map<String, FolderMapping>,
    volumes: List<Volume>,
    progress: List<FolderProgress>,
    running: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf<FolderInfo?>(null) }
    var pendingChange by remember { mutableStateOf<Pair<FolderInfo, String>?>(null) }
    var busy by remember { mutableStateOf(false) }

    SectionCard {
        Text(tr("Папки", "Папки"), style = MaterialTheme.typography.titleMedium)
        if (folders.isEmpty()) {
            Muted(tr("Список папок з'явиться після з'єднання з комп'ютером. Папки додаються в програмі на ПК; там само можна вибрати й папку на телефоні.", "Список папок появится после соединения с компьютером. Папки добавляются в программе на ПК; там же можно выбрать и папку на телефоне."))
        }
        folders.forEachIndexed { index, folder ->
            if (index > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
            val mapping = mappings[folder.id] ?: FolderMapping()
            FolderRow(
                folder = folder,
                mapping = mapping,
                state = progress.firstOrNull { it.id == folder.id },
                volumes = volumes,
                locked = running || busy,
                onPick = { picking = folder },
                onToggle = { enabled ->
                    config.setMapping(folder.id, mapping.copy(enabled = enabled))
                    if (enabled) SyncWorker.syncNow(context)
                },
            )
        }
        if (running) Muted(tr("Змінити папку можна після завершення синхронізації.", "Изменить папку можно после завершения синхронизации."))
        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Muted(tr("Переносимо папку…", "Переносим папку…"))
            }
        }
    }

    picking?.let { folder ->
        val mapping = mappings[folder.id] ?: FolderMapping()
        FolderPickerDialog(
            title = if (folder.direction == Direction.TO_PHONE) tr("Куди зберігати «${folder.name}»", "Куда сохранять «${folder.name}»") else tr("Яку папку надсилати в «${folder.name}»", "Какую папку отправлять в «${folder.name}»"),
            initialPath = mapping.localPath,
            onPick = { path ->
                picking = null
                if (path != mapping.localPath) {
                    if (folder.direction == Direction.TO_PHONE && mapping.localPath != null) {
                        pendingChange = folder to path
                    } else {
                        config.setMapping(folder.id, mapping.copy(localPath = path))
                        SyncWorker.syncNow(context)
                    }
                }
            },
            onDismiss = { picking = null },
        )
    }

    pendingChange?.let { (folder, path) ->
        val mapping = mappings[folder.id] ?: FolderMapping()
        val oldPath = mapping.localPath
        val movable = remember(oldPath, path) { FolderMove.canMove(Storage.volumes(context), oldPath, path) }
        fun apply(deleteOld: Boolean) {
            pendingChange = null
            busy = true
            scope.launch {
                withContext(Dispatchers.IO) {
                    FolderMove.change(context, config, server.serverId, folder, mapping, path, deleteOld)
                }
                busy = false
                SyncWorker.syncNow(context)
            }
        }
        AlertDialog(
            onDismissRequest = { pendingChange = null },
            title = { Text(tr("Перенести «${folder.name}»?", "Перенести «${folder.name}»?")) },
            text = {
                Text(
                    if (movable) {
                        tr("Файли буде перенесено з «${Storage.describe(volumes, oldPath)}» у «${Storage.describe(volumes, path)}» ", "Файлы будут перенесены из «${Storage.describe(volumes, oldPath)}» в «${Storage.describe(volumes, path)}» ") +
                            tr("без повторного завантаження.", "без повторной загрузки.")
                    } else {
                        tr("Файли буде заново отримано з комп'ютера в «${Storage.describe(volumes, path)}». ", "Файлы будут заново получены с компьютера в «${Storage.describe(volumes, path)}». ") +
                            tr("Що зробити з файлами в старій папці «${Storage.describe(volumes, oldPath)}»?", "Что сделать с файлами в старой папке «${Storage.describe(volumes, oldPath)}»?")
                    },
                )
            },
            confirmButton = {
                if (movable) {
                    TextButton(onClick = { apply(false) }) { Text(tr("Перенести", "Перенести")) }
                } else {
                    Row {
                        TextButton(onClick = { apply(false) }) { Text(tr("Залишити", "Оставить")) }
                        TextButton(onClick = { apply(true) }) { Text(tr("Видалити", "Удалить"), color = MaterialTheme.colorScheme.error) }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { pendingChange = null }) { Text(tr("Скасувати", "Отмена")) } },
        )
    }
}

@Composable
private fun FolderRow(
    folder: FolderInfo,
    mapping: FolderMapping,
    state: FolderProgress?,
    volumes: List<Volume>,
    locked: Boolean,
    onPick: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val toPhone = folder.direction == Direction.TO_PHONE
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(folder.name, style = MaterialTheme.typography.titleSmall)
                val types = when (folder.fileTypes) {
                    FileTypes.ALL -> ""
                    FileTypes.MUSIC -> tr(" · лише музика", " · только музыка")
                    FileTypes.MOVIES -> tr(" · лише фільми", " · только фильмы")
                }
                Text(
                    (if (toPhone) tr("З комп'ютера на телефон", "С компьютера на телефон") else tr("З телефона на комп'ютер", "С телефона на компьютер")) + types,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Switch(checked = mapping.enabled, onCheckedChange = onToggle)
        }
        if (!mapping.enabled) {
            Muted(tr("Не синхронізується", "Не синхронизируется"))
            return@Column
        }
        val message = state?.message
        val status = when {
            mapping.localPath == null -> null
            message != null -> message
            state == null -> null
            state.totalFiles == 0 -> tr("Порожня папка", "Пустая папка")
            state.remainingFiles == 0 -> tr("Усе синхронізовано · ${Format.files(state.totalFiles)}", "Всё синхронизировано · ${Format.files(state.totalFiles)}")
            else -> tr("${Format.number(state.syncedFiles.toLong())} з ${Format.filesGenitive(state.totalFiles)} · ", "${Format.number(state.syncedFiles.toLong())} из ${Format.filesGenitive(state.totalFiles)} · ") +
                tr("залишилось ${Format.bytes(state.remainingBytes)}", "осталось ${Format.bytes(state.remainingBytes)}")
        }
        status?.let { Muted(it, maxLines = 2) }
        val path = mapping.localPath
        if (path == null) {
            Text(
                if (toPhone) tr("Виберіть папку на телефоні", "Выберите папку на телефоне") else tr("Виберіть папку телефона, яку надсилати на комп'ютер", "Выберите папку телефона, которую отправлять на компьютер"),
                color = Amber,
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onPick, enabled = !locked) { Text(tr("Вибрати папку", "Выбрать папку")) }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Muted(tr("На телефоні: ${Storage.describe(volumes, path)}", "На телефоне: ${Storage.describe(volumes, path)}"), maxLines = 2) }
                TextButton(onClick = onPick, enabled = !locked) { Text(tr("Змінити", "Изменить")) }
            }
        }
        Muted(
            when {
                toPhone && folder.deleteMissing -> tr("Файли, видалені на ПК, видаляються й з телефона", "Файлы, удалённые на ПК, удаляются и с телефона")
                toPhone -> tr("Файли, видалені на ПК, залишаються на телефоні", "Файлы, удалённые на ПК, остаются на телефоне")
                else -> tr("З комп'ютера нічого не видаляється: файли, видалені на телефоні, залишаються на ПК", "С компьютера ничего не удаляется: файлы, удалённые на телефоне, остаются на ПК")
            },
        )
        if (!folder.available) {
            Text(tr("Папка на комп'ютері недоступна", "Папка на компьютере недоступна"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ------------------------------------------------------------------ settings

@Composable
private fun SettingsCard(
    autoSync: Boolean,
    onAutoSync: (Boolean) -> Unit,
    onUnpair: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    SectionCard {
        Text(tr("Налаштування", "Настройки"), style = MaterialTheme.typography.titleMedium)
        SwitchRow(
            title = tr("Автоматична синхронізація", "Автоматическая синхронизация"),
            subtitle = tr("Кожні 15 хвилин і щоразу, коли ви відкриваєте застосунок, — якщо телефон у домашній Wi-Fi мережі, а комп'ютер увімкнено", "Каждые 15 минут и каждый раз, когда вы открываете приложение, — если телефон в домашней Wi-Fi сети, а компьютер включён"),
            checked = autoSync,
            onChange = onAutoSync,
        )
        HorizontalDivider()
        TextButton(onClick = { confirm = true }) {
            Text(tr("Відключитися від комп'ютера", "Отключиться от компьютера"), color = MaterialTheme.colorScheme.error)
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(tr("Відключитися від комп'ютера?", "Отключиться от компьютера?")) },
            text = { Text(tr("Синхронізація припиниться. Файли на телефоні й на комп'ютері залишаться.", "Синхронизация прекратится. Файлы на телефоне и на компьютере останутся.")) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onUnpair()
                }) { Text(tr("Відключитися", "Отключиться"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(tr("Скасувати", "Отмена")) } },
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
