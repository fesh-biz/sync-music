package biz.fesh.syncmusic.desktop.ui

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.type
import biz.fesh.syncmusic.desktop.Settings
import java.awt.event.KeyEvent as Awt

/**
 * Commands of the file window and their keys. The defaults are Total Commander's;
 * the user can change them (Settings.fmHotkeys keeps only what differs from the defaults).
 */
enum class FmAction(val title: String, val defaults: List<String>, val group: String) {
    SWITCH_PANEL("Перейти в іншу панель", listOf("Tab"), NAV),
    OPEN("Відкрити папку або файл", listOf("Enter"), NAV),
    UP("Папка вище (..)", listOf("Backspace", "Ctrl+PageUp"), NAV),
    ROOT("Корінь диска", listOf("Ctrl+\\"), NAV),
    LEFT_DRIVES("Диски комп'ютера (ліва панель)", listOf("Alt+F1"), NAV),
    RIGHT_DRIVES("Пам'ять і SD-карта телефона (права панель)", listOf("Alt+F2"), NAV),
    REFRESH("Оновити панель", listOf("Ctrl+R"), NAV),

    SELECT("Позначити й перейти до наступного", listOf("Insert", "Space"), SEL),
    SELECT_ALL("Позначити все", listOf("Ctrl+A", "Ctrl+Num+"), SEL),
    DESELECT_ALL("Зняти всі позначки", listOf("Ctrl+Num-"), SEL),
    SELECT_GROUP("Позначити за маскою", listOf("Num+"), SEL),
    DESELECT_GROUP("Зняти позначки за маскою", listOf("Num-"), SEL),
    INVERT("Інвертувати позначки", listOf("Num*"), SEL),

    VIEW("Перегляд", listOf("F3"), FILES),
    EDIT("Правка", listOf("F4"), FILES),
    COPY("Копіювати в іншу панель", listOf("F5"), FILES),
    MOVE("Перемістити в іншу панель", listOf("F6"), FILES),
    RENAME("Перейменувати", listOf("Shift+F6"), FILES),
    MKDIR("Нова папка", listOf("F7"), FILES),
    DELETE("Видалити", listOf("F8", "Delete"), FILES),

    SORT_NAME("Сортувати за ім'ям", listOf("Ctrl+F3"), SORT),
    SORT_EXT("Сортувати за типом", listOf("Ctrl+F4"), SORT),
    SORT_TIME("Сортувати за датою", listOf("Ctrl+F5"), SORT),
    SORT_SIZE("Сортувати за розміром", listOf("Ctrl+F6"), SORT),

    HOTKEYS("Налаштувати клавіші", listOf("F9"), SORT),
}

private const val NAV = "Переходи"
private const val SEL = "Позначення"
private const val FILES = "Файли"
private const val SORT = "Інше"

/** One key press, independent of Compose so that tests can send it too. */
data class KeyPress(
    val code: Int,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    /** Typed character (for the quick search), 0 when none. */
    val char: Char = '\u0000',
) {
    /** "Ctrl+Shift+F6"; null for keys that cannot be bound (modifiers alone, unknown keys). */
    val combo: String?
        get() {
            val name = Hotkeys.keyName(code) ?: return null
            return buildString {
                if (ctrl) append("Ctrl+")
                if (alt) append("Alt+")
                if (shift) append("Shift+")
                append(name)
            }
        }

    val plain: Boolean get() = !ctrl && !alt
}

object Hotkeys {
    /** Arrows, PageUp/PageDown, Home/End (with Shift: marking) and Esc always do what they do in Total Commander. */
    val fixed = setOf(
        "Up", "Down", "PageUp", "PageDown", "Home", "End", "Esc",
        "Shift+Up", "Shift+Down", "Shift+PageUp", "Shift+PageDown", "Shift+Home", "Shift+End",
    )

    fun fromCompose(event: KeyEvent): KeyPress? {
        if (event.type != KeyEventType.KeyDown) return null
        val awt = event.nativeKeyEvent as? Awt ?: return null
        val ch = awt.keyChar
        return KeyPress(
            code = awt.keyCode,
            ctrl = event.isCtrlPressed,
            alt = event.isAltPressed,
            shift = event.isShiftPressed,
            char = if (ch != Awt.CHAR_UNDEFINED && !ch.isISOControl()) ch else '\u0000',
        )
    }

    /** "Ctrl+Shift+F6" → a key press (for tests); null when it does not parse. */
    fun parse(combo: String): KeyPress? {
        val parts = combo.split('+').toMutableList()
        // "Num+" and "Ctrl+Num+" end with an empty part.
        if (combo.endsWith("+") && parts.size >= 2) {
            parts.removeAt(parts.size - 1)
            parts[parts.size - 1] = parts.last() + "+"
        }
        val key = parts.last()
        val code = codeOf(key) ?: return null
        val mods = parts.dropLast(1).map { it.lowercase() }
        return KeyPress(code, ctrl = "ctrl" in mods, alt = "alt" in mods, shift = "shift" in mods)
    }

