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
Headings carry `-0.015em` tracking.

**The scale is the reference app's, not the stylesheet's.** `styles.css` is a
desktop sheet; the sizes below are the ones `On Device AI` ships on a phone
(`ui/theme/NocturneType.kt`), one step down from the CSS throughout. Transcribing
the CSS directly — 25 sp screen titles, a 17 sp card title, a 22 sp metric — put
this product on a 480 dpi phone looking like a consumer app with the
accessibility text size turned up: a container tile 78 dp tall to hold one word,
and four readings that could not fit across a rail. Two products in one family
should not disagree about how big a card title is.

| Role | Family | Size / line | Where |
|---|---|---|---|
| `display` | Inter 500, tracking −0.015em | 26 / 30 | unused on phone |
| `title` | Inter 500, tracking −0.015em | 21 / 25 | root destination header |
| `subtitle` | Inter 500 | 17 / 21 | pushed toolbar, dialog title |
| `cardTitle` | Inter 500 | 14 / 18 | card and tile titles |
| `body` | Inter 400 | 13.5 / 19 | param titles, list rows, fields |
| `bodySmall` | Inter 400 | 12 / 17 | the help sentence under a control |
| `label` | Inter 400 | 11 / 14 | field labels, bottom-nav labels |
| `overline` | Inter 400, uppercase, tracking 0.08em | 10 / 13 | section kickers |
| `control` | Inter 500 | 12.5 / 16 | buttons and inputs, so they align |
| `mono` | JetBrains Mono 400, tabular | 11.5 / 16 | versions, flags, hashes, paths |
| `monoSmall` | JetBrains Mono 400, tabular | 10 / 14 | log lines, captions, legends |
| `metric` | JetBrains Mono 500, tabular | 17 / 20 | a live number with room around it |
| `metricSmall` | JetBrains Mono 500, tabular | 13 / 16 | a live number in the rail |

Both ship as bundled variable fonts so the product is identical across devices.
The two `metric` steps are for live numbers — FPS, frame time, memory — where
tabular figures stop digits jittering as values change. **A readout never
wraps**: every one of these is a single token, and `573 MB` broken over three
lines does not read as a shortened number, it reads as `57`.

## Metrics and motion

- Radius: `sm` 4, `md` 8, `lg` 14 (Nocturne's scale — tighter than Material's)
- Spacing, from Nocturne's 2.8 dp base: 3, 6, 8, 11, 17, 22
- Controls: every field, dropdown, stepper and text button is **36 dp** tall, so
  a button beside an input is exactly as tall as it. A bare icon action keeps the
  44 dp touch floor: compact never shrinks a target, only the box around a glyph.
- **Elevation is a hairline ring, not a shadow.** `elevSm` = 1 dp
  `neutral-800`; `elevMd` = 1 dp `neutral-700` plus ambient darkness
- Cards: `surface` ground, `md` radius, ring only when raised
- Motion: 150 ms standard easing; 250 ms for sheets; no springs

### Landscape, and the one rule that handles it

This phone is 421 dp across in portrait and **927 dp in landscape**. Every layout
here is designed at phone width and is correct there; left to fill, the same
layouts stretch a session row until its status tag is 800 dp from its timestamp,
and set a centred empty-state sentence on one unreadable 900 dp line.

So: **content lives in a column capped at 560 dp and centred**, and that is the
whole adaptation — no landscape-only layouts, no breakpoint tree. Dialogs cap
tighter, at 420 dp. Bars are the exception in one direction only: a bottom nav's
ground and hairline run edge to edge while its destinations sit inside the same
capped column, so the bar reads as structure and its contents line up with the
list above them.

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
| `VContainerCard` | Container tile: name, last run, launch — two lines, not a slab |
| `VAppTile` | Windows app: icon, name, arch badge, container |
| `VParamRow` | One setting from the manifest: label, description, control |
| `VMetricStrip` | The wide readout: a row of live values on a raised card |
| `VMetricGrid` | The narrow readout: the same values in fixed columns, for the rail |
| `VMetricSpark` | One quantity, one ceiling: name, reading and a 20 dp sparkline — the rail |
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

`VParamRow`'s right-hand value column exists so the whole configuration can be
read down one edge without touching a control — and it prints **only what the
control cannot show itself**. An enum's own field already carries its label, so
printing it again gave every dropdown on the screen `Resolution  1280 x 720
(720p)` with `1280 x 720 (720p)` in the box directly beneath it. What is left is
an integer (whose control is a slider with no number on it) and a component
(whose field shows the resolved build while the column shows the selector that
resolved it).

### Session, in detail

Five states, not one. Most designs for this only draw the happy one, and then
the first real launch shows a black rectangle with no explanation.

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

#### Four of the five states are not a screen

**Only RUNNING gets a destination.** Preparing and Starting are a dialog over
whatever the user was already looking at, Failed is a dialog, and a clean Exited
is *nothing at all*.

The old shape pushed one `session/{id}` screen the moment Launch was tapped, and
that screen was a checklist which turned into a desktop. Three things were wrong
with it and each is a rule now:

- *A checklist is not a place.* Waiting for six rows to tick is not somewhere a
  user navigated to; it is something happening to the thing they tapped. Giving
  it a toolbar, a back arrow and a full screen of ground told them otherwise —
  and the back arrow was a way out of a screen whose job was to be waited on.
- *A clean exit is not news.* "The Windows desktop closed — exit code 0" is a
  dialog that says the thing the user just did, and then makes them tap Close to
  admit it. Exit code 0 now returns silently to wherever they were. **A non-zero
  exit and a FAILED launch keep their dialog**, because that one is not a
  notification, it is the diagnosis — and it is layered over the checklist, which
  is the attribution: which of six steps got that far.
