package app.vessel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The row model and the composer, pinned.
 *
 * Every failure mode on this surface is silent in the same way the rest of
 * `docs/LOGGING.md` is: a `WINEDEBUG` term in the wrong order produces an empty
 * log rather than an error, a level that emits one term where it needed two
 * produces *most* of a log, and a read-only row whose displayed value has drifted
 * from the environment is a screen that lies. None of that is visible on a device
 * without already suspecting it, so it is here.
 */
class ContainerDiagnosticsTest {

    private val default = ContainerDiagnostics()

    /** One row, named and levelled, the way the screen would build it. */
    private fun row(name: String, level: String? = null) =
        default.withRowAdded().withRowNamed(0, name).let { record ->
            if (level == null) record else record.withRowLevel(0, level)
        }

    // — the default, which is the whole safety property ------------------------

    @Test
    fun `a fresh container has added nothing at all`() {
        assertTrue(default.isDefault)
        assertEquals(emptyList<DiagnosticSetting>(), default.rows)
        assertEquals(WINEDEBUG_CHANNELS, composeWineDebug(default))
        // Not "the same values as the fixed block" — *nothing*. That is what makes
        // "a fresh container runs with today's environment" a property of one line
        // rather than of reading two blocks together.
        assertEquals(emptyMap<String, String>(), diagnosticEnvironment(default))
    }

    @Test
    fun `an empty row contributes nothing until it is named`() {
        val added = default.withRowAdded()
        assertEquals(1, added.rows.size)
        assertEquals(WINEDEBUG_CHANNELS, composeWineDebug(added))
        assertEquals(emptyMap<String, String>(), diagnosticEnvironment(added))
    }

    @Test
    fun `the row Add makes can actually be named`() {
        // The regression that made Add useless: name and level shared one
        // editable flag, so a row with no name yet came back fully disabled and
        // there was no way to type into it. A row you cannot fill in is worse
        // than no Add button.
        val fresh = diagnosticRows(default.withRowAdded()).last()
        assertEquals("", fresh.name)
        assertTrue("Add produced a row nobody can name", fresh.nameEditable)
        assertTrue(fresh.removable)
        // Nothing to choose until it is named, and that column says so.
        assertFalse(fresh.levelEditable)
    }

    @Test
    fun `a row sitting where the environment already is contributes nothing`() {
        // Naming a variable puts the row at that entry's `addAt`, so this checks
        // the other direction: dropped back to its baseline, it goes quiet.
        val at = row("DXVK_LOG_LEVEL", level = FIXED_DXVK_LOG_LEVEL)
        assertEquals(emptyMap<String, String>(), diagnosticEnvironment(at))
    }

    // — the inventory is truthful ----------------------------------------------

    @Test
    fun `the fixed Wine rows are exactly the terms the prefix sends`() {
        // Display cannot be derived from the constant by the generic composer —
        // the prefix writes `warn+module` where a row would write three terms —
        // so the two are declared separately and joined here. This is what stops
        // the screen claiming something the session does not send.
        assertEquals(
            WINEDEBUG_CHANNELS,
            BASELINE_WINE_TERMS.flatMap { it.second }.joinToString(","),
        )
        // …and every one of them has a row, in the same order.
        assertEquals(
            BASELINE_WINE_TERMS.map { it.first },
            LOGGABLES.filter { it.isFixed }.take(BASELINE_WINE_TERMS.size).map { it.name },
        )
    }

    @Test
    fun `the all row stands for both of its terms honestly`() {
        // `-all,err+all` is exactly the ERRORS stop's own term pair, which is what
        // lets one row stand for two terms without paraphrasing them.
        assertEquals(
            BASELINE_WINE_TERMS.first { it.first == "all" }.second,
            wineChannelTerms("all", WineChannelLevel.ERRORS),
        )
    }

    @Test
    fun `no fixed row reads Off`() {
        // The property that keeps "greyed out" from reading as "switched off".
        assertTrue(fixedRowsNeverReadOff())
    }

