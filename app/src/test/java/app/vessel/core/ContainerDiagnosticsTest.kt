package app.vessel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composer, pinned stop by stop.
 *
 * Every failure mode on this surface is silent in the same way the rest of
 * `docs/LOGGING.md` is: a `WINEDEBUG` term in the wrong order produces an empty
 * log rather than an error, and a level that emits one term where it needed two
 * produces *most* of a log, which is worse. None of that is visible on a device
 * without already suspecting it, so the exact strings are here.
 */
class ContainerDiagnosticsTest {

    private val default = ContainerDiagnostics()

    // — the default, which is the whole safety property ------------------------

    @Test
    fun `an untouched record composes exactly the fixed channel string`() {
        assertEquals(WINEDEBUG_CHANNELS, composeWineDebug(default))
    }

    @Test
    fun `an untouched record contributes no environment at all`() {
        // Not "the same values as the fixed block" — *nothing*. That is what
        // makes "a fresh container runs with today's environment" a property of
        // one line rather than of reading the fixed block and this one together.
        assertEquals(emptyMap<String, String>(), diagnosticEnvironment(default))
        assertTrue(default.isDefault)
    }

    @Test
    fun `writing a channel's own default back changes nothing`() {
        val record = DIAGNOSTIC_WINE_CHANNELS.fold(default) { acc, spec ->
            acc.withWineChannel(spec.channel, spec.defaultLevel)
        }
        assertEquals(WINEDEBUG_CHANNELS, composeWineDebug(record))
        assertEquals(emptyMap<String, String>(), diagnosticEnvironment(record))
    }

    // — the Wine ladder --------------------------------------------------------

    @Test
    fun `each stop emits the terms that force exactly its classes`() {
        // Read out of `add_option` (native/wine/dlls/ntdll/unix/debug.c:88-123):
        // a channel is created with `(default_flags & ~clear) | set` and modified
        // with the same expression, so a `+`-only term can raise a channel and
        // never lower one. The `-chan` reset is what makes a stop mean the same
        // thing whether or not the fixed prefix already named the channel.
        assertEquals(listOf("-file"), wineChannelTerms("file", WineChannelLevel.OFF))
        assertEquals(
            listOf("-file", "err+file"),
            wineChannelTerms("file", WineChannelLevel.ERRORS),
        )
        assertEquals(
            listOf("-file", "err+file", "warn+file"),
            wineChannelTerms("file", WineChannelLevel.WARNINGS),
        )
        assertEquals(
            listOf("-file", "err+file", "warn+file", "fixme+file"),
            wineChannelTerms("file", WineChannelLevel.STUBS),
        )
        assertEquals(listOf("+file"), wineChannelTerms("file", WineChannelLevel.EVERYTHING))
    }

    @Test
    fun `the stubs stop names warn as well, because inheritance is from the defaults`() {
        // A lone `fixme+x` gives ERR|FIXME and skips WARN — the new channel is
        // seeded from `default_flags`, not from the stop below it. This is the
        // one non-obvious rule in the whole composer.
        val terms = wineChannelTerms("file", WineChannelLevel.STUBS)
        assertTrue("warn+file" in terms)
        assertTrue("fixme+file" in terms)
    }

    @Test
    fun `every stop of every channel leaves minus-all first and the prefix intact`() {
        for (spec in DIAGNOSTIC_WINE_CHANNELS) {
            for (level in spec.levels) {
                val composed = composeWineDebug(
                    default.withWineChannel(spec.channel, level).copy(
                        // Arm everything, so the dangerous stops are actually
                        // composed rather than reverted by `inForce`.
                        armed = DangerousControl.entries.map { it.id }.toSet(),
                    ),
                )
                val terms = composed.split(",")
                assertEquals("$spec at $level", "-all", terms.first())
                assertTrue(
                    "$spec at $level dropped the fixed prefix",
                    composed.startsWith(WINEDEBUG_CHANNELS),
                )
                assertFalse("`+err` is a class name, not a channel", "+err" in terms)
                assertFalse("+warn" in terms)
                assertFalse("+fixme" in terms)
                // Only ever one trailing `-all`, and never one Vessel wrote: a
                // trailing `-all` erases every term before it.
                assertEquals(1, terms.count { it == "-all" })
            }
        }
    }

    @Test
    fun `lowering a channel the fixed prefix already set actually lowers it`() {
        // `loaddll` is `+loaddll` in the prefix, so it is already at all four
        // classes. A `warn+loaddll` would leave it there; the reset is what makes
        // the row's "Errors" stop mean errors.
        val record = default.withWineChannel("loaddll", WineChannelLevel.ERRORS)
        assertEquals(
            "$WINEDEBUG_CHANNELS,-loaddll,err+loaddll",
            composeWineDebug(record),
        )
    }

