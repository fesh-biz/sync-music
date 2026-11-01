package biz.fesh.syncmusic.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import biz.fesh.syncmusic.desktop.ui.App
import biz.fesh.syncmusic.desktop.ui.FilesKeys
import biz.fesh.syncmusic.desktop.ui.FilesScreen
import biz.fesh.syncmusic.shared.Protocol
import java.awt.Frame
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import javax.imageio.ImageIO
import java.lang.management.ManagementFactory
import biz.fesh.syncmusic.shared.Direction
import biz.fesh.syncmusic.shared.FileTypes
import biz.fesh.syncmusic.shared.SyncProgress
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.launch
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/** Keys of the file window (Total Commander style); also pressed by test commands (fm-key). */
private val filesKeys = FilesKeys()

fun main(args: Array<String>) {
    val startMinimized = args.any { it == "--minimized" }
    val headless = args.any { it == "--headless" }

    if (!SingleInstance.acquire()) {
        AppLog.i("Second launch: asking the running copy to show its window (${sinceLaunch()} ms after launch)")
        signalExistingInstance()
        exitProcess(0)
    }

    Thread.setDefaultUncaughtExceptionHandler { thread, error -> AppLog.e("Uncaught exception in ${thread.name}", error) }

    val controller = AppController()
    controller.start()
    AppLog.i("Core started ${sinceLaunch()} ms after launch")

    // Used by automated tests: state is mirrored to status.json, commands are read from command.txt.
    if (headless || args.any { it == "--status" }) {
        thread(isDaemon = true, name = "status-mirror") { mirrorStatus(controller) }
    }
    if (headless) {
        while (true) Thread.sleep(60_000)
    }

    val appIcon = loadIcon()
    val traySupported = try { java.awt.SystemTray.isSupported() } catch (_: Throwable) { false }

    application {
        var visible by remember { mutableStateOf(!startMinimized) }
        val trayState = rememberTrayState()
        val settings by controller.settings.state.collectAsState()

        fun quit() {
            controller.shutdown()
            exitApplication()
        }

        if (traySupported) {
            Tray(
                icon = appIcon,
                state = trayState,
                tooltip = "Sync Music",
                onAction = { controller.requestShowWindow() },
                menu = {
                    Item("Відкрити Sync Music", onClick = { controller.requestShowWindow() })
                    Item("Вийти", onClick = { quit() })
                },
            )
        }

        LaunchedEffect(Unit) {
            controller.events.collect { message ->
                trayState.sendNotification(Notification("Sync Music", message, Notification.Type.Info))
            }
        }
        LaunchedEffect(Unit) {
            controller.showWindow.collect {
                if (!visible) AppLog.i("Showing window on request")
                visible = true
            }
        }
        LaunchedEffect(visible) {
            if (visible) controller.onWindowShown()
        }
        LaunchedEffect(Unit) {
            controller.hideWindow.collect { visible = false }
        }

        val windowState = rememberWindowState(width = 1040.dp, height = 800.dp, position = WindowPosition(Alignment.Center))

        Window(
            onCloseRequest = {
                if (settings.closeToTray && traySupported) {
                    visible = false
                    if (!settings.trayHintShown) {
                        trayState.sendNotification(
                            Notification(
                                "Sync Music працює у фоні",
                                "Синхронізація триває. Відкрити вікно можна через значок у треї.",
                                Notification.Type.Info,
                            ),
                        )
                        controller.markTrayHintShown()
                    }
                } else {
                    quit()
                }
            },
            visible = visible,
            state = windowState,
            title = "Sync Music",
            icon = appIcon,
        ) {
            LaunchedEffect(Unit) {
                AppLog.i("Window composed ${sinceLaunch()} ms after launch")
            }
            LaunchedEffect(Unit) {
                controller.showWindow.collect {
                    windowState.isMinimized = false
                    window.extendedState = window.extendedState and Frame.ICONIFIED.inv()
                    window.toFront()
                    window.requestFocus()
                }
            }
            App(controller)
        }

        // The file window: PC and phone side by side.
        val filesOpen by controller.files.open.collectAsState()
        if (filesOpen) {
            val filesState = rememberWindowState(width = 1240.dp, height = 840.dp, position = WindowPosition(Alignment.Center))
            Window(
                onCloseRequest = controller.files::closeWindow,
                state = filesState,
                title = "Sync Music — файли телефона",
                icon = appIcon,
                onPreviewKeyEvent = filesKeys::onKeyEvent,
            ) {
                LaunchedEffect(Unit) {
                    window.toFront()
                    AppLog.i("File window shown")
                }
                FilesScreen(controller, filesKeys)
            }
        }
    }
}

/** Milliseconds since the JVM (i.e. SyncMusic.exe) was started. */
private fun sinceLaunch(): Long = try {
    System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().startTime
} catch (_: Throwable) {
    -1
}

