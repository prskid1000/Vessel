package app.vessel.core

/**
 * `VESSEL_TRACE` — one vocabulary for a stack that has five.
 *
 * **The problem this exists to remove.** Getting one question answered used to
 * mean knowing five tools' private vocabularies and which stop of each ladder
 * held the answer. "Show me the graphics path" is `WINEDEBUG=…+vulkan`, plus
 * `DXVK_LOG_LEVEL=debug`, plus `VKD3D_DEBUG=trace`, plus `VKD3D_SHADER_DEBUG`,
 * plus `MESA_LOG=file`, plus `TU_DEBUG=perf` — six settings in four grammars,
 * three of which are firehoses at the stop that finally shows anything. Two
 * twenty-minute device runs were spent on exactly that mistake and are recorded
 * at the `seh` and `vulkan` rows in [LOGGABLES]: `seh` at *Everything* produced
 * 191,000 lines of `RtlInitializeExtendedContext2` and zero exceptions, and
 * `vulkan` at *+ Stubs* produced nothing at all because that channel's output is
 * entirely TRACE.
 *
 * So this names **what you want to see**, not which tool prints it:
 *
 * ```
 * VESSEL_TRACE=graphics:stubs,x86:errors
 * VESSEL_TRACE=everything:warnings
 * VESSEL_TRACE=shaders                       # warnings, the default stop
 * ```
 *
 * One [TraceLevel] ladder across every topic, and each topic knows how to say
 * that stop in each component's own words. [TraceTopic.expansion] is the whole
 * translation table and it is data: adding a topic is one entry, and no `when`
 * anywhere else changes.
 *
 * **It composes through [Emit], not around it.** A topic emits the same
 * [Emit.WineChannel] / [Emit.Variable] / [Emit.ListMember] strategies a
 * hand-added row does, into the same [EmittedEnvironment], so the fixed prefix
 * is still un-deletable, `-all` is still first, and a row the user added still
 * wins over a topic — see [diagnosticEnvironment], which folds the topics before
 * the rows for exactly that reason.
 *
 * **Every stop carries a volume, and where the number comes from.** That is the
 * third of the three things a level ladder was measured to get wrong: it told
 * you the *order* of the stops and nothing about their cost, so `everything`
 * looked like one more notch rather than 49 MB. [TraceStop.volume] is a
 * lines-per-minute order of magnitude and [TraceStop.basis] says whether it was
 * measured on the device or counted out of the source, because an estimate
 * presented as a measurement is worse than no number.
 *
 * **It is also passed through to the guest**, unexpanded, and that is not
 * redundancy. Two things the expansion below cannot reach live inside the guest
 * and read this string themselves: relay scoping (`calls:<module>`, which is a
 * registry list in `dlls/ntdll/relay.c:179-183` and has no environment variable
 * at all) and the compiled-in function instrumentation. See `docs/TRACING.md`.
 */
const val TRACE_SPEC_ENV: String = "VESSEL_TRACE"

/**
 * The environment row that switches on the frame generation pipeline's own
 * reporting. See [ContainerDiagnostics.frameGenerationLog].
 *
 * Beside [TRACE_SPEC_ENV] because it is the same kind of thing: a row in the
 * environment table that Vessel reads itself rather than merely forwarding to
 * the guest. Nothing inside the container knows the compositor is inventing
 * frames, so nothing inside it could answer a question about them.
 */
const val FG_LOG_ENV: String = "FG_LOG"

/**
 * The one ladder, in the one order, for every topic.
 *
 * Five stops rather than each tool's own three-to-six, and the mapping is
 * declared per topic because the stops do not line up: vkd3d's `warn` already
 * carries `fixme` (`libs/vkd3d-common/debug.c:38-47` orders them
 * `none < err < info < fixme < warn < trace`), while Wine's four class bits are
 * independent and a channel's `fixme` tier is separate from its `warn` tier.
 *
 * [wire] is what a user types and what a container document stores; it must not
 * change once a document has been written with it.
 */
enum class TraceLevel(val wire: String, val label: String) {
    OFF("off", "Off"),

    /** Only what is already broken. Every topic is bounded here. */
    ERRORS("errors", "Errors"),

    /** The default stop for a topic named with no level. Bounded on most topics. */
    WARNINGS("warnings", "+ Warnings"),

    /** Calls that are stubbed rather than implemented. */
    STUBS("stubs", "+ Stubs"),

    /** The per-call tier. This is where every firehose in the stack lives. */
    EVERYTHING("everything", "Everything"),
    ;

    /**
     * The Wine class ladder this stop corresponds to.
     *
     * The two enums are deliberately not merged. [WineChannelLevel] is a
     * statement about one parser's four class bits and is serialised into
     * container documents by name; this one is a product-level vocabulary that
     * has to mean something for `DXVK_LOG_LEVEL` too. Keeping them apart is what
     * lets either move without rewriting stored documents of the other.
     */
    val wine: WineChannelLevel
        get() = when (this) {
            OFF -> WineChannelLevel.OFF
            ERRORS -> WineChannelLevel.ERRORS
            WARNINGS -> WineChannelLevel.WARNINGS
            STUBS -> WineChannelLevel.STUBS
            EVERYTHING -> WineChannelLevel.EVERYTHING
        }

