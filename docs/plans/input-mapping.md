# Input mapping — implementation plan

*Structure and behaviour only. A design pass will style this; nothing here
specifies colour, spacing or type.*

## 1. The constraint

**Vessel ships no XInput, and nothing in this plan changes that.**

A Windows game that wants a gamepad calls `XInputGetState`, which means a DLL
inside the guest talking to something outside it. Winlator's `WinHandler` is
that something, and it is deliberately not vendored — `XServerDisplay.kt:118`
says so, and `Gamepad.kt:25-38` says the same thing from the other end.

So the only channel into the guest is the X server, and the X server carries
**keys and a pointer**. Every control this feature configures resolves to one of
exactly six things, which is `GuestInput`:

| `GuestInput` | What it is |
|---|---|
| `Key(keycode, keysym, pressed)` | one X11 key edge, keycode in 8..127 |
| `Button(PointerButton, pressed)` | one of the seven X11 pointer buttons |
| `MoveBy(dx, dy)` | relative pointer motion — what "look" is |
| `MoveTo(x, y)` | absolute pointer position, in view pixels |
| `Scroll(axis, ticks)` | wheel detents |
| `Zoom(ticks)` | `Ctrl` held over detents |

Anything a user could want that is not expressible as one of those — rumble,
gyro, analogue triggers as analogue values, a game reading a real stick axis —
**cannot be built**, and the UI must not draw a control that implies otherwise.
The word "gamepad" in the UI names the *input device*, never the thing the guest
sees.

Two consequences that will be asked about:

- **A stick bound to look moves the mouse cursor.** For a game reading raw mouse
  deltas that is mouselook. For a game that grabs and warps the cursor, or one
  that hits the edge of the 1280x720 desktop, it will fight the boundary.
  **Unverified on device — see Open questions.**
- **Analogue is quantised at the boundary.** A stick half-axis becomes a held key
  at `GamepadConfig.deadzone` with hysteresis at `releaseZone`, which is why
  those two numbers exist and why they are the only stick tuning worth exposing.

## 2. Entry point

### Recommended: the session rail is primary; the container sheet gets a secondary row

**Primary — a fifth tool in `RailActions`** (`ui/screens/SessionScreen.kt`),
captioned `Input`, opening a panel over the running desktop.

1. **A binding is only knowable by testing it.** Whether `R2` should be
   left-click or `Space` is a question about the game that is running. Every
   other design makes the loop *edit → save → launch → discover → back out →
   edit*, which on this device is a two-minute cycle per binding.
2. **The touch overlay cannot be edited anywhere else without a second
   implementation of the letterbox.** Controls are positioned as fractions of
   the *surface*, and the surface only exists during a session. A cold editor
   would need a preview rectangle standing in for the panel — a second copy of a
   fit `GuestViewport` already owns, which is the trap `WindowDragBorders`
   documents.
3. **The rail already owns input.** Pointer mode and the keyboard are there.
   `SessionDisplayServer.pointerMode` exists on the seam precisely because "it is
   the one input setting the *user* changes mid-session". This is the second and
   third.
4. It costs one row, and `docs/DESIGN.md` says the rail scrolls so that "the
   failure mode of adding a row is a scrollbar rather than a Stop button below
   the fold".

**Secondary — one `VSheetRow` in the container sheet**, reading
`Input · <profile name>`, opening the same editor cold: the pad table fully
editable, the touch canvas laid out against the container's configured
`display.resolution` aspect with a note that it will be laid out for real over a
running session. It also carries the per-container default picker, because
*which profile this container starts with* is a setting about the container.

### Rejected

**(a) A button on the container screen alone** — the user's suggestion, kept as
the secondary entry, rejected as the only one. It can edit cold but cannot answer
the question the editor exists to answer, and it is the wrong home for the touch
overlay for the reason in (2).

**(b) A section inside the container sheet.** `docs/DESIGN.md` is explicit:
"There is no 'Show advanced'. If the manifest ever grows past what a sheet can
hold, the answer is to cut knobs, not to add a disclosure." Diagnostics already
takes the sheet over rather than growing it. A twenty-row binding table plus a
drag canvas is two screens. It also fights the sheet's contract — everything
there commits at Save, and the whole argument for the rail is that a binding
change takes effect *now*.

