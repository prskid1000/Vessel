# Vessel design system

## Principle

Vessel is an instrument, not a launcher. It shows machine facts — driver
builds, translation engines, frame times, memory-ordering flags — to someone
who wants to see them. The design goal is the feel of good professional
software: dense, precise, calm, and confident. Not gamer chrome, not a toy.

**The interface is designed from scratch.** Vessel reuses proven *engine* code
from the Winlator lineage — container setup, the X server, driver loading — but
none of its interface. No screen here is a restyled Winlator screen. Those apps
grew by accretion: settings scattered across nested dialogs, terminology that
assumes you already know what Box64 is, and layouts that expose the
implementation rather than the task. Vessel starts from what the user is trying
to do — run a program — and works backward. Depth is available on every screen,
but never in the way.

Concretely, that means: four bottom-nav destinations and no more; the common
path (open the app, launch a program) is two taps from cold start; every
advanced knob is explained in one plain sentence next to itself; and defaults
are correct for this device so a new container needs zero configuration.

Four rules everything follows:

1. **Color carries meaning.** Architecture, health, and state are colored.
   Decoration is not.
2. **Machine facts are monospaced.** Versions, flags, hashes, frame times, and
   log output are set in mono with tabular figures. This single choice does
   most of the work of looking technical and trustworthy.
3. **Flat and precise over soft and shadowed.** Hairline borders and elevation
   by surface tone, not drop shadows.
4. **Motion is confirmation, never decoration.** 150 ms; no bounce.

## Color

Dark-first. The palette is defined as tokens and passed down through
`CompositionLocalProvider`, following the pattern the On-Device AI app uses —
no reliance on Material dynamic color, so the product looks the same on every
device.

### Neutrals

| Token | Value | Use |
|---|---|---|
| `bg` | `#0A0C0F` | Window background |
| `surface` | `#12161B` | Cards, sheets, bars |
| `surfaceRaised` | `#1A1F26` | Nested surfaces, inputs, hovered rows |
| `surfaceSunken` | `#070A0D` | Log panes, code blocks, wells |
| `border` | `#2A323C` | Hairline dividers and card edges |
| `borderStrong` | `#3A444F` | Focus rings, selected edges |
| `textPrimary` | `#E6EDF3` | Titles, values |
| `textSecondary` | `#9AA7B4` | Labels, supporting copy |
| `textTertiary` | `#6B7885` | Metadata, disabled |

### Accent

| Token | Value | Use |
|---|---|---|
| `accent` | `#4CC9F0` | Primary action, focus, active nav |
| `accentPressed` | `#35A8CC` | Pressed state |
| `accentSoft` | `#4CC9F0` @ 14% | Chip and badge fills |

### Architecture palette — functional, never decorative

The single most distinctive element of the UI. Every executable and container
is tagged with the architecture it runs, and the color is consistent everywhere
it appears.

| Token | Value | Meaning |
|---|---|---|
| `archNative` | `#3FD98B` green | ARM64 / ARM64EC — runs natively, no translation |
| `archX64` | `#4CC9F0` cyan | x86-64 — translated by FEX |
| `archX86` | `#B98CFF` violet | x86-32 — WoW64 path |
| `archUnknown` | `#6B7885` grey | Not yet inspected or unreadable |

Green reads as "free", and that is exactly what a native ARM64 app is.

### Status

| Token | Value |
|---|---|
| `ok` | `#3FD98B` |
| `warn` | `#F5B14C` |
| `danger` | `#FF6B6B` |
| `info` | `#4CC9F0` |

Telemetry graphs interpolate `accent` → `#B98CFF` (violet) for load ramps and
switch to `warn`/`danger` past thresholds.

## Typography

| Role | Family | Size / line |
|---|---|---|
| `display` | Inter 700, tracking −0.02em | 28 / 34 |
| `title` | Inter 600, tracking −0.01em | 20 / 26 |
| `subtitle` | Inter 600 | 16 / 22 |
| `body` | Inter 400 | 14 / 20 |
| `label` | Inter 500 | 12 / 16 |
| `mono` | JetBrains Mono 400, tabular | 12 / 16 |
| `monoSmall` | JetBrains Mono 400, tabular | 11 / 14 |
| `metric` | JetBrains Mono 600, tabular | 22 / 26 |

Both families are bundled as variable fonts so the product is identical across
devices. `metric` is for live numbers — FPS, frame time, memory — and tabular
figures keep digits from jittering as values change.

## Metrics and motion