    companion object {
        /**
         * A topic named with no level.
         *
         * Warnings and not Errors: every channel already inherits ERR from
         * `err+all` in [WINEDEBUG_CHANNELS], so a bare topic that meant *Errors*
         * would be a term that changes nothing — the exact "the switch did
         * nothing" failure this file exists to remove.
         */
        val DEFAULT: TraceLevel = WARNINGS

        fun ofWire(wire: String): TraceLevel? =
            entries.firstOrNull { it.wire.equals(wire.trim(), ignoreCase = true) }
    }
}

/**
 * How loud one stop of one topic is, and how that is known.
 *
 * @param linesPerMinute an order of magnitude, not a prediction. Zero means the
 *   stop emits only on a failure, so a healthy session sees nothing.
 * @param basis where the number came from, in one clause. **Measured** names a
 *   device session; **counted** names source sites; **estimated** is a guess and
 *   says so. A guess dressed as a measurement is the thing this field exists to
 *   prevent, so it is required rather than defaulted.
 */
data class TraceStop(
    val level: TraceLevel,
    val linesPerMinute: Long,
    val basis: String,
    /** Applied to an [EmittedEnvironment] exactly as a hand-added row is. */
    val emits: List<Pair<Emit, String>> = emptyList(),
)

/**
 * One thing a person wants to see, across whichever components produce it.
 *
 * @param name what the user types. Lower case, one word, no punctuation — the
 *   spec is parsed by splitting on `,` and `:` and a name containing either
 *   would be unreachable.
 * @param secondary one sentence, in the terms of the question rather than of the
 *   tool. The audience is someone whose session is already broken.
 * @param stops every stop this topic has, quietest first. A topic need not offer
 *   all five: [TraceTopic.CALLS] has no meaningful middle, and offering one
 *   would be the `vulkan`-at-*Stubs* mistake again.
 */
data class TraceTopic(
    val name: String,
    val secondary: String,
    val stops: List<TraceStop>,
) {
    /** The stop at or below [level] — the loudest this topic can honestly do. */
    fun stopFor(level: TraceLevel): TraceStop? =
        stops.lastOrNull { it.level.ordinal <= level.ordinal }?.takeIf { it.level != TraceLevel.OFF }

    companion object {
        const val GRAPHICS = "graphics"
        const val D3D = "d3d"
        const val SHADERS = "shaders"
        const val DRIVER = "driver"
        const val X86 = "x86"
        const val LOADER = "loader"
        const val AUDIO = "audio"
        const val INPUT = "input"
        const val SYNC = "sync"
        const val EXCEPTIONS = "exceptions"
        const val CALLS = "calls"

        /** Not a topic: the word that means every topic at the named stop. */
        const val EVERY = "all"
    }
}

// — the translation table -----------------------------------------------------
//
// Everything below is data. Each stop lists the `Emit` strategies it applies and
// the value each is applied with, in that component's own vocabulary — which is
// deliberately *not* normalised, because the value that ends up in the
// environment has to be a word the tool's own parser accepts.

private fun wine(channel: String, level: TraceLevel): Pair<Emit, String> =
    Emit.WineChannel(channel) to level.wine.name

private fun variable(name: String, baseline: String, value: String): Pair<Emit, String> =
    Emit.Variable(name, baseline) to value

private fun turnip(flag: String): Pair<Emit, String> =
    Emit.ListMember("TU_DEBUG", flag) to Emit.ON

/**
 * Every topic, in the order the help text lists them.
 *
 * Ordered by how often the question is asked rather than alphabetically:
 * graphics first, because every session on this device currently ends in the
 * present path.
 */
