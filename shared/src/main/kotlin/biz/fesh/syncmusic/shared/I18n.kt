package biz.fesh.syncmusic.shared

import java.util.Locale

/** Interface language of both apps. */
enum class Lang(val code: String, val title: String) {
    UK("uk", "Українська"),
    RU("ru", "Русский");

    companion object {
        fun of(code: String?): Lang? = entries.firstOrNull { it.code == code }

        /** Russian when the system is set to Russian (or Belarusian), Ukrainian otherwise. */
        fun system(): Lang = when (Locale.getDefault().language) {
            "ru", "be" -> RU
            else -> UK
        }
    }
}

/**
 * The current interface language. Every user-visible string goes through [tr], which reads it at the
 * moment the text is built, so changing [lang] and redrawing the UI switches the language.
 */
object I18n {
    @Volatile
    var lang: Lang = Lang.system()

    val locale: Locale get() = if (lang == Lang.RU) Locale.forLanguageTag("ru-RU") else Locale.forLanguageTag("uk-UA")
}

/** Picks the Ukrainian or the Russian text for the current [I18n.lang]. */
fun tr(uk: String, ru: String): String = if (I18n.lang == Lang.RU) ru else uk