    @Test
    fun `the inventory lists what is sent, then what the user added`() {
        val rows = diagnosticRows(row("relay"))
        val fixed = rows.filterNot { it.removable }
        assertEquals(LOGGABLES.count { it.isFixed }, fixed.size)
        assertTrue("a fixed row is never editable", fixed.none { it.nameEditable })
        assertTrue("a fixed row's level is never editable", fixed.none { it.levelEditable })
        assertTrue("a fixed row has no cross", fixed.none { it.removable })
        assertTrue("the fixed rows come first", rows.take(fixed.size) == fixed)
        assertEquals("relay", rows.last().name)
        assertTrue(rows.last().removable)
        assertTrue(rows.last().nameEditable)
        assertTrue(rows.last().levelEditable)
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
        // seeded from `default_flags`, not from the stop below it.
        val terms = wineChannelTerms("file", WineChannelLevel.STUBS)
        assertTrue("warn+file" in terms)
        assertTrue("fixme+file" in terms)
    }

    @Test
    fun `every stop of every Wine entry leaves minus-all first and the prefix intact`() {
        for (loggable in LOGGABLES.filter { it.emit is Emit.WineChannel }) {
            for (level in loggable.levels) {
                val composed = composeWineDebug(row(loggable.name, level))
                val terms = composed.split(",")
                assertEquals("${loggable.name} at $level", "-all", terms.first())
                assertTrue(
                    "${loggable.name} at $level dropped the prefix",
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
    fun `lowering a channel the prefix already set actually lowers it`() {
        // `loaddll` is `+loaddll` in the prefix, so it is already at all four
        // classes. A `warn+loaddll` would leave it there; the reset is what makes
        // the row's "Errors" stop mean errors.
        assertEquals(
            "$WINEDEBUG_CHANNELS,-loaddll,err+loaddll",
            composeWineDebug(row("loaddll", WineChannelLevel.ERRORS.name)),
        )
    }

    @Test
    fun `rows are written in the order they were added`() {
        val record = default
            .withRowAdded().withRowNamed(0, "file")
            .withRowAdded().withRowNamed(1, "reg")
        assertEquals(
            "$WINEDEBUG_CHANNELS,-file,err+file,warn+file,-reg,err+reg,warn+reg",
            composeWineDebug(record),
        )
    }

    @Test
    fun `removing a row takes its terms with it`() {
        val two = default
            .withRowAdded().withRowNamed(0, "file")
            .withRowAdded().withRowNamed(1, "reg")
        assertEquals(
            "$WINEDEBUG_CHANNELS,-reg,err+reg,warn+reg",
            composeWineDebug(two.withRowRemoved(0)),
        )
    }

    @Test
    fun `renaming a row resets its level to what the new thing wants`() {
        // A level is a word in one subsystem's vocabulary; carrying `+ Warnings`
        // over to DXVK_LOG_LEVEL would leave a row holding a value its own ladder
        // does not contain.
        val renamed = row("file", WineChannelLevel.EVERYTHING.name).withRowNamed(0, "DXVK_LOG_LEVEL")
        assertEquals(loggableFor("DXVK_LOG_LEVEL").addAt, renamed.rows[0].level)
        assertTrue(renamed.rows[0].level in loggableFor("DXVK_LOG_LEVEL").levels)
    }

    @Test
    fun `a channel nobody anticipated gets a row, the full ladder and no caution`() {
        val loggable = loggableFor("winmm")
        assertEquals(WineChannelLevel.entries.map { it.name }, loggable.levels)
        assertNull("nothing is known about what it costs", loggable.caution)
        assertNull("and no stop that would spend it after one launch", loggable.oneSessionFrom)
        assertEquals(
            "$WINEDEBUG_CHANNELS,-winmm,err+winmm,warn+winmm",
            composeWineDebug(row("winmm")),
        )
    }

    @Test
    fun `d3d stops at warnings, and the rest of the ladder is not offered`() {
        val loggable = loggableFor("d3d")
        assertEquals(
            listOf(
                WineChannelLevel.OFF.name,
                WineChannelLevel.ERRORS.name,
                WineChannelLevel.WARNINGS.name,
            ),
            loggable.levels,
        )
        assertNotNull("the 659-site caution travels with the entry", loggable.caution)
    }

    // — what may be typed into the name column ---------------------------------

    @Test
    fun `a channel name is one word, and the parser's punctuation is refused`() {
        assertTrue(isLoggableName("winmm"))
        assertTrue(isLoggableName("d3d11"))
        // `parse_options` reads these as structure, so a name containing one is
        // several terms rather than one channel.
        assertFalse(isLoggableName("+relay,-heap"))
        assertFalse(isLoggableName("-heap"))
        assertFalse(isLoggableName("metro.exe:+relay"))
        assertFalse(isLoggableName("two words"))
        assertFalse(isLoggableName(""))
        // `add_option` drops a name that reaches `char name[15]`, and says
        // nothing — a row that logs nothing is the failure worth refusing.
        assertTrue(isLoggableName("a".repeat(14)))
        assertFalse(isLoggableName("a".repeat(15)))
        // A declared entry is exempt: a variable is not a channel and is never
        // written into WINEDEBUG.
        assertTrue(isLoggableName("VKD3D_SHADER_DEBUG"))
        LOGGABLES.forEach { assertTrue("${it.name} fails its own check", isLoggableName(it.name)) }
    }

    @Test
    fun `the word that kills the process cannot be composed, whatever is typed`() {
        // `init_options` compares the *whole* variable against "help" and calls
        // `debug_usage()`, which writes to fd 2 and exit(1)
        // (`native/wine/dlls/ntdll/unix/debug.c:183-193, 213`) — a session that
        // dies before the program starts, with the reason on a stream nothing
        // shows. The design answer was to have no free-text WINEDEBUG field at
        // all, so the hazard is unreachable by construction; this pins that,
        // because "unreachable by construction" is a claim about a shape that a
        // later raw-input row would silently break.
        for (level in loggableFor("help").levels) {
            val composed = composeWineDebug(row("help", level))
            assertTrue("help escaped the prefix", composed.startsWith(WINEDEBUG_CHANNELS))
            assertFalse("the bare word is what Wine tests for", composed == "help")
            assertTrue("help" !in composed.split(","))
        }
        // The same for the other whole-variable spelling Wine accepts, and for
        // the one term that would erase everything before it.
        assertFalse(isLoggableName("-all"))
        assertFalse(isLoggableName("help,x"))
    }

    @Test
    fun `an invalid name is flagged on the row and composes nothing`() {
        val bad = row("+relay,-heap")
        assertTrue(diagnosticRows(bad).last().nameIsInvalid)
        // The level never becomes real, so the row is inert either way.
        assertEquals(WINEDEBUG_CHANNELS, composeWineDebug(bad.withRowLevel(0, "")))
    }

    @Test
    fun `silencing what the prefix switched on is the Off stop`() {
        // `-heap` and friends without a free-text WINEDEBUG box. Per-program
        // scoping is the one thing that went with it — see composeWineDebug.
        assertEquals(
            "$WINEDEBUG_CHANNELS,-heap",
            composeWineDebug(row("heap", WineChannelLevel.OFF.name)),
        )
    }

    // — the declared vocabularies ----------------------------------------------

    @Test
    fun `DXVK and vkd3d keep their own words in their own order`() {
        assertEquals(
            listOf("none", "error", "warn", "info", "debug", "trace"),
            loggableFor("DXVK_LOG_LEVEL").levels,
        )
        // vkd3d's `info` sits between `err` and `fixme`, so `warn` already carries
        // both. Normalising this to DXVK's order would be a screen that lies.
        assertEquals(
            listOf("none", "err", "info", "fixme", "warn", "trace"),
            loggableFor("VKD3D_DEBUG").levels,
        )
        assertEquals(
            loggableFor("VKD3D_DEBUG").levels,
            loggableFor("VKD3D_SHADER_DEBUG").levels,
        )
    }

    @Test
    fun `every declaration is coherent`() {
        for (loggable in LOGGABLES) {
            assertTrue("${loggable.name} has no ladder", loggable.levels.isNotEmpty())
            assertTrue(
                "${loggable.name}'s baseline is not one of its stops",
                loggable.baseline in loggable.levels,
            )
            assertTrue(
                "${loggable.name}'s addAt is not one of its stops",
                loggable.addAt in loggable.levels,
            )
            loggable.fixedLevel?.let {
                assertTrue("${loggable.name}'s fixed value is not a stop", it in loggable.levels)
            }
            loggable.gate?.let { gate ->
                assertNotNull("${loggable.name} gates on nothing", LOGGABLES.firstOrNull { it.name == gate })
            }
        }
        // Names are unique, or `loggableFor` silently picks one of two.
        assertEquals(LOGGABLES.size, LOGGABLES.map { it.name }.toSet().size)
    }

    @Test
    fun `each declared variable writes only itself`() {
        assertEquals(
            mapOf("DXVK_LOG_LEVEL" to "error"),
            diagnosticEnvironment(row("DXVK_LOG_LEVEL", "error")),
        )
        assertEquals(
            mapOf("VKD3D_DEBUG" to "info"),
            diagnosticEnvironment(row("VKD3D_DEBUG", "info")),
        )
        // `fixme` is the shipped baseline for this one, and a row at the
        // baseline contributes nothing — that is the property this file asserts
        // elsewhere, so the level here has to be one that differs.
        assertEquals(
            mapOf("VKD3D_SHADER_DEBUG" to "warn"),
            diagnosticEnvironment(row("VKD3D_SHADER_DEBUG", "warn")),
        )
        // "0" is the off-baseline stop now that silent is the default, so it is
        // the one that has anything to write. A row at "1" contributes nothing,
        // which is the point of Emit.Variable comparing against the baseline.
        assertEquals(
            mapOf("FEX_SILENTLOG" to "0"),
            diagnosticEnvironment(row("FEX_SILENTLOG", "0")),
        )
        assertEquals(emptyMap<String, String>(), diagnosticEnvironment(row("FEX_SILENTLOG", "1")))
        assertEquals(mapOf("MESA_LOG" to "file"), diagnosticEnvironment(row("MESA_LOG", "file")))
    }

    @Test
    fun `a row for something the fixed block sends writes nothing`() {
        // The Fixed strategy reports and does not compose; writing the same value
        // again would put it in the map for a container nobody has touched. Note
        // this is the *unaddable* set and not the read-only one: the four Wine
        // channels the prefix names are shown read-only and are still overridable.
        LOGGABLES.filterNot { it.isAddable }.forEach {
            assertEquals(
                "${it.name} composed something",
                emptyMap<String, String>(),
                diagnosticEnvironment(row(it.name, it.fixedLevel)),
            )
        }
    }

    // — the Turnip gate --------------------------------------------------------

    @Test
    fun `Turnip flags are unreachable until the driver logger is on`() {
        // Enforced in the record and not only in the UI, so a hand-edited document
        // cannot arrange for a switch whose output nothing can read.
        assertFalse(diagnosticEnvironment(row("perf", Emit.ON)).containsKey("TU_DEBUG"))
        val gated = diagnosticRows(row("perf", Emit.ON)).last()
        assertFalse("a gated row cannot pick a level", gated.levelEditable)
        // …but it keeps its name and its cross, which is what tells a gated row
        // apart from a fixed one.
        assertTrue(gated.nameEditable)
        assertTrue(gated.removable)

        val allowed = row("MESA_LOG", "file")
            .withRowAdded().withRowNamed(1, "perf").withRowLevel(1, Emit.ON)
        // `startup` stays last: it is the ground truth for whether Turnip loaded.
        assertEquals("perf,startup", diagnosticEnvironment(allowed)["TU_DEBUG"])
        assertTrue(diagnosticRows(allowed).last().levelEditable)
    }

    @Test
    fun `the manifest's own Turnip flags are added to, not replaced`() {
        val allowed = row("MESA_LOG", "file")
            .withRowAdded().withRowNamed(1, "noubwc").withRowLevel(1, Emit.ON)
        assertEquals(
            "noubwc,sysmem,startup",
            diagnosticEnvironment(allowed, listOf("sysmem", TU_DEBUG_STARTUP))["TU_DEBUG"],
        )
    }

    @Test
    fun `no record can reach the variables reserved for their absence`() {
        val keys = diagnosticEnvironment(everythingOn()).keys
        assertFalse("VKD3D_LOG_FILE" in keys)
        assertFalse("DXVK_LOG_PATH" in keys)
        // `MESA_VK_WSI_DEBUG` was asserted here and is deliberately not any
        // more. It was never in this company: those two must be *absent* or the
        // D3D layers' output leaves the pipe the session log reads, whereas this
        // one had a fixed value chosen because half of Mesa's X11 WSI was not
        // compiled — and `patches/mesa/0004` and `0006` compiled it. It is a
        // declared row now; the test below is the one that pins it.
    }

    @Test
    fun `the present path is a row, and it is silent until somebody moves it`() {
        // The row exists so a black window does not need a rebuild to undo. Two
        // halves, and the second is the one that could rot quietly: picking the
        // *other* path must compose, and sitting where the session already is
        // must compose nothing at all.
        //
        // **Written against the constant rather than against `sw`.** Both
        // assertions used to name the paths outright and both broke the day
        // `ZERO_COPY_PRESENT` flipped, though nothing they were testing had
        // changed. The property is "the baseline is silent, the other side is
        // not", and it holds whichever way the default points.
        val other = if (ZERO_COPY_PRESENT) WSI_SOFTWARE else WSI_DRI3
        assertEquals(
            mapOf("MESA_VK_WSI_DEBUG" to other),
            diagnosticEnvironment(row("MESA_VK_WSI_DEBUG", other)),
        )
        assertEquals(
            emptyMap<String, String>(),
            diagnosticEnvironment(row("MESA_VK_WSI_DEBUG", FIXED_MESA_VK_WSI_DEBUG)),
        )
        // Naming the row arms it — the same shape as every other cautioned row
        // on this surface, and the confirmation dialog is what stands between
        // the two. A row that had to be named *and then* switched on would read
        // as broken the first time somebody added it.
        assertEquals(WSI_DRI3, row("MESA_VK_WSI_DEBUG").rows[0].level)
        // Not one-session: `consumed()` is about log volume, and a present path
        // that disarmed itself every launch could never be shipped on.
        assertFalse(row("MESA_VK_WSI_DEBUG").rows[0].isOneSession)
        assertEquals(row("MESA_VK_WSI_DEBUG"), row("MESA_VK_WSI_DEBUG").consumed())
        // It files itself under Mesa by prefix, with no declared type anywhere.
        assertEquals("mesa", loggableFor("MESA_VK_WSI_DEBUG").family)
        // And it carries a caution, which is what makes the screen ask first.
        assertNotNull(loggableFor("MESA_VK_WSI_DEBUG").caution)
    }

    // — duration ---------------------------------------------------------------

    @Test
    fun `one session is a property of the level, not of the thing`() {
        // A row lands one-session because the stop it was added at is loud, and
        // stops being one the moment it is turned down. `DXVK_LOG_LEVEL` at
        // `warn` is ordinary and at `trace` is a firehose; one entry, both.
        assertTrue(row("relay").rows[0].isOneSession)
        assertTrue(row("seh").rows[0].isOneSession)
        assertFalse(row("file").rows[0].isOneSession)
        assertFalse(row("MESA_LOG").rows[0].isOneSession)

        assertTrue(row("DXVK_LOG_LEVEL", "trace").rows[0].isOneSession)
        assertFalse(row("DXVK_LOG_LEVEL", "warn").rows[0].isOneSession)
        // Turning a firehose down takes the chip with it.
        assertFalse(row("relay", WineChannelLevel.OFF.name).rows[0].isOneSession)
    }

    @Test
    fun `one session disarms the row without deleting it`() {
        val record = row("relay")
            .withRowAdded().withRowNamed(1, "file")
        assertTrue(composeWineDebug(record).contains(",+relay"))

        val spent = record.consumed()
        // Both rows are still there. Deleting the loud one used to be the
        // behaviour, and it read from the outside as the setting never having
        // applied -- the one doubt a diagnostic surface must not create.
        assertEquals(listOf("relay", "file"), spent.rows.map { it.name })
        // Disarmed: the firehose term is gone. Not "relay is absent" -- a Wine
        // channel's baseline is ERRORS, not OFF, because `err+all` in the fixed
        // prefix means every channel already carries that. So the disarmed row
        // reads `err+relay` and only the bare `,+relay` is the loud one.
        assertFalse(composeWineDebug(spent).contains(",+relay"))
        assertEquals(loggableFor("relay").baseline, spent.rows[0].level)
        assertFalse(spent.rows[0].isOneSession)
        // Spending is idempotent: a disarmed row has nothing left to spend.
        assertEquals(spent, spent.consumed())
    }

    @Test
    fun `turning a loud row down keeps it across launches`() {
        // The way to keep a channel that has a one-session stop is to run it
        // below that stop, which is honest: what is spent is the firehose, not
        // the channel.
        val kept = row("relay", WineChannelLevel.ERRORS.name)
        assertEquals(kept, kept.consumed())
    }

    @Test
    fun `a fresh container has nothing to spend`() {
        assertEquals(default, default.consumed())
    }

    @Test
    fun `the cost warning says the concrete things and names the mechanism`() {
        val message = costWarning(loggableFor("relay").caution, oneSession = true)
        assertTrue(message.contains("Hundreds of megabytes"))
        assertTrue("it has to say the log fills", message.contains("cap sooner"))
        assertTrue("it has to say the session slows", message.contains("slower"))
        assertTrue(
            "naming the mechanism is what stops it being dismissed",
            message.contains("removes itself after the next launch"),
        )
        // And it stays a sentence for a costly row that is not one-session.
        assertFalse(costWarning(null, oneSession = false).contains("removes itself"))
    }

    // — the log caps -----------------------------------------------------------

    @Test
    fun `the caps default to the top of every ladder`() {
        val limits = SessionLogLimits()
        assertEquals(SessionLogLimits.HEAD_LADDER.last(), limits.headBytes)
        assertEquals(SessionLogLimits.TAIL_LADDER.last(), limits.tailBytes)
        assertEquals(SessionLogLimits.RATE_LADDER.last(), limits.rateLimitLines)
        assertEquals(limits.tailBytes / 2, limits.tailSegmentBytes)
        // The numbers the storage card shows, stated once here so a change to a
        // ladder cannot move them silently.
        assertEquals(48L * 1024 * 1024, limits.worstCaseBytesPerSession)
        assertEquals(480L * 1024 * 1024, limits.worstCaseBytesPerContainer)
    }

    @Test
    fun `the first rung of every ladder is what shipped before this was a setting`() {
        assertEquals(5L * 1024 * 1024, SessionLogLimits.SHIPPED.headBytes)
        assertEquals(1536L * 1024, SessionLogLimits.SHIPPED.tailSegmentBytes)
        assertEquals(2_000, SessionLogLimits.SHIPPED.rateLimitLines)
    }

    /** Every addable entry at its loudest — the widest a record can be. */
    private fun everythingOn(): ContainerDiagnostics {
        var record = ContainerDiagnostics()
        ADDABLE_LOGGABLES.forEachIndexed { index, loggable ->
            record = record.withRowAdded()
                .withRowNamed(index, loggable.name)
                .withRowLevel(index, loggable.levels.last())
        }
        return record
    }

    @Test
    fun `a channel the prefix names is shown read-only and is still overridable`() {
        // Two different properties: `isFixed` puts a row at the head of the
        // inventory, `isAddable` says the user may add their own row for it. The
        // four prefix channels are both, which is what makes "quiet loaddll"
        // reachable at all.
        val loaddll = loggableFor("loaddll")
        assertTrue(loaddll.isFixed)
        assertTrue(loaddll.isAddable)
        assertTrue("loaddll" in ADDABLE_LOGGABLES.map { it.name })
        // And the unaddable ones really cannot compose.
        assertTrue(LOGGABLES.filterNot { it.isAddable }.all { it.emit is Emit.Fixed })

        // `all` is the exception among the prefix channels: shown, never
        // offered. `+all` is every class on every channel and a second `-all`
        // erases the prefix, so neither direction is something a row may do.
        assertTrue(loggableFor("all").isFixed)
        assertFalse(loggableFor("all").isAddable)
        assertFalse("all" in ADDABLE_LOGGABLES.map { it.name })
    }
}