val TRACE_TOPICS: List<TraceTopic> = listOf(
    TraceTopic(
        name = TraceTopic.GRAPHICS,
        secondary = "The whole path from a draw call to a pixel: how Wine found the " +
            "driver, both Direct3D translators, and the driver itself.",
        stops = listOf(
            TraceStop(
                TraceLevel.ERRORS,
                linesPerMinute = 0,
                basis = "measured — a healthy six-minute Resident Evil Requiem session " +
                    "logged 350 lines from every non-FEX source combined",
                emits = listOf(
                    wine("vulkan", TraceLevel.ERRORS),
                    variable("DXVK_LOG_LEVEL", FIXED_DXVK_LOG_LEVEL, "error"),
                    variable("VKD3D_DEBUG", FIXED_VKD3D_DEBUG, "err"),
                ),
            ),
            TraceStop(
                TraceLevel.WARNINGS,
                linesPerMinute = 4_500,
                basis = "measured — 26,966 lines in six minutes, one burst per shader " +
                    "across 3,025 shaders. **They come from `VKD3D_SHADER_DEBUG`, not " +
                    "`VKD3D_DEBUG`** — see the `shaders` topic, which is the one that " +
                    "silences them",
                emits = listOf(
                    wine("vulkan", TraceLevel.WARNINGS),
                    variable("DXVK_LOG_LEVEL", FIXED_DXVK_LOG_LEVEL, "warn"),
                    variable("VKD3D_DEBUG", FIXED_VKD3D_DEBUG, "warn"),
                    variable("VKD3D_SHADER_DEBUG", FIXED_VKD3D_SHADER_DEBUG, "warn"),
                    variable(MESA_LOG_ENV, FIXED_MESA_LOG, "file"),
                    variable(MESA_LOG_LEVEL_ENV, FIXED_MESA_LOG_LEVEL, "warning"),
                ),
            ),
            TraceStop(
                TraceLevel.STUBS,
                linesPerMinute = 6_000,
                basis = "estimated — the warnings figure plus DXVK's `info`, which is " +
                    "bounded by device and pipeline setup rather than by frames. Wine's " +
                    "`vulkan` channel is deliberately *not* raised here: it has no FIXME " +
                    "tier, so a stop between warnings and everything would be silence " +
                    "sold as an increment",
                emits = listOf(
                    wine("vulkan", TraceLevel.WARNINGS),
                    variable("DXVK_LOG_LEVEL", FIXED_DXVK_LOG_LEVEL, "info"),
                    variable("VKD3D_DEBUG", FIXED_VKD3D_DEBUG, "warn"),
                    variable("VKD3D_SHADER_DEBUG", FIXED_VKD3D_SHADER_DEBUG, "warn"),
                    variable(MESA_LOG_ENV, FIXED_MESA_LOG, "file"),
                    variable(MESA_LOG_LEVEL_ENV, FIXED_MESA_LOG_LEVEL, "info"),
                    turnip("perf"),
                ),
            ),
            TraceStop(
                TraceLevel.EVERYTHING,
                linesPerMinute = 2_000_000,
                basis = "estimated — one line per Vulkan call on three layers at once, so " +
                    "a drawn frame is thousands and a second is tens of thousands. Not " +
                    "run to completion on the device; the log hits its cap first",
                emits = listOf(
                    wine("vulkan", TraceLevel.EVERYTHING),
                    variable("DXVK_LOG_LEVEL", FIXED_DXVK_LOG_LEVEL, "trace"),
                    variable("VKD3D_DEBUG", FIXED_VKD3D_DEBUG, "trace"),
                    variable("VKD3D_SHADER_DEBUG", FIXED_VKD3D_SHADER_DEBUG, "trace"),
                    variable(MESA_LOG_ENV, FIXED_MESA_LOG, "file"),
                    variable(MESA_LOG_LEVEL_ENV, FIXED_MESA_LOG_LEVEL, "debug"),
                    turnip("perf"),
                ),
            ),
        ),
    ),

    TraceTopic(
        name = TraceTopic.D3D,
        secondary = "The two Direct3D translators only — no Wine channel and no driver. " +
            "What to use when the driver is known good and the question is which " +
            "translator is unhappy.",
        stops = listOf(
            TraceStop(
                TraceLevel.ERRORS,
                linesPerMinute = 0,
                basis = "measured — the shipped defaults with vkd3d lowered to `err` " +
                    "produced a 61 KB six-minute session",
                emits = listOf(
                    variable("DXVK_LOG_LEVEL", FIXED_DXVK_LOG_LEVEL, "error"),
                    variable("VKD3D_DEBUG", FIXED_VKD3D_DEBUG, "err"),
                ),
            ),
            TraceStop(
                TraceLevel.WARNINGS,
                linesPerMinute = 20,
                basis = "counted — vkd3d's API channel warns on a missing extension and " +
                    "on a rejected feature, at init. The 26,966-line burst attributed to " +
                    "this variable came from `VKD3D_SHADER_DEBUG` instead; see `shaders`",
                emits = listOf(
                    variable("DXVK_LOG_LEVEL", FIXED_DXVK_LOG_LEVEL, "warn"),
                    variable("VKD3D_DEBUG", FIXED_VKD3D_DEBUG, "warn"),
                ),
            ),
            TraceStop(
                TraceLevel.STUBS,
                linesPerMinute = 5_000,
                basis = "estimated — DXVK's `info` names the adapter, the extension list " +
                    "and every rejected feature once, at device creation",
                emits = listOf(
                    variable("DXVK_LOG_LEVEL", FIXED_DXVK_LOG_LEVEL, "info"),
                    variable("VKD3D_DEBUG", FIXED_VKD3D_DEBUG, "warn"),
                ),
            ),
            TraceStop(
                TraceLevel.EVERYTHING,
                linesPerMinute = 1_500_000,
                basis = "estimated — one line per Direct3D call from both layers",
                emits = listOf(
                    variable("DXVK_LOG_LEVEL", FIXED_DXVK_LOG_LEVEL, "trace"),
                    variable("VKD3D_DEBUG", FIXED_VKD3D_DEBUG, "trace"),
                ),
            ),
        ),
    ),

    TraceTopic(
        name = TraceTopic.SHADERS,
        secondary = "Shader translation only. `VKD3D_DEBUG` does not carry it — the two " +
            "are separate channels with independent levels, which is the single most " +
            "common reason a shader failure is invisible. It is also where the biggest " +
            "measured burst in this stack comes from.",
        // **This topic is where a mis-attribution was found, and correcting it is
        // worth more than the topic itself.** The 26,966-line burst — 98% of a
        // session's output once FEX was silenced, `skip_dword_unknown` and
        // `parse_dxbc: Ignoring DXBC checksum`, one burst per shader across
        // 3,025 shaders — was attributed to `VKD3D_DEBUG` and mitigated by
        // setting `VKD3D_DEBUG=err` on the container.
        //
        // That mitigation cannot have worked. Both messages are emitted from
        // `libs/vkd3d-shader/dxbc.c:66-73` and `:124-125`, and that translation
        // unit opens with `#define VKD3D_DBG_CHANNEL VKD3D_DBG_CHANNEL_SHADER`
        // (`dxbc.c:20`). `libs/vkd3d-common/debug.c:49-53` maps that channel to
        // `VKD3D_SHADER_DEBUG` and the API channel to `VKD3D_DEBUG`, and the
        // gate at `:181` compares only the channel's own level. So the variable
        // that was lowered has no bearing on the lines that were counted.
        //
        // Worse, the burst was **Vessel's own doing rather than upstream's**.
        // `debug.c:96-97` defaults an unset channel to `FIXME` (4), and both
        // messages are `WARN` (5) in a ladder where `warn` is *above* `fixme`
        // (`debug.c:38-47`) — so upstream was silent for both and that session
        // was not, because [FIXED_VKD3D_SHADER_DEBUG] was `warn` at the time.
        //
        // **Past tense on purpose: the constant is `fixme` as of `a647bb5`
        // (`ContainerDiagnostics.kt:1711`, and its neighbour `FIXED_VKD3D_DEBUG`
        // at `:1699`), which is vkd3d's own default.** So the default session no
        // longer emits either message, and the ~62% fall in default log volume
        // that commit measured is mostly this. Nothing above is retracted — the
        // mis-attribution was real, the mechanism is right, and the 26,966 lines
        // were really counted — it is only that the tier they were counted at is
        // now something a reader has to *ask* for rather than the baseline. The
        // `warnings` stop below is where that ask lives, which is why its
        // 4,500 lines/minute is still the right figure for it and why the
        // arithmetic underneath is still worth carrying.
        //
        // This line said "because `FIXED_VKD3D_SHADER_DEBUG` is `warn`" from
        // `a647bb5` until this correction — the same day, and still long enough
        // for `docs/TODO.md` to have to carry it as an open item. It is recorded
        // rather than quietly corrected because the failure is structural and
        // will recur: a comment that restates a constant's *value* goes stale
        // silently and nothing compiles it, where one that names the constant
        // does not. Hence the link form above.
        //
        // One arithmetic consequence worth keeping: `dxbc.c:124-125` is a
        // checksum warning immediately followed by `skip_dword_unknown(&ptr, 4)`,
        // which prints a heading plus one line per DWORD. That is six lines per
        // parse, plus two more at `:193`, which is 8 × 3,025 ≈ 24,200 — within
        // 10% of the 26,966 that were counted. The mechanism is confirmed by the
        // count, not merely by the grep.
        stops = listOf(
            TraceStop(
                TraceLevel.ERRORS,
                linesPerMinute = 0,
                basis = "counted — fires per failed translation, and a session that " +
                    "renders has none",
                emits = listOf(variable("VKD3D_SHADER_DEBUG", FIXED_VKD3D_SHADER_DEBUG, "err")),
            ),
            // **No `stubs` stop, because vkd3d inverts the two.** Its ladder is
            // `none < err < info < fixme < warn < trace` (`debug.c:38-47`), so
            // `fixme` is *quieter* than `warn` there and louder than it here.
            // There is therefore no value that means "warnings but not stubs",
            // and offering a `stubs` stop that emitted `warn` again would be a
            // fourth control doing what the third already did. `fixme` is not
            // lost: it is the new baseline — see [FIXED_VKD3D_SHADER_DEBUG].
            TraceStop(
                TraceLevel.WARNINGS,
                linesPerMinute = 4_500,
                basis = "measured — 26,966 lines in six minutes; eight lines per DXBC " +
                    "parse from `dxbc.c:124-125` and `:193`, times 3,025 shaders is " +
                    "24,200, which is within 10% of what was counted",
                emits = listOf(variable("VKD3D_SHADER_DEBUG", FIXED_VKD3D_SHADER_DEBUG, "warn")),
            ),
            TraceStop(
                TraceLevel.EVERYTHING,
                linesPerMinute = 300_000,
                basis = "estimated — the per-instruction tier, times 3,025 shaders",
                emits = listOf(variable("VKD3D_SHADER_DEBUG", FIXED_VKD3D_SHADER_DEBUG, "trace")),
            ),
        ),
    ),

    TraceTopic(
        name = TraceTopic.DRIVER,
        secondary = "Turnip. Nothing it prints reaches this log until Mesa's file logger " +
            "is on, because its Android default is logcat and Vessel reads no logcat.",
        stops = listOf(
            // Every stop sets `MESA_LOG=file` and none of them is optional about
            // it: `mesa_log_init_once` picks `MESA_LOG_CONTROL_ANDROID` when no
            // logger bit is set on Android (`src/util/log.c:119-132`), which is
            // `__android_log_write` at `:371-396`, and Vessel reads no logcat.
            // Without this variable every other setting here is a switch whose
            // output nothing can read.
            TraceStop(
                TraceLevel.ERRORS,
                linesPerMinute = 1,
                basis = "counted — `TU_DEBUG=startup` is one line, and it is the only " +
                    "ground truth that Turnip loaded at all",
                emits = listOf(
                    variable(MESA_LOG_ENV, FIXED_MESA_LOG, "file"),
                    variable(MESA_LOG_LEVEL_ENV, FIXED_MESA_LOG_LEVEL, "error"),
                ),
            ),
            TraceStop(
                TraceLevel.WARNINGS,
                linesPerMinute = 1,
                basis = "counted — Mesa's own warnings are init-time and few. Note the " +
                    "word is `warning` and not `warn`: `level_from_str` matches the four " +
                    "names at `src/util/log.c:76-89` exactly, and an unrecognised one " +
                    "silently reverts to the build default",
                emits = listOf(
                    variable(MESA_LOG_ENV, FIXED_MESA_LOG, "file"),
                    variable(MESA_LOG_LEVEL_ENV, FIXED_MESA_LOG_LEVEL, "warning"),
                ),
            ),
            TraceStop(
                TraceLevel.STUBS,
                linesPerMinute = 3_000,
                basis = "estimated — `perf` is one record per render pass and this title " +
                    "averages about twenty draws a pass",
                emits = listOf(
                    variable(MESA_LOG_ENV, FIXED_MESA_LOG, "file"),
                    variable(MESA_LOG_LEVEL_ENV, FIXED_MESA_LOG_LEVEL, "info"),
                    turnip("perf"),
                ),
            ),
            TraceStop(
                TraceLevel.EVERYTHING,
                linesPerMinute = 6_000,
                basis = "estimated — `perf` plus the skipped-tile counters, both per pass",
                emits = listOf(
                    variable(MESA_LOG_ENV, FIXED_MESA_LOG, "file"),
                    variable(MESA_LOG_LEVEL_ENV, FIXED_MESA_LOG_LEVEL, "debug"),
                    turnip("perf"),
                    turnip("log_skip_gmem_ops"),
                ),
            ),
        ),
    ),

    TraceTopic(
        name = TraceTopic.X86,
        secondary = "The x86 translator. Silent by default, and silence hides its " +
            "configuration mistakes as well as its crashes.",
        // **One stop, and the missing middle is the honest part.** FEX's Windows
        // logging init is `Source/Windows/Common/Logging.cpp:36-49` in its
        // entirety and it reads `SilentLog` and nothing else: there is no level,
        // so every message the build contains is either all on or all off. And
        // `MSG_LEVEL` is a `constexpr = INFO` in
        // `FEXCore/Include/FEXCore/Utils/LogManager.h:41`, so `DFmt` is compiled
        // in and cannot be built out either.
        //
        // Offering `errors` and `warnings` as separate stops here would be four
        // controls that all do the same thing, which is precisely the ladder
        // defect this file exists to fix. One stop, with the measurement on it.
        //
        // **`patches/fex/0003` gives it real stops, and this table must not
        // claim them until that patch is in the shipped component.** The patch
        // adds a level ceiling to `MsgHandler` — defaulting to ERROR, raised by
        // this very topic, which FEX reads out of `VESSEL_TRACE` itself because
        // the Kotlin side has no variable to express it through. When the
        // component is rebuilt, this topic gains `stubs` (FEX's DEBUG tier, the
        // unaligned-atomic flood) and `everything` (its INFO tier), and the stop
        // below drops to a few dozen lines a minute. Adding those stops now
        // would put a figure on this screen that the running binary does not
        // produce, which is the one thing a volume hint may never do.
        stops = listOf(
            TraceStop(
                TraceLevel.ERRORS,
                linesPerMinute = 85_000,
                basis = "measured on the device 2026-08-13 — one Resident Evil Requiem " +
                    "session of about six minutes produced 49 MB and ~508,000 lines, of " +
                    "which 99.9% were a single pair per unaligned atomic from " +
                    "`LogMan::Msg::DFmt` at `Source/Windows/ARM64EC/Module.cpp:716`. " +
                    "There is no quieter stop: see the comment above",
                emits = listOf(variable("FEX_SILENTLOG", FIXED_FEX_SILENTLOG, "0")),
            ),
        ),
    ),

    TraceTopic(
        name = TraceTopic.LOADER,
        secondary = "What the program loaded, what it could not find, and which exports " +
            "were missing from what it did find.",
        stops = listOf(
            TraceStop(
                TraceLevel.ERRORS,
                linesPerMinute = 0,
                basis = "counted — this is already the shipped baseline; `err+all` and " +
                    "`+loaddll` are in `WINEDEBUG_CHANNELS`",
                emits = listOf(wine("module", TraceLevel.ERRORS)),
            ),
            TraceStop(
                TraceLevel.WARNINGS,
                linesPerMinute = 5,
                basis = "counted — `dlls/ntdll/loader.c` has 14 WARN sites on this " +
                    "channel and they fire once per missing export",
                emits = listOf(wine("module", TraceLevel.WARNINGS)),
            ),
            TraceStop(
                TraceLevel.EVERYTHING,
                linesPerMinute = 40_000,
                basis = "counted — 46 TRACE sites in `loader.c` alone plus six other " +
                    "files defaulting to this channel, and `file` fires per open",
                emits = listOf(
                    wine("module", TraceLevel.EVERYTHING),
                    wine("file", TraceLevel.EVERYTHING),
                ),
            ),
        ),
    ),

    TraceTopic(
        name = TraceTopic.AUDIO,
        secondary = "What the guest wrote and what the device took. Its per-top-up tier " +
            "is what tells a stalled timer apart from a guest producing no frames.",
        stops = listOf(
            TraceStop(
                TraceLevel.ERRORS,
                linesPerMinute = 0,
                basis = "counted — already inherited from `err+all`",
                emits = listOf(wine("oss", TraceLevel.ERRORS)),
            ),
            TraceStop(
                TraceLevel.WARNINGS,
                linesPerMinute = 1,
                basis = "counted — the AAudio driver in `patches/wine/0008` warns on " +
                    "underrun and on a rejected format, and nothing else",
                emits = listOf(wine("oss", TraceLevel.WARNINGS)),
            ),
            TraceStop(
                TraceLevel.EVERYTHING,
                linesPerMinute = 600,
                basis = "counted — one line per top-up at ten a second, naming the queue " +
                    "depth. Bounded by the period rather than by the workload",
                emits = listOf(wine("oss", TraceLevel.EVERYTHING)),
            ),
        ),
    ),

    TraceTopic(
        name = TraceTopic.INPUT,
        secondary = "The gamepad bridge and the window messages it turns into.",
        stops = listOf(
            TraceStop(
                TraceLevel.ERRORS,
                linesPerMinute = 0,
                basis = "counted — already inherited from `err+all`",
                emits = listOf(wine("winebus", TraceLevel.ERRORS)),
            ),
            TraceStop(
                TraceLevel.WARNINGS,
                linesPerMinute = 2,
                basis = "counted — the bus in `patches/wine/0016` warns on a malformed " +
                    "frame, which is a bug rather than a rate",
                emits = listOf(wine("winebus", TraceLevel.WARNINGS)),
            ),
            TraceStop(
                TraceLevel.EVERYTHING,
                linesPerMinute = 120_000,
                basis = "estimated — `msg` fires on every window message including mouse " +
                    "movement, so it fires whenever the screen is touched",
                emits = listOf(
                    wine("winebus", TraceLevel.EVERYTHING),
                    wine("msg", TraceLevel.EVERYTHING),
                ),
            ),
        ),
    ),

    TraceTopic(
        name = TraceTopic.SYNC,
        secondary = "Whether fast synchronisation started, and every wait it serves. The " +
            "only place the answer to \"is fsync actually on?\" is written.",
        stops = listOf(
            TraceStop(
                TraceLevel.ERRORS,
                linesPerMinute = 0,
                basis = "counted — `fsync_init()`'s own ERR lines name the futex_waitv " +
                    "and shared-memory cases separately, and fire once",
                emits = listOf(wine("fsync", TraceLevel.ERRORS)),
            ),
            TraceStop(
                TraceLevel.EVERYTHING,
                linesPerMinute = 400_000,
                basis = "estimated — one line per wait and wake on every primitive, " +
                    "including idle ones. This is the input to " +
                    "`programs/vkd3d-fsync-log-to-profile.py`",
                emits = listOf(
                    wine("fsync", TraceLevel.EVERYTHING),
                    wine("sync", TraceLevel.EVERYTHING),
                ),
            ),
        ),
    ),

    TraceTopic(
        name = TraceTopic.EXCEPTIONS,
        secondary = "Every exception raised, handled or not. Note that an unhandled one " +
            "is printed without this — `format_exception_msg` does not go through the " +
            "channel system at all.",
        // Only two stops, and the gap is the measurement. `seh` at *Everything*
        // was run once on the device for 20 minutes and produced 191,000 lines
        // of `RtlInitializeExtendedContext2` with no dispatch in them; its ERR
        // tier was already on through `err+all`, so the run answered nothing and
        // cost a session. Offering three intermediate stops that behave
        // identically is what made that mistake available.
        stops = listOf(
            TraceStop(
                TraceLevel.ERRORS,
                linesPerMinute = 0,
                basis = "measured — already on through `err+all`, which is why raising " +
                    "this channel to its middle stops changes nothing",
                emits = listOf(wine("seh", TraceLevel.ERRORS)),
            ),
            TraceStop(
                TraceLevel.EVERYTHING,
                linesPerMinute = 10_000,
                basis = "measured on the device — 191,000 lines in one ~20-minute " +
                    "session, almost all of them `RtlInitializeExtendedContext2`",
                emits = listOf(wine("seh", TraceLevel.EVERYTHING)),
            ),
        ),
    ),

    TraceTopic(
        name = TraceTopic.CALLS,
        secondary = "Names every call between libraries as it happens, with arguments and " +
            "return values. Scope it to one module or it is hundreds of megabytes.",
        // **The one topic whose useful form this layer cannot compose**, and it
        // is why `VESSEL_TRACE` is passed through to the guest as well as
        // expanded here. Wine's relay scoping is a registry list —
        // `RelayInclude`, `RelayExclude`, `RelayFromInclude` and their Snoop
        // counterparts, read once from `HKCU\Software\Wine\Debug` by
        // `init_debug_lists` at `dlls/ntdll/relay.c:162-190` — and there is no
        // environment variable for any of them. So `calls` on its own is the
        // unscoped firehose, and `calls:<module>` needs a guest-side reader.
        //
        // It is also unproven on this base and that must not be glossed: relay
        // is compiled out for arm64ec outright —
        // `#if (…) && !defined(__arm64ec__)` at `dlls/ntdll/relay.c:37` — and
        // Vessel's PE modules are ARM64X. Whether the aarch64 half of an ARM64X
        // module still gets relay thunks has not been observed. One session at
        // this topic with any output at all settles it; until then the volume
        // below is an upper bound on a thing that may print nothing.
        stops = listOf(
            TraceStop(
                TraceLevel.EVERYTHING,
                linesPerMinute = 20_000_000,
                basis = "documented — \"hundreds of megabytes in seconds\". Unverified on " +
                    "this ARM64EC base, where relay may be compiled out entirely",
                emits = listOf(wine("relay", TraceLevel.EVERYTHING)),
            ),
        ),
    ),
)

