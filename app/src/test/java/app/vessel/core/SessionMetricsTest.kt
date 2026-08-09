package app.vessel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parsers are where this feature can be silently wrong.
 *
 * A misread counter does not crash and does not look broken — it draws a
 * confident line at the wrong height, and the number gets quoted in a bug
 * report. Every case here is one that was either observed on the target device
 * or is a shape the kernel is documented to produce.
 */
class ProcPidStatTest {

    @Test
    fun `utime and stime are summed from the columns after the comm field`() {
        // Real line from the device, truncated after stime's neighbours.
        val line = "11144 (app.vessel) S 2044 2044 0 0 -1 4194624 27882 0 1889 0 301 41 0 0 20 0 29 0"
        assertEquals(342L, parseProcPidStatCpuTicks(line))
    }

    @Test
    fun `an executable name containing spaces and parentheses does not shift the columns`() {
        // The case that breaks every split-on-space parser, and the one Wine
        // guarantees: a Windows process is named after its .exe.
        val line = "9001 (Rocket League (Epic).exe) R 1 9001 0 0 -1 0 0 0 0 0 700 55 0 0 20 0 4 0"
        assertEquals(755L, parseProcPidStatCpuTicks(line))
    }

    @Test
    fun `a truncated or unparseable line is no reading rather than zero`() {
        assertNull(parseProcPidStatCpuTicks("11144 (app.vessel) S 2044"))
        assertNull(parseProcPidStatCpuTicks("nonsense"))
        assertNull(parseProcPidStatCpuTicks(""))
    }

    @Test
    fun `resident pages come from the second column of statm`() {
        assertEquals(51802L, parseProcPidStatmResidentPages("4710836 51802 36721 2 0 1167456 0"))
        assertNull(parseProcPidStatmResidentPages("4710836"))
    }
}

class CpuLoadTest {

    /** 100 ticks per second, eight cores — the target device's numbers. */
    private fun load() = CpuLoad(ticksPerSecond = 100L, cores = 8)

    @Test
    fun `the first reading is a baseline and not a measurement`() {
        // Reporting here would show the process's entire lifetime of CPU as one
        // second's worth, which is a session that opens pinned at 100%.
        assertNull(load().update(mapOf(1 to 5_000L), atMillis = 1_000L))
    }

    @Test
    fun `one fully busy core out of eight is one eighth of capacity`() {
        val cpu = load()
        cpu.update(mapOf(1 to 0L), atMillis = 0L)
        // 100 ticks in one second is one core saturated; 1/8 of the device.
        assertEquals(12, cpu.update(mapOf(1 to 100L), atMillis = 1_000L))
    }

    @Test
    fun `work is summed across the whole process tree`() {
        val cpu = load()
        cpu.update(mapOf(1 to 0L, 2 to 0L, 3 to 0L), atMillis = 0L)
        val percent = cpu.update(mapOf(1 to 200L, 2 to 200L, 3 to 400L), atMillis = 1_000L)
        assertEquals(100, percent)
    }

    @Test
    fun `a process that appears mid-session contributes nothing until it has a baseline`() {
        val cpu = load()
        cpu.update(mapOf(1 to 0L), atMillis = 0L)
        // Pid 2 arrives holding 50 000 ticks of history. Counting it would spike
        // the graph to 100% every time an installer spawned a child.
        assertEquals(12, cpu.update(mapOf(1 to 100L, 2 to 50_000L), atMillis = 1_000L))
    }

    @Test
    fun `a process that exits does not push the total negative`() {
        val cpu = load()
        cpu.update(mapOf(1 to 100L, 2 to 5_000L), atMillis = 0L)
        assertEquals(0, cpu.update(mapOf(1 to 100L), atMillis = 1_000L))
    }

    @Test
    fun `a recycled pid reading backwards is clamped rather than subtracted`() {
        val cpu = load()
        cpu.update(mapOf(1 to 9_000L), atMillis = 0L)
        assertEquals(0, cpu.update(mapOf(1 to 12L), atMillis = 1_000L))
    }

    @Test
    fun `a zero or backwards interval produces no reading`() {
        val cpu = load()
        cpu.update(mapOf(1 to 0L), atMillis = 5_000L)
        assertNull(cpu.update(mapOf(1 to 100L), atMillis = 5_000L))
    }

