# Vessel design system

## Principle

Vessel is an instrument, not a launcher. It shows machine facts — driver
builds, translation engines, frame times, memory-ordering flags — to someone
who wants to see them. The design goal is the feel of good professional
software: dense, precise, calm, and confident. Not gamer chrome, not a toy.

**The interface is designed from scratch.** Vessel reuses proven *engine* code
from the Winlator lineage — container setup, the X server, driver loading — but
no screen here is a restyled Winlator screen. Concretely: **one root and no
bottom navigation**; the common path (open the app, launch a program) is two taps
from cold start; every advanced knob is explained in one plain sentence next to
itself; and defaults are correct for this device, so a new container needs zero
configuration.

**Home does everything.** A program is listed inside the container that owns it,
so there is no Apps destination — the same `.exe` in two containers is two
different things, with two different drivers and two different registries, and
the only list worth having is the one that says so. Short things are bottom
sheets over home; only the file browser and the log viewer are pushes. The bottom
edge is left clear on every screen, because over a running session that edge is
the taskbar's reveal gesture.

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

**Every dp, sp and colour in the product is a token.** `ui/theme/VesselTheme.kt`
is the only file in `ui/` allowed to write a `dp` or an `sp` literal or a
`Color(0x…)`, and that rule is enforceable by grep — which is the point of it.
A layout that needs a value the tokens do not carry adds a token with a sentence
saying what it is for; it does not write the number where it is used. `VMetrics`
therefore holds ceilings and component geometry (`railWidth`, `sheetHandleWidth`,
`proseMaxWidth`, `checklistMaxHeight`, `logGutterWidth`, …) alongside the spacing
scale, because a magic number in a screen file is a magic number whatever it
measures.

Two tokens exist only so the rule stays honest: `none` (zero, so "this edge has
no padding" is not a bare `0.dp`) and `hairGap` (2 dp, the one gap below the
scale, for the inside of a graph where 3 dp reads as a step rather than as
attachment).

### Orientation is pinned, not adapted to

Two destinations, two orientations, and nothing else in the app touches
`requestedOrientation`.

| Surface | Orientation |
|---|---|
| Home, Files, the run history, the log viewer | **portrait** |
| The session desktop | **sensorLandscape** |
| Taskbar, launcher | inherit from the desktop |
| Every sheet and dialog | set nothing |

The desktop is landscape because the guest desktop is 1280×720 and cannot be
resized after it is created — a portrait session was always a letterboxed
compromise. `sensor` rather than plain landscape, so somebody holding the phone
the other way round gets a desktop the right way up. Everything else is a
vertical list of rows, cards or log lines: 927 dp of width buys none of them
anything, and 422 dp of height has already produced one clipped form.

**Exactly one composable owns `requestedOrientation`, and it is not a screen.**
The obvious shape — each destination setting its own on enter and restoring on
dispose — races: `NavHost` composes the incoming destination before disposing the
outgoing one, so the old screen's `onDispose` runs last and puts the *old* value
back over the new screen's. `VesselApp` owns it, keyed on the current route
(`ui/OrientationLock.kt`). That also makes the session's release free: a clean
exit, a failure, Stop from the rail and backing out all pop the route, so nothing
has to remember to unlock.

Sheets and dialogs set nothing, because each appears over a destination that has
already decided — an overlay that sets an orientation beats its own host and then
restores the wrong value on the way out.

This is independent of `android:configChanges`, which stays in the manifest:
that is what stops an Activity recreation taking the EGL surface with it and
leaving a black rectangle over a running Wine session. Both are needed.

### Landscape, and the one rule that handles it

Pinning orientation does not retire this rule, and it is worth saying why: the
session desktop is landscape, a sheet or a dialog can be raised over it, and a
foldable or a split-screen window can hand any destination a width the phone's
own portrait never would. A capped column costs nothing when it never fires.

This phone is 422 dp across in portrait and **927 dp in landscape**. Every layout
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
| `VScaffold` | Root/push toolbars and a bottom action bar |
| `VSheet` | The bottom sheet: scrim, drag handle, three dismiss gestures |
| `VSheetHeader` / `VSheetRow` / `VLabeledField` | A sheet's title row, its routes, its fields |
| `VArchBadge` | Mono pill: `ARM64` / `x64` / `x86`, architecture palette |
| `VContainerCard` | A container and the four columns of programs inside it |
| `VAppTile` / `VAppGrid` | A program: letter, name, arch badge — home and the launcher |
| `VParamRow` | One setting from the manifest: label, description, control |
| `VMetricGraphCard` | One quantity with its ceiling, legend and statistics — the Metrics tab |
| `VMetricSpark` | The same quantity as a 22 dp sparkline — the rail |
| `VEmptyState` | Icon, one sentence, one action |
| `VConfirmSheet` | Destructive confirmation — a centred panel, not a sheet |
| `VOutcomeDialog` | What happened, in plain words, over the evidence |
| `VIcons` | Phosphor, as vector paths |

Gone with the redesign: `VBottomNav` (there is one root), `VEngineChip` (one
build of each component, so there is nothing to chip), `VMetricStrip` and
`VMetricGrid` (the rail draws sparklines and there is no fps overlay),
`VOverflowMenu` (nothing is behind an overflow any more).

