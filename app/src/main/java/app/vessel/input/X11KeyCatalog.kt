package app.vessel.input

/**
 * How the key list is grouped in a picker.
 *
 * Display order is declaration order: the things a game binds first come first,
 * and the numpad — which almost nothing on a phone wants — comes last.
 */
enum class KeyGroup(val title: String) {
    MOUSE("Mouse"),
    MODIFIERS("Modifiers"),

    /**
     * Space, Enter, Tab, Esc and the two deletes.
     *
     * A group the plan's sketch did not name and the design draws as "Keys":
     * none of the other seven honestly holds `Esc`, and putting it under
     * Navigation next to Page Down makes it unfindable.
     */
    EDITING("Keys"),
    NAVIGATION("Navigation"),
    LETTERS("Letters"),
    DIGITS("Digits"),
    FUNCTION("Function"),
    PUNCTUATION("Punctuation"),
    NUMPAD("Numpad"),
}

/** One offer in the picker: what it is called, where it sits, what it sends. */
data class KeyChoice(val label: String, val group: KeyGroup, val action: GamepadAction)

/**
 * Every [GamepadAction] a binding may be given, and the one place a binding's
 * display text is decided.
 *
 * Built from the same [X11] object [X11KeyMap] is, so the list cannot offer a
 * keycode the vendored server would refuse — the whole 8..127 range, and nothing
 * outside it. [label] is used by the row *and* by the picker, which is what stops
 * the two disagreeing about what a control is bound to.
 *
 * [GamepadAction.None] is deliberately **not** an entry. Clearing is an explicit
 * affordance on the row and the first line of the picker rather than a key that
 * happens to mean nothing, and keeping it out of [entries] is what lets the
 * catalogue promise that every label is distinct.
 */
object X11KeyCatalog {

    /** What an unbound control reads as, everywhere. */
    const val UNBOUND: String = "Unbound"

    // Declared before [entries] rather than beside the other private helpers:
    // an `object`'s properties initialise in declaration order, and a list read
    // by an earlier initialiser is still null when it gets there.
    private val LETTERS = listOf(
        "A" to X11.A, "B" to X11.B, "C" to X11.C, "D" to X11.D, "E" to X11.E,
        "F" to X11.F, "G" to X11.G, "H" to X11.H, "I" to X11.I, "J" to X11.J,
        "K" to X11.K, "L" to X11.L, "M" to X11.M, "N" to X11.N, "O" to X11.O,
        "P" to X11.P, "Q" to X11.Q, "R" to X11.R, "S" to X11.S, "T" to X11.T,
        "U" to X11.U, "V" to X11.V, "W" to X11.W, "X" to X11.X, "Y" to X11.Y,
        "Z" to X11.Z,
    )

    private val DIGITS = listOf(
        "1" to X11.N1, "2" to X11.N2, "3" to X11.N3, "4" to X11.N4, "5" to X11.N5,
        "6" to X11.N6, "7" to X11.N7, "8" to X11.N8, "9" to X11.N9, "0" to X11.N0,
    )