    fun keyName(code: Int): String? = when (code) {
        in Awt.VK_F1..Awt.VK_F12 -> "F${code - Awt.VK_F1 + 1}"
        in Awt.VK_A..Awt.VK_Z -> ('A' + (code - Awt.VK_A)).toString()
        in Awt.VK_0..Awt.VK_9 -> ('0' + (code - Awt.VK_0)).toString()
        in Awt.VK_NUMPAD0..Awt.VK_NUMPAD9 -> "Num" + ('0' + (code - Awt.VK_NUMPAD0))
        else -> names[code]
    }

    private fun codeOf(name: String): Int? {
        for (code in 0..0xFFFF) if (keyName(code) == name) return code
        return null
    }

    private val names = mapOf(
        Awt.VK_ENTER to "Enter",
        Awt.VK_TAB to "Tab",
        Awt.VK_BACK_SPACE to "Backspace",
        Awt.VK_DELETE to "Delete",
        Awt.VK_INSERT to "Insert",
        Awt.VK_SPACE to "Space",
        Awt.VK_ESCAPE to "Esc",
        Awt.VK_HOME to "Home",
        Awt.VK_END to "End",
        Awt.VK_PAGE_UP to "PageUp",
        Awt.VK_PAGE_DOWN to "PageDown",
        Awt.VK_UP to "Up",
        Awt.VK_DOWN to "Down",
        Awt.VK_LEFT to "Left",
        Awt.VK_RIGHT to "Right",
        Awt.VK_ADD to "Num+",
        Awt.VK_SUBTRACT to "Num-",
        Awt.VK_MULTIPLY to "Num*",
        Awt.VK_DIVIDE to "Num/",
        Awt.VK_DECIMAL to "Num.",
        Awt.VK_BACK_SLASH to "\\",
        Awt.VK_SLASH to "/",
        Awt.VK_MINUS to "-",
        Awt.VK_EQUALS to "=",
        Awt.VK_COMMA to ",",
        Awt.VK_PERIOD to ".",
        Awt.VK_SEMICOLON to ";",
        Awt.VK_QUOTE to "'",
        Awt.VK_OPEN_BRACKET to "[",
        Awt.VK_CLOSE_BRACKET to "]",
        Awt.VK_BACK_QUOTE to "`",
        Awt.VK_CONTEXT_MENU to "Menu",
        Awt.VK_PAUSE to "Pause",
    )

    /** Keys of [action] with the user's changes. */
    fun keysOf(settings: Settings, action: FmAction): List<String> = settings.fmHotkeys[action.name] ?: action.defaults

    /** Combination → command. */
    fun table(settings: Settings): Map<String, FmAction> {
        val result = HashMap<String, FmAction>()
        for (action in FmAction.entries) {
            for (combo in keysOf(settings, action)) result.putIfAbsent(combo, action)
        }
        return result
    }

    /** First key of [action] for the buttons and hints ("" when it has none). */
    fun label(settings: Settings, action: FmAction): String = keysOf(settings, action).firstOrNull().orEmpty()

    /**
     * Gives [combo] to [action]; another command that had it loses it. Returns the new overrides
     * (only the commands whose keys differ from the defaults are kept).
     */
    fun assign(settings: Settings, action: FmAction, combo: String): Map<String, List<String>> {
        val current = FmAction.entries.associateWith { keysOf(settings, it) }.toMutableMap()
        for (other in FmAction.entries) {
            if (other != action && combo in current.getValue(other)) current[other] = current.getValue(other) - combo
        }
        if (combo !in current.getValue(action)) current[action] = current.getValue(action) + combo
        return overrides(current)
    }

    fun remove(settings: Settings, action: FmAction, combo: String): Map<String, List<String>> {
        val current = FmAction.entries.associateWith { keysOf(settings, it) }.toMutableMap()
        current[action] = current.getValue(action) - combo
        return overrides(current)
    }

    fun owner(settings: Settings, combo: String): FmAction? = FmAction.entries.firstOrNull { combo in keysOf(settings, it) }

    private fun overrides(all: Map<FmAction, List<String>>): Map<String, List<String>> =
        all.filter { (action, keys) -> keys != action.defaults }.mapKeys { it.key.name }
}

/** Passes the file window's key presses (window-level) to whatever shows it. */
class FilesKeys {
    @Volatile var handler: ((KeyPress) -> Boolean)? = null

    fun onKeyEvent(event: KeyEvent): Boolean {
        val press = Hotkeys.fromCompose(event) ?: return false
        return handler?.invoke(press) ?: false
    }

    /** Test hook: presses "F5", "Shift+Down", … */
    fun press(combo: String): Boolean {
        val press = Hotkeys.parse(combo) ?: return false
        return handler?.invoke(press) ?: false
    }
}
