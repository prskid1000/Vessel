# Vessel design system

## Principle

Vessel is an instrument, not a launcher. It shows machine facts — driver
builds, translation engines, frame times, memory-ordering flags — to someone
who wants to see them. The design goal is the feel of good professional
software: dense, precise, calm, and confident. Not gamer chrome, not a toy.

**The interface is designed from scratch.** Vessel reuses proven *engine* code
from the Winlator lineage — container setup, the X server, driver loading — but
no screen here is a restyled Winlator screen. Concretely: two bottom-nav
destinations and no more; the common path (open the app, launch a program) is
two taps from cold start; every advanced knob is explained in one plain sentence
next to itself; and defaults are correct for this device, so a new container
needs zero configuration.

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

Vessel uses **Nocturne**, the same design system as the On-Device AI app, so
the two products read as one family. Every token below is transcribed from
`_ds/nocturne-*/styles.css` in that project, which is the system's source of
truth — retune there, then mirror here.

Tokens are passed down through `CompositionLocalProvider`. Material dynamic
color is not used, so the product looks identical on every device.

### Core

| Token | Value | Use |
|---|---|---|
| `bg` | `#161826` | Window ground — a deep indigo, deliberately not black |
| `surface` | `#232532` | Cards, sheets, bars |
| `text` | `#E9E9ED` | Primary text |
| `accent` | `#9184D9` | Primary action, focus, active nav |
| `accent2` | `#A7A1DB` | Secondary accent, second series in charts |
| `divider` | `text` @ 16% | Hairlines |
| `textMuted` | `text` @ 55% | Captions, metadata |
| `textLabel` | `text` @ 70% | Form labels |

### Neutral ramp

`100 #F3F5FE` · `200 #E4E7F5` · `300 #CFD3E5` · `400 #B2B6CA` · `500 #9397AB`
· `600 #75798C` · `700 #595D6C` · `800 #3F424D` · `900 #292B31`

### Accent ramp

`100 #F5F4FF` · `200 #E7E5FE` · `300 #D2CEFD` · `400 #B5ABFC` · `500 #968AE0`
· `600 #796CBF` · `700 #5D5294` · `800 #423A6A` · `900 #2B2741`

Tag fills use `accent-800` ground with `accent-100` text, per Nocturne.

### Architecture palette — functional, never decorative

Nocturne's accent is itself violet, so these must avoid that hue — otherwise a
badge stops reading as information and starts reading as a button.

| Token | Value | Meaning |
|---|---|---|
| `archNative` | `#5BD99A` green | ARM64 / ARM64EC — runs natively, no translation |
| `archX64` | `#7FB0F0` blue | x86-64 — translated by FEX |
| `archX86` | `#E0A458` amber | x86-32 — WoW64 path |
| `archUnknown` | `#75798C` (neutral-600) | Not yet inspected or unreadable |

### Status

| Token | Value |
|---|---|
| `ok` | `#5BD99A` |
| `warn` | `#E0A458` |
| `danger` | `#E5697A` |
| `info` | `accent` |

Telemetry graphs run `accent` → `accent2` for load ramps and switch to
`warn`/`danger` past thresholds.

## Typography

Inter, per Nocturne. Note the heading weight is **500** — not 600 or 700.
Headings carry `-0.015em` tracking and a 1.12 line height.

| Role | Family | Size / line |
|---|---|---|
| `display` | Inter 500, tracking −0.015em | 32 / 36 |
| `title` | Inter 500, tracking −0.015em | 25 / 28 |
| `subtitle` | Inter 500 | 20 / 24 |
| `cardTitle` | Inter 500 | 17 / 20 |
| `body` | Inter 400 | 15 / 23 |
| `bodySmall` | Inter 400 | 13 / 19 |
| `label` | Inter 400 | 12 / 16 |
| `overline` | Inter 400, uppercase, tracking 0.08em | 11 / 14 |
| `mono` | JetBrains Mono 400, tabular | 13 / 18 |
| `monoSmall` | JetBrains Mono 400, tabular | 11 / 15 |
| `metric` | JetBrains Mono 500, tabular | 22 / 26 |

Both ship as bundled variable fonts so the product is identical across devices.
`metric` is for live numbers — FPS, frame time, memory — where tabular figures
stop digits jittering as values change.

## Metrics and motion

- Radius: `sm` 4, `md` 8, `lg` 14 (Nocturne's scale — tighter than Material's)
- Spacing, from Nocturne's 2.8 dp base: 3, 6, 8, 11, 17, 22
- **Elevation is a hairline ring, not a shadow.** `elevSm` = 1 dp
  `neutral-800`; `elevMd` = 1 dp `neutral-700` plus ambient darkness
- Cards: `surface` ground, `md` radius, ring only when raised
- Motion: 150 ms standard easing; 250 ms for sheets; no springs

Two Nocturne signatures that are easy to get wrong:

**Buttons are outlined, not filled.** A primary button is accent text with a
1 dp accent border on a transparent ground, tinting to 12% accent on hover and
22% on press. A solid accent slab is not a Nocturne form and should not appear
anywhere in Vessel.

**Freestanding rules fade at both ends** — transparent to `divider` over 48 dp
at each end. Box outlines, in-control separators, and short accent marks stay
solid.

## Components (`V` prefix)