    val entries: List<KeyChoice> = buildList {
        // The seven X11 pointer buttons. Four of them are the wheel, because
        // that is how X11 carries a wheel — there is no scroll axis on the wire.
        add(mouse("Left mouse", PointerButton.LEFT))
        add(mouse("Middle mouse", PointerButton.MIDDLE))
        add(mouse("Right mouse", PointerButton.RIGHT))
        add(mouse("Wheel up", PointerButton.SCROLL_UP))
        add(mouse("Wheel down", PointerButton.SCROLL_DOWN))
        add(mouse("Wheel left", PointerButton.SCROLL_LEFT))
        add(mouse("Wheel right", PointerButton.SCROLL_RIGHT))

        add(key("Left Shift", KeyGroup.MODIFIERS, X11.SHIFT_L))
        add(key("Left Ctrl", KeyGroup.MODIFIERS, X11.CTRL_L))
        add(key("Left Alt", KeyGroup.MODIFIERS, X11.ALT_L))
        add(key("Right Shift", KeyGroup.MODIFIERS, X11.SHIFT_R))
        add(key("Right Ctrl", KeyGroup.MODIFIERS, X11.CTRL_R))
        add(key("Right Alt", KeyGroup.MODIFIERS, X11.ALT_R))
        add(key("Caps Lock", KeyGroup.MODIFIERS, X11.CAPS_LOCK))
        // The three the vendored keysym table has no entry for at all: they
        // carry their keysym, which the server installs on first press.
        add(key("Left Super", KeyGroup.MODIFIERS, X11.SUPER_L, Keysym.SUPER_L))
        add(key("Right Super", KeyGroup.MODIFIERS, X11.SUPER_R, Keysym.SUPER_R))
        add(key("Menu", KeyGroup.MODIFIERS, X11.MENU, Keysym.MENU))

        add(key("Space", KeyGroup.EDITING, X11.SPACE))
        add(key("Enter", KeyGroup.EDITING, X11.ENTER))
        add(key("Tab", KeyGroup.EDITING, X11.TAB))
        add(key("Esc", KeyGroup.EDITING, X11.ESC))
        add(key("Backspace", KeyGroup.EDITING, X11.BKSP))
        add(key("Delete", KeyGroup.EDITING, X11.DEL))
        add(key("Insert", KeyGroup.EDITING, X11.INSERT))

        add(key("Up", KeyGroup.NAVIGATION, X11.UP))
        add(key("Down", KeyGroup.NAVIGATION, X11.DOWN))
        add(key("Left", KeyGroup.NAVIGATION, X11.LEFT))
        add(key("Right", KeyGroup.NAVIGATION, X11.RIGHT))
        add(key("Home", KeyGroup.NAVIGATION, X11.HOME))
        add(key("End", KeyGroup.NAVIGATION, X11.END))
        add(key("Page Up", KeyGroup.NAVIGATION, X11.PRIOR))
        add(key("Page Down", KeyGroup.NAVIGATION, X11.NEXT))
        add(key("Print Screen", KeyGroup.NAVIGATION, X11.PRTSCN, Keysym.PRINT))
        add(key("Scroll Lock", KeyGroup.NAVIGATION, X11.SCROLL_LOCK, Keysym.SCROLL_LOCK))

        LETTERS.forEach { (label, keycode) -> add(key(label, KeyGroup.LETTERS, keycode)) }
        DIGITS.forEach { (label, keycode) -> add(key(label, KeyGroup.DIGITS, keycode)) }

        add(key("F1", KeyGroup.FUNCTION, X11.F1))
        add(key("F2", KeyGroup.FUNCTION, X11.F2))
        add(key("F3", KeyGroup.FUNCTION, X11.F3))
        add(key("F4", KeyGroup.FUNCTION, X11.F4))
        add(key("F5", KeyGroup.FUNCTION, X11.F5))
        add(key("F6", KeyGroup.FUNCTION, X11.F6))
        add(key("F7", KeyGroup.FUNCTION, X11.F7))
        add(key("F8", KeyGroup.FUNCTION, X11.F8))
        add(key("F9", KeyGroup.FUNCTION, X11.F9))
        add(key("F10", KeyGroup.FUNCTION, X11.F10))
        add(key("F11", KeyGroup.FUNCTION, X11.F11))
        add(key("F12", KeyGroup.FUNCTION, X11.F12))

        add(key("Minus  -", KeyGroup.PUNCTUATION, X11.MINUS))
        add(key("Equals  =", KeyGroup.PUNCTUATION, X11.EQUAL))
        add(key("Left bracket  [", KeyGroup.PUNCTUATION, X11.BRACKET_LEFT))
        add(key("Right bracket  ]", KeyGroup.PUNCTUATION, X11.BRACKET_RIGHT))
        add(key("Semicolon  ;", KeyGroup.PUNCTUATION, X11.SEMICOLON))
        add(key("Apostrophe  '", KeyGroup.PUNCTUATION, X11.APOSTROPHE))
        add(key("Grave  `", KeyGroup.PUNCTUATION, X11.GRAVE))
        add(key("Backslash  \\", KeyGroup.PUNCTUATION, X11.BACKSLASH))
        add(key("Comma  ,", KeyGroup.PUNCTUATION, X11.COMMA))
        add(key("Period  .", KeyGroup.PUNCTUATION, X11.PERIOD))
        add(key("Slash  /", KeyGroup.PUNCTUATION, X11.SLASH))

        add(key("Numpad 0", KeyGroup.NUMPAD, X11.KP_0))
        add(key("Numpad 1", KeyGroup.NUMPAD, X11.KP_1))
        add(key("Numpad 2", KeyGroup.NUMPAD, X11.KP_2))
        add(key("Numpad 3", KeyGroup.NUMPAD, X11.KP_3))
        add(key("Numpad 4", KeyGroup.NUMPAD, X11.KP_4))
        add(key("Numpad 5", KeyGroup.NUMPAD, X11.KP_5))
        add(key("Numpad 6", KeyGroup.NUMPAD, X11.KP_6))
        add(key("Numpad 7", KeyGroup.NUMPAD, X11.KP_7))
        add(key("Numpad 8", KeyGroup.NUMPAD, X11.KP_8))
        add(key("Numpad 9", KeyGroup.NUMPAD, X11.KP_9))
        add(key("Numpad .", KeyGroup.NUMPAD, X11.KP_DEL))
        add(key("Numpad +", KeyGroup.NUMPAD, X11.KP_ADD))
        add(key("Numpad −", KeyGroup.NUMPAD, X11.KP_SUBTRACT))
        add(key("Numpad ×", KeyGroup.NUMPAD, X11.KP_MULTIPLY))
        add(key("Numpad ÷", KeyGroup.NUMPAD, X11.KP_DIVIDE))
        add(key("Numpad Enter", KeyGroup.NUMPAD, X11.KP_ENTER, Keysym.KP_ENTER))
        add(key("Num Lock", KeyGroup.NUMPAD, X11.NUM_LOCK))
    }

