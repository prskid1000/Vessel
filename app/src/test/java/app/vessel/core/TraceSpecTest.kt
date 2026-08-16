package app.vessel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `VESSEL_TRACE`, the one vocabulary.
 *
 * Two kinds of assertion here and they are worth telling apart. Most are about
 * the *mechanism* — that a topic composes into the right variables, that a row
 * still wins over a topic, that nothing escapes the reserved set. A handful are
 * about the *declaration*: that every topic has a level a user can reach, that
 * every stop says where its number came from, that no stop composes a variable
 * the diagnostics stage would then drop on the floor. Those are the ones that
 * catch a topic added later and wired up wrongly, which is the whole failure
 * mode a data-driven table has.
 */
class TraceSpecTest {

    // — the declaration --------------------------------------------------------

    @Test
    fun `every topic has at least one stop and they are in ladder order`() {
        TRACE_TOPICS.forEach { topic ->
            assertTrue("${topic.name} has no stops", topic.stops.isNotEmpty())
            val order = topic.stops.map { it.level.ordinal }
            assertEquals(
                "${topic.name}'s stops are not quietest first, which stopFor relies on",
                order.sorted(),
                order,
            )
            assertEquals(
                "${topic.name} declares a stop twice",
                topic.stops.size,
                topic.stops.map { it.level }.distinct().size,
            )
        }
    }

    /**
     * A volume with no provenance is a guess presented as a measurement, which is
     * the thing the whole level-hint idea exists to stop.
     */
    @Test
    fun `every stop says where its number came from`() {
        TRACE_TOPICS.flatMap { topic -> topic.stops.map { topic.name to it } }
            .forEach { (name, stop) ->
                assertTrue(
                    "$name:${stop.level.wire} has no basis for its volume",
                    stop.basis.isNotBlank(),
                )
                assertTrue(
                    "$name:${stop.level.wire} does not say whether it was measured, " +
                        "counted, estimated or documented",
                    BASIS_WORDS.any { stop.basis.startsWith(it) },
                )
            }
    }

    /**
     * A stop that emits nothing is a control that does nothing, which is the
     * failure this file was written to remove rather than to reproduce.
     */
    @Test
    fun `every stop emits something`() {
        TRACE_TOPICS.forEach { topic ->
            topic.stops.forEach { stop ->
                assertTrue(
                    "${topic.name}:${stop.level.wire} composes nothing",
                    stop.emits.isNotEmpty(),
                )
            }
        }
    }

    /**
     * The gate that would otherwise fail silently.
     *
     * `sessionEnvironment` filters the diagnostics stage against
     * [DIAGNOSTIC_SESSION_ENV] and drops anything outside it *without saying so*
     * — deliberately, so that a mistake here presents as "the switch did
     * nothing" rather than as an unintended variable reaching a session. Which
     * means a topic wired to a variable nobody widened the set for is exactly
     * that silent no-op, and this is the only thing that can catch it.
     */
    @Test
    fun `no topic composes a variable the session would then drop`() {
        val named = TRACE_TOPICS.flatMap { it.stops }.flatMap { it.emits }.map { it.first }
            .mapNotNull { emit ->
                when (emit) {
                    is Emit.Variable -> emit.variable
                    is Emit.ListMember -> emit.variable
                    // A channel is composed into WINEDEBUG, which is in the set.
                    is Emit.WineChannel -> "WINEDEBUG"
                    Emit.Fixed -> null
                }
            }
            .distinct()
        named.forEach {
            assertTrue("$it is composed by a topic but is not in DIAGNOSTIC_SESSION_ENV", it in DIAGNOSTIC_SESSION_ENV)
        }
    }

    // — parsing ----------------------------------------------------------------

    @Test
    fun `a bare topic runs at warnings, not at errors`() {
        // Errors would be a term that changes nothing: every channel already
        // inherits ERR from err+all in the fixed prefix.
        val spec = parseTraceSpec("graphics")
        assertEquals(1, spec.terms.size)
        assertEquals(TraceLevel.WARNINGS, spec.terms[0].stop?.level)
        assertNull(spec.terms[0].problem)
    }

    @Test
    fun `terms are comma separated and whitespace is not structure`() {
        val spec = parseTraceSpec("  graphics:stubs ,  x86:errors  ")
        assertEquals(listOf(TraceTopic.GRAPHICS, TraceTopic.X86), spec.terms.map { it.topic?.name })
    }

    @Test
    fun `all names every topic at the level given`() {
        val spec = parseTraceSpec("all:errors")
        assertEquals(TRACE_TOPICS.map { it.name }, spec.terms.map { it.topic?.name })
    }

    @Test
    fun `a misspelt topic is reported rather than ignored`() {
        val spec = parseTraceSpec("grahpics:warnings")
        assertEquals(1, spec.problems.size)
        assertTrue(spec.problems[0].problem!!.contains("is not a topic"))
        // And it composes nothing, so the session is unchanged.
        val out = EmittedEnvironment()
        applyTraceSpec(spec, out)
        assertTrue(out.variables.isEmpty() && out.wineTerms.isEmpty())
    }