### Icons

**Phosphor, regular weight, transcribed as path data into `VIcons.kt`.** Phosphor
ships no Compose artifact, and `material-icons-extended` is several thousand
glyphs and about a megabyte of dex for the two dozen used here. Each constant in
that file is the `d` attribute of the matching `assets/regular/<name>.svg` in
`@phosphor-icons/core`, unaltered and verified against the package.

The one trap: **Phosphor draws on a 256-unit grid and Material on 24.** An
`ImageVector` built at `viewportWidth = 24f` scales every glyph to a tenth of its
box and renders as a speck in the corner of a button — a failure that looks like
a missing icon rather than a wrong number.

## Screens

Single activity, `NavHost`, string routes in `ui/Navigation.kt`.

**One root. Three other destinations, and one of those is the desktop.**
Everything short is a bottom sheet over home rather than a route.

| Route | What it is |
|---|---|
| `home` | containers, and the programs inside them |
| `files/{containerId}?pick=` | the container's `C:` — a push, because you navigate into it |
| `logs/{containerId}` · `logs/{containerId}/{startedAt}` | a container's runs, and one run |
| `session` | the running desktop, full-bleed |

### The root

**Home** — a `VContainerCard` per container: its name, one mono line of *ran 12
minutes ago · 1280×720 · 60 fps*, a folder button, a play button, and a
four-column grid of the programs added to it. Tapping the name opens the
container's settings sheet; tapping a tile launches it; long-pressing one opens
its profile sheet.

**Apps is not a screen, and that is the navigation decision of this redesign.**
It was a flat grid of every executable across every container whose first job was
to tell you which container each belonged to. A program is meaningless without its
prefix, so the list that says so is the only list worth having — and it is these
four columns, drawn once and reused by the launcher over a running session.

### Sheets, over home

Three, and each is a handful of fields *about the thing the user just tapped*.
Pushing a screen for any of them threw away the context that made them make
sense; a sheet keeps it visible behind. All three dismiss three ways — swipe
down, tap the scrim, press back.

**Container sheet** — new and edit are the same sheet, because two forms for one
object is how a setting ends up changeable in one and not the other. Name, then
everything the manifest declares, then Delete and Session logs. The manifest
currently declares exactly four controls — resolution, frame rate, the file
manager toggle and the DLL overrides — which is the whole of the sheet and the
whole of what a container has to be told. **There is no "Show advanced".** If the
manifest ever grows past what a sheet can hold, the answer is to cut knobs, not
to add a disclosure.

Everything below the name is still rendered by `VParamRow` from
`assets/params-manifest.json`, so adding a knob is a data change and never a UI
one. There is one `when` in that file and it is over `ParamType`; no manifest key
appears anywhere in it.

**App sheet** — a program's profile, and the form that adds one. Three fields and
one read-only fact: the executable, its launch arguments, its working directory,
and the architecture *read from the PE header together with how it was
determined* — because `unread` is a different claim from `x86` and only the
sentence separates them. Everything else about how a program runs belongs to its
container. Adding is the same sheet with empty values and one field, because the
name and the architecture are read from the file: nothing here is typed twice.

Editing commits on the way out. The profile has Launch and Remove and no Save,
because it has no draft — adding does have an explicit Add, because until it is
pressed there is nothing to edit.

**Delete confirmation** is a *centred panel*, not a second sheet: two stacked
sheets have no visible order, and a destructive confirmation is the one place the
buttons stay words.

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

**The rail hugs, and it is one card.** 212 dp wide, sized by its content, and
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

**Pause and Stop are at the foot, as two equal squares, and they are the only
bare glyphs.** They were briefly at the top right, where a window's controls live
on a desktop — but this rail is a column read top to bottom, not a window, and
putting the destructive action first meant the first thing under the reader's
thumb was the one that closes everything. A triangle and a cross are the two
marks in this product that carry themselves; Stop keeps `danger` and keeps its
confirmation.

**Everything else is captioned, not a bare glyph.** The rail used to be a 2×2
grid of unlabelled squares — pointer mode, keyboard, files, log — and the honest
summary of that design is that its author had to be asked what the icons were. A
glyph can carry a *known* action; it cannot introduce one. The four tools are now
one equal-spaced row with a word under each, which costs eleven dp against four
full-width rows. The pointer tool's caption is the mode it will switch *to*,
matching its glyph — an icon alone on a two-state control is a coin toss, and
losing that bet mid-game means the cursor stops behaving.

**The rail's Files opens Vessel's own `C:` browser, not `winefile`.** Wine's file
manager runs inside the guest, so it cannot reach Android storage at all; the
browser reads `drive_c` directly and gets import and export for free.

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

## The shell — taskbar and launcher

The product's missing centre: without it Vessel can run a Windows *desktop* but
there is no way to pick and run a Windows *program*.

**It is drawn in Compose over the GL surface, not inside Wine.** Launching a
program into a running desktop is already proven from the Android side; theme,
fonts and 44 dp touch targets come free; and a Win32 shell would mean
owner-drawing a whole UI toolkit to avoid using the one already here.

