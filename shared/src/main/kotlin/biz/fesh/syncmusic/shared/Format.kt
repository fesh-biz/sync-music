package biz.fesh.syncmusic.shared

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Ukrainian-language formatting helpers used by both UIs. */
object Format {
    private val uk: Locale = Locale.forLanguageTag("uk-UA")

    fun bytes(value: Long): String {
        if (value < 1024) return "$value Б"
        val units = arrayOf("КБ", "МБ", "ГБ", "ТБ")
        var v = value / 1024.0
        var i = 0
        while (v >= 1024 && i < units.lastIndex) {
            v /= 1024
            i++
        }
        val number = if (v >= 100) String.format(uk, "%.0f", v) else String.format(uk, "%.1f", v)
        return "$number ${units[i]}"
    }

    fun speed(bytesPerSecond: Long): String = bytes(bytesPerSecond) + "/с"

    fun number(value: Long): String = String.format(uk, "%,d", value)

    /** Ukrainian plural: [one] for 1, 21…; [few] for 2–4, 22–24…; [many] otherwise. */
    fun plural(n: Long, one: String, few: String, many: String): String {
        val n10 = (n % 10).toInt()
        val n100 = (n % 100).toInt()
        return when {
            n10 == 1 && n100 != 11 -> one
            n10 in 2..4 && n100 !in 12..14 -> few
            else -> many
        }
    }

    /** "1 файл", "3 файли", "12 файлів". */
    fun files(n: Long): String = "${number(n)} ${plural(n, "файл", "файли", "файлів")}"
    fun files(n: Int): String = files(n.toLong())

    /** Form used after "з N": "з 21 файлу", "з 1 234 файлів". */
    fun filesGenitive(n: Long): String = "${number(n)} ${plural(n, "файлу", "файлів", "файлів")}"
    fun filesGenitive(n: Int): String = filesGenitive(n.toLong())

    fun duration(seconds: Long): String = when {
        seconds < 60 -> "менше хвилини"
        seconds < 3600 -> "${seconds / 60} хв"
        else -> {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            if (m == 0L) "$h год" else "$h год $m хв"
        }
    }

    fun eta(remainingBytes: Long, bytesPerSecond: Long): String? {
        if (bytesPerSecond <= 0 || remainingBytes <= 0) return null
        return "≈ " + duration(remainingBytes / bytesPerSecond)
    }

    /** "сьогодні о 17:40", "вчора о 09:12", "12.10.2026 о 08:00". */
    fun moment(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
        if (epochMillis <= 0) return "ніколи"
        val diff = now - epochMillis
        if (diff in 0 until 60_000) return "щойно"
        if (diff in 60_000 until 3_600_000) {
            val m = diff / 60_000
            return "$m ${plural(m, "хвилину", "хвилини", "хвилин")} тому"
        }
        val cal = Calendar.getInstance().apply { timeInMillis = epochMillis }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val time = SimpleDateFormat("HH:mm", uk).format(Date(epochMillis))
        val sameYear = cal.get(Calendar.YEAR) == today.get(Calendar.YEAR)
        val dayDiff = today.get(Calendar.DAY_OF_YEAR) - cal.get(Calendar.DAY_OF_YEAR)
        return when {
            sameYear && dayDiff == 0 -> "сьогодні о $time"
            sameYear && dayDiff == 1 -> "вчора о $time"
            else -> SimpleDateFormat("dd.MM.yyyy", uk).format(Date(epochMillis)) + " о $time"
        }
    }
}
