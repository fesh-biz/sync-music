package biz.fesh.syncmusic.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object Palette {
    val Indigo = Color(0xFF4F46E5)
    val IndigoSoft = Color(0xFFE8E7FD)
    val Sky = Color(0xFF0EA5E9)
    val Green = Color(0xFF16A34A)
    val GreenSoft = Color(0xFFDCF5E3)
    val Amber = Color(0xFFD97706)
    val AmberSoft = Color(0xFFFDF0DC)
    val Red = Color(0xFFDC2626)
    val RedSoft = Color(0xFFFDE4E4)
    val Grey = Color(0xFF6B6A7B)
    val GreySoft = Color(0xFFEDEDF2)
}

private val colors = lightColorScheme(
    primary = Palette.Indigo,
    onPrimary = Color.White,
    primaryContainer = Palette.IndigoSoft,
    onPrimaryContainer = Color(0xFF221C7A),
    secondary = Palette.Sky,
    background = Color(0xFFF4F5FB),
    onBackground = Color(0xFF1C1B29),
    surface = Color.White,
    onSurface = Color(0xFF1C1B29),
    surfaceVariant = Color(0xFFF0F0F7),
    onSurfaceVariant = Palette.Grey,
    outline = Color(0xFFD6D5E3),
    outlineVariant = Color(0xFFE7E6F0),
    error = Palette.Red,
)

@Composable
fun SyncMusicTheme(content: @Composable () -> Unit) {
    val base = Typography()
    val typography = base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}

val PinStyle = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp)

@Composable
fun QrCode(text: String, size: Dp, modifier: Modifier = Modifier) {
    val matrix: BitMatrix? = remember(text) {
        try {
            QRCodeWriter().encode(
                text,
                BarcodeFormat.QR_CODE,
                0,
                0,
                mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
            )
        } catch (_: Throwable) {
            null
        }
    }
    Box(modifier.size(size)) {
        if (matrix != null) {
            Canvas(Modifier.size(size)) {
                val n = matrix.width
                val cell = this.size.minDimension / n
                drawRect(Color.White)
                for (y in 0 until n) {
                    for (x in 0 until n) {
                        if (matrix.get(x, y)) {
                            drawRect(
                                color = Color(0xFF111122),
                                topLeft = Offset(x * cell, y * cell),
                                size = Size(cell + 0.5f, cell + 0.5f),
                            )
                        }
                    }
                }
            }
        }
    }
}