**(c) The launcher or taskbar.** The launcher is "what could run", the taskbar is
"what is running". A binding table is neither.

## 3. Physical controller remapping

### 3.1 Model changes in `app.vessel.input`

`GamepadTranslator.onSticks` hardwires the left stick to four half-axes and the
right to look. That hardwiring is the one thing in the way of "bind a stick to
look, or to keys, or to nothing", so it becomes data.

```kotlin
enum class GamepadControl { /* existing */, STICK_R_UP, STICK_R_DOWN, STICK_R_LEFT, STICK_R_RIGHT }

enum class Stick { LEFT, RIGHT }

sealed interface StickRole {
    data object Look : StickRole    // relative pointer motion at lookSpeed
    data object Keys : StickRole    // four half-axis controls
    data object None : StickRole
}

data class GamepadProfile(
    val name: String,
    val bindings: Map<GamepadControl, GamepadAction>,
    val sticks: Map<Stick, StickRole> =
        mapOf(Stick.LEFT to StickRole.Keys, Stick.RIGHT to StickRole.Look),
)
```

`GamepadAction` deliberately gains **no** `Look` member: look is a property of a
whole stick, and modelling it per-half-axis would let a user bind "left half of
the right stick" to look and produce a translator state nobody can reason about.

Two sticks both set to `Look` sum their deflections. `config` becomes a `var` so
a sensitivity change lands without rebuilding the translator and dropping every
held key.

**The regression gate is that `GamepadTest.kt` passes unmodified.** The default
profile's `sticks` map reproduces today's behaviour exactly; if that file needs
an edit, the refactor is wrong.

### 3.2 The screen

One scrolling list, grouped the way a pad is shaped rather than the way the enum
is ordered: Sticks (role, then half-axis rows for whichever stick is `Keys`),
D-pad, Face, Shoulders, Thumbsticks, System. Each row is name / binding label /
clear, the same row shape as `DiagnosticRow`, because it is the same kind of list
and the product already has a rendering for it.

Two things belong on the screen rather than in a help file:

- **A live-press indicator** — the editor keeps feeding the translator, so a row
  highlights the moment its physical control is pressed. That is what turns
  "which button is X on this pad?" into a press. Costs a
  `StateFlow<Set<GamepadControl>>` on the seam.
- **`STICK_R_*` rows are absent while the right stick is `Look`.** A binding that
  cannot fire is worse than a missing one.

### 3.3 Picking a key: a searchable list, with capture as a shortcut

**The list is primary.** A new `input/X11KeyCatalog.kt`:

```kotlin
enum class KeyGroup { MOUSE, LETTERS, DIGITS, MODIFIERS, NAVIGATION, FUNCTION, NUMPAD, PUNCTUATION }

data class KeyChoice(val label: String, val group: KeyGroup, val action: GamepadAction)

object X11KeyCatalog {
    val entries: List<KeyChoice>
    fun search(query: String): List<KeyChoice>
    fun label(action: GamepadAction): String
}
```

Built from the same `X11` object `X11KeyMap` uses, so the list cannot offer a
keycode the server would refuse. `label(action)` is the single source of a
binding's display text, which stops the row and the picker disagreeing.

One change to an existing file: `Keysym` in `X11Key.kt` becomes `internal` so the
catalogue carries the correct keysym for the six keys the vendored server does
not preinstall, instead of duplicating six numbers.

**Capture is secondary and must be honest about its limits.**

1. **A physical keyboard over Bluetooth** captures exactly. This is why capture
   exists.
2. **The IME** — `onCreateInputConnection` sets `TYPE_NULL` so a soft keyboard
   delivers raw `KeyEvent`s. But a soft keyboard has no `Esc`, `Ctrl`, `Alt`,
   arrows or function keys, and a character delivered as `ACTION_MULTIPLE`
   carries no keycode at all. So capture is a secondary button on the picker,
   not the picker itself, and it says "or pick one from the list" while waiting.