    @Test
    fun `d3d stops at warnings, and the rest of the ladder is not offered`() {
        val spec = wineChannelSpec(D3D_CHANNEL)!!
        assertEquals(
            listOf(
                WineChannelLevel.OFF,
                WineChannelLevel.ERRORS,
                WineChannelLevel.WARNINGS,
            ),
            spec.levels,
        )
        assertNotNull("the 659-site caution has to be on the row", spec.caution)
    }

    // — the raw escape hatch ---------------------------------------------------

    @Test
    fun `raw terms are appended after everything, so a later term wins`() {
        val record = default
            .withWineChannel("file", WineChannelLevel.EVERYTHING)
            .withRawTerms(" metro.exe:+winmm , -file ")
        assertEquals(
            "$WINEDEBUG_CHANNELS,+file,metro.exe:+winmm,-file",
            composeWineDebug(record),
        )
    }

    @Test
    fun `help is refused, because Wine answers it by exiting`() {
        // `init_options` compares the whole variable against "help" and calls
        // `debug_usage()`, which writes to fd 2 and exit(1) — debug.c:183-193,213.
        val issue = rawTermsIssue("help")
        assertNotNull(issue)
        assertTrue(issue!!.blocking)
        // And the terms never reach the string.
        assertEquals(WINEDEBUG_CHANNELS, composeWineDebug(default.withRawTerms("help")))
    }

    @Test
    fun `a leading minus-all is warned about but still sent`() {
        val issue = rawTermsIssue("-all,+relay")
        assertNotNull(issue)
        assertFalse("it is legal, and occasionally meant", issue!!.blocking)
        assertEquals(
            "$WINEDEBUG_CHANNELS,-all,+relay",
            composeWineDebug(default.withRawTerms("-all,+relay")),
        )
    }

    @Test
    fun `an empty raw field is not an issue and adds nothing`() {
        assertNull(rawTermsIssue(""))
        assertNull(rawTermsIssue("   "))
        assertEquals(WINEDEBUG_CHANNELS, composeWineDebug(default.withRawTerms("   ")))
    }

    // — the other subsystems ---------------------------------------------------

    @Test
    fun `DXVK and vkd3d keep their own words in their own order`() {
        assertEquals(
            listOf("none", "error", "warn", "info", "debug", "trace"),
            DxvkLogLevel.entries.map { it.wire },
        )
        // vkd3d's `info` sits between `err` and `fixme`, so `warn` already
        // carries both. Normalising this to DXVK's order would be a screen that
        // lies about what it sets.
        assertEquals(
            listOf("none", "err", "info", "fixme", "warn", "trace"),
            Vkd3dLogLevel.entries.map { it.wire },
        )
    }

    @Test
    fun `each subsystem writes only its own variable`() {
        assertEquals(
            mapOf("DXVK_LOG_LEVEL" to "error"),
            diagnosticEnvironment(default.withDxvkLevel(DxvkLogLevel.ERROR)),
        )
        assertEquals(
            mapOf("VKD3D_DEBUG" to "info"),
            diagnosticEnvironment(default.withVkd3dLevel(Vkd3dLogLevel.INFO)),
        )
        assertEquals(
            mapOf("VKD3D_SHADER_DEBUG" to "fixme"),
            diagnosticEnvironment(default.withVkd3dShaderLevel(Vkd3dLogLevel.FIXME)),
        )
    }

    @Test
    fun `FEX_SILENTLOG is the inverse of the control, and only appears when off`() {
        assertFalse(diagnosticEnvironment(default).containsKey("FEX_SILENTLOG"))
        assertEquals(
            mapOf("FEX_SILENTLOG" to "1"),
            diagnosticEnvironment(default.withFexMessages(false)),
        )
    }

    @Test
    fun `the driver logger is only named when it is asked for`() {
        assertFalse(diagnosticEnvironment(default).containsKey("MESA_LOG"))
        assertEquals(
            mapOf("MESA_LOG" to "file"),
            diagnosticEnvironment(default.withDriverMessagesInLog(true)),
        )
    }

    @Test
    fun `no record can reach TU_DEBUG or the two variables reserved for their absence`() {
        val everything = everythingOn()
        val keys = diagnosticEnvironment(everything).keys
        // TU_DEBUG is absent because there is no Turnip control: its output goes
        // to logcat, which this product does not read.
        assertFalse("TU_DEBUG" in keys)
        assertFalse("VKD3D_LOG_FILE" in keys)
        assertFalse("MESA_VK_WSI_DEBUG" in keys)
        assertFalse("DXVK_LOG_PATH" in keys)
    }

