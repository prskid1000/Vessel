package app.vessel.core

import app.vessel.core.params.ParamValue
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one field this feature adds to the container document, and the three
 * things it must not do to a container that never asked for it.
 *
 * A container written before this existed has to read back as "the built-in
 * default"; a container that has chosen nothing has to write nothing new; and a
 * container naming a profile that has since been deleted has to keep naming it,
 * because a profile being deleted out from under a container is ordinary rather
 * than corruption and the sheet says so in words.
 */
class ContainerInputTest {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    private fun container(input: ContainerInput = ContainerInput()) = ContainerProfile(
        id = "c1",
        name = "Display proof",
        wineBuild = "wine",
        driver = "turnip",
        d3dLayer = "dxvk",
        params = mapOf("display.resolution" to ParamValue.Text("1280x720")),
        input = input,
    )

    @Test
    fun `a container written before the field existed decodes to the default`() {
        val before = """
            {"id":"c1","name":"Display proof","wineBuild":"wine","driver":"turnip",
             "d3dLayer":"dxvk"}
        """.trimIndent()
        val profile = json.decodeFromString(ContainerProfile.serializer(), before)
        assertEquals(ContainerInput(), profile.input)
        assertNull(profile.input.profileId)
        assertFalse(profile.input.touchVisible)
        assertTrue(profile.input.isDefault)
    }

    @Test
    fun `an untouched container round-trips to the same bytes`() {
        val text = json.encodeToString(ContainerProfile.serializer(), container())
        val again = json.encodeToString(
            ContainerProfile.serializer(),
            json.decodeFromString(ContainerProfile.serializer(), text),
        )
        assertEquals(text, again)
    }

    @Test
    fun `a chosen profile is stored as its id and nothing else`() {
        val profile = container(ContainerInput(profileId = "p2", touchVisible = true))
        val text = json.encodeToString(ContainerProfile.serializer(), profile)
        assertTrue("the id should be in the document", text.contains("\"p2\""))
        // The profile itself lives in input-profiles.json. Anything of its
        // content appearing here would mean the two documents had stopped
        // failing independently, which is the whole reason there are two.
        assertFalse(text.contains("bindings"))
        assertFalse(text.contains("deadzone"))
        assertEquals(
            profile.input,
            json.decodeFromString(ContainerProfile.serializer(), text).input,
        )
    }

    @Test
    fun `a stale id stays stale`() {
        // Resolution is the repository's job and it answers the default; what is
        // asserted here is that nothing in the *container* changes as a result.
        // Rewriting it would turn "you deleted a profile" into a silent edit of
        // the container document during a launch.
        val profile = container(ContainerInput(profileId = "deleted-profile"))
        val text = json.encodeToString(ContainerProfile.serializer(), profile)
        val back = json.decodeFromString(ContainerProfile.serializer(), text)
        assertEquals("deleted-profile", back.input.profileId)
        assertEquals(profile, back)
    }

    @Test
    fun `the input field does not disturb the diagnostics field beside it`() {
        // Both are typed defaulted fields on the same document; a container
        // carrying one must still read the other.
        val profile = container(ContainerInput(profileId = "p3")).copy(
            diagnostics = ContainerDiagnostics(
                rows = listOf(DiagnosticSetting(name = "relay", level = "warn")),
            ),
        )
        val back = json.decodeFromString(
            ContainerProfile.serializer(),
            json.encodeToString(ContainerProfile.serializer(), profile),
        )
        assertEquals(profile.diagnostics, back.diagnostics)
        assertEquals(profile.input, back.input)
    }
}
