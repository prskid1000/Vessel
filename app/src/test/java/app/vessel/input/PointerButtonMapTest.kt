package app.vessel.input

import com.winlator.xserver.Pointer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [PointerButton] against the vendored enum it is a copy of.
 *
 * `GuestInputSink` crosses between the two **by ordinal**, because the names
 * disagree on the last two: X11 calls buttons 6 and 7 horizontal scroll and
 * upstream calls them `SCROLL_CLICK_LEFT`/`RIGHT`. An ordinal crossing is a
 * silent wrong answer when the orders drift, so the orders are pinned here.
 */
class PointerButtonMapTest {

    @Test
    fun `the two enums are the same buttons in the same order`() {
        assertEquals(Pointer.Button.entries.size, PointerButton.entries.size)
        PointerButton.entries.forEach {
            assertEquals(
                "${it.name} is at a different index than ${Pointer.Button.entries[it.ordinal]}",
                it.ordinal,
                Pointer.Button.entries[it.ordinal].ordinal,
            )
        }
    }

    @Test
    fun `the x11 code is the ordinal plus one`() {
        // X numbers buttons from 1, and that is the whole relationship. Stating
        // it separately from the table means a hand-edited x11Code cannot quietly
        // disagree with the position the sink actually uses.
        PointerButton.entries.forEach { assertEquals(it.ordinal + 1, it.x11Code) }
        assertEquals(1, PointerButton.LEFT.x11Code)
        assertEquals(4, PointerButton.SCROLL_UP.x11Code)
        assertEquals(5, PointerButton.SCROLL_DOWN.x11Code)
    }

    @Test
    fun `a scroll becomes the press and release pairs X11 carries it as`() {
        assertEquals(
            listOf(
                GuestInput.Button(PointerButton.SCROLL_UP, pressed = true),
                GuestInput.Button(PointerButton.SCROLL_UP, pressed = false),
                GuestInput.Button(PointerButton.SCROLL_UP, pressed = true),
                GuestInput.Button(PointerButton.SCROLL_UP, pressed = false),
            ),
            GuestInput.Scroll(ScrollAxis.VERTICAL, 2).buttons(),
        )

        assertEquals(
            listOf(
                GuestInput.Button(PointerButton.SCROLL_DOWN, pressed = true),
                GuestInput.Button(PointerButton.SCROLL_DOWN, pressed = false),
            ),
            GuestInput.Scroll(ScrollAxis.VERTICAL, -1).buttons(),
        )

        assertEquals(
            listOf(
                GuestInput.Button(PointerButton.SCROLL_LEFT, pressed = true),
                GuestInput.Button(PointerButton.SCROLL_LEFT, pressed = false),
            ),
            GuestInput.Scroll(ScrollAxis.HORIZONTAL, -1).buttons(),
        )

        assertEquals(emptyList<GuestInput.Button>(), GuestInput.Scroll(ScrollAxis.VERTICAL, 0).buttons())
    }
}