private fun mirrorStatus(controller: AppController) {
    val file = File(AppPaths.dataDir, "status.json")
    val commands = File(AppPaths.dataDir, "command.txt")
    while (true) {
        try {
            if (commands.isFile) {
                val text = commands.readText().trim()
                commands.delete()
                AppLog.i("Test command: $text")
                val parts = text.split('|')
                fun folderByName(name: String) = controller.settings.value.folders.firstOrNull { it.name == name }
                when (parts[0]) {
                    "remove-devices" -> controller.settings.value.devices.forEach { controller.removeDevice(it.id) }
                    "rescan" -> controller.rescan()
                    // add-folder|<path>|TO_PHONE or TO_PC
                    "add-folder" -> AppLog.i("add-folder: " + (controller.addFolder(parts[1], Direction.valueOf(parts[2])) ?: "ok"))
                    // set-delete|<name>|true/false
                    "set-delete" -> folderByName(parts[1])?.let { controller.setDeleteMissing(it.id, parts[2].toBoolean()) }
                    // set-direction|<name>|TO_PHONE or TO_PC
                    "set-direction" -> folderByName(parts[1])?.let { controller.setDirection(it.id, Direction.valueOf(parts[2])) }
                    // set-file-types|<name>|ALL, MUSIC or MOVIES
                    "set-file-types" -> folderByName(parts[1])?.let { controller.setFileTypes(it.id, FileTypes.valueOf(parts[2])) }
                    "remove-folder" -> folderByName(parts[1])?.let { controller.removeFolder(it.id) }
                    // set-phone-folder|<name>|<absolute phone path>[|deleteOld] (for the first phone)
                    "set-phone-folder" -> {
                        val device = controller.settings.value.devices.firstOrNull()
                        val folder = folderByName(parts[1])
                        if (device != null && folder != null) {
                            controller.setPhoneFolder(folder.id, device.id, parts[2], parts.getOrNull(3) == "true")
                        }
                    }
                    // show-phone-picker|<name>[|new] / hide-phone-picker (screenshots of the dialog; "new" = as after adding)
                    "show-phone-picker" -> folderByName(parts[1])?.let { controller.openPhonePicker(it.id, isNew = parts.getOrNull(2) == "new") }
                    "hide-phone-picker" -> controller.closePhonePicker()
                    // show-connect / show-settings / hide-dialog (screenshots of the modal windows)
                    "show-connect" -> controller.openDialog(MainDialog.CONNECT)
                    "show-settings" -> controller.openDialog(MainDialog.SETTINGS)
                    "hide-dialog" -> controller.closeDialog()
                    // File window: fm-open / fm-close, fm-pc|<PC folder>, fm-phone|<phone folder or empty>,
                    // fm-list|<phone folder> (result in status.json), fm-to-phone|<PC file or folder>|<phone folder>,
                    // fm-to-pc|<phone file or folder>|<PC folder>|<dir: true/false>[|move], fm-theme|BRIGHT or CLASSIC,
                    // fm-to-phone|…|…|move, fm-delete-phone|<phone path>, fm-rename-phone|<phone path>|<new name>,
                    // fm-key|<combination, e.g. F5, Shift+Down, Num+> (as if pressed in the file window)
                    "fm-open" -> controller.files.openWindow()
                    "fm-close" -> controller.files.closeWindow()
                    "fm-pc" -> controller.files.pcPath.value = parts.getOrNull(1)?.ifEmpty { null }
                    "fm-phone" -> controller.files.phonePath.value = parts.getOrNull(1)?.ifEmpty { null }
                    "fm-list" -> controller.scope.launch { controller.files.testList(parts.getOrElse(1) { "" }) }
                    "fm-to-phone" -> controller.settings.value.devices.firstOrNull()?.let {
                        controller.files.copyToPhone(it.id, listOf(File(parts[1])), parts[2], move = parts.getOrNull(3) == "move")
                    }
                    "fm-to-pc" -> controller.settings.value.devices.firstOrNull()?.let {
                        val path = parts[1]
                        val name = path.trimEnd('/').substringAfterLast('/')
                        controller.files.copyToPc(
                            it.id,
                            listOf(PhoneItem(path, name, parts.getOrNull(3) == "true", 0, 0)),
                            File(parts[2]),
                            move = parts.getOrNull(4) == "move",
                        )
                    }
                    "fm-delete-phone" -> controller.settings.value.devices.firstOrNull()?.let {
                        controller.files.lastTestOp = null
                        controller.scope.launch {
                            controller.files.lastTestOp = controller.files.deletePhone(it.id, listOf(parts[1])) ?: "ok"
                        }
                    }
                    "fm-rename-phone" -> controller.settings.value.devices.firstOrNull()?.let {
                        controller.files.lastTestOp = null
                        controller.scope.launch {
                            val reply = controller.files.request(it.id, biz.fesh.syncmusic.shared.FmOp.RENAME, parts[1], name = parts[2])
                            controller.files.lastTestOp = if (reply.ok) "ok" else reply.error ?: "error"
                        }
                    }
                    "fm-key" -> {
                        val combo = text.substringAfter('|')
                        javax.swing.SwingUtilities.invokeLater { AppLog.i("fm-key $combo: " + filesKeys.press(combo)) }
                    }
                    "fm-theme" -> controller.setTheme(UiTheme.valueOf(parts[1]))
                    "show" -> controller.requestShowWindow()
                    "hide" -> controller.requestHideWindow()
                    "quit" -> {
                        controller.shutdown()
                        exitProcess(0)
                    }
                }
            }
            val infos = controller.folderInfos()
            val json = buildJsonObject {
                put("pin", controller.pin.value)
                put("server", controller.serverState.value.toString())
                put("libraryFiles", infos.sumOf { it.files })
                put("libraryBytes", infos.sumOf { it.bytes })
                put(
                    "folders",
                    buildJsonArray {
                        for (info in infos) {
                            val folder = controller.runtime(info.id)?.folder
                            add(
                                buildJsonObject {
                                    put("id", info.id)
                                    put("name", info.name)
                                    put("path", folder?.path)
                                    put("direction", info.direction.name)
                                    put("deleteMissing", info.deleteMissing)
                                    put("fileTypes", info.fileTypes.name)
                                    put("files", info.files)
                                    folder?.phoneTargets?.values?.firstOrNull()?.let { put("phoneTarget", it.path) }
                                },
                            )
                        }
                    },
                )
                put("fm", fmStatus(controller))
                put("addresses", controller.addresses.value?.joinToString(",") { it.address } ?: "")
                put(
                    "devices",
                    buildJsonArray {
                        for (device in controller.settings.value.devices) {
                            val live = controller.live.value[device.id]?.progress ?: device.lastProgress
                            add(
                                buildJsonObject {
                                    put("name", device.name)
                                    put("lastSeen", device.lastSeen)
                                    controller.phoneStorage.value[device.id]?.let { storage ->
                                        put("storageVolumes", storage.volumes.size)
                                        put("storageDirs", storage.volumes.sumOf { it.dirs.size })
                                    }
                                    if (live != null) put("progress", Protocol.json.encodeToJsonElement(SyncProgress.serializer(), live))
                                },
                            )
                        }
                    },
                )
            }
            val tmp = File(file.parentFile, "status.json.tmp")
            tmp.writeText(json.toString())
            tmp.renameTo(file)
        } catch (t: Throwable) {
            AppLog.w("status.json failed", t)
        }
        Thread.sleep(500)
    }
}

