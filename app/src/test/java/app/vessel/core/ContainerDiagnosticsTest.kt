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
    fun `a fresh container has added nothing at all`() {
        // The baseline is invisible and is not a setting: there are no rows to
        // start with, so there is nothing to accidentally leave at a default that
        // is not today's behaviour.
        assertTrue(default.isDefault)
        assertEquals(emptyList<WineChannelSetting>(), default.wineChannels)
        assertEquals(emptyMap<String, String>(), default.subsystemLevels)
        assertEquals(emptyMap<String, Boolean>(), default.subsystemFlags)
    }

    @Test
    fun `an untouched record composes exactly the fixed channel string`() {
        assertEquals(WINEDEBUG_CHANNELS, composeWineDebug(default))
    }

    @Test
    fun `an untouched record contributes no environment at all`() {
        // Not "the same values as the fixed block" — *nothing*. That is what
        // makes "a fresh container runs with today's environment" a property of
        // one line rather than of reading two blocks together.
        assertEquals(emptyMap<String, String>(), diagnosticEnvironment(default))
    }

    @Test
    fun `setting a subsystem back to its own default drops the entry`() {
        val spec = subsystemLevel("dxvk")!!
        val moved = default.withSubsystemLevel(spec.id, "warn")
        assertEquals(mapOf("DXVK_LOG_LEVEL" to "warn"), diagnosticEnvironment(moved))

        val back = moved.withSubsystemLevel(spec.id, spec.default)
        assertTrue(back.subsystemLevels.isEmpty())
        assertEquals(emptyMap<String, String>(), diagnosticEnvironment(back))
    }

    // — the Wine ladder --------------------------------------------------------

    @Test
    fun `each stop emits the terms that force exactly its classes`() {
        // Read out of `add_option` (native/wine/dlls/ntdll/unix/debug.c:88-123):
        // a channel is created with `(default_flags & ~clear) | set` and modified
        // with the same expression, so a `+`-only term can raise a channel and
        // never lower one. The `-chan` reset is what makes a stop mean the same
        // thing whether or not the invisible baseline already named the channel.
        assertEquals(listOf("-file"), wineChannelTerms("file", WineChannelLevel.OFF))
        assertEquals(listOf("-file", "err+file"), wineChannelTerms("file", WineChannelLevel.ERRORS))
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
    fun `every stop of every catalogue channel leaves minus-all first and the baseline intact`() {
        for (info in WINE_CHANNEL_CATALOGUE) {
            for (level in info.levels) {
                val record = default
                    .withChannelAdded(info.channel)
                    .withChannelLevel(info.channel, level)
                    // Armed, so a one-session stop is actually composed rather
                    // than reverted by `inForce`.
                    .let { it.copy(armed = it.oneSessionIds()) }
                val composed = composeWineDebug(record)
                val terms = composed.split(",")
                assertEquals("${info.channel} at $level", "-all", terms.first())
                assertTrue(
                    "${info.channel} at $level dropped the baseline",
                    composed.startsWith(WINEDEBUG_CHANNELS),
                )
                assertFalse("`+err` is a class name, not a channel", "+err" in terms)
                assertFalse("+warn" in terms)
                assertFalse("+fixme" in terms)
                // Never a second `-all`: a later one erases every term before it.
                assertEquals(1, terms.count { it == "-all" })
            }
        }
    }

    @Test
    fun `lowering a channel the baseline already set actually lowers it`() {
        // `loaddll` is `+loaddll` in the baseline, so it is already at all four
        // classes. A `warn+loaddll` would leave it there; the reset is what makes
        // the row's "Errors" stop mean errors.
        val record = default
            .withChannelAdded("loaddll")
            .withChannelLevel("loaddll", WineChannelLevel.ERRORS)
        assertEquals("$WINEDEBUG_CHANNELS,-loaddll,err+loaddll", composeWineDebug(record))
    }

    @Test
    fun `rows are written in the order they were added`() {
        val record = default
            .withChannelAdded("file")
            .withChannelAdded("reg")
        assertEquals(listOf("file", "reg"), record.wineChannels.map { it.channel })
        assertEquals(
            "$WINEDEBUG_CHANNELS,-file,err+file,warn+file,-reg,err+reg,warn+reg",
            composeWineDebug(record),
        )
    }

    @Test
    fun `adding the same channel twice does nothing the second time`() {
        val once = default.withChannelAdded("file")
        assertEquals(once, once.withChannelAdded("file"))
    }

    @Test
    fun `removing a row reverts the channel rather than silencing it`() {
        // Two different things, and the distinction is real: `Off` writes `-chan`
        // and stops even the errors `err+all` gives every channel; removing the
        // row hands the channel back to the invisible baseline.
        val off = default.withChannelAdded("file").withChannelLevel("file", WineChannelLevel.OFF)
        assertEquals("$WINEDEBUG_CHANNELS,-file", composeWineDebug(off))
        assertEquals(WINEDEBUG_CHANNELS, composeWineDebug(off.withChannelRemoved("file")))
    }

    @Test
    fun `a channel nobody anticipated gets a row, the full ladder and no arm`() {
        val info = wineChannelInfo("winmm")
        assertEquals("winmm", info.channel)
        assertEquals(WineChannelLevel.entries, info.levels)
        assertNull("nothing is known about what it costs", info.oneSessionAt)
        assertNull(info.caution)

        val record = default.withChannelAdded("winmm")
        assertEquals(
            "$WINEDEBUG_CHANNELS,-winmm,err+winmm,warn+winmm",
            composeWineDebug(record),
        )
    }

    @Test
    fun `d3d stops at warnings, and the rest of the ladder is not offered`() {
        val info = wineChannelInfo("d3d")
        assertEquals(
            listOf(WineChannelLevel.OFF, WineChannelLevel.ERRORS, WineChannelLevel.WARNINGS),
            info.levels,
        )
        assertNotNull("the 659-site caution travels with the entry", info.caution)
    }

    @Test
    fun `every catalogue entry can be described and every loud one is marked`() {
        for (info in WINE_CHANNEL_CATALOGUE) {
            assertTrue("${info.channel} has no summary", info.summary.isNotBlank())
            // A channel that is spent after one launch has to say why, or the
            // confirmation is a scary-sounding dialog with nothing in it.
            if (info.oneSessionAt != null) {
                assertNotNull("${info.channel} is one-session with no caution", info.caution)
            }
            assertTrue(info.addAt.ordinal <= info.maxLevel.ordinal)
        }
        // Names are unique, or the picker offers a duplicate and `withChannelAdded`
        // silently refuses it.
        assertEquals(
            WINE_CHANNEL_CATALOGUE.size,
            WINE_CHANNEL_CATALOGUE.map { it.channel }.toSet().size,
        )
    }

    // — what may be typed into the picker --------------------------------------

    @Test
    fun `a channel name is one word, and the parser's punctuation is refused`() {
        // The typed field is the only way into this list the catalogue does not
        // control, so what it accepts is what Wine would register.
        assertTrue(isChannelName("winmm"))
        assertTrue(isChannelName("d3d11"))
        // `parse_options` reads these as structure, so a name containing one is
        // several terms rather than one channel.
        assertFalse(isChannelName("+relay,-heap"))
        assertFalse(isChannelName("-heap"))
        assertFalse(isChannelName("metro.exe:+relay"))
        assertFalse(isChannelName("two words"))
        assertFalse(isChannelName(""))
        // `add_option` drops a name that reaches `char name[15]`, and says
        // nothing — a row that logs nothing is the failure worth refusing.
        assertTrue(isChannelName("a".repeat(14)))
        assertFalse(isChannelName("a".repeat(15)))
        // Everything the catalogue offers has to pass its own check.
        WINE_CHANNEL_CATALOGUE.forEach {
            assertTrue("${it.channel} is not a name Wine would register", isChannelName(it.channel))
        }
    }

    @Test
    fun `a name the parser would not register never becomes a row`() {
        assertEquals(default, default.withChannelAdded("+relay,-heap"))
        assertEquals(default, default.withChannelAdded("  "))
        assertEquals(WINEDEBUG_CHANNELS, composeWineDebug(default.withChannelAdded("metro.exe:+relay")))
    }

    @Test
    fun `silencing what the baseline switched on survives without a raw field`() {
        // The raw `WINEDEBUG` box is gone; `-heap` and friends are the picker's
        // Off stop, which composes to exactly that. Per-program scoping is the
        // one thing that went with it — see composeWineDebug.
        val record = default
            .withChannelAdded("heap")
            .withChannelLevel("heap", WineChannelLevel.OFF)
        assertEquals("$WINEDEBUG_CHANNELS,-heap", composeWineDebug(record))
    }

    // — the other subsystems ---------------------------------------------------

    @Test
    fun `DXVK and vkd3d keep their own words in their own order`() {
        assertEquals(
            listOf("none", "error", "warn", "info", "debug", "trace"),
            subsystemLevel("dxvk")!!.options,
        )
        // vkd3d's `info` sits between `err` and `fixme`, so `warn` already
        // carries both. Normalising this to DXVK's order would be a screen that
        // lies about what it sets.
        assertEquals(
            listOf("none", "err", "info", "fixme", "warn", "trace"),
            subsystemLevel("vkd3d")!!.options,
        )
        assertEquals(
            subsystemLevel("vkd3d")!!.options,
            subsystemLevel("vkd3d.shader")!!.options,
        )
        // Two channels, two variables, independent levels.
        assertEquals("VKD3D_DEBUG", subsystemLevel("vkd3d")!!.variable)
        assertEquals("VKD3D_SHADER_DEBUG", subsystemLevel("vkd3d.shader")!!.variable)
    }

    @Test
    fun `every declared level and flag is coherent`() {
        for (spec in SUBSYSTEM_LEVELS) {
            assertTrue("${spec.id}'s default is not one of its options", spec.default in spec.options)
            spec.oneSessionFrom?.let {
                assertTrue("${spec.id}'s one-session stop is not an option", it in spec.options)
                assertFalse("${spec.id} is one-session at its own default", spec.isOneSession(spec.default))
            }
        }
        for (spec in SUBSYSTEM_FLAGS) {
            // A switch whose default state also writes something would put a
            // value in the map for a container nobody has touched.
            assertNull("${spec.id} writes something at its own default", spec.valueAt(spec.default))
            assertNotNull("${spec.id} does nothing at all", spec.valueAt(!spec.default))
        }
    }

    @Test
    fun `each subsystem writes only its own variable`() {
        assertEquals(
            mapOf("DXVK_LOG_LEVEL" to "error"),
            diagnosticEnvironment(default.withSubsystemLevel("dxvk", "error")),
        )
        assertEquals(
            mapOf("VKD3D_DEBUG" to "info"),
            diagnosticEnvironment(default.withSubsystemLevel("vkd3d", "info")),
        )
        assertEquals(
            mapOf("VKD3D_SHADER_DEBUG" to "fixme"),
            diagnosticEnvironment(default.withSubsystemLevel("vkd3d.shader", "fixme")),
        )
    }

    @Test
    fun `FEX_SILENTLOG is the inverse of the control, and only appears when off`() {
        assertFalse(diagnosticEnvironment(default).containsKey("FEX_SILENTLOG"))
        assertEquals(
            mapOf("FEX_SILENTLOG" to "1"),
            diagnosticEnvironment(default.withSubsystemFlag(FEX_MESSAGES_FLAG.id, false)),
        )
    }

    @Test
    fun `the driver logger is only named when it is asked for`() {
        assertFalse(diagnosticEnvironment(default).containsKey("MESA_LOG"))
        assertEquals(
            mapOf("MESA_LOG" to "file"),
            diagnosticEnvironment(default.withSubsystemFlag(DRIVER_LOG_FLAG.id, true)),
        )
    }

    @Test
    fun `Turnip flags are unreachable until the driver logger is on`() {
        // Enforced in the record and not only in the UI, so a hand-edited
        // document cannot arrange for a switch whose output nothing can read.
        val forged = default.copy(turnipFlags = listOf("perf"))
        assertFalse(diagnosticEnvironment(forged).containsKey("TU_DEBUG"))

        val allowed = default
            .withSubsystemFlag(DRIVER_LOG_FLAG.id, true)
            .withTurnipFlag("perf", true)
        // `startup` stays last: it is the ground truth for whether Turnip loaded
        // and nothing may displace it.
        assertEquals("perf,startup", diagnosticEnvironment(allowed)["TU_DEBUG"])
    }

    @Test
    fun `Turnip flags keep catalogue order however they were clicked`() {
        val record = default
            .withSubsystemFlag(DRIVER_LOG_FLAG.id, true)
            .withTurnipFlag("noubwc", true)
            .withTurnipFlag("perf", true)
        assertEquals(listOf("perf", "noubwc"), record.turnipFlags)
    }

    @Test
    fun `the manifest's own Turnip flags are added to, not replaced`() {
        val allowed = default
            .withSubsystemFlag(DRIVER_LOG_FLAG.id, true)
            .withTurnipFlag("perf", true)
        assertEquals(
            "perf,sysmem,startup",
            diagnosticEnvironment(allowed, listOf("sysmem", TU_DEBUG_STARTUP))["TU_DEBUG"],
        )
    }

    @Test
    fun `no record can reach the two variables reserved for their absence`() {
        val keys = diagnosticEnvironment(everythingOn()).keys
        assertFalse("VKD3D_LOG_FILE" in keys)
        assertFalse("MESA_VK_WSI_DEBUG" in keys)
        assertFalse("DXVK_LOG_PATH" in keys)
    }

    // — the one-session tier ---------------------------------------------------

    @Test
    fun `adding a firehose arms it, and removing it disarms it`() {
        val armed = default.withChannelAdded("relay")
        assertEquals(setOf(oneSessionId("wine", "relay")), armed.armed)
        assertTrue(composeWineDebug(armed).endsWith(",+relay"))

        val gone = armed.withChannelRemoved("relay")
        assertEquals(emptySet<String>(), gone.armed)
        assertEquals(WINEDEBUG_CHANNELS, composeWineDebug(gone))
    }

    @Test
    fun `a quiet stop on a loud channel is not armed`() {
        // `relay` is only a firehose at its top stop; below that it is silence,
        // not a quieter version of itself.
        val quiet = default
            .withChannelAdded("relay")
            .withChannelLevel("relay", WineChannelLevel.OFF)
        assertEquals(emptySet<String>(), quiet.armed)
        assertEquals("$WINEDEBUG_CHANNELS,-relay", composeWineDebug(quiet))
    }

    @Test
    fun `one session spends it, and the next one is ordinary`() {
        val armed = default
            .withChannelAdded("seh")
            .withSubsystemLevel("dxvk", "trace")
        assertTrue(composeWineDebug(armed).endsWith(",+seh"))
        assertEquals("trace", diagnosticEnvironment(armed)["DXVK_LOG_LEVEL"])

        val spent = armed.consumed()
        assertEquals(WINEDEBUG_CHANNELS, composeWineDebug(spent))
        assertEquals(emptyMap<String, String>(), diagnosticEnvironment(spent))
        assertTrue(spent.isDefault)
    }

    @Test
    fun `spending a firehose leaves the quiet rows alone`() {
        val record = default
            .withChannelAdded("file")
            .withChannelAdded("relay")
            .withSubsystemLevel("vkd3d", "info")
        val spent = record.consumed()
        assertEquals(listOf("file"), spent.wineChannels.map { it.channel })
        assertEquals("info", diagnosticEnvironment(spent)["VKD3D_DEBUG"])
    }

    @Test
    fun `a loud value with no arm cannot take effect at all`() {
        // The state a hand-edited or half-migrated document could be in. Every
        // read path goes through `inForce`, so an unarmed firehose is unreachable
        // rather than merely unlikely.
        val forged = ContainerDiagnostics(
            wineChannels = listOf(WineChannelSetting("relay", WineChannelLevel.EVERYTHING)),
            subsystemLevels = mapOf("dxvk" to "trace"),
            armed = emptySet(),
        )
        assertEquals(WINEDEBUG_CHANNELS, composeWineDebug(forged))
        assertEquals(emptyMap<String, String>(), diagnosticEnvironment(forged))
    }

    @Test
    fun `the warning says the three concrete things`() {
        val message = oneSessionWarning(wineChannelInfo("relay").caution)
        assertTrue(message.contains("Hundreds of megabytes"))
        assertTrue("it has to say the log fills", message.contains("cap in seconds"))
        assertTrue("it has to say the session slows", message.contains("slower"))
        assertTrue("naming the mechanism is what stops it being dismissed",
            message.contains("switches itself off after the next launch"))
        // And it still reads as a sentence with nothing known about the channel.
        assertTrue(oneSessionWarning(null).startsWith("The log"))
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
        // The numbers the storage card shows, stated once here so a change to a
        // ladder cannot move them silently.
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

    /** Every declared control at its loudest — the widest a record can be. */
    private fun everythingOn(): ContainerDiagnostics {
        var record = ContainerDiagnostics()
        WINE_CHANNEL_CATALOGUE.forEach {
            record = record.withChannelAdded(it.channel).withChannelLevel(it.channel, it.maxLevel)
        }
        SUBSYSTEM_LEVELS.forEach { record = record.withSubsystemLevel(it.id, it.options.last()) }
        SUBSYSTEM_FLAGS.forEach { record = record.withSubsystemFlag(it.id, !it.default) }
        TURNIP_FLAGS.forEach { record = record.withTurnipFlag(it.flag, true) }
        return record.withChannelAdded("winmm")
    }
}
