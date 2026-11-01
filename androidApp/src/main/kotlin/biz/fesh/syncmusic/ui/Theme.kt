package biz.fesh.syncmusic.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Same palette as the PC app's bright look: twilight violet into rose, lagoon teal as the second accent.
val Indigo = Color(0xFF7A3FF2)
val Green = Color(0xFF12A15A)
val Amber = Color(0xFFD97706)
val Twilight = Color(0xFF2B1868)
val Rose = Color(0xFFE8457C)
val Teal = Color(0xFF0E9F9A)

private val Light = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEEE6FF),
    onPrimaryContainer = Twilight,
    secondary = Rose,
    tertiary = Teal,
    background = Color(0xFFF6F2FC),
    surface = Color.White,
    surfaceVariant = Color(0xFFF2EDFA),
    onSurfaceVariant = Color(0xFF675D7E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFC4A8FF),
    onPrimary = Color(0xFF2B1868),
    primaryContainer = Color(0xFF4A2BA8),
    secondary = Color(0xFFFF8FB3),
    tertiary = Color(0xFF5EDBD3),
)

@Composable
fun SyncTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) Dark else Light
    MaterialTheme(colorScheme = scheme, content = content)
}