Two constraints, both found on the device, and both shape the design:

- **An Android overlay always covers a fullscreen Windows application.** There is
  no z-order in which a Compose layer sits under the guest's output. So the
  taskbar auto-hides after four seconds and is revealed by an edge gesture,
  exactly as the rail is. Two edges, two gestures, and they must not collide: the
  rail comes in from the **left** through a full-height 20 dp target with a 4 dp
  accent mark, the taskbar comes up from the **bottom** and takes the gesture
  bar's own width as its handle.
- **Tray icons cannot work.** Receiving one needs a helper process inside the
  guest, which this project deliberately does not ship. A program that minimises
  to tray vanishes from the taskbar rather than docking in it, and **the bar says
  so in words** where a user would go looking for the icon — an empty corner
  would read as a bug in the bar.

**The taskbar shows what is running; the launcher shows what could run.** Those
are two different lists and conflating them is the mistake the layout exists to
avoid. The taskbar carries a start button, the guest's open windows, the
session's elapsed time, and Pause and Stop repeated from the rail — because the
taskbar is the surface that is already open when a session needs ending.

**The launcher is the home screen's tile grid, scoped to the running container.**
One concept drawn once, at the same 44 dp. It anchors above the start button
rather than filling the screen, because launching a second program is not a
reason to hide the first. A search field filters it, and *Browse C:* is the
escape hatch for an executable with no shortcut yet.

## The file browser

**A container's `C:` drive, read straight off Android with Wine stopped.** A Wine
prefix is an ordinary directory tree, so listing it is `File.listFiles()`. That is
the whole argument for browsing it here rather than starting `winefile` in the
guest: it works before the container has ever launched, it works while a session
is running, and it gets import and export to Android storage for free — which a
file manager running *inside* the guest cannot do at all, because from in there
is no Android to copy to.

Directories first, then files, each case-blind alphabetical. An executable's mark
is a ringed glyph in its architecture's own colour, because in a folder of
downloads the fact anybody is looking for is which of these runs natively. Back
goes up one folder while the path has depth and leaves the screen at the drive
root, so Back and the toolbar arrow never disagree.

**What the shell will start, and what it refuses.** Decided before anything runs,
from the extension and — for a PE — the header:

| Extension | Verdict |
|---|---|
| `.exe` | runs. ARM64 native, x86-64 via `libarm64ecfex.dll`, x86-32 via WoW64 |
| `.bat` `.cmd` | runs — `cmd.exe /c` |
| `.msi` | runs — `msiexec.exe /i` |
| `.lnk` | runs — Wine resolves it |
| `.vbs` `.js` | runs, with a caveat: Wine's Windows Script Host is real but partial |
| `.ps1` | **refused.** Wine's `powershell.exe` is a stub that cannot run a script |
| a `.exe` with no PE header | **refused.** A partly-downloaded file looks exactly like this |
| Linux ELF, `.sh` | never offered. Android is bionic, and FEX ships as Wine's DLLs rather than as `FEXLoader` |

The two refusals are the "honest refusal over silent failure" rule doing its job:
a `.ps1` shortcut would *appear* to launch and then do nothing, which is the
failure mode this product treats as worse than an error. The reason is shown at
the point the user tries, beside the button that would have run it.

**Components and Driver manager are gone as screens.** This build compiles in one
version of each component and one driver, so both could only recite what was
already decided at build time. Provenance is still the point — "compiled for your
device" is a claim you should be able to check — and it belongs in the session
log's header, where it is printed on every run, rather than on a screen nobody
opens.

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
- **The Apps root, and the bottom navigation with it.** A flat grid of every
  executable across every container had to name the container on every tile,
  which is the shape of a list that should have been nested. With Apps gone there
  was one destination left, and a bar with one destination on it advertises that
  the app has somewhere else to be when it does not.
- **The container editor and the app profile as pushed screens.** Both were a
  handful of fields about the thing the user had just tapped, and both are sheets
  now. See *Sheets, over home*.
- **`VMetricStrip`, the optional fps overlay.** Specified, never built, and the
  rail's sparklines cover the same ground with a history behind them. The
  component went with it rather than sitting unused.

### Not built, and named rather than faked

Two things in this design are drawn and cannot yet work, because both need
something in `data/` that does not exist. Both say so at the point of use rather
than failing quietly, and both are specified in `out/ui-needs-from-core.md`.

- **App shortcuts do not survive a cold start.** The registry the UI is written
  against is real (`ui/shell/AppRegistry`); the implementation behind it is
  in-memory.
- **Tapping a program refuses, out loud.** `SessionRuntime.start` takes a
  container and nothing else, so there is no way to ask a prefix to run a named
  executable. The alternative — starting the container's plain desktop instead
  and saying nothing — is a launcher that appears to work: you tap Notepad++, a
  Windows desktop appears, and Notepad++ is not on it.
- **The taskbar cannot list the guest's windows.** The X server does not publish
  them yet, so the bar says which piece is missing instead of showing an empty
  strip that reads as a bug.

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