    /** The catalogue as the picker draws it: groups in declaration order, non-empty only. */
    val groups: List<Pair<KeyGroup, List<KeyChoice>>> =
        KeyGroup.entries.map { group -> group to entries.filter { it.group == group } }
            .filter { it.second.isNotEmpty() }

    /**
     * Everything whose label contains [query], case-insensitively.
     *
     * A blank query is every entry rather than none: the picker opens on the
     * whole list, and typing narrows it.
     */
    fun search(query: String): List<KeyChoice> {
        val needle = query.trim()
        if (needle.isEmpty()) return entries
        return entries.filter { it.label.contains(needle, ignoreCase = true) }
    }

    /** [search], regrouped for a sectioned list. */
    fun searchGroups(query: String): List<Pair<KeyGroup, List<KeyChoice>>> {
        val hits = search(query)
        return KeyGroup.entries.map { group -> group to hits.filter { it.group == group } }
            .filter { it.second.isNotEmpty() }
    }

    /**
     * What a binding is called on screen. The single source of that text.
     *
     * A keycode the catalogue does not offer still gets a truthful answer rather
     * than a blank — a profile imported from a build that knew one more key
     * should read as a number, not as unbound.
     */
    fun label(action: GamepadAction): String = when (action) {
        GamepadAction.None -> UNBOUND
        is GamepadAction.Button ->
            byButton[action.button]?.label ?: "Button ${action.button.x11Code}"

        is GamepadAction.Key -> byKeycode[action.keycode]?.label ?: "Keycode ${action.keycode}"
    }

    /** The catalogue entry a binding came from, when there is one. */
    fun choiceFor(action: GamepadAction): KeyChoice? = when (action) {
        GamepadAction.None -> null
        is GamepadAction.Button -> byButton[action.button]
        is GamepadAction.Key -> byKeycode[action.keycode]
    }

    private val byKeycode: Map<Int, KeyChoice> =
        entries.mapNotNull { choice ->
            (choice.action as? GamepadAction.Key)?.let { it.keycode to choice }
        }.toMap()

    private val byButton: Map<PointerButton, KeyChoice> =
        entries.mapNotNull { choice ->
            (choice.action as? GamepadAction.Button)?.let { it.button to choice }
        }.toMap()

    private fun key(label: String, group: KeyGroup, keycode: Int, keysym: Int = 0) =
        KeyChoice(label, group, GamepadAction.Key(keycode, keysym))

    private fun mouse(label: String, button: PointerButton) =
        KeyChoice(label, KeyGroup.MOUSE, GamepadAction.Button(button))
}