private fun fmStatus(controller: AppController) = buildJsonObject {
    val files = controller.files
    val jobs = files.jobs.value
    put("open", files.open.value)
    put("online", controller.settings.value.devices.firstOrNull()?.let { files.isOnline(it.id) } ?: false)
    put("jobs", jobs.size)
    for (state in JobState.entries) put(state.name.lowercase(), jobs.count { it.state == state })
    jobs.firstOrNull { it.state == JobState.FAILED }?.let { put("firstError", "${it.name}: ${it.error}") }
    put("savedAs", buildJsonArray { jobs.mapNotNull { it.savedAs }.takeLast(20).forEach { add(it) } })
    files.notice.value?.let { put("notice", it) }
    files.lastTestOp?.let { put("lastOp", it) }
    files.lastTestList?.let { reply ->
        put("listOk", reply.ok)
        reply.error?.let { put("listError", it) }
        put("listNames", buildJsonArray { (reply.entries.map { it.name } + reply.volumes.map { it.root }).forEach { add(it) } })
    }
}

private fun loadIcon(): Painter {
    val stream = AppPaths::class.java.getResourceAsStream("/icon.png")
    val image = stream?.use { ImageIO.read(it) }
        ?: java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    return BitmapPainter(image.toComposeImageBitmap())
}

/** Ask the already running copy (via its local HTTP endpoint) to show its window. */
private fun signalExistingInstance() {
    val port = try {
        val file = File(AppPaths.dataDir, "settings.json")
        if (file.isFile) Protocol.json.decodeFromString(Settings.serializer(), file.readText()).port else Protocol.HTTP_PORT
    } catch (_: Throwable) {
        Protocol.HTTP_PORT
    }
    try {
        val connection = URI("http://127.0.0.1:$port/local/show").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 2_000
        connection.readTimeout = 2_000
        connection.doOutput = true
        connection.outputStream.use { }
        connection.responseCode
        connection.disconnect()
    } catch (_: Throwable) {
    }
}
