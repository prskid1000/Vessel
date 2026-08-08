package app.vessel.input

import android.view.KeyEvent

/**
 * X11 keycodes, as plain integers.
 *
 * These are the same numbers as `com.winlator.xserver.XKeycode`, transcribed so
 * this package can stay free of that import — and pinned by `X11KeyMapTest`,
 * which fails if the two ever disagree. They are the traditional XFree86
 * keycodes (Linux evdev code + 8) that the vendored server's default keysym
 * table is built around.
 */
object X11 {
    const val ESC = 9
    const val N1 = 10
    const val N2 = 11
    const val N3 = 12
    const val N4 = 13
    const val N5 = 14
    const val N6 = 15
    const val N7 = 16
    const val N8 = 17
    const val N9 = 18
    const val N0 = 19
    const val MINUS = 20
    const val EQUAL = 21
    const val BKSP = 22
    const val TAB = 23
    const val Q = 24
    const val W = 25
    const val E = 26
    const val R = 27
    const val T = 28
    const val Y = 29
    const val U = 30
    const val I = 31
    const val O = 32
    const val P = 33
    const val BRACKET_LEFT = 34
    const val BRACKET_RIGHT = 35
    const val ENTER = 36
    const val CTRL_L = 37
    const val A = 38
    const val S = 39
    const val D = 40
    const val F = 41
    const val G = 42
    const val H = 43
    const val J = 44
    const val K = 45
    const val L = 46
    const val SEMICOLON = 47
    const val APOSTROPHE = 48
    const val GRAVE = 49
    const val SHIFT_L = 50
    const val BACKSLASH = 51
    const val Z = 52
    const val X = 53
    const val C = 54
    const val V = 55
    const val B = 56
    const val N = 57
    const val M = 58
    const val COMMA = 59
    const val PERIOD = 60
    const val SLASH = 61
    const val SHIFT_R = 62
    const val KP_MULTIPLY = 63
    const val ALT_L = 64
    const val SPACE = 65
    const val CAPS_LOCK = 66
    const val F1 = 67
    const val F2 = 68
    const val F3 = 69
    const val F4 = 70
    const val F5 = 71
    const val F6 = 72
    const val F7 = 73
    const val F8 = 74
    const val F9 = 75
    const val F10 = 76
    const val NUM_LOCK = 77
    const val SCROLL_LOCK = 78
    const val KP_7 = 79
    const val KP_8 = 80
    const val KP_9 = 81
    const val KP_SUBTRACT = 82
    const val KP_4 = 83
    const val KP_5 = 84
    const val KP_6 = 85
    const val KP_ADD = 86
    const val KP_1 = 87
    const val KP_2 = 88
    const val KP_3 = 89
    const val KP_0 = 90
    const val KP_DEL = 91
    const val F11 = 95
    const val F12 = 96
    const val KP_ENTER = 104
    const val CTRL_R = 105
    const val KP_DIVIDE = 106
    const val PRTSCN = 107
    const val ALT_R = 108
    const val HOME = 110
    const val UP = 111
    const val PRIOR = 112
    const val LEFT = 113
    const val RIGHT = 114
    const val END = 115
    const val DOWN = 116
    const val NEXT = 117
    const val INSERT = 118
    const val DEL = 119

    /**
     * The three keycodes the vendored table leaves free, for keys it has no
     * entry for at all.
     *
     * They are Vessel's own numbers, not X convention — evdev puts `Super_L` at
     * 133, and nothing above [MAX_KEYCODE] can be sent at all: the vendored
     * keyboard API is `byte`-typed end to end (`XKeycode.id`, `setKeysyms`,
     * `hasKeysym`, `onKeyPress`), and each of those does `keycode - MIN_KEYCODE`
     * as a *signed* byte. Keycode 128 arrives as −128, indexes `keysyms` at
     * −272, and throws `ArrayIndexOutOfBoundsException` on the X client thread
     * the first time the key is pressed.
     *
     * So the choice is not "the first free slots" but "the only free slots":
     * `XKeycode` occupies every id in 8..126 except 8, 92 and 127. That is
     * exactly three, and exactly three are needed. Which number carries which
     * key does not matter — the guest learns it from the keysym below, which the
     * server installs on first press and announces with a `MappingNotify`.
     */
    const val SUPER_L = 127
    const val SUPER_R = 92
    const val MENU = 8