- Radius: `sm` 8, `md` 12, `lg` 16, `pill` 999
- Spacing scale: 4, 8, 12, 16, 20, 24, 32
- Border: 1 dp hairline throughout
- Cards: `surface` + 1 dp `border` + `lg` radius, no shadow
- Motion: 150 ms standard easing; 250 ms for sheets; no springs

## Components (`V` prefix)

| Component | Purpose |
|---|---|
| `VScaffold` | Root/push toolbars, bottom nav, sheet host |
| `VArchBadge` | Mono pill: `ARM64` / `x64` / `x86`, architecture palette |
| `VEngineChip` | `FEX 2608`, `Box64 0.4.4` — mono, tappable to switch |
| `VContainerCard` | Container tile: name, arch profile, Wine, driver, last run, launch |
| `VAppTile` | Windows app: icon, name, arch badge, container |
| `VParamRow` | One setting from the manifest: label, description, control |
| `VMetricStrip` | Live FPS / frametime / CPU / GPU / thermal, `metric` type |
| `VSparkline` | Compact frametime history |
| `VProgressCard` | Download/build/install progress with speed and ETA |
| `VLogPane` | `surfaceSunken`, mono, level-colored, follow-tail |
| `VEmptyState` | Icon, one sentence, one action |
| `VConfirmSheet` | Destructive confirmation |

## Screens

Single activity, `NavHost`, string routes in `ui/Navigation.kt`.

Bottom navigation has four roots; everything else is pushed.

### Roots

**1. Containers** — the home screen. `VContainerCard` list. Each card shows the
architecture profile (Universal / Compatibility), Wine build, GPU driver, and
last run, with a prominent launch affordance. Empty state guides to creating
the first container.

**2. Apps** — every Windows application detected across all containers, with
icons extracted from the PE resources and a `VArchBadge` on each tile. Filter
by architecture and container. Long-press to pin to the Android home screen.

**3. Components** — the `.wcp` store, and the piece most directly borrowed from
the On-Device AI model manager. Sections for Engines, Wine builds, GPU drivers,
and D3D layers; each entry shows version, build tag, size, and whether it is
installed. Understands **matched sets**: a Turnip build and the DXVK version
validated against it are offered together, and a mismatch is flagged rather
than silently allowed. Downloads are resumable and verified by hash before
install.

**4. Settings** — registry URL, update channel, storage, theme, diagnostics
entry point, about/credits.

### Pushed

**5. Container editor** — create/edit. Architecture profile picker with a plain
explanation of each choice, Wine build, GPU driver, D3D layer, resolution, and
the full parameter surface rendered by `VParamRow` from a JSON manifest
(`assets/params-manifest.json`), exactly like the On-Device AI `ParamRenderer`.
Adding a new `BOX64_DYNAREC_*` or FEX TSO knob means adding a manifest entry,
never touching UI code. Parameters are grouped (Engine, Memory ordering,
Graphics, Audio, Input) and each carries a one-line explanation of what it
actually does.

**6. Session** — the running Windows desktop. Vulkan surface plus an
edge-swipe overlay: `VMetricStrip`, performance profile switch, input mode,
on-screen controls, and a kill switch. The overlay is the only place the design
allows translucency.

**7. App profile** — per-executable overrides: engine, component pins, memory
ordering, launch arguments. Shows the detected architecture and how it was
determined.

**8. Benchmark** — run a standard workload against the current container
configuration, store results, and compare runs side by side. This is what turns
"which engine is faster" from an argument into a measurement, and it writes
winning configurations back into app profiles.

**9. Driver manager** — installed GPU drivers, what each reports at runtime,
per-container assignment, and a warning when a driver does not claim support
for this GPU.

**10. Diagnostics** — `VLogPane` over Wine/FEX/Box64/Turnip output, device
capability report (the same facts recorded in `build/targets/canoe.env`), and
a one-tap export bundle for bug reports.

**11. File manager** — browse container drives, import/export, shared folders.

## Reused patterns from the On-Device AI app

Deliberately borrowed, because they already solve our problems:

| Pattern | There | Here |
|---|---|---|
| Manifest-driven settings UI | `params/ParamRenderer.kt` | Every engine/driver knob |
| Runtime registry from JSON | `engine/RuntimeRegistry.kt` | `.wcp` component registry |
| Resumable verified downloads | `data/download/Downloader.kt` | Component downloads |
| Foreground download service | `DownloadService.kt` | Same |
| Boot sweep for stalled jobs | `BootSweepReceiver` | Same |
| Token-based dark design system | `ui/theme/NocturneTheme.kt` | `VesselTheme` |
| Separate process for restarts | `:restart` activity | Engine/session restarts |
| Thermal/memory residency policy | `engine/workflow/Residency.kt` | Session survival under pressure |
