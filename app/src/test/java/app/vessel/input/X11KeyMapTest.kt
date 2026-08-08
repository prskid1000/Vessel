package app.vessel.input

import android.view.KeyEvent
import com.winlator.xserver.Keyboard
import com.winlator.xserver.XKeycode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keycode table against the server it has to agree with.
 *
 * [X11] is a transcription of `XKeycode` — see the comment on it — and a
 * transcription is only safe if something fails when the two drift. That is what
 * this is. It costs nothing at runtime and it is the reason `app.vessel.input`
 * is allowed to stay free of `com.winlator`.
 *
 * Everything here is pure Java or a compile-time constant, so no Android runtime
 * and no `libwinlator` is involved: `XKeycode` and `Pointer.Button` are plain
 * enums, and `KeyEvent.KEYCODE_*` and `Keyboard.KEYS_COUNT` are `static final`
 * ints that the compiler inlines.
 */
class X11KeyMapTest {

    /**
     * Every `X11` constant that names a key, by its Kotlin name.
     *
     * `isSynthetic` is not enough on its own — the Compose compiler adds a
     * perfectly ordinary `public static final int $stable` to every object it
     * sees, and it would otherwise be read as a keycode of 0.
     */
    private val x11Keys: Map<String, Int> =
        X11::class.java.declaredFields
            .filter { it.type == Int::class.javaPrimitiveType && !it.isSynthetic }
            .associate { it.name to it.getInt(null) }
            .filterKeys { it != "MIN_KEYCODE" && it != "MAX_KEYCODE" && !it.startsWith("$") }

    @Test
    fun `every keycode survives the byte the server addresses keys with`() {
        // The whole reason MAX_KEYCODE is 127. XKeycode.id, Keyboard.setKeysyms,
        // hasKeysym and onKeyPress are all byte-typed, and each does
        // `keycode - MIN_KEYCODE` signed: 128 arrives as -128 and indexes the
        // keysyms array at -272.
        x11Keys.forEach { (name, code) ->
            assertTrue(
                "$name = $code cannot round-trip a signed byte",
                code == code.toByte().toInt(),
            )
            assertTrue("$name = $code is below MIN_KEYCODE", code >= X11.MIN_KEYCODE)
            assertTrue("$name = $code is above MAX_KEYCODE", code <= X11.MAX_KEYCODE)
        }
    }

    @Test
    fun `MAX_KEYCODE is the byte ceiling and the keysyms array can hold it`() {
        assertEquals(127, X11.MAX_KEYCODE)
        assertEquals(Keyboard.MIN_KEYCODE.toInt(), X11.MIN_KEYCODE)

        val slotsNeeded = (X11.MAX_KEYCODE - X11.MIN_KEYCODE + 1) * Keyboard.KEYSYMS_PER_KEYCODE
        assertTrue(
            "keysyms holds ${Keyboard.KEYS_COUNT} and $slotsNeeded are needed",
            slotsNeeded <= Keyboard.KEYS_COUNT,
        )
    }

    @Test
    fun `the transcribed keycodes match the vendored table`() {
        val vendored = XKeycode.entries.associate { it.name to it.id.toInt() }
        var compared = 0
        x11Keys.forEach { (name, code) ->
            // N1..N0 are `KEY_1`..`KEY_0` upstream; everything else is the same
            // name behind `KEY_`. A name with no counterpart is either one of the
            // three Vessel additions or a key upstream does not have, and the
            // next test is what covers those.
            val upstreamName = if (name.length == 2 && name[0] == 'N' && name[1].isDigit()) {
                "KEY_${name[1]}"
            } else {
                "KEY_$name"
            }
            val upstream = vendored[upstreamName] ?: return@forEach
            assertEquals("$name disagrees with $upstreamName", upstream, code)
            compared++
        }
        // A rename upstream would otherwise silently reduce this to zero
        // comparisons and the test would still pass. 100 is what the two tables
        // currently share; the three Vessel additions are covered by the next
        // test, and the rest are keys upstream has and this map does not bind.
        assertEquals("the set of shared keycodes changed", 100, compared)
    }

    @Test
    fun `the three Vessel keycodes collide with nothing upstream`() {
        val vendored = XKeycode.entries.map { it.id.toInt() }.toSet()
        listOf("SUPER_L" to X11.SUPER_L, "SUPER_R" to X11.SUPER_R, "MENU" to X11.MENU)
            .forEach { (name, code) ->
                assertTrue("$name = $code is already an XKeycode", code !in vendored)
            }
        // And they are distinct from each other, which a copy-paste would break
        // without any other test noticing.
        assertEquals(3, setOf(X11.SUPER_L, X11.SUPER_R, X11.MENU).size)
    }

    @Test
    fun `the map binds the keys a physical keyboard has`() {
        assertEquals(X11.A, X11KeyMap[KeyEvent.KEYCODE_A]?.keycode)
        assertEquals(X11.N1, X11KeyMap[KeyEvent.KEYCODE_1]?.keycode)
        assertEquals(X11.F12, X11KeyMap[KeyEvent.KEYCODE_F12]?.keycode)
        assertEquals(X11.SHIFT_L, X11KeyMap[KeyEvent.KEYCODE_SHIFT_LEFT]?.keycode)
        assertNotNull("Super is why the three extra keycodes exist", X11KeyMap[KeyEvent.KEYCODE_META_LEFT])
    }

    @Test
    fun `keys upstream has no keysym for carry one`() {
        // Everything else must pass 0: a keysym per press makes
        // InputDeviceManager.onKeyPress overwrite both shift levels of that
        // keycode with the same value, so Shift+a starts producing `a`.
        val withKeysym = X11KeyMap.mappedAndroidKeyCodes()
            .mapNotNull { X11KeyMap[it] }
            .filter { it.keysym != 0 }
            .map { it.keycode }
            .toSet()
        assertEquals(
            setOf(X11.PRTSCN, X11.SCROLL_LOCK, X11.KP_ENTER, X11.SUPER_L, X11.SUPER_R, X11.MENU),
            withKeysym,
        )
    }

    @Test
    fun `back is not bound, so the rail can never be swallowed`() {
        assertNull(X11KeyMap[KeyEvent.KEYCODE_BACK])
    }

    @Test
    fun `a held key repeats as a release and a press`() {
        val binding = X11KeyBinding(X11.BKSP)

        assertEquals(
            listOf(GuestInput.Key(X11.BKSP, 0, pressed = true)),
            X11KeyMap.edgesForDown(binding, repeat = false),
        )

        // Keyboard.setKeyPress drops a press for a key it already holds, so a
        // bare repeat would delete exactly one character however long backspace
        // is held.
        assertEquals(
            listOf(
                GuestInput.Key(X11.BKSP, 0, pressed = false),
                GuestInput.Key(X11.BKSP, 0, pressed = true),
            ),
            X11KeyMap.edgesForDown(binding, repeat = true),
        )
    }

    @Test
    fun `every binding is inside the array the server will index with it`() {
        X11KeyMap.mappedAndroidKeyCodes().forEach { android ->
            val code = X11KeyMap[android]!!.keycode
            val index = (code.toByte() - Keyboard.MIN_KEYCODE) * Keyboard.KEYSYMS_PER_KEYCODE
            assertTrue("android $android maps to $code, which indexes $index", index >= 0)
            assertTrue(
                "android $android maps to $code, which indexes past ${Keyboard.KEYS_COUNT}",
                index + 1 < Keyboard.KEYS_COUNT,
            )
        }
    }
}