- *Every screen the desktop is not is a screen in the desktop's way.* With the
  checklist gone, the route holds exactly one thing, so entering it and leaving
  it line up exactly with the session starting to draw and stopping.

**The route survives, and does not become an overlay.** The tempting
simplification is to drop `Routes.SESSION` entirely and draw the desktop as a
`Box` over the `NavHost`. It is wrong for one concrete reason: **the rail's
Session log button pushes a route**, and a desktop drawn over the `NavHost`
would be drawn over the log it just opened. The back stack is what makes
"desktop → log → back → desktop" work, and re-implementing it as a visibility
flag is re-implementing the back stack. The route also keeps Back on the running
desktop meaning what it already meant — leave the screen, the session keeps
running, the foreground service owns it — which is behaviour with a bug fix
behind it and no reason to relitigate.

So: `Routes.SESSION` is the fullscreen desktop and nothing else. Navigation into
and out of it is driven by the phase, in one place (`SessionHost` in
`ui/Navigation.kt`), never by a button:

| Phase | What is on screen |
|---|---|
| PREPARING / STARTING | The checklist, as a dialog over the current screen |
| RUNNING | `Routes.SESSION` pushed — the desktop, full-bleed |
| EXITED, code 0 | Nothing. The route is popped and the runtime cleared |
| EXITED non-zero, FAILED | The route is popped; the outcome dialog states why |

`MainActivity`'s `openSession` extra — the running-session notification, and
`tools/device-display.sh` — starts the container rather than navigating to a
screen, and the RUNNING rule above does the navigating. That is strictly more
robust than the old behaviour: the extra used to push a screen that then had to
decide whether to launch, so a second tap of the notification could stack a
second copy of it.

Dismissing the checklist dialog **does not cancel the launch** — it hides a
progress report, and the notification is still there. Cancel does cancel, and
unlike Stop it is not behind a confirmation: nothing inside a container that has
not started yet can lose work.

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
is scarce and horizontal space is not. It sits on a translucent `surface`, the
sole place the design permits translucency.

**The rail hugs, and it is one card.** 178 dp wide, sized by its content, and
centred against the screen's vertical middle.

- *Nothing is `fillMaxHeight`.* A rail pinned to the full height with its content
  at the top is two thirds empty translucent slab over the guest's own window —
  which is not a control, it is an obstruction.
- *It scrolls rather than clips.* Four graphs plus a header plus five actions is
  around 400 dp, and a landscape window on this phone is 421 dp before the
  gesture inset. That fits, and the margin is one design change wide — so the
  content column scrolls, and the failure mode of adding a row is a scrollbar
  rather than a Stop button nobody can reach.
- *A readout never wraps.* See the wrapping rule under Typography; the rail is
  the layout that has to make it true, which is why every reading here is a
  fixed-width cell and never a `weight(1f)` column.

One card holds all of it. Nesting a readout card inside the rail card draws a
ringed box inside a ringed box, 8 dp apart, over a running desktop.

**Close and Pause are at the top right, and they are the only bare glyphs.**
Session-lifetime controls belong in the header where a window's controls always
are, not at the bottom of a list of unrelated toggles. Stop keeps `danger` and
keeps its confirmation.

**Everything else is a labelled row, not a glyph.** The rail used to be a 2×2
grid of unlabelled squares — pointer mode, keyboard, files, log — and the honest
summary of that design is that its author had to be asked what the icons were.
A 24 dp glyph can carry a *known* action (a play triangle, a back arrow); it
cannot introduce one. Four ambiguous glyphs stacked in a grid also hide their
own state: the pointer toggle's icon is the mode it will switch *to*, which is
unreadable without the word beside it. So each is an icon plus its name on one
36 dp row, and the pointer row's label is the mode it will switch to.

**Four graphs, one per quantity — CPU, GPU, mean clock, memory.** They were one
shared graph, which cannot be right: a percentage, a percentage, a clock in MHz
and a memory figure in MB have no common vertical axis, so three of the four
lines were being drawn against a ceiling that had nothing to do with them.
Separate graphs also let each keep its own honest ceiling — 100% for the loads,
the part's rated maximum for the clock, device RAM for memory — which is what
makes a line near the top mean "flat out" rather than "taller than the others".

Each is one dense 38 dp block: name and current reading on one line, a 20 dp
sparkline under it. That is the whole compromise this surface is under — it
floats over a desktop the user is trying to see, so it earns its space by being
four glances rather than one chart.

**Pause is `SIGSTOP`, and it is real.** Pausing sends `SIGSTOP` to every process
in the guest tree and `SIGCONT` to resume; the X server is ours and keeps
running, so the desktop simply freezes on its last frame. Three details are the
whole feature:

- *Order.* Clients are stopped before `wineserver` and continued after it. A
  client resumed while the server it talks to is still stopped blocks on its
  first request, which is a hang that looks like the pause failed.
- *A paused session is labelled, never silently idle.* The sampler keeps
  sampling and every reading truthfully drops to nothing, which is
  indistinguishable from a container that has finished loading and is waiting for
  input. The rail says `PAUSED` and the readings say so too.
- *Stop works while paused, and this is the part that bites.* A `SIGSTOP`ped
  process cannot be killed by `SIGTERM`: the signal is queued and delivered when
  the process next runs, which is never. Teardown therefore sends `SIGCONT` to
  the whole tree **before** it destroys anything or runs `wineserver -k`. This is
  the same class of bug as the `drain` deadlock in `data/WineProcessRunner.kt`,
  where Stop appeared to do nothing at all.

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

Stop is in the rail behind a confirmation. Back closes the rail if it is open and
otherwise leaves the screen with the session still running — it does not stop the
container, and it is not forwarded as `Esc`, because a gesture that cannot be
escaped from is how the first build trapped the user on a running desktop.

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
