package biz.fesh.syncmusic.shared

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Russian-language formatting helpers used by both UIs. */
object Format {
    private val ru: Locale = Locale.forLanguageTag("ru-RU")

    fun bytes(value: Long): String {
        if (value < 1024) return "$value Б"
        val units = arrayOf("КБ", "МБ", "ГБ", "ТБ")
        var v = value / 1024.0
        var i = 0
        while (v >= 1024 && i < units.lastIndex) {
            v /= 1024
            i++
        }
        val number = if (v >= 100) String.format(ru, "%.0f", v) else String.format(ru, "%.1f", v)
        return "$number ${units[i]}"
    }

    fun speed(bytesPerSecond: Long): String = bytes(bytesPerSecond) + "/с"

    fun number(value: Long): String = String.format(ru, "%,d", value)

    fun plural(n: Long, one: String, few: String, many: String): String {
        val n10 = (n % 10).toInt()
        val n100 = (n % 100).toInt()
        return when {
            n10 == 1 && n100 != 11 -> one
            n10 in 2..4 && n100 !in 12..14 -> few
            else -> many
        }
    }

    fun files(n: Long): String = "${number(n)} ${plural(n, "файл", "файла", "файлов")}"
    fun files(n: Int): String = files(n.toLong())

    /** "файла" / "файлов" form used after "из N". */
    fun filesGenitive(n: Long): String = "${number(n)} ${plural(n, "файла", "файлов", "файлов")}"
    fun filesGenitive(n: Int): String = filesGenitive(n.toLong())

    fun duration(seconds: Long): String = when {
        seconds < 60 -> "меньше минуты"
        seconds < 3600 -> "${seconds / 60} мин"
        else -> {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            if (m == 0L) "$h ч" else "$h ч $m мин"
        }
    }

    fun eta(remainingBytes: Long, bytesPerSecond: Long): String? {
        if (bytesPerSecond <= 0 || remainingBytes <= 0) return null
        return "≈ " + duration(remainingBytes / bytesPerSecond)
    }

    /** "сегодня в 17:40", "вчера в 09:12", "12.10 в 08:00". */
    fun moment(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
        if (epochMillis <= 0) return "никогда"
        val diff = now - epochMillis
        if (diff in 0 until 60_000) return "только что"
        if (diff in 60_000 until 3_600_000) {
            val m = diff / 60_000
            return "$m ${plural(m, "минуту", "минуты", "минут")} назад"
        }
        val cal = Calendar.getInstance().apply { timeInMillis = epochMillis }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val time = SimpleDateFormat("HH:mm", ru).format(Date(epochMillis))
        val sameYear = cal.get(Calendar.YEAR) == today.get(Calendar.YEAR)
        val dayDiff = today.get(Calendar.DAY_OF_YEAR) - cal.get(Calendar.DAY_OF_YEAR)
        return when {
            sameYear && dayDiff == 0 -> "сегодня в $time"
            sameYear && dayDiff == 1 -> "вчера в $time"
            else -> SimpleDateFormat("dd.MM.yyyy", ru).format(Date(epochMillis)) + " в $time"
        }
    }
}