/** The topic called [name], or null. */
fun traceTopicFor(name: String): TraceTopic? =
    TRACE_TOPICS.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

// — parsing -------------------------------------------------------------------

/**
 * One term of a spec, after parsing. [problem] is non-null for a term that will
 * do nothing, which is the state the whole file exists to make visible.
 */
data class TraceTerm(
    val raw: String,
    val topic: TraceTopic?,
    val level: TraceLevel,
    val problem: String? = null,
) {
    /** The stop this term actually reaches, which may be quieter than asked for. */
    val stop: TraceStop? get() = topic?.stopFor(level)
}

/**
 * A whole `VESSEL_TRACE` value, parsed.
 *
 * **Nothing here throws and nothing is silently dropped.** A misspelled topic
 * produces a [TraceTerm] carrying a [TraceTerm.problem] rather than an absence,
 * because the failure this replaces — a setting that looks applied and does
 * nothing — is the one that costs a twenty-minute device run.
 */
data class TraceSpec(
    val terms: List<TraceTerm>,
) {
    val isEmpty: Boolean get() = terms.isEmpty()

    /** Terms that will produce no output, with the reason. Shown, never swallowed. */
    val problems: List<TraceTerm> get() = terms.filter { it.problem != null }

    /**
     * The whole spec's expected volume, summed over its terms.
     *
     * Summed rather than maxed: two topics both at *Everything* really do cost
     * both. Zero means every term only fires on a failure.
     */
    val linesPerMinute: Long get() = terms.sumOf { it.stop?.linesPerMinute ?: 0L }

    /**
     * How long this spec takes to fill a session's whole byte budget.
     *
     * The arithmetic [SessionLogLimits] already states, run the other way:
     * at roughly 120 bytes a line, `(head + tail) / (lines/min × 120)` minutes.
     * Null when the spec emits only on failure, which never fills anything.
     */
    fun minutesToFill(limits: SessionLogLimits): Double? {
        val rate = linesPerMinute
        if (rate <= 0) return null
        return limits.worstCaseBytesPerSession.toDouble() / (rate * BYTES_PER_LINE)
    }

    companion object {
        /**
         * The same 120 bytes [SessionLogLimits] uses for its own arithmetic.
         * Named once so the two figures cannot drift apart.
         */
        const val BYTES_PER_LINE: Int = 120

        val EMPTY: TraceSpec = TraceSpec(emptyList())
    }
}

