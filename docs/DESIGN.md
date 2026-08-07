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
assumes you already know what an ARM64EC thunk is, and layouts that expose the
implementation rather than the task. Vessel starts from what the user is trying
to do — run a program — and works backward. Depth is available on every screen,
but never in the way.

Concretely, that means: two bottom-nav destinations and no more; the common
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

Every executable and container is tagged with the architecture it runs, and the
colour is consistent everywhere it appears.

Because Nocturne's accent is itself violet, these must avoid that hue —
otherwise a badge stops reading as information and starts reading as a button.

| Token | Value | Meaning |
|---|---|---|
| `archNative` | `#5BD99A` green | ARM64 / ARM64EC — runs natively, no translation |
| `archX64` | `#7FB0F0` blue | x86-64 — translated by FEX |
| `archX86` | `#E0A458` amber | x86-32 — WoW64 path |
| `archUnknown` | `#75798C` (neutral-600) | Not yet inspected or unreadable |

Green reads as "free", and that is exactly what a native ARM64 app is.

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

Inter is Nocturne's own family; JetBrains Mono is Vessel's addition, because
this product shows far more machine fact than the AI app does. Both ship as
bundled variable fonts so the product is identical across devices. `metric` is
for live numbers — FPS, frame time, memory — where tabular figures stop digits
jittering as values change.

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
are reached from an overflow menu on Containers rather than the bar. The count
came down from four as the product's scope sharpened: each removal is recorded
below, because a deleted screen is a design decision and the reasoning is worth
more than the screen was.

### Roots

**1. Containers** — the home screen. `VContainerCard` list showing Wine build,
GPU driver, D3D layer and last run, with a prominent launch affordance. Empty
state guides to creating the first container. The toolbar carries `+` and the
overflow.

**2. Apps** — every Windows application detected across all containers, with
icons extracted from the PE resources and a `VArchBadge` on each tile. Filter by
architecture. Long-press to pin to the Android home screen.

### Pushed

**Container editor** — create/edit. The default view is short by design: name,
engine, GPU driver, resolution, frame-rate limit. Everything else sits behind a
single "Show advanced" disclosure. The whole surface is rendered by `VParamRow`
from `assets/params-manifest.json`, exactly like the On-Device AI
`ParamRenderer`, so adding a knob is a data change and never a UI one. Each
parameter carries a one-sentence explanation of what it actually does.

**Session** — the running Windows desktop. Specified in full below, because it
is the screen the product is actually for and the one where a bad design shows
within seconds.

### Session, in detail

Five states, not one. Most designs for this screen only draw the happy one, and
then the first real launch shows a black rectangle with no explanation.

```
PREPARING ──► STARTING ──► RUNNING ──► EXITED
     │             │           │
     └─────────────┴───────────┴──────► FAILED
```

**1. Preparing** — first launch of a container only. Creating the Wine prefix
and installing the five components takes real time, so it gets a checklist with
per-step progress, not a spinner: *create prefix · install Wine · install FEX ·
install driver · install D3D layers · first-run registry*. A step that fails
stops there and turns red, so the failure is attributable to a step rather than
to "it didn't work".

**2. Starting** — wineserver, the X server, then the executable. Short, but it
is where a missing DLL surfaces, so it shows the last log line as it goes rather
than a blank screen.

**3. Running** — the desktop surface, described below.

**4. Failed** — the important one, and the one most apps get wrong. Shows what
step failed, the last error line from the log, and two actions: **View log**
(straight into the session's log, already filtered to errors) and **Retry**. No
generic "something went wrong".

**5. Exited** — clean exit with the exit code, and a link to the log. A
non-zero exit is stated plainly rather than dismissed silently.

Early on, states 1, 2 and 4 are where users will spend most of their time. They
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
from the left edge, collapsed to a 4 dp handle. A bottom sheet would be the
obvious choice and is the wrong one: this screen is usually landscape, where
vertical space is scarce and horizontal space is not. The rail carries icon
buttons only — keyboard, input mode, metrics, logs, and stop — on a translucent
`surface`. This is the sole place the design permits translucency.

**The auxiliary key bar** is what makes "run any laptop app" true rather than
aspirational. Android's IME has no `Esc`, no `Tab`, no `Ctrl`, no function keys,
no arrows — and a Windows application is unusable without them. A compact
persistent bar supplies them, with modifiers that latch so `Ctrl`+click works
from a touchscreen. Toggleable, because a fullscreen game does not want it.

**Two input modes, because the software cannot infer which you want:**

- **Direct** — you touch where you want to click. Correct for desktop
  applications, menus, installers.
- **Trackpad** — relative movement with the pointer decoupled from your finger.
  Correct for anything with mouselook, where absolute positioning is unusable.

This is a genuine preference, so it is a control. Rendering mode was not, and
is not.

Physical keyboard, mouse and gamepad are used automatically when present — no
setting, because their presence is a fact rather than a preference.

**Metrics** (`VMetricStrip`: fps, frame time) are off by default and pinned to a
corner when on. Deliberately small: this is the one screen where our UI competes
with the content for attention, and the content wins.

#### Leaving

Stop is in the rail, behind a confirmation — an accidental tap should not
discard an unsaved document in a Windows application. Android's back gesture
does not exit; it is forwarded as `Esc`, because in a desktop application that
is what a user means by back.

**App profile** — per-executable overrides: component pins, memory ordering,
launch arguments. Shows the detected architecture and how it was determined.

**Components** — one current build of each component, all compiled for this
device, with provenance from the `.wcp` (source commit and the compiler flags
actually used) shown on each row. That provenance is the point: "compiled for
your device" is the product's central claim, and a claim you cannot check is
just an assertion.

This is the deliberate difference from every other app in this space. Those ship
a catalogue — a dozen Wine versions, DXVK 1.x through 2.x, a Turnip build for
every Adreno generation — and leave the user to guess. Here there is one build
per component, so there is nothing to guess about. Reached from the overflow,
not the nav bar, because with nothing to choose it is a status view rather than
a store.

**Driver manager** — installed GPU drivers, what each reports at runtime,
per-container assignment, and a warning when a driver does not claim support for
this GPU.

**File manager** — browse container drives, import/export, shared folders.

### Removed, and why

**Settings.** Storage and theme were readouts, not controls — the product is
dark-only and stores containers in one place. The registry URL and update
channel were build plumbing a user cannot evaluate, and the update channel read
`BuildConfig`, so it could not be changed at all. A control that controls
nothing teaches the user that the whole screen is decorative. Credits live in
`CREDITS.md`, which is where anyone who cares about them already is.

**Diagnostics** and **Benchmark.** Both were built and both worked —
Diagnostics read live CPU features, page size and driver state off the device;
Benchmark was to turn "which configuration is faster" into a measurement. They
were cut because this product is for running programs, not for instrumenting
the thing that runs them. The cost is real and worth naming: without
Diagnostics, a bug report is harder to produce, and without Benchmark, tuning
claims go back to being arguments. Both are recoverable from git history if
that trade turns out wrong.

**The architecture-profile picker.** Removed with Box64 — see
[ARCHITECTURE.md](ARCHITECTURE.md). There is one kind of container now, so
there is nothing to pick.

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