3. **Capturing the *source* control** — a "Learn" affordance: press a button on
   the pad and the corresponding row scrolls into view and opens.
   `gamepadControl(event)` already names every physical control, so this is free,
   and it is the best answer to "which of these twenty rows is the button under
   my finger".

**Clearing** is an explicit `None`, first entry of every picker and an `X` on
every bound row. `GamepadTranslator.emit` already treats a missing key and
`None` identically, so the stored form writes every control explicitly and a
round trip is stable.

### 3.4 Sensitivity and deadzone

Two controls, both per-profile:

- **Deadzone**, 0.10–0.40, default 0.25. `releaseZone` is *derived*, not exposed:
  `releaseZone = deadzone * 0.72f`, reproducing today's 0.18/0.25 exactly.
  Exposing both invites `releaseZone > deadzone`, and the resulting chatter is
  the exact failure the two-threshold design prevents.
- **Look speed**, 200–2400 px/s, default 900, labelled *Look sensitivity*.

`triggerThreshold` stays fixed at 0.5. It is not a preference; it is where a
pad's analogue trigger stops being noise.

## 4. On-screen touch controls

### 4.1 Data, and why fractions

`input/TouchControls.kt`, pure Kotlin: `TouchKind { BUTTON, STICK, DPAD }`,
`TouchControl(id, kind, cx, cy, size, opacity, label, action, role, up, down,
left, right)`, `TouchLayout(controls)`.

- **Fractions of the surface, not of the guest desktop.** The desktop resolution
  is a container setting and the panel is 1264x2780; a thumb rests where a thumb
  rests, and changing the guest resolution must not move a button under it. The
  overlay is drawn and hit-tested in view pixels only; `GuestViewport` is never
  consulted. This is the opposite decision from `WindowDragBorders`, and the
  reason is that a window lives in the guest and a thumb does not.
- **`size` is a fraction of the shorter edge.** On 1264x2780, a radius as a
  fraction of width and of height would make every button a 2.2:1 ellipse.
- **`cx`/`cy` clamped to `[size, 1 - size]`** on write and on import, so a control
  can never land half off-screen where it cannot be hit or recovered.

The session route is orientation-locked to `sensorLandscape`, so one layout per
profile is enough. `TouchLayout` is a wrapper type so a per-orientation variant
is an added field rather than a schema break.

### 4.2 Edit mode versus play mode

A mode on the overlay, not a separate screen.

- **Play** — drawn at their own opacity, hit-tested, every press produces
  `GuestInput`.
- **Edit** — ring and size handle on every control, drag to move, handle to
  resize, long press for the control's own sheet, `+` to add. **In edit mode the
  overlay produces no `GuestInput` at all** — not a filtered subset, none — and
  the panel says the guest is not receiving input. A mode that half-forwards is
  one where you rebind a button by accidentally shooting.

### 4.3 Hit-testing and coexistence — the crux

`SessionSurfaceView.onTouchEvent` hands **every** finger to `PointerGestures`
today. With an overlay, each pointer must be routed once, at its own DOWN, and
stay routed for its whole life. This gets a named type, `input/PointerRouter.kt`,
because written inline it will break:

- **A claimed finger must never appear in the list `PointerGestures` sees.** That
  machine counts `peakFingers` and maps 1/2/3 fingers to left/right/middle click.
  Without the filter, holding an on-screen fire button and tapping the screen is
  a **right click**.
- **The phase must be rewritten.** If pointer 0 is claimed and pointer 1 is not,
  pointer 1 arrives as `ACTION_POINTER_DOWN`; the gesture machine must see it as
  `DOWN` or it will never start a gesture. Conversely a claimed `POINTER_DOWN`
  must not reach the machine at all, or `onExtraDown` takes the contact press
  back in `DIRECT` mode.
- **`ACTION_CANCEL` and `onDetachedFromWindow` reset both machines and the
  router**, and both `reset()` results are fed to the sink.
- **A finger sliding off a button does not release it**; only lifting does. That
  is what a physical pad does under a thumb, and the opposite makes a sprint
  button unusable. A finger sliding off a *stick* keeps steering, clamped to the
  radius.