/**
 * Parse a `VESSEL_TRACE` value. Never throws; never returns null.
 *
 * `all` expands to every topic at the named level, and is a word rather than a
 * topic because a topic called "all" would need a stop table describing every
 * other topic's stops at once.
 */
fun parseTraceSpec(spec: String): TraceSpec {
    val terms = spec.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .flatMap { term -> parseTerm(term) }
    return TraceSpec(terms)
}

private fun parseTerm(term: String): List<TraceTerm> {
    val name = term.substringBefore(':').trim()
    val levelWord = term.substringAfter(':', "").trim()
    val level = if (levelWord.isEmpty()) TraceLevel.DEFAULT else TraceLevel.ofWire(levelWord)

    if (level == null) {
        return listOf(
            TraceTerm(
                raw = term,
                topic = traceTopicFor(name),
                level = TraceLevel.DEFAULT,
                problem = "\"$levelWord\" is not a level. The levels are " +
                    TraceLevel.entries.joinToString(", ") { it.wire } + ".",
            ),
        )
    }

    if (name.equals(TraceTopic.EVERY, ignoreCase = true)) {
        return TRACE_TOPICS.map { topic -> resolve(term, topic, level) }
    }

    val topic = traceTopicFor(name)
        ?: return listOf(
            TraceTerm(
                raw = term,
                topic = null,
                level = level,
                problem = "\"$name\" is not a topic. The topics are " +
                    TRACE_TOPICS.joinToString(", ") { it.name } + ", and all.",
            ),
        )

    return listOf(resolve(term, topic, level))
}