    @Test
    fun `the result never leaves nought to a hundred`() {
        val cpu = load()
        cpu.update(mapOf(1 to 0L), atMillis = 0L)
        // Physically impossible, but a suspend/resume can make the clock and the
        // jiffy counters disagree, and a 4000% CPU reading is not a graph.
        assertEquals(100, cpu.update(mapOf(1 to 100_000L), atMillis = 1_000L))
    }
}

class KgslGpuBusyTest {

    @Test
    fun `the counter is busy and total microseconds`() {
        // Observed on device: a window of almost exactly one second.
        val busy = parseKgslGpuBusy("  25641 1004321\n")
        assertEquals(GpuBusy(25_641L, 1_004_321L), busy)
        assertEquals(2, busy?.percent)
    }

    @Test
    fun `a powered-down GPU reads zero over zero and is genuinely nought percent`() {
        // KGSL clears the counters after printing them when the GPU bus is off.
        // This is the one zero in the whole feature that is not a fabrication.
        val busy = parseKgslGpuBusy("      0       0")
        assertEquals(GpuBusy(0L, 0L), busy)
        assertEquals(0, busy?.percent)
    }

    @Test
    fun `garbage is a missing reading, which is not the same as nought`() {
        assertNull(parseKgslGpuBusy(""))
        assertNull(parseKgslGpuBusy("25641"))
        assertNull(parseKgslGpuBusy("busy idle"))
    }

    @Test
    fun `a saturated window is a hundred and cannot exceed it`() {
        assertEquals(100, parseKgslGpuBusy("1004321 1004321")?.percent)
        assertEquals(100, parseKgslGpuBusy("2000000 1004321")?.percent)
    }
}

class CpuFreqTest {

    @Test
    fun `kilohertz become whole megahertz`() {
        assertEquals(556, parseCpuFreqKhz("556800\n"))
        assertEquals(3_801, parseCpuFreqKhz("3801600"))
    }

    @Test
    fun `an offline core reporting nothing is no reading`() {
        assertNull(parseCpuFreqKhz(""))
        assertNull(parseCpuFreqKhz("0"))
        assertNull(parseCpuFreqKhz("<unknown>"))
    }
}

class MetricHistoryTest {

    private fun sample(index: Int, cpu: Int?) =
        MetricSample(elapsedMs = index * 1_000L, cpuPercent = cpu)

    @Test
    fun `the window drops its oldest sample rather than growing`() {
        var history = MetricHistory(capacity = 3)
        repeat(5) { history += sample(it, it * 10) }
        assertEquals(3, history.size)
        assertEquals(listOf(20, 30, 40), history.series { it.cpuPercent })
        assertEquals(40, history.latest?.cpuPercent)
    }

    @Test
    fun `gaps survive into the series so the graph can break the line`() {
        var history = MetricHistory(capacity = 4)
        history += sample(0, 10)
        history += sample(1, null)
        history += sample(2, 30)
        assertEquals(listOf(10, null, 30), history.series { it.cpuPercent })
    }

    @Test
    fun `peak and mean ignore the gaps rather than treating them as zero`() {
        var history = MetricHistory(capacity = 4)
        history += sample(0, 10)
        history += sample(1, null)
        history += sample(2, 30)
        assertEquals(30, history.peak { it.cpuPercent })
        assertEquals(20, history.mean { it.cpuPercent })
    }

    @Test
    fun `a field that never produced a reading has no peak and no mean`() {
        var history = MetricHistory(capacity = 4)
        repeat(3) { history += sample(it, null) }
        assertNull(history.peak { it.cpuPercent })
        assertNull(history.mean { it.cpuPercent })
        assertTrue(history.series { it.cpuPercent }.all { it == null })
    }
}

class MetricStatsTest {

    private fun history(vararg values: Int?): MetricHistory {
        var built = MetricHistory(capacity = 16)
        values.forEachIndexed { index, value ->
            built += MetricSample(elapsedMs = index * 1_000L, cpuPercent = value)
        }
        return built
    }

    @Test
    fun `every number a card prints comes off one pass`() {
        val stats = history(10, 40, 30, 60)?.stats { it.cpuPercent }!!
        assertEquals(60, stats.current)
        assertEquals(10, stats.min)
        assertEquals(60, stats.peak)
        assertEquals(35, stats.mean)
        assertEquals(10, stats.first)
        assertEquals(4, stats.samples)
        assertEquals(0, stats.gaps)
        assertEquals(50, stats.delta)
    }