    // — the one-launch tier ----------------------------------------------------

    @Test
    fun `switching a firehose on arms it, and switching it off disarms it`() {
        val armed = default.withWineChannel(RELAY_CHANNEL, WineChannelLevel.EVERYTHING)
        assertEquals(setOf(DangerousControl.WINE_RELAY.id), armed.armed)
        assertTrue(composeWineDebug(armed).endsWith(",+relay"))

        val off = armed.withWineChannel(RELAY_CHANNEL, WineChannelLevel.ERRORS)
        assertEquals(emptySet<String>(), off.armed)
        assertEquals(WINEDEBUG_CHANNELS, composeWineDebug(off))
    }

    @Test
    fun `one session spends it, and the next one is ordinary`() {
        val armed = default
            .withWineChannel(SEH_CHANNEL, WineChannelLevel.EVERYTHING)
            .withDxvkLevel(DxvkLogLevel.TRACE)
        assertTrue(composeWineDebug(armed).endsWith(",+seh"))
        assertEquals("trace", diagnosticEnvironment(armed)["DXVK_LOG_LEVEL"])

        val spent = armed.consumed()
        assertEquals(WINEDEBUG_CHANNELS, composeWineDebug(spent))
        assertEquals(emptyMap<String, String>(), diagnosticEnvironment(spent))
        assertTrue(spent.isDefault)
    }

    @Test
    fun `a dangerous value with no arm cannot take effect at all`() {
        // The state a hand-edited or half-migrated document could be in. Every
        // read path goes through `inForce`, so an unarmed firehose is unreachable
        // rather than merely unlikely.
        val forged = ContainerDiagnostics(
            wineChannels = mapOf(RELAY_CHANNEL to WineChannelLevel.EVERYTHING),
            dxvkLevel = DxvkLogLevel.TRACE,
            armed = emptySet(),
        )
        assertEquals(WINEDEBUG_CHANNELS, composeWineDebug(forged))
        assertEquals(emptyMap<String, String>(), diagnosticEnvironment(forged))
    }

    @Test
    fun `every dangerous control declares how to put itself back`() {
        for (control in DangerousControl.entries) {
            val armed = everythingOn()
            assertTrue("${control.id} should be dangerous here", control.isDangerous(armed))
            assertFalse(
                "${control.id} did not disarm itself",
                control.isDangerous(control.disarm(armed)),
            )
            // The three concrete things the copy has to say, in whatever words.
            assertTrue(
                "${control.id}'s warning does not say it turns itself off",
                control.warning.contains("after the next launch"),
            )
            assertTrue(
                "${control.id}'s warning does not say the session gets slower",
                control.warning.contains("slower"),
            )
        }
    }

    // — the log caps -----------------------------------------------------------

    @Test
    fun `the caps default to the top of every ladder`() {
        val limits = SessionLogLimits()
        assertEquals(SessionLogLimits.HEAD_LADDER.last(), limits.headBytes)
        assertEquals(SessionLogLimits.TAIL_LADDER.last(), limits.tailBytes)
        assertEquals(SessionLogLimits.RATE_LADDER.last(), limits.rateLimitLines)
        // Two retained segments make up the tail, so one is half of it.
        assertEquals(limits.tailBytes / 2, limits.tailSegmentBytes)
        // The number the screen has to show, stated once here so a change to a
        // ladder cannot move it silently.
        assertEquals(48L * 1024 * 1024, limits.worstCaseBytesPerSession)
        assertEquals(480L * 1024 * 1024, limits.worstCaseBytesPerContainer)
    }

    @Test
    fun `the first rung of every ladder is what shipped before this was a setting`() {
        assertEquals(5L * 1024 * 1024, SessionLogLimits.SHIPPED.headBytes)
        // 1536 KB a segment, two segments.
        assertEquals(1536L * 1024, SessionLogLimits.SHIPPED.tailSegmentBytes)
        assertEquals(2_000, SessionLogLimits.SHIPPED.rateLimitLines)
    }

    /** Every control at its loudest, armed — the widest a record can be. */
    private fun everythingOn(): ContainerDiagnostics = DIAGNOSTIC_WINE_CHANNELS
        .fold(ContainerDiagnostics()) { acc, spec -> acc.withWineChannel(spec.channel, spec.maxLevel) }
        .withDxvkLevel(DxvkLogLevel.TRACE)
        .withVkd3dLevel(Vkd3dLogLevel.TRACE)
        .withVkd3dShaderLevel(Vkd3dLogLevel.TRACE)
        .withFexMessages(false)
        .withDriverMessagesInLog(true)
        .withRawTerms("+winmm")
}
