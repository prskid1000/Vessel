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
installed.

**Only components built for this device are listed, and only the newest of
each.** This is the deliberate difference from every other app in this space.
Those apps ship a catalogue — a dozen Wine versions, DXVK 1.x through 2.x, a
Turnip build for every Adreno generation — and leave the user to guess. Vessel
has exactly one current build per component, compiled for this chip, so there
is nothing to guess about. No A7xx drivers, no Wine 9.x, no legacy D3D layers:
if it is in the list, we built it for this phone.

Older builds remain installable as a rollback path, but they live behind an
explicit "previous builds" disclosure, not in the main list.

Each entry shows its provenance from the `.wcp` — source commit and the actual
compiler flags used — so the claim "compiled for your device" is verifiable in
the UI rather than just asserted. Matched sets are understood: a Turnip build
and the DXVK version validated against it are offered together, and a mismatch
is flagged rather than silently allowed. Downloads are resumable and verified
by hash before install.

**4. Settings** — storage location, theme, and an entry point to Diagnostics.

Deliberately *not* here: registry URL, update channel, and an About page. The
first two are build-plumbing that a user has no reason to change and no way to
evaluate; they belong in Diagnostics if anywhere. Credits live in the
repository, which is where anyone who cares about them already is.

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