    @Test
    fun `gaps are counted, not averaged in`() {
        val stats = history(10, null, null, 30).stats { it.cpuPercent }!!
        assertEquals(2, stats.samples)
        assertEquals(2, stats.gaps)
        assertEquals(20, stats.mean)
    }

    @Test
    fun `a value that never moved reports flat so a card prints one number`() {
        assertTrue(history(50, 50, 50).stats { it.cpuPercent }!!.flat)
        assertTrue(!history(50, 51).stats { it.cpuPercent }!!.flat)
    }

    @Test
    fun `a field that never reported has no statistics at all`() {
        assertNull(history(null, null).stats { it.cpuPercent })
    }

    @Test
    fun `a window rebuilt from a trace keeps the most recent samples`() {
        val samples = (0 until 10).map { MetricSample(elapsedMs = it * 1_000L, cpuPercent = it) }
        val restored = MetricHistory.of(samples, capacity = 4)
        assertEquals(4, restored.size)
        assertEquals(listOf(6, 7, 8, 9), restored.series { it.cpuPercent })
        assertEquals(9_000L, restored.elapsedMs)
    }

    @Test
    fun `a core's clocks come back indexed by cpu number`() {
        var built = MetricHistory(capacity = 4)
        built += MetricSample(elapsedMs = 0L, coreClocksMhz = listOf(400, null, 1200))
        built += MetricSample(elapsedMs = 1_000L, coreClocksMhz = listOf(800, 900, 1400))
        assertEquals(listOf(400, 800), built.coreSeries(0))
        // cpu1 was parked for the first tick: a gap, not a zero.
        assertEquals(listOf(null, 900), built.coreSeries(1))
        // A core the sample never carried is all gaps rather than an exception.
        assertEquals(listOf(null, null), built.coreSeries(7))
    }
}

class ThermalTest {

    @Test
    fun `millidegrees become tenths of a degree`() {
        // Real readings from the device's cpu-0-0-0 and batt-therm zones.
        assertEquals(366, parseThermalMilliCelsius("36600"))
        assertEquals(342, parseThermalMilliCelsius("34250"))
    }

    @Test
    fun `a zone that reports whole degrees is not read as a hundredth of one`() {
        assertEquals(410, parseThermalMilliCelsius("41"))
    }

    @Test
    fun `a threshold flag pinned at zero is not a temperature`() {
        // pm8550-bcl-lvl0..2 sit permanently at 0 on this device. A 0.0 degree
        // line on a temperature graph is a fabrication.
        assertNull(parseThermalMilliCelsius("0"))
        assertNull(parseThermalMilliCelsius("-40000"))
    }

    @Test
    fun `an implausible reading is rejected rather than drawn`() {
        assertNull(parseThermalMilliCelsius("40000000"))
        assertNull(parseThermalMilliCelsius("hot"))
    }

    @Test
    fun `zones are recognised by type, because the index is not stable`() {
        // Every one of these is a real `type` string from the target device.
        assertEquals(ThermalRole.CPU, thermalRoleOf("cpu-0-0-0"))
        assertEquals(ThermalRole.CPU, thermalRoleOf("cpu-0-2-0"))
        assertEquals(ThermalRole.GPU, thermalRoleOf("gpuss-0"))
        assertEquals(ThermalRole.GPU, thermalRoleOf("gpuss-7"))
        assertEquals(ThermalRole.BATTERY, thermalRoleOf("batt-therm"))
        assertEquals(ThermalRole.BATTERY, thermalRoleOf("battery"))
        assertEquals(ThermalRole.BATTERY, thermalRoleOf("main_battery"))
        assertEquals(ThermalRole.SKIN, thermalRoleOf("quiet-therm"))
    }

    @Test
    fun `zones we have no use for are ignored rather than guessed at`() {
        assertNull(thermalRoleOf("pm8550_tz"))
        assertNull(thermalRoleOf("conn-therm"))
        assertNull(thermalRoleOf("pm8550-bcl-lvl0"))
        assertNull(thermalRoleOf(""))
    }