    /** `Keyboard.MIN_KEYCODE`. */
    const val MIN_KEYCODE = 8

    /**
     * The highest keycode that can reach the server, which is neither
     * `Keyboard.MAX_KEYCODE` (255) nor the 131 the `keysyms` array could hold.
     * It is the signed-byte ceiling. See [SUPER_L]; asserted in `X11KeyMapTest`.
     */
    const val MAX_KEYCODE = 127
}

/**
 * X11 keysyms for the handful of keys the vendored `Keyboard.createKeyboard`
 * does not preinstall.
 *
 * Everything else passes keysym 0, which tells the server "the mapping you
 * already have is right" — the alternative, sending a keysym per press, makes
 * `InputDeviceManager.onKeyPress` overwrite *both* shift levels of that keycode
 * with the same value, so `Shift`+`a` would start producing `a`.
 */
private object Keysym {
    const val PRINT = 0xFF61
    const val SCROLL_LOCK = 0xFF14
    const val KP_ENTER = 0xFF8D
    const val SUPER_L = 0xFFEB
    const val SUPER_R = 0xFFEC
    const val MENU = 0xFF67
}

/** One physical key's X11 identity. */
data class X11KeyBinding(val keycode: Int, val keysym: Int = 0)

/**
 * Android keycode to X11 keycode, for a **physical** keyboard.
 *
 * This exists rather than reusing the vendored `Keyboard.onKeyEvent` because
 * that method is written for a soft keyboard and gets a hardware one subtly
 * wrong: it synthesises a `Shift_L` press whenever `isShiftPressed()` and
 * releases it on *any* key up, so holding shift and typing `ABC` on a Bluetooth
 * keyboard produces `Abc`. The IME still goes through the vendored path, which
 * is where its unicode and `ACTION_MULTIPLE` handling belong — see
 * `SessionInputView`.
 *
 * The layout is US. A physical keyboard set to another layout types the US
 * character in the same position, because getting that right means rewriting
 * the server's keysym table per level and Wine only reads it once. Text in
 * another layout goes through the IME, which handles arbitrary unicode.
 */
object X11KeyMap {

    private val table: Array<X11KeyBinding?> = buildTable()

    /** The binding for an Android keycode, or null if there is no sane one. */
    operator fun get(androidKeyCode: Int): X11KeyBinding? =
        table.getOrNull(androidKeyCode)

    /** Every Android keycode this map knows, for tests and for diagnostics. */
    fun mappedAndroidKeyCodes(): List<Int> =
        table.indices.filter { table[it] != null }

    /**
     * The key edges one Android `ACTION_DOWN` should produce.
     *
     * A held key repeats in Android by re-delivering `ACTION_DOWN` with a
     * rising `repeatCount`, and the vendored `Keyboard.setKeyPress` drops a
     * press for a key it already holds — correct for de-duplication, fatal for
     * a text field, where holding backspace would delete exactly one character.
     * A repeat is therefore sent as a release and a press, which is what an X
     * server's own auto-repeat looks like on the wire.
     */
    fun edgesForDown(binding: X11KeyBinding, repeat: Boolean): List<GuestInput.Key> =
        if (repeat) {
            listOf(
                GuestInput.Key(binding.keycode, pressed = false),
                GuestInput.Key(binding.keycode, binding.keysym, pressed = true),
            )
        } else {
            listOf(GuestInput.Key(binding.keycode, binding.keysym, pressed = true))
        }