/**
 * One resolved term, with a problem attached when the stop asked for is not one
 * this topic has.
 *
 * A topic that lacks the requested stop falls back to the loudest quieter one it
 * does have — and **says so**, because silently rounding down is how `vulkan` at
 * *+ Stubs* came back empty. Rounding *up* would be worse: a user asking for
 * warnings must never be handed a firehose.
 */
private fun resolve(raw: String, topic: TraceTopic, level: TraceLevel): TraceTerm {
    if (level == TraceLevel.OFF) {
        return TraceTerm(raw, topic, level, problem = null)
    }
    val stop = topic.stopFor(level)
        ?: return TraceTerm(
            raw = raw,
            topic = topic,
            level = level,
            problem = "${topic.name} has no stop at or below ${level.wire}. Its stops are " +
                topic.stops.joinToString(", ") { it.level.wire } + ".",
        )
    val problem = if (stop.level == level) {
        null
    } else {
        "${topic.name} has no ${level.wire} stop, so it is running at ${stop.level.wire} " +
            "instead — the loudest quieter stop it has."
    }
    return TraceTerm(raw, topic, level, problem)
}

// — composition ---------------------------------------------------------------

/**
 * Apply a parsed spec to an [EmittedEnvironment].
 *
 * Applied **before** the hand-added rows in [diagnosticEnvironment], so a row
 * wins: the topic is the broad brush and the row is the instrument, and someone
 * who has typed both means the second one. That ordering is free rather than
 * arranged — Wine's parser takes the last term and a `LinkedHashMap` takes the
 * last `put`.
 *
 * A term with a [TraceTerm.problem] that resolved to no stop contributes
 * nothing; a term that rounded *down* contributes its quieter stop, which is
 * what its problem text says it will do.
 */