    /**
     * The bug this filter exists for. Both of these are real zones on the target
     * device, both sit permanently at 105 °C, and both are the hardware shutdown
     * trip rather than a sensor. Because a role reports the hottest of its zones
     * they won every sample, and the panel showed a steady CPU 105.0 °C while the
     * phone was at 37 °C.
     */
    @Test
    fun `a trip point is not a sensor, however much its name looks like one`() {
        assertNull(thermalRoleOf("cpu-hw-trip-0"))
        assertNull(thermalRoleOf("cpu-hw-trip-1"))
        assertNull(thermalRoleOf("gpu-trip-warn"))
        assertNull(thermalRoleOf("socd-shutdown"))
        assertNull(thermalRoleOf("cpu-limit-0"))
    }

    @Test
    fun `the cluster cache sensors still count as cpu`() {
        // Real zones, real readings around 38 C — dropping them would throw away
        // half the CPU thermal picture along with the trip points.
        assertEquals(ThermalRole.CPU, thermalRoleOf("cpullc-0-0"))
        assertEquals(ThermalRole.CPU, thermalRoleOf("cpullc-1-1"))
    }
}

class MetricFormatTest {

    @Test
    fun `values read the way an instrument prints them`() {
        assertEquals("5.86 W", formatWatts(5_862))
        assertEquals("0.42 W", formatWatts(420))
        // Charging is a negative draw; the sign is the caller's to explain.
        assertEquals("7.62 W", formatWatts(-7_620))
        assertEquals("512 MB", formatMegabytes(512))
        assertEquals("2.3 GB", formatMegabytes(2_412))
        assertEquals("41.3", formatDeciCelsius(413))
        assertEquals("806 MHz", formatMegahertz(806))
        assertEquals("3.32 GHz", formatMegahertz(3_321))
        assertEquals("3.80 GHz", formatMegahertz(3_801))
    }

    @Test
    fun `elapsed time is as short as it can be and still unambiguous`() {
        assertEquals("0s", formatElapsed(0L))
        assertEquals("9s", formatElapsed(9_400L))
        assertEquals("1m30s", formatElapsed(90_000L))
        assertEquals("2h05m", formatElapsed(7_500_000L))
    }
    // — frame statistics ---------------------------------------------------

    private fun framesOf(vararg fps: Float): MetricHistory =
        fps.foldIndexed(MetricHistory(capacity = 512)) { i, history, value ->
            history + MetricSample(elapsedMs = i * 1000L, fps = value)
        }

    @Test
    fun `frame stats are null until something has been composited`() {
        assertNull(MetricHistory().frameStats())
        assertNull((MetricHistory() + MetricSample(elapsedMs = 0)).frameStats())
    }

    @Test
    fun `the one percent low is the mean of the slowest one percent`() {
        // 200 samples: 198 at 60, two at 10 and 20. One percent of 200 is 2, so
        // the low is the mean of those two and not the single worst sample.
        val history = framesOf(*(FloatArray(198) { 60f } + floatArrayOf(10f, 20f)))
        val stats = history.frameStats()!!
        assertEquals(15f, stats.onePercentLow, 0.001f)
        assertEquals(10f, stats.min, 0.001f)
        assertEquals(60f, stats.peak, 0.001f)
    }

    @Test
    fun `a short run still has a one percent low, of its worst single sample`() {
        // Ten samples: 10/100 rounds to zero, and a statistic that divides by
        // zero or returns nothing on a short run is worse than a coarse one.
        val stats = framesOf(60f, 60f, 60f, 60f, 60f, 60f, 60f, 60f, 60f, 12f).frameStats()!!
        assertEquals(12f, stats.onePercentLow, 0.001f)
    }

    @Test
    fun `a session that drew nothing is measured, not missing`() {
        val stats = framesOf(0f, 0f, 0f).frameStats()!!
        assertTrue(stats.neverDrew)
        assertEquals(0f, stats.mean, 0.001f)
        // Three samples, no gaps: frames are counted by this app, so a zero is a
        // reading rather than an unreadable source.
        assertEquals(3, stats.samples)
    }

    @Test
    fun `current is the last sample, not the best one`() {
        val stats = framesOf(60f, 58f, 9f).frameStats()!!
        assertEquals(9f, stats.current!!, 0.001f)
        assertFalse(stats.neverDrew)
    }
}