    @Test
    fun `a misspelt level is reported rather than ignored`() {
        val spec = parseTraceSpec("graphics:loud")
        assertEquals(1, spec.problems.size)
        assertTrue(spec.problems[0].problem!!.contains("is not a level"))
    }

    /**
     * The rounding rule, and the reason it is announced.
     *
     * `exceptions` has no `stubs` stop because raising that channel to its middle
     * tiers was measured to change nothing. Asking for one gets the quieter stop
     * — never the louder — and is told so, because silently rounding down is what
     * produced a twenty-minute device run that came back empty.
     */
    @Test
    fun `a stop a topic does not have rounds down and says so`() {
        val term = parseTraceSpec("exceptions:stubs").terms.single()
        assertEquals(TraceLevel.ERRORS, term.stop?.level)
        assertNotNull(term.problem)
        assertTrue(term.problem!!.contains("errors"))
    }

    @Test
    fun `off composes nothing at all`() {
        val out = EmittedEnvironment()
        applyTraceSpec(parseTraceSpec("all:off"), out)
        assertTrue(out.variables.isEmpty())
        assertTrue(out.wineTerms.isEmpty())
        assertTrue(out.lists.isEmpty())
    }

    @Test
    fun `an empty spec is empty and costs nothing`() {
        assertTrue(parseTraceSpec("").isEmpty)
        assertTrue(parseTraceSpec("  ,  ,").isEmpty)
        assertEquals(0L, parseTraceSpec("").linesPerMinute)
    }

    // — volume -----------------------------------------------------------------

    @Test
    fun `a spec's volume is the sum of its terms, because it really costs both`() {
        val graphics = parseTraceSpec("graphics:warnings").linesPerMinute
        val shaders = parseTraceSpec("shaders:warnings").linesPerMinute
        assertEquals(graphics + shaders, parseTraceSpec("graphics,shaders").linesPerMinute)
    }

    @Test
    fun `a spec that only fires on failure never fills the log`() {
        assertNull(parseTraceSpec("shaders:errors").minutesToFill(SessionLogLimits()))
    }

    /**
     * The arithmetic that turns a rate into a decision.
     *
     * At the default 48 MB a session and 120 bytes a line, the loudest graphics
     * stop must not last a minute — if this ever says otherwise, either the
     * estimate or the caps have moved and the hint has stopped being a warning.
     */
    @Test
    fun `the loudest graphics stop fills a whole session's budget in under a minute`() {
        val minutes = parseTraceSpec("graphics:everything").minutesToFill(SessionLogLimits())
        assertNotNull(minutes)
        assertTrue("expected under a minute, got $minutes", minutes!! < 1.0)
    }

    // — composition ------------------------------------------------------------

    /**
     * One topic, four layers, each addressed in its own vocabulary — and the two
     * that are *already* where the topic wants them contribute nothing.
     *
     * That last part is the same rule a hand-added row follows (`a row sitting
     * where the environment already is contributes nothing`) and it is why an
     * untouched container still produces the golden environment byte for byte.
     * `VKD3D_DEBUG` and `VKD3D_SHADER_DEBUG` both ship at `warn`, so
     * `graphics:warnings` is a no-op for them by construction rather than by
     * omission.
     */
    @Test
    fun `graphics at warnings addresses each layer in its own words`() {
        val out = EmittedEnvironment()
        applyTraceSpec(parseTraceSpec("graphics"), out)
        assertEquals("warn", out.variables["DXVK_LOG_LEVEL"])
        assertEquals("file", out.variables["MESA_LOG"])
        // Mesa's word is `warning`, not `warn`; an unrecognised value silently
        // reverts to the build default. See FIXED_MESA_LOG_LEVEL.
        assertEquals("warning", out.variables["MESA_LOG_LEVEL"])
        assertEquals(
            wineChannelTerms("vulkan", WineChannelLevel.WARNINGS),
            out.wineTerms,
        )
        // **These used to be null and the reason is worth keeping.** Both
        // shipped at `warn`, so the topic asking for `warn` changed nothing and
        // the assertion read "already the shipped value". The baselines are now
        // vkd3d's own default of `fixme` — 62% of a Requiem session was vkd3d at
        // WARN, all of it since shown benign — so this topic genuinely raises
        // them, which is what a graphics topic at *warnings* should always have
        // done.
        assertEquals("warn", out.variables["VKD3D_DEBUG"])
        assertEquals("warn", out.variables["VKD3D_SHADER_DEBUG"])
    }

    /** And the same topic at a stop the environment is not already at does write them. */
    @Test
    fun `graphics at everything raises the two vkd3d channels off their baseline`() {
        val out = EmittedEnvironment()
        applyTraceSpec(parseTraceSpec("graphics:everything"), out)
        assertEquals("trace", out.variables["VKD3D_DEBUG"])
        assertEquals("trace", out.variables["VKD3D_SHADER_DEBUG"])
        assertEquals("trace", out.variables["DXVK_LOG_LEVEL"])
        assertEquals(listOf("perf"), out.lists["TU_DEBUG"])
    }

