package app.vessel.data

import app.vessel.core.MetricSample
import app.vessel.core.MetricSource
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trace is the only durable record of what a run cost, and on this device
 * almost every run ends by being killed. So the case that has to work is not the
 * clean one.
 */
class SessionTraceTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val header = SessionTraceHeader(
        containerId = "c1",
        startedAt = 1_700_000_000_000L,
        cores = 8,
        coreCeilingsMhz = listOf(3321, 3321, 3321, 3321, 3321, 3321, 3801, 3801),
        sources = listOf(
            MetricSource("cpu", "/proc/<pid>/stat", available = true),
            MetricSource("gpu clock", "gpuclk", available = false, reason = "denied here."),
        ),
    )

    private fun sample(index: Int) = MetricSample(
        elapsedMs = index * 1_000L,
        cpuPercent = 40 + index,
        coreClocksMhz = listOf(800, null, 1200, 1200, 1200, 1200, 2400, 2400),
        clockMhz = 1_400,
        clockPeakMhz = 2_400,
        gpuPercent = if (index == 1) null else 30 + index,
        ramUsedMb = 8_000,
        ramTotalMb = 15_240,
        sessionRssMb = 400 + index,
        cpuTempDeciC = 380 + index,
        gpuTempDeciC = 360,
        batteryTempDeciC = 330,
        skinTempDeciC = 350,
        powerMilliwatts = -3_400,
        batteryPercent = 80,
        batteryMillivolts = 4_209,
        batteryMilliamps = -812,
    )

    private fun encode(header: SessionTraceHeader, samples: List<MetricSample>): List<String> =
        listOf(json.encodeToString(SessionTraceHeader.serializer(), header)) +
            samples.map { json.encodeToString(MetricSample.serializer(), it) }

    @Test
    fun `a whole trace round-trips, nulls and all`() {
        val samples = (0 until 3).map(::sample)
        val trace = parseTrace(encode(header, samples).asSequence(), json)
        assertNotNull(trace)
        assertEquals(header, trace!!.header)
        assertEquals(samples, trace.samples)
        // The gap has to survive serialization or the replayed graph bridges it.
        assertNull(trace.samples[1].gpuPercent)
        assertEquals(null, trace.samples[0].coreClocksMhz[1])
        // Sources travel with the trace, so a replay can still say why a column
        // is missing without re-probing a device that may have changed.
        assertEquals(false, trace.header.sources[1].available)
        assertEquals("denied here.", trace.header.sources[1].reason)
    }

    @Test
    fun `a half-written last line keeps every complete sample before it`() {
        // Exactly what a session killed between write and flush leaves behind.
        val lines = encode(header, (0 until 3).map(::sample)).toMutableList()
        lines += """{"elapsedMs":3000,"cpuPer"""
        val trace = parseTrace(lines.asSequence(), json)
        assertNotNull(trace)
        assertEquals(3, trace!!.samples.size)
        assertEquals(2_000L, trace.samples.last().elapsedMs)
    }

    @Test
    fun `a trace that got no further than its header is a run with no samples`() {
        val trace = parseTrace(encode(header, emptyList()).asSequence(), json)
        assertNotNull(trace)
        assertEquals(0, trace!!.samples.size)
        assertEquals(true, trace.isEmpty)
    }

    @Test
    fun `a truncated header is no trace at all, not a headerless one`() {
        assertNull(parseTrace(sequenceOf("""{"schemaVers"""), json))
        assertNull(parseTrace(emptySequence(), json))
    }

    @Test
    fun `blank lines are skipped rather than ending the read`() {
        val lines = encode(header, (0 until 2).map(::sample)).toMutableList()
        lines.add(1, "")
        lines.add("")
        assertEquals(2, parseTrace(lines.asSequence(), json)?.samples?.size)
    }

    /**
     * The app's shared `Json` pretty-prints, so that container sidecars can be
     * read over adb. A pretty-printed sample spans a dozen lines and would turn
     * every trace into one unparseable record — the store derives its own
     * instance with printing off, and this is the guard on that.
     */
    @Test
    fun `a sample encodes to exactly one line`() {
        val shared = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
        val forTrace = Json(from = shared) { prettyPrint = false }
        val line = forTrace.encodeToString(MetricSample.serializer(), sample(0))
        assertEquals(1, line.lines().size)
        // And the setting that lets an older build read a newer trace survives
        // being derived from the shared configuration.
        assertEquals(51, parseTrace(
            sequenceOf(
                forTrace.encodeToString(SessionTraceHeader.serializer(), header),
                """{"elapsedMs":0,"cpuPercent":51,"fieldFromTheFuture":1}""",
            ),
            forTrace,
        )?.samples?.first()?.cpuPercent)
    }

    /**
     * A version 1 trace has no `d3d*` keys at all, and every one of them has to
     * come back as null rather than as a zero — which is the difference between
     * "this run predates the graphics counters" and "this run drew nothing".
     */
    @Test
    fun `a trace from before the graphics counters still reads, with nulls`() {
        val trace = parseTrace(
            sequenceOf(
                """{"schemaVersion":1,"containerId":"c1","startedAt":1,"cores":8}""",
                """{"elapsedMs":0,"cpuPercent":40,"gpuPercent":30}""",
            ),
            json,
        )
        assertEquals(1, trace?.header?.schemaVersion)
        assertEquals(40, trace?.samples?.first()?.cpuPercent)
        assertNull(trace?.samples?.first()?.d3dDrawCallsPerFrame)
        assertNull(trace?.samples?.first()?.d3dMemUsedMb)
        assertNull(trace?.samples?.first()?.d3dPipelines)
    }

    @Test
    fun `the graphics counters survive a round trip as the fractions they are`() {
        val sample = sample(0).copy(
            d3dFps = 29.97f,
            d3dDrawCallsPerFrame = 1f,
            d3dRenderPassesPerFrame = 1f,
            d3dSubmissionsPerFrame = 2.4f,
            d3dBarriersPerFrame = 9f,
            d3dPipelines = 1,
            d3dPipelineLibraries = 481,
            d3dPipeTasksPending = 3,
            d3dMemAllocatedMb = 1_083,
            d3dMemUsedMb = 850,
        )
        val trace = parseTrace(encode(header, listOf(sample)).asSequence(), json)
        assertEquals(sample, trace?.samples?.first())
        // Not rounded on the way through: 2.4 submissions a frame stored as an
        // integer alternates 2 and 3 and puts a sawtooth in a flat line.
        assertEquals(2.4f, trace?.samples?.first()?.d3dSubmissionsPerFrame)
    }

    /**
     * `encodeDefaults = false` for samples, and the reason is size.
     *
     * Four columns this device can never fill, plus thirteen graphics columns a
     * session with no Direct3D program never fills, written on every line for
     * the length of every run. Omitting them is free on read — the default *is*
     * the null the key would have carried — and it is what stops the trace
     * growing faster than the thing that prunes it.
     */
    @Test
    fun `a sample leaves out the fields it has nothing to say about`() {
        val shared = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
        val compact = Json(from = shared) { prettyPrint = false; encodeDefaults = false }
        val line = compact.encodeToString(MetricSample.serializer(), sample(0))
        assertFalse("d3dDrawCallsPerFrame" in line)
        assertFalse("gpuClockMhz" in line)
        assertFalse("cpuPowerMilliwatts" in line)
        // `elapsedMs` has no default and must survive being zero, or the first
        // sample of every run loses its x coordinate.
        assertTrue("elapsedMs" in line)
        // And it still reads back to the same record.
        assertEquals(
            sample(0),
            parseTrace(
                sequenceOf(compact.encodeToString(SessionTraceHeader.serializer(), header), line),
                compact,
            )?.samples?.first(),
        )
    }

    @Test
    fun `a sample written by a newer build does not stop the read`() {
        // `ignoreUnknownKeys` is what makes a schema addition backward-readable;
        // without it one new field would truncate every older reader's trace.
        val lines = encode(header, listOf(sample(0))).toMutableList()
        lines += """{"elapsedMs":1000,"cpuPercent":51,"somethingNew":7}"""
        val trace = parseTrace(lines.asSequence(), json)
        assertEquals(2, trace?.samples?.size)
        assertEquals(51, trace?.samples?.last()?.cpuPercent)
    }
}