    private fun buildTable(): Array<X11KeyBinding?> {
        val entries = mapOf(
            KeyEvent.KEYCODE_ESCAPE to X11.ESC,
            KeyEvent.KEYCODE_ENTER to X11.ENTER,
            KeyEvent.KEYCODE_TAB to X11.TAB,
            KeyEvent.KEYCODE_SPACE to X11.SPACE,
            KeyEvent.KEYCODE_DEL to X11.BKSP,
            KeyEvent.KEYCODE_FORWARD_DEL to X11.DEL,
            KeyEvent.KEYCODE_INSERT to X11.INSERT,
            KeyEvent.KEYCODE_MOVE_HOME to X11.HOME,
            KeyEvent.KEYCODE_MOVE_END to X11.END,
            KeyEvent.KEYCODE_PAGE_UP to X11.PRIOR,
            KeyEvent.KEYCODE_PAGE_DOWN to X11.NEXT,
            KeyEvent.KEYCODE_DPAD_LEFT to X11.LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT to X11.RIGHT,
            KeyEvent.KEYCODE_DPAD_UP to X11.UP,
            KeyEvent.KEYCODE_DPAD_DOWN to X11.DOWN,

            KeyEvent.KEYCODE_SHIFT_LEFT to X11.SHIFT_L,
            KeyEvent.KEYCODE_SHIFT_RIGHT to X11.SHIFT_R,
            KeyEvent.KEYCODE_CTRL_LEFT to X11.CTRL_L,
            KeyEvent.KEYCODE_CTRL_RIGHT to X11.CTRL_R,
            KeyEvent.KEYCODE_ALT_LEFT to X11.ALT_L,
            KeyEvent.KEYCODE_ALT_RIGHT to X11.ALT_R,
            KeyEvent.KEYCODE_CAPS_LOCK to X11.CAPS_LOCK,
            KeyEvent.KEYCODE_NUM_LOCK to X11.NUM_LOCK,

            KeyEvent.KEYCODE_A to X11.A,
            KeyEvent.KEYCODE_B to X11.B,
            KeyEvent.KEYCODE_C to X11.C,
            KeyEvent.KEYCODE_D to X11.D,
            KeyEvent.KEYCODE_E to X11.E,
            KeyEvent.KEYCODE_F to X11.F,
            KeyEvent.KEYCODE_G to X11.G,
            KeyEvent.KEYCODE_H to X11.H,
            KeyEvent.KEYCODE_I to X11.I,
            KeyEvent.KEYCODE_J to X11.J,
            KeyEvent.KEYCODE_K to X11.K,
            KeyEvent.KEYCODE_L to X11.L,
            KeyEvent.KEYCODE_M to X11.M,
            KeyEvent.KEYCODE_N to X11.N,
            KeyEvent.KEYCODE_O to X11.O,
            KeyEvent.KEYCODE_P to X11.P,
            KeyEvent.KEYCODE_Q to X11.Q,
            KeyEvent.KEYCODE_R to X11.R,
            KeyEvent.KEYCODE_S to X11.S,
            KeyEvent.KEYCODE_T to X11.T,
            KeyEvent.KEYCODE_U to X11.U,
            KeyEvent.KEYCODE_V to X11.V,
            KeyEvent.KEYCODE_W to X11.W,
            KeyEvent.KEYCODE_X to X11.X,
            KeyEvent.KEYCODE_Y to X11.Y,
            KeyEvent.KEYCODE_Z to X11.Z,

            KeyEvent.KEYCODE_0 to X11.N0,
            KeyEvent.KEYCODE_1 to X11.N1,
            KeyEvent.KEYCODE_2 to X11.N2,
            KeyEvent.KEYCODE_3 to X11.N3,
            KeyEvent.KEYCODE_4 to X11.N4,
            KeyEvent.KEYCODE_5 to X11.N5,
            KeyEvent.KEYCODE_6 to X11.N6,
            KeyEvent.KEYCODE_7 to X11.N7,
            KeyEvent.KEYCODE_8 to X11.N8,
            KeyEvent.KEYCODE_9 to X11.N9,

            KeyEvent.KEYCODE_COMMA to X11.COMMA,
            KeyEvent.KEYCODE_PERIOD to X11.PERIOD,
            KeyEvent.KEYCODE_SEMICOLON to X11.SEMICOLON,
            KeyEvent.KEYCODE_APOSTROPHE to X11.APOSTROPHE,
            KeyEvent.KEYCODE_LEFT_BRACKET to X11.BRACKET_LEFT,
            KeyEvent.KEYCODE_RIGHT_BRACKET to X11.BRACKET_RIGHT,
            KeyEvent.KEYCODE_GRAVE to X11.GRAVE,
            KeyEvent.KEYCODE_MINUS to X11.MINUS,
            KeyEvent.KEYCODE_EQUALS to X11.EQUAL,
            KeyEvent.KEYCODE_SLASH to X11.SLASH,
            KeyEvent.KEYCODE_BACKSLASH to X11.BACKSLASH,

            KeyEvent.KEYCODE_NUMPAD_0 to X11.KP_0,
            KeyEvent.KEYCODE_NUMPAD_1 to X11.KP_1,
            KeyEvent.KEYCODE_NUMPAD_2 to X11.KP_2,
            KeyEvent.KEYCODE_NUMPAD_3 to X11.KP_3,
            KeyEvent.KEYCODE_NUMPAD_4 to X11.KP_4,
            KeyEvent.KEYCODE_NUMPAD_5 to X11.KP_5,
            KeyEvent.KEYCODE_NUMPAD_6 to X11.KP_6,
            KeyEvent.KEYCODE_NUMPAD_7 to X11.KP_7,
            KeyEvent.KEYCODE_NUMPAD_8 to X11.KP_8,
            KeyEvent.KEYCODE_NUMPAD_9 to X11.KP_9,
            KeyEvent.KEYCODE_NUMPAD_DOT to X11.KP_DEL,
            KeyEvent.KEYCODE_NUMPAD_ADD to X11.KP_ADD,
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT to X11.KP_SUBTRACT,
            KeyEvent.KEYCODE_NUMPAD_MULTIPLY to X11.KP_MULTIPLY,
            KeyEvent.KEYCODE_NUMPAD_DIVIDE to X11.KP_DIVIDE,

            KeyEvent.KEYCODE_F1 to X11.F1,
            KeyEvent.KEYCODE_F2 to X11.F2,
            KeyEvent.KEYCODE_F3 to X11.F3,
            KeyEvent.KEYCODE_F4 to X11.F4,
            KeyEvent.KEYCODE_F5 to X11.F5,
            KeyEvent.KEYCODE_F6 to X11.F6,
            KeyEvent.KEYCODE_F7 to X11.F7,
            KeyEvent.KEYCODE_F8 to X11.F8,
            KeyEvent.KEYCODE_F9 to X11.F9,
            KeyEvent.KEYCODE_F10 to X11.F10,
            KeyEvent.KEYCODE_F11 to X11.F11,
            KeyEvent.KEYCODE_F12 to X11.F12,
        )

        // Keys with no preinstalled keysym. A Bluetooth keyboard has all of
        // these physically and the vendored table has none of them, which is
        // why they carry their keysym: Print for a screenshot key that a guest
        // actually watches for, and Super because Windows software reads the
        // Windows key as a modifier of its own.
        val withKeysym = mapOf(
            KeyEvent.KEYCODE_SYSRQ to X11KeyBinding(X11.PRTSCN, Keysym.PRINT),
            KeyEvent.KEYCODE_SCROLL_LOCK to X11KeyBinding(X11.SCROLL_LOCK, Keysym.SCROLL_LOCK),
            KeyEvent.KEYCODE_NUMPAD_ENTER to X11KeyBinding(X11.KP_ENTER, Keysym.KP_ENTER),
            KeyEvent.KEYCODE_META_LEFT to X11KeyBinding(X11.SUPER_L, Keysym.SUPER_L),
            KeyEvent.KEYCODE_META_RIGHT to X11KeyBinding(X11.SUPER_R, Keysym.SUPER_R),
            KeyEvent.KEYCODE_MENU to X11KeyBinding(X11.MENU, Keysym.MENU),
        )

        val size = (entries.keys + withKeysym.keys).max() + 1
        val table = arrayOfNulls<X11KeyBinding>(size)
        entries.forEach { (android, x11) -> table[android] = X11KeyBinding(x11) }
        withKeysym.forEach { (android, binding) -> table[android] = binding }
        return table
    }
}