`TouchControlTranslator` does **not** reimplement stick hysteresis. An on-screen
stick computes a normalised deflection and feeds a second `GamepadTranslator`
through `onSticks`, so there is exactly one implementation of the deadzone, the
release zone and the look tick, and an on-screen stick behaves identically to a
physical one by construction.

### 4.4 Opacity and visibility

Per-control opacity 0.10–0.80, default 0.35. A profile-level on/off stored as
`ContainerInput.touchVisible` so a container played with a real pad comes up
clean. **No auto-hide and no fade-on-idle** — a control that fades is one you
cannot aim at.

### 4.5 Stock layouts

Three, provided in code and copied into a new profile on first use rather than
persisted as defaults: **WASD + look**, **Arrows + Enter/Esc** for an installer,
and **None**. Same posture as `GamepadProfile.Default`: an untouched container
writes nothing.

## 5. Profiles and persistence

### 5.1 A third document, not a field

Profiles live in their own DataStore document, following the argument already
made for `shortcuts.json` versus `containers.json`: separate failure domains.
Losing your bindings must not cost you your containers, and the reverse would be
much worse.

`data/InputProfileStore.kt` — `InputProfileDocument(schemaVersion, profiles)`,
`CURRENT_INPUT_SCHEMA = 1`, `INPUT_PROFILES_FILE = "input-profiles.json"`, bound
in `DataModule` with the same `ReplaceFileCorruptionHandler` and `.corrupt`
preservation.

### 5.2 Stored shapes — near-copies, enums as strings

Following `StoredShortcut`'s rule: the storage format holds still while the
runtime types move, and an enum is stored as its `name` so a value from a newer
build degrades instead of throwing.

`StoredInputProfile(id, name, pad: Map<String, StoredAction>, sticks:
Map<String, String>, deadzone, lookSpeed, touch: List<StoredTouchControl>)`.

`StoredAction(kind, keycode, keysym, button)` uses **a `kind` string rather than
sealed-class polymorphism**: the app's single `Json` is built with no
`SerializersModule`, and adding one changes how every other document reads. A
tagged record also keeps the reader *total* — an unrecognised `kind` decodes as
`none` rather than throwing, and a throw here is a `CorruptionException` that
costs the user every profile they have.

### 5.3 Per-container default

`core/ContainerInput.kt` — `ContainerInput(profileId: String? = null,
touchVisible: Boolean = false)`, added as a typed defaulted field on
`ContainerProfile` exactly as `diagnostics` is.

A `profileId` naming a profile that no longer exists resolves to the built-in
default and **the container is not rewritten** — a stale id is ordinary, not
corruption, and the sheet says "Default — the profile it named has been deleted"
rather than silently forgetting. The built-in default is never written to disk,
so an untouched container produces byte-identically today's behaviour.

### 5.4 Repository

`data/InputProfileRepository.kt`, `@Singleton`, modelled on
`ContainerRepository`: `profiles`, `get(id)`, `save`, `delete`, `duplicate`,
`importFrom`, `export`.

### 5.5 Import and export

Export writes one profile wrapped as `{"schemaVersion": 1, "profile": {...}}` so
the file is self-describing, through `CreateDocument`. Import reads one through
`OpenDocument`, then a pure `sanitize()` before it goes near the store:

- an unknown `schemaVersion` is **refused with a sentence**, not read
  optimistically — the one place a version number is actually consulted;
- a fresh `id` always, name de-duplicated with the existing `nextName` shape;
- unknown enum names dropped;
- **keycodes outside 8..127 dropped** — a keycode of 128 arrives at the vendored
  server as −128 and throws `ArrayIndexOutOfBoundsException` on the X client
  thread;
- `cx`/`cy`/`opacity` clamped, `size` floored so an imported control is always
  hittable and always on screen.

### 5.6 Versioning, and what happens to a container saved by an older build

| Case | Result |
|---|---|
| Old `containers.json`, new build | `input` absent → default → identical to today. No migration needed. |
| Old build reads new `containers.json` | `ignoreUnknownKeys = true` drops `input`; everything still loads. |
| **Old build *writes* after reading a new file** | `save` rewrites the whole document, so **`input` is erased for every container**. The containers survive; the association is lost. This is the strongest argument for the separate document — `input-profiles.json` is a file an old build never opens, so the profiles themselves survive a downgrade intact and the user re-picks one per container. |
| New build, corrupt profiles file | Moved to `.corrupt`, logged at ERROR with byte count and cause, app opens with the built-in default. Containers and shortcuts untouched. |
| `CURRENT_INPUT_SCHEMA` bumped later | Independent of the other two documents, because the three files fail independently. |