| Component | Purpose |
|---|---|
| `VScaffold` | Root/push toolbars, bottom nav, sheet host |
| `VArchBadge` | Mono pill: `ARM64` / `x64` / `x86`, architecture palette |
| `VEngineChip` | `FEX 2608` — mono; the build actually loaded |
| `VContainerCard` | Container tile: name, Wine, driver, D3D layer, last run, launch |
| `VAppTile` | Windows app: icon, name, arch badge, container |
| `VParamRow` | One setting from the manifest: label, description, control |
| `VMetricStrip` | Live FPS / frametime / CPU / GPU / thermal, `metric` type |
| `VSparkline` | Compact frametime history |
| `VProgressCard` | Download/build/install progress with speed and ETA |
| `VEmptyState` | Icon, one sentence, one action |
| `VConfirmSheet` | Destructive confirmation |

## Screens

Single activity, `NavHost`, string routes in `ui/Navigation.kt`.

**Two bottom-nav roots.** Everything else is pushed, and the technical screens
are reached from an overflow menu on Containers rather than the bar.

### Roots

**1. Containers** — the home screen. `VContainerCard` list showing Wine build,
GPU driver, D3D layer and last run, with a prominent launch affordance.

**2. Apps** — every Windows application detected across all containers, icons
extracted from the PE resources, a `VArchBadge` on each tile, filter by
architecture, long-press to pin to the Android home screen.

### Pushed

**Container editor** — create/edit. The default view is short by design: name,
engine, GPU driver, resolution, frame-rate limit; everything else sits behind a
single "Show advanced" disclosure. The whole surface is rendered by `VParamRow`
from `assets/params-manifest.json`, so adding a knob is a data change and never
a UI one.

### Session, in detail

Five states, not one. Most designs for this screen only draw the happy one, and
then the first real launch shows a black rectangle with no explanation.

```
PREPARING ──► STARTING ──► RUNNING ──► EXITED
     │             │           │
     └─────────────┴───────────┴──────► FAILED
```

**Preparing** (first launch only) gets a per-step checklist rather than a
spinner — *create prefix · install Wine · install FEX · install driver · install
D3D layers · first-run registry* — so a failure is attributable to a step.
**Starting** shows the last log line as it goes, because that is where a missing
DLL surfaces. **Failed** shows the failing step, the last error line, and two
actions: **View log** (already filtered to errors) and **Retry** — never a
generic "something went wrong". **Exited** states the exit code plainly.

States 1, 2 and 4 are where users will spend most of their time early on, and
get the same design attention as 3.

#### The running surface

Full-bleed Vulkan surface, immersive — no system bars, gesture inset handling
only. Nothing is drawn over the desktop except on request.

```
┌─────────────────────────────────────────────┐
│  ⟨48 fps  12.4 ms⟩              ← optional  │
│                                             │
│                                             │
│              Windows desktop                │
│           (full-bleed surface)              │
│                                             │
│ ▎← 4dp handle, swipe to open the rail       │
│                                             │
│  [ Esc  Tab  Ctrl  Alt  ⌨  ← ↑ ↓ → ]        │
└─────────────────────────────────────────────┘
```

**The rail, not a sheet.** Controls live in a narrow vertical rail swiped in
from the left edge, collapsed to a 4 dp handle. A bottom sheet is the obvious
choice and the wrong one: this screen is usually landscape, where vertical space
is scarce and horizontal space is not. The rail carries icon buttons only —
keyboard, input mode, metrics, logs, stop — on a translucent `surface`, the sole
place the design permits translucency.

**The auxiliary key bar.** Android's IME has no `Esc`, `Tab`, `Ctrl`, function
keys or arrows, and a Windows application is unusable without them. A compact
persistent bar supplies them, with modifiers that latch so `Ctrl`+click works
from a touchscreen. Toggleable, because a fullscreen game does not want it.

**Two input modes,** because the software cannot infer which you want: *Direct*
(touch where you want to click — desktop applications, menus, installers) and
*Trackpad* (relative movement, for anything with mouselook). Physical keyboard,
mouse and gamepad are used automatically when present — their presence is a fact
rather than a preference.

**Metrics** (`VMetricStrip`: fps, frame time) are off by default and pinned to a
corner when on. Deliberately small: this is the one screen where our UI competes
with the content for attention, and the content wins.

Stop is in the rail behind a confirmation. Android's back gesture does not exit;
it is forwarded as `Esc`, because in a desktop application that is what a user
means by back.

**App profile** — per-executable overrides: component pins, memory ordering,
launch arguments. Shows the detected architecture and how it was determined.

**Components** — one current build of each component, with provenance from the
`.wcp` (source commit and the compiler flags actually used) on each row. That
provenance is the point: "compiled for your device" is the product's central
claim, and a claim you cannot check is just an assertion. Every other app in
this space ships a catalogue and leaves the user to guess; here there is one
build per component, so this is a status view rather than a store — hence the
overflow rather than the nav bar.

**Driver manager** — installed GPU drivers, what each reports at runtime,
per-container assignment, and a warning when a driver does not claim support for
this GPU.

**File manager** — browse container drives, import/export, shared folders.

### Removed, and why

- **Settings.** Every control on it was a readout or build plumbing (the update
  channel read `BuildConfig` and could not be changed at all). A control that
  controls nothing teaches the user that the whole screen is decorative.
- **Diagnostics** and **Benchmark.** Both worked; both were cut because this
  product is for running programs, not instrumenting the thing that runs them.
  The cost is real — bug reports are harder to produce and tuning claims go back
  to being arguments — and both are recoverable from git history.
- **The architecture-profile picker.** Removed with Box64; there is one kind of
  container now. See [ARCHITECTURE.md](ARCHITECTURE.md).

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
