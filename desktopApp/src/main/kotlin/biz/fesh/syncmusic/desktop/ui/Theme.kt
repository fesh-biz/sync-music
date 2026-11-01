package biz.fesh.syncmusic.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import biz.fesh.syncmusic.desktop.UiTheme
import biz.fesh.syncmusic.shared.Direction
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

    // "Терези": Venus at dusk — deep twilight violet into rose, with a lagoon teal as the other pan.
    val Twilight = Color(0xFF2B1868)
    val Violet = Color(0xFF7A3FF2)
    val VioletSoft = Color(0xFFEEE6FF)
    val Rose = Color(0xFFE8457C)
    val RoseSoft = Color(0xFFFDE4EE)
    val Teal = Color(0xFF0E9F9A)
    val TealSoft = Color(0xFFD9F5F2)
    val Mist = Color(0xFFF6F2FC)
}

/** Colors that differ between the bright and the classic look beyond the Material scheme. */
@Immutable
data class Look(
    val bright: Boolean,
    /** PC → phone: the toggle, the folder's edge stripe and "Копіювати на телефон". */
    val toPhone: Color,
    val toPhoneSoft: Color,
    val toPhoneBrush: Brush,
    /** Phone → PC. */
    val toPc: Color,
    val toPcSoft: Color,
    val toPcBrush: Brush,
    /** Filled part of progress bars. */
    val progress: Brush,
    val done: Brush,
) {
    fun color(direction: Direction) = if (direction == Direction.TO_PHONE) toPhone else toPc
    fun soft(direction: Direction) = if (direction == Direction.TO_PHONE) toPhoneSoft else toPcSoft
    fun brush(direction: Direction) = if (direction == Direction.TO_PHONE) toPhoneBrush else toPcBrush
}

private val classicLook = Look(
    bright = false,
    toPhone = Palette.Indigo,
    toPhoneSoft = Palette.IndigoSoft,
    toPhoneBrush = SolidBrush(Palette.Indigo),
    toPc = Palette.Indigo,
    toPcSoft = Palette.IndigoSoft,
    toPcBrush = SolidBrush(Palette.Indigo),
    progress = SolidBrush(Palette.Indigo),
    done = SolidBrush(Palette.Green),
)

private val brightLook = Look(
    bright = true,
    toPhone = Palette.Violet,
    toPhoneSoft = Palette.VioletSoft,
    toPhoneBrush = Brush.horizontalGradient(listOf(Palette.Violet, Palette.Rose)),
    toPc = Palette.Teal,
    toPcSoft = Palette.TealSoft,
    toPcBrush = Brush.horizontalGradient(listOf(Color(0xFF0B8C9E), Color(0xFF19B8A0))),
    progress = Brush.horizontalGradient(listOf(Palette.Violet, Palette.Rose)),
    done = Brush.horizontalGradient(listOf(Color(0xFF12A15A), Color(0xFF19B8A0))),
)

@Suppress("FunctionName")
private fun SolidBrush(color: Color): Brush = Brush.horizontalGradient(listOf(color, color))

val LocalLook = staticCompositionLocalOf { classicLook }

/** Gradient of the bright header banner. */
val HeaderBrush = Brush.linearGradient(listOf(Palette.Twilight, Palette.Violet, Palette.Rose))

private val brightColors = lightColorScheme(
    primary = Palette.Violet,
    onPrimary = Color.White,
    primaryContainer = Palette.VioletSoft,
    onPrimaryContainer = Palette.Twilight,
    secondary = Palette.Rose,
    tertiary = Palette.Teal,
    background = Palette.Mist,
    onBackground = Color(0xFF1F1633),
    surface = Color.White,
    onSurface = Color(0xFF1F1633),
    surfaceVariant = Color(0xFFF2EDFA),
    onSurfaceVariant = Color(0xFF675D7E),
    outline = Color(0xFFDCD2EE),
    outlineVariant = Color(0xFFEAE3F6),
    error = Color(0xFFD7263D),
)

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
fun SyncMusicTheme(theme: UiTheme = UiTheme.CLASSIC, content: @Composable () -> Unit) {
    val bright = theme == UiTheme.BRIGHT
    val base = Typography()
    val typography = base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = if (bright) FontWeight.Bold else FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
    CompositionLocalProvider(LocalLook provides if (bright) brightLook else classicLook) {
        MaterialTheme(colorScheme = if (bright) brightColors else colors, typography = typography, content = content)
    }
}

/** The app's mark: balance scales with a computer on one pan and a phone on the other. */
@Composable
fun ScalesLogo(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = w * 0.055f
        val line = Stroke(width = stroke, cap = StrokeCap.Round)
        // Post and foot
        drawLine(color, Offset(w * 0.5f, h * 0.16f), Offset(w * 0.5f, h * 0.86f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.32f, h * 0.88f), Offset(w * 0.68f, h * 0.88f), stroke, StrokeCap.Round)
        drawCircle(color, radius = stroke * 1.1f, center = Offset(w * 0.5f, h * 0.14f))
        // Beam
        drawLine(color, Offset(w * 0.12f, h * 0.24f), Offset(w * 0.88f, h * 0.24f), stroke, StrokeCap.Round)
        for (cx in listOf(w * 0.2f, w * 0.8f)) {
            // Strings down to the pan
            drawLine(color, Offset(cx, h * 0.24f), Offset(cx - w * 0.12f, h * 0.56f), stroke * 0.6f, StrokeCap.Round)
            drawLine(color, Offset(cx, h * 0.24f), Offset(cx + w * 0.12f, h * 0.56f), stroke * 0.6f, StrokeCap.Round)
            // Pan
            drawArc(
                color,
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(cx - w * 0.16f, h * 0.46f),
                size = Size(w * 0.32f, h * 0.2f),
                style = line,
            )
        }
        // A computer screen on the left pan, a phone on the right one
        drawRoundRect(color, Offset(w * 0.11f, h * 0.42f), Size(w * 0.18f, h * 0.12f), CornerRadius(stroke), style = Stroke(stroke * 0.7f))
        drawRoundRect(color, Offset(w * 0.75f, h * 0.37f), Size(w * 0.1f, h * 0.17f), CornerRadius(stroke), style = Stroke(stroke * 0.7f))
    }
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
