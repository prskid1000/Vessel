package app.vessel.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picker cannot offer a key the server would refuse, and cannot fail to
 * offer one the app can already produce.
 *
 * The first half is the `ArrayIndexOutOfBoundsException` in `X11Key`'s comment:
 * a keycode of 128 arrives at the vendored keyboard as −128 and throws on the X
 * client thread. The second is the reason the catalogue is built from [X11]
 * rather than typed out — a key a Bluetooth keyboard can send but a pad cannot be
 * bound to is a hole nobody would find.
 */
class X11KeyCatalogTest {

    @Test
    fun `every offered keycode is one the server can carry`() {
        X11KeyCatalog.entries.forEach { choice ->
            val action = choice.action
            if (action is GamepadAction.Key) {
                assertTrue(
                    "${choice.label} is keycode ${action.keycode}, outside 8..127",
                    action.keycode in X11.MIN_KEYCODE..X11.MAX_KEYCODE,
                )
            }
        }
    }

    @Test
    fun `every keycode the physical keyboard map can produce is offered`() {
        val offered = X11KeyCatalog.entries
            .mapNotNull { (it.action as? GamepadAction.Key)?.keycode }
            .toSet()

        X11KeyMap.mappedAndroidKeyCodes().forEach { android ->
            val binding = X11KeyMap[android]!!
            assertTrue(
                "keycode ${binding.keycode} is reachable from a keyboard but not from the catalogue",
                binding.keycode in offered,
            )
        }
    }

    @Test
    fun `all seven pointer buttons are offered`() {
        val offered = X11KeyCatalog.entries
            .mapNotNull { (it.action as? GamepadAction.Button)?.button }
            .toSet()
        assertEquals(PointerButton.entries.toSet(), offered)
    }

    @Test
    fun `the six keys with no preinstalled keysym carry the right one`() {
        fun keysymOf(keycode: Int): Int? = X11KeyCatalog.entries
            .mapNotNull { it.action as? GamepadAction.Key }
            .firstOrNull { it.keycode == keycode }
            ?.keysym

        assertEquals(Keysym.PRINT, keysymOf(X11.PRTSCN))
        assertEquals(Keysym.SCROLL_LOCK, keysymOf(X11.SCROLL_LOCK))
        assertEquals(Keysym.KP_ENTER, keysymOf(X11.KP_ENTER))
        assertEquals(Keysym.SUPER_L, keysymOf(X11.SUPER_L))
        assertEquals(Keysym.SUPER_R, keysymOf(X11.SUPER_R))
        assertEquals(Keysym.MENU, keysymOf(X11.MENU))
    }

    @Test
    fun `everything else passes keysym zero`() {
        // Sending a keysym per press makes the vendored InputDeviceManager
        // overwrite both shift levels of that keycode, so Shift+a starts
        // producing `a`. Only the six above may carry one.
        val withKeysym = X11KeyCatalog.entries
            .mapNotNull { it.action as? GamepadAction.Key }
            .filter { it.keysym != 0 }
            .map { it.keycode }
            .toSet()
        assertEquals(
            setOf(X11.PRTSCN, X11.SCROLL_LOCK, X11.KP_ENTER, X11.SUPER_L, X11.SUPER_R, X11.MENU),
            withKeysym,
        )
    }

    @Test
    fun `no two entries share a label`() {
        val duplicates = X11KeyCatalog.entries
            .groupBy { it.label }
            .filterValues { it.size > 1 }
            .keys
        assertEquals(emptySet<String>(), duplicates)
    }

    @Test
    fun `no two entries send the same thing`() {
        val duplicates = X11KeyCatalog.entries
            .groupBy { it.action }
            .filterValues { it.size > 1 }
            .keys
        assertEquals(emptySet<GamepadAction>(), duplicates)
    }

    @Test
    fun `the label a row shows is the label the picker offers`() {
        X11KeyCatalog.entries.forEach { choice ->
            assertEquals(choice.label, X11KeyCatalog.label(choice.action))
        }
        assertEquals(X11KeyCatalog.UNBOUND, X11KeyCatalog.label(GamepadAction.None))
        // A profile from a build that knew one more key reads as a number
        // rather than as unbound, which would be a lie about what it sends.
        assertEquals("Keycode 126", X11KeyCatalog.label(GamepadAction.Key(126)))
    }

    @Test
    fun `every default binding is nameable`() {
        GamepadProfile.Default.bindings.values.forEach { action ->
            assertNotNull(
                "the default profile binds something the catalogue cannot name: $action",
                X11KeyCatalog.choiceFor(action),
            )
        }
    }

    @Test
    fun `search narrows and a blank query does not`() {
        assertEquals(X11KeyCatalog.entries, X11KeyCatalog.search("   "))
        assertEquals(
            listOf("Left mouse", "Middle mouse", "Right mouse"),
            X11KeyCatalog.search("mouse").map { it.label },
        )
        assertTrue(X11KeyCatalog.search("shift").all { it.label.contains("Shift") })
        assertEquals(emptyList<KeyChoice>(), X11KeyCatalog.search("no such key"))
    }

    @Test
    fun `the grouped view is every entry, once, in declaration order`() {
        assertEquals(
            X11KeyCatalog.entries.size,
            X11KeyCatalog.groups.sumOf { it.second.size },
        )
        assertEquals(
            KeyGroup.entries.filter { group -> X11KeyCatalog.entries.any { it.group == group } },
            X11KeyCatalog.groups.map { it.first },
        )
    }
}