fun applyTraceSpec(spec: TraceSpec, out: EmittedEnvironment) {
    for (term in spec.terms) {
        val stop = term.stop ?: continue
        for ((emit, value) in stop.emits) emit.apply(value, out)
    }
}

/**
 * The help text the env table shows under `VESSEL_TRACE`.
 *
 * Built from [TRACE_TOPICS] rather than written out, so a topic added to the
 * table is a topic the help mentions. Kept to the names and the levels: the
 * per-topic sentence is longer than an env-table row can hold, and
 * `docs/TRACING.md` is where it lives.
 */
fun traceSpecHelp(): String =
    "One name for what you want to see, not five tools' ladders. " +
        "Topics: " + TRACE_TOPICS.joinToString(", ") { it.name } + ", all. " +
        "Levels: " + TraceLevel.entries.joinToString(", ") { it.wire } +
        " (warnings if you name none). Example: graphics:stubs,x86:errors."

/** Mesa's logger variable, named once so the topics and the rows cannot drift. */
private const val MESA_LOG_ENV = "MESA_LOG"

/**
 * Mesa's *level*, which is a separate variable from its *logger* and was missing
 * from this project's model of Mesa entirely.
 *
 * `MESA_LOG` chooses the sink (`null`, `file`, `syslog`, `android`, `windbg` —
 * `src/util/log.c:64-74`) and `MESA_LOG_LEVEL` chooses the severity floor
 * (`src/util/log.c:134-137`). The pair matters because the level's release-build
 * default is `MESA_LOG_INFO` (`src/util/log.h:50-53`), so switching the logger on
 * without saying anything about the level gets INFO and everything above it.
 */
private const val MESA_LOG_LEVEL_ENV = "MESA_LOG_LEVEL"