### 5.7 Applying a profile to a running session

The seam grows `inputProfile` / `setInputProfile`, `touchControlsVisible` /
`setTouchControlsVisible`, and `heldControls`, alongside `pointerMode` and for
the same stated reason. `SessionDisplayServer.Absent` implements all four
inertly.

`setInputProfile` **releases before it changes** — `sink.accept(gamepad.reset())`
first. Changing a binding while a key is held would otherwise leave the guest
holding a key nothing can ever release.

Live edits **persist immediately and push immediately**. A draft-with-Save was
rejected: the entire argument for the in-session entry is that a change is
visible the moment it is made.

## 6. Testability

`./gradlew testSideloadDebugUnitTest`. Three constraints from the current build:

- **JUnit 4 only** — no Robolectric, no `coroutines-test`, no Turbine. Suspending
  code uses `runBlocking`, as `ContainerProvisionerTest` does.
- **Android constants are fine; Android *calls* are not.** `X11Key.kt` imports
  `KeyEvent` and its test passes with no Robolectric because `KEYCODE_*` are
  compile-time constants and get inlined. A `Method not mocked` failure is the
  tell.
- `kotlinx-serialization-json` is on the unit-test classpath transitively.

| Layer | Test | What it pins |
|---|---|---|
| Stick refactor | **`GamepadTest` unmodified** | The default reproduces today exactly. The Phase 0 gate. |
| Stick refactor | `GamepadStickRoleTest` | Right stick as `Keys`; left as `Look`; both summing; `None` silent. |
| Tuning | `GamepadConfigTest` | `releaseZone = deadzone * 0.72` gives 0.18 at default; `releaseZone < deadzone` across 0.10–0.40. |
| Key catalogue | `X11KeyCatalogTest` | Every keycode in range; every keycode `X11KeyMap` can produce is offered; all seven buttons; keysyms equal `Keysym`'s; no duplicate labels. |
| Touch layout | `TouchLayoutTest` | Inside claims, gap does not, overlaps resolve to last-declared, a clamped edge control is fully hittable. |
| Touch translator | `TouchControlTranslatorTest` | Press/release edges; sliding off a button holds; a stick routes through `GamepadTranslator`. |
| Routing | **`PointerRouterTest`** | The three-finger trap: a held fire button plus a tap is still a *left* click. First unclaimed finger arrives as `DOWN`. Cancel clears everything. |
| Store | `InputProfileStoreTest` | Round-trip; unknown `kind` reads as `none`; unknown control dropped; empty file is the default. |
| Import | `InputProfileImportTest` | Clamps and drops; unknown version refused with a message; name collision becomes `(2)`; duplicate id re-issued. |
| Container field | `ContainerInputTest` | Absent `input` decodes to default; stale `profileId` resolves without rewriting; untouched container unchanged. |

**Not unit-testable, named rather than faked:** the `SessionSurfaceView` wiring
(needs a real `MotionEvent`), the Compose drag arithmetic, and whether a given
Windows game reads the keys a profile sends. These get a numbered device
checklist appended to `docs/SHELL-ACCEPTANCE.md` in that document's format.

## 7. Build order

Each phase compiles, ships and is tested on its own.

**Phase 0 — the input model.** `Stick`, `StickRole`, four `STICK_R_*`, the
`sticks` map, `config` as `var`, `X11KeyCatalog`, `Keysym` internal.
*Ships:* nothing visible. *Gate:* `GamepadTest` unmodified plus three new tests.

**Phase 1 — persistence and the seam.** The document, serializer, DI binding,
repository, `ContainerInput`, the four seam members, resolution in
`SessionRuntime.prepare` and push in `runDesktop`, one `VSheetRow`.
*Ships:* a container can be pointed at a profile created by hand-editing
`input-profiles.json` over adb — a real capability, and how Phase 2 gets
debugged. *Gate:* `InputProfileStoreTest`, `ContainerInputTest`.