    /**
     * The correction that came out of reading vkd3d rather than the brief.
     *
     * The 26,966-line burst was attributed to `VKD3D_DEBUG` and mitigated by
     * lowering it. Both messages are on the shader channel
     * (`libs/vkd3d-shader/dxbc.c:20`, `:66-73`, `:124-125`), which
     * `libs/vkd3d-common/debug.c:49-53` maps to `VKD3D_SHADER_DEBUG`, so that
     * mitigation cannot have worked. The topic that silences them is `shaders`.
     */
    @Test
    fun `the shader burst is silenced by the shaders topic and not by d3d`() {
        val d3d = EmittedEnvironment()
        applyTraceSpec(parseTraceSpec("d3d:errors"), d3d)
        assertNull(
            "d3d must not claim to control the shader channel",
            d3d.variables["VKD3D_SHADER_DEBUG"],
        )

        val shaders = EmittedEnvironment()
        applyTraceSpec(parseTraceSpec("shaders:errors"), shaders)
        assertEquals("err", shaders.variables["VKD3D_SHADER_DEBUG"])
    }

    @Test
    fun `x86 has one stop, because FEX has one switch`() {
        val topic = traceTopicFor(TraceTopic.X86)!!
        assertEquals(1, topic.stops.size)
        // Every level above off reaches it, so nobody has to know that.
        listOf("errors", "warnings", "stubs", "everything").forEach { level ->
            val out = EmittedEnvironment()
            applyTraceSpec(parseTraceSpec("x86:$level"), out)
            assertEquals("0", out.variables["FEX_SILENTLOG"])
        }
    }

    // — how it reaches a session ------------------------------------------------

    @Test
    fun `the spec is read out of the container's own environment table`() {
        val diagnostics = ContainerDiagnostics(
            env = listOf(EnvSetting(TRACE_SPEC_ENV, "shaders:errors")),
        )
        assertEquals("err", diagnosticEnvironment(diagnostics)["VKD3D_SHADER_DEBUG"])
    }

    /**
     * The precedence rule: a topic is the broad brush, a row is the instrument.
     */
    @Test
    fun `a hand-added row wins over a topic that also names its variable`() {
        val diagnostics = ContainerDiagnostics(
            rows = listOf(DiagnosticSetting("DXVK_LOG_LEVEL", "error")),
            env = listOf(EnvSetting(TRACE_SPEC_ENV, "d3d:everything")),
        )
        assertEquals("error", diagnosticEnvironment(diagnostics)["DXVK_LOG_LEVEL"])
    }

    @Test
    fun `the same is true for a Wine channel, because the parser takes the last term`() {
        val diagnostics = ContainerDiagnostics(
            rows = listOf(DiagnosticSetting("vulkan", WineChannelLevel.OFF.name)),
            env = listOf(EnvSetting(TRACE_SPEC_ENV, "graphics:everything")),
        )
        val debug = diagnosticEnvironment(diagnostics)["WINEDEBUG"]!!
        assertTrue(debug.startsWith(WINEDEBUG_CHANNELS))
        assertTrue("the row's term must come last", debug.endsWith("-vulkan"))
    }

    /**
     * The spec reaches the guest as well as being expanded, because two things it
     * can ask for have no environment variable at all — relay scoping is a
     * registry list (`dlls/ntdll/relay.c:179-183`) and the compiled-in
     * instrumentation reads the string itself.
     */
    @Test
    fun `the raw spec is passed through to the guest, not consumed`() {
        val diagnostics = ContainerDiagnostics(
            env = listOf(EnvSetting(TRACE_SPEC_ENV, "graphics:stubs")),
        )
        assertEquals("graphics:stubs", diagnostics.environmentOverrides()[TRACE_SPEC_ENV])
    }

    @Test
    fun `an untouched container has no spec and composes nothing`() {
        assertTrue(ContainerDiagnostics.DEFAULT.traceSpec().isEmpty)
        assertTrue(diagnosticEnvironment(ContainerDiagnostics.DEFAULT).isEmpty())
    }

    @Test
    fun `the env table offers the spec with a description of its own topics`() {
        val known = knownEnvFor(TRACE_SPEC_ENV)
        assertNotNull("VESSEL_TRACE must be offered, not merely accepted", known)
        TRACE_TOPICS.forEach {
            assertTrue("${it.name} is missing from the help", known!!.secondary.contains(it.name))
        }
        assertFalse(known!!.secondary.contains("null"))
    }

    private companion object {
        /**
         * The four words a basis may open with. `documented` is for a figure
         * taken from a project's own claim about itself rather than from a
         * measurement or a count of its source.
         */
        val BASIS_WORDS = listOf("measured", "counted", "estimated", "documented")
    }
}
