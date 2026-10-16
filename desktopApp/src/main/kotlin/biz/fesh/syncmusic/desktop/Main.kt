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
import biz.fesh.syncmusic.shared.Protocol
import java.awt.Frame
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import javax.imageio.ImageIO
import javax.swing.UIManager
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val startMinimized = args.any { it == "--minimized" }

    if (!SingleInstance.acquire()) {
        signalExistingInstance()
        exitProcess(0)
    }

    Thread.setDefaultUncaughtExceptionHandler { thread, error -> AppLog.e("Uncaught exception in ${thread.name}", error) }
    try {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    } catch (_: Throwable) {
    }

    val controller = AppController()
    controller.start()
    val appIcon = loadIcon()

    application {
        var visible by remember { mutableStateOf(!startMinimized) }
        val trayState = rememberTrayState()
        val settings by controller.settings.state.collectAsState()

        fun quit() {
            controller.shutdown()
            exitApplication()
        }

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

        LaunchedEffect(Unit) {
            controller.events.collect { message ->
                trayState.sendNotification(Notification("Sync Music", message, Notification.Type.Info))
            }
        }
        LaunchedEffect(Unit) {
            controller.showWindow.collect { visible = true }
        }

        val windowState = rememberWindowState(width = 1040.dp, height = 800.dp, position = WindowPosition(Alignment.Center))

        Window(
            onCloseRequest = {
                if (settings.closeToTray) {
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
                controller.showWindow.collect {
                    windowState.isMinimized = false
                    window.extendedState = window.extendedState and Frame.ICONIFIED.inv()
                    window.toFront()
                    window.requestFocus()
                }
            }
            App(controller)
        }
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