**Phase 2 — the pad remap editor.** The rail tool, panel, grouped list, picker
(list + search + capture + Learn), live-press indicator, clear, deadzone, look
speed. *Ships:* the whole physical-controller feature.

**Phase 3 — touch overlay, play mode.** `TouchControl`, `TouchLayout`,
`TouchControlTranslator`, `PointerRouter`, the surface wiring, the overlay, the
visibility toggle, three stock layouts. *Ships:* a phone with no controller can
play.

**Phase 4 — touch overlay, edit mode.** Drag, resize, add, delete, per-control
bindings and opacity, the edit/play toggle and its statement.

**Phase 5 — profile management.** Create, rename, duplicate, delete, the
per-container picker with a real list, import and export.

## 8. Not planning

- **Any form of XInput, DirectInput or a guest-side helper.** Restated because it
  is the constraint that shapes everything above.
- **Rumble and gyro.** No channel for either; rumble needs a caller *inside* the
  guest.
- **Analogue values reaching the guest.** A trigger is a threshold and a stick is
  four thresholds or a pointer delta. That is all X11 can carry.
- **Per-program profiles.** A profile binds to a container. A shortcut-level
  override would be one nullable field if it is ever wanted.
- **Macros, combos, turbo, chords, hold-vs-toggle.** The one that will be asked
  for first is a **toggle** for crouch or sprint, and it is cheap — one branch in
  `emit` and one `Boolean` on `Key`. Out of v1 so v1's translator stays a
  function of its inputs.
- **Auto-detecting controller type.** An 8BitDo, a DualSense and a Backbone all
  report the same axes through this path.
- **An auxiliary key bar** (`Esc`/`Tab`/`Ctrl`/arrows), specified in DESIGN.md and
  still unbuilt. Adjacent, shares `X11KeyCatalog`, separate work — folding it in
  would double Phase 2.
- **Migrating anything.** There is nothing on any device to migrate from.

## 9. Open

1. **Does stick-look survive a game that grabs the cursor?** Relative-mouse mode
   is what `WinHandler` provides for Winlator and it is not here. Must be
   measured before Phase 3's look pad is worth finishing, and it may cap what the
   feature can honestly promise.
2. **Does the rail hold a fifth tool at 421 dp landscape without scrolling?**
   DESIGN.md says the margin was one design change wide. This is that change.
3. **Does this device's default IME deliver usable keycodes for capture?** The
   `TYPE_NULL` arrangement says it should; unverified. If not, capture degrades to
   hardware-keyboard-only and the list carries the feature — which it can.
4. **Where does export write?** SAF assumed; the product has an all-files grant
   and might reasonably write beside the container. Not decided.
5. **Should a profile be shareable between containers by default?** The model
   allows it; whether editing a shared profile from one container's session
   should warn is a product question.

### Read versus guessed

Read directly: `Gamepad.kt`, `GuestInput.kt`, `PointerGestures.kt`, `X11Key.kt`,
`XServerDisplay.kt` (whole), `SessionScreen.kt`, `SessionShell.kt`,
`ContainerSheet.kt`, `ContainerSheetViewModel.kt`, `ContainerProfile.kt`,
`ContainerDiagnostics.kt`, `ContainerStore.kt`, `AppShortcutStore.kt`,
`DataModule.kt`, `ContainerRepository.kt`, `ContainerPaths.kt`,
`SessionDisplay.kt`, `SessionViewModel.kt`, `Navigation.kt`, `VScaffold.kt`,
`GamepadTest.kt`, `app/build.gradle.kts`, `libs.versions.toml`, `DESIGN.md`.

**Guessed, not read:** `SessionRuntime.kt` was read only around `prepare`,
`runDesktop` and `LaunchPlan` — the claim that adding one field to `LaunchPlan`
is the whole of the launch-time wiring is inferred and should be confirmed.
`ShellHost`, `SessionService` and `VSheet`'s internals were not read in full. The
`releaseZone` ratio of 0.72 is arithmetic from the two current defaults, not a
tuning result.
