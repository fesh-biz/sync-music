package biz.fesh.syncmusic.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Indigo = Color(0xFF4F46E5)
val Green = Color(0xFF16A34A)
val Amber = Color(0xFFD97706)

private val Light = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8E7FD),
    onPrimaryContainer = Color(0xFF221C7A),
    secondary = Color(0xFF0EA5E9),
    background = Color(0xFFF4F5FB),
    surface = Color.White,
    surfaceVariant = Color(0xFFF0F0F7),
    onSurfaceVariant = Color(0xFF6B6A7B),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA5A1FF),
    onPrimary = Color(0xFF1B1464),
    primaryContainer = Color(0xFF332B9E),
    secondary = Color(0xFF7DD3FC),
)

@Composable
fun SyncTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) Dark else Light
    MaterialTheme(colorScheme = scheme, content = content)
}
