package biz.fesh.syncmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import biz.fesh.syncmusic.shared.Format
import biz.fesh.syncmusic.sync.Storage
import java.io.File

/** Full-screen folder browser over the phone memory and SD cards (the app has "all files" access). */
@Composable
fun FolderPickerDialog(title: String, initialPath: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val volumes = remember { Storage.volumes(context) }
    var current by remember {
        mutableStateOf(initialPath?.let(::File)?.takeIf { it.isDirectory } ?: volumes.first().root)
    }
    var refresh by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    val volume = Storage.volumeOf(volumes, current.path) ?: volumes.first()
    val children = remember(current, refresh) {
        current.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.sortedBy { it.name.lowercase() }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    volumes.forEach { v ->
                        val selected = v.id == volume.id
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { current = v.root }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text(
                                v.label,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
                if (volume.freeBytes >= 0) {
                    Text(
                        "Вільно ${Format.bytes(volume.freeBytes)} з ${Format.bytes(volume.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    Storage.describe(volumes, current.path) ?: current.path,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (volume.appPrivate) {
                    Text(
                        "На цій версії Android на SD-карту можна писати лише в папку застосунку. Вона видаляється разом із застосунком.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Amber,
                    )
                }
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    if (current.path != volume.root.path) {
                        item {
                            FolderLine("‹  На рівень вище") { current.parentFile?.let { current = it } }
                        }
                    }
                    when {
                        children == null -> item { Hint("Немає доступу до цієї папки") }
                        children.isEmpty() -> item { Hint("Тут немає вкладених папок") }
                        else -> items(children, key = { it.path }) { dir -> FolderLine(dir.name) { current = dir } }
                    }
                }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("Скасувати") }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { creating = true }) { Text("Нова папка") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onPick(current.path) }) { Text("Вибрати цю папку") }
                }
            }
        }
    }

    if (creating) {
        var name by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("Нова папка") },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, placeholder = { Text("Назва") })
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clean = name.trim()
                    if (clean.isEmpty() || clean.contains('/') || clean.startsWith(".")) {
                        error = "Недопустима назва"
                    } else {
                        val dir = File(current, clean)
                        if (dir.isDirectory || dir.mkdirs()) {
                            creating = false
                            refresh++
                            current = dir
                        } else {
                            error = "Не вдалося створити папку"
                        }
                    }
                }) { Text("Створити") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Скасувати") } },
        )
    }
}

@Composable
private fun FolderLine(name: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 22.dp, height = 16.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.primaryContainer))
        Spacer(Modifier.width(14.dp))
        Text(name, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
