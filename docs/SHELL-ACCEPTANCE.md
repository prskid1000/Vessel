# Shell acceptance matrix

Every interaction the session shell offers, as a row that is either **watched
working on the device** or is not. Nothing here is ticked from reading the code.

The shell is the part of Vessel that is not done, and it has been wrong in ways
that reading could not catch: the taskbar listed nothing because Wine sets
`_NET_WM_NAME` and not `WM_NAME`; windows had no title bar because a program
launched into a session starts rootless rather than joining the desktop; fixing
the second emptied the taskbar the first had just filled. Each was found by
running it, twice by the user rather than by me. Hence a list.

**Device:** Motorola Signature (SM8845, `ZD2232JMB9`), Android 16, 1264×2780.
**Container:** 1280×720, 60 fps.

Legend: `[x]` watched working · `[!]` watched failing · `[ ]` not yet run ·
`[~]` partly.

---

## 1. Windows

| # | Action | State | Notes |
|---|---|---|---|
| 1.1 | One window opens and is visible | `[x]` | Command Prompt: real console, `C:\>`, scrollbar |
| 1.2 | A **second** window opens beside the first | `[x]` | Two consoles, cascaded 48 px apart, both captions reachable |
| 1.3 | A third opens; all three stay visible | `[ ]` | |
| 1.4 | Windows do **not** all stack at the top-left | `[x]` | `XServerDisplay.cascade` steps a colliding window and sends ConfigureNotify; Wine accepted it — chrome drawn at the new rectangle |
| 1.5 | Title bar is drawn | `[x]` | `C:\windows\system32\cmd.exe` with all three buttons, once `launchProgram` went through `explorer /desktop=` |
| 1.6 | Caption is finger-height (~40 px, not 22) | `[x]` | **Only on a container created after seed 8.** Older prefixes keep mouse-sized chrome, which is why every earlier screenshot showed a thin caption |
| 1.7 | **Minimise** button present and works | `[ ]` | |
| 1.8 | **Maximise** button present and works | `[ ]` | |
| 1.9 | **Close** button present and works | `[ ]` | |
| 1.10 | Window can be **dragged** by its caption | `[x]` | Direct touch. Needed the press-on-contact change — the 380 ms hold was the gate |
| 1.11 | Window can be **resized** by its border | `[x]` | Right edge dragged in; seed 8's 8 px grab region is hittable |
| 1.12 | Resize works on a **second** window too | `[ ]` | |
| 1.13 | Window chrome matches the Vessel theme | `[ ]` | Seed 3 colours; classic drawing via seed 4 |
| 1.14 | A closed window leaves no ghost on the desktop | `[ ]` | |

## 2. Taskbar

| # | Action | State | Notes |
|---|---|---|---|
| 2.1 | Reveal handle visible when the bar is hidden | `[x]` | Bottom-left, level with Android's pill |
| 2.2 | Tapping the handle reveals the bar | `[x]` | |
| 2.3 | Bar carries no prose when nothing is open | `[x]` | |
| 2.4 | One open window ⇒ exactly one button | `[x]` | `C:\windows\system32\cmd.exe` |
| 2.5 | Two open windows ⇒ exactly two buttons | `[x]` | |
| 2.6 | Tapping a button **raises** that window | `[x]` | Tree dump proves the tapped window goes topmost; looked broken only because the buttons reshuffled and the windows overlapped exactly |
| 2.7 | Tapping a button **focuses** it (keys follow) | `[ ]` | |
| 2.8 | The focused window's button is marked | `[ ]` | |
| 2.9 | Closing a window removes its button | `[ ]` | |
| 2.10 | ~~Pause button in the taskbar~~ | — | Removed; the rail owns it |
| 2.11 | ~~Resume in the taskbar~~ | — | Removed; the rail owns it |
| 2.12 | ~~Stop in the taskbar~~ | — | Removed; the rail owns it |
| 2.13 | Start button opens and closes the launcher | `[x]` | |

## 3. Launcher

| # | Action | State | Notes |
|---|---|---|---|
| 3.1 | Opens over the desktop, anchored to the button | `[x]` | |
| 3.2 | Scrolls; nothing is clipped off the bottom | `[x]` | Was clipping the terminal row and Browse C: |
| 3.3 | Programs listed with correct badges | `[x]` | ARM64 / x86 / x64 / cmd / msiexec / wscript |
| 3.4 | Tapping a program launches it | `[x]` | |
| 3.5 | Search filters the grid | `[ ]` | |
| 3.6 | `cmd` opens a console | `[x]` | Twice, on two builds |
| 3.7 | `pwsh` disabled, says why | `[x]` | Not installed — no component yet |
| 3.8 | `sh` disabled, says why | `[x]` | Not installed — no component yet |
| 3.9 | `C:` opens the file browser | `[ ]` | |
| 3.10 | Tapping outside closes it | `[ ]` | |

## 4. Input

| # | Action | State | Notes |
|---|---|---|---|
| 4.1 | Android soft keyboard opens from the rail | `[ ]` | |
| 4.2 | Typed characters reach the guest | `[!]` | Reported not reaching Wine |
| 4.3 | Enter / Backspace / arrows reach the guest | `[ ]` | |
| 4.4 | Keys go to the **focused** window after a switch | `[ ]` | Depends on 2.7 |
| 4.5 | Trackpad mode moves the cursor | `[x]` | |
| 4.5b | Trackpad mode drags a window | `[!]` | Failed under test, cause unresolved. `input draganddrop` may start moving before the 380 ms hold elapses, which is exactly the condition the gesture needs — so this is failed-under-test, not proven broken. Needs a human finger |
| 4.5a | A window edge shows a resize cursor on hover | `[ ]` | Wine hit-tests the frame on `WM_SETCURSOR`, which needs a *hover*; trackpad mode only moves the cursor while a finger is down, so there may be no hover to report against. Input-mode question, not a Wine one |
| 4.6 | Direct-touch mode points, presses and drags | `[x]` | Press on contact; a finger drag is a mouse drag |
| 4.7 | Tap = left click, two-finger = right click | `[ ]` | |
| 4.8 | Scroll gesture scrolls the guest | `[ ]` | |
| 4.9 | Bluetooth **keyboard** reaches the guest | `[ ]` | Same path as 4.2 |
| 4.10 | Bluetooth **mouse** moves the cursor | `[ ]` | Needs pointer capture; not wired |
| 4.11 | Bluetooth **controller** reaches the guest | `[ ]` | `GamepadTranslator` exists |

## 4b. Input mapping

Everything below was written without a device — see `docs/plans/input-mapping.md`.
Unit tests cover the model, the catalogue and the store; **none of these rows is
testable off the device**, and none of them is ticked.

| # | Action | State | Notes |
|---|---|---|---|
| 4b.1 | The rail holds **five** tools in one row at 421 dp landscape, without scrolling | `[ ]` | The rail went 212 → 260 dp for this. Plan §9.2 is the open question; the arithmetic says five 44 dp targets and four 3 dp gaps need 232 dp inside the card, which 260 gives. Measured on nothing |
| 4b.2 | Input opens a panel beside the rail; the guest stays visible to its right | `[ ]` | 260 + 560 = 820 dp of a 927 dp window |
| 4b.3 | Pressing a control on a Bluetooth pad lights its row | `[ ]` | The live-press indicator, and the whole argument for `heldControls` on the seam. Depends on 4.11 |
| 4b.4 | With **Learn** on, a press opens that control's picker | `[ ]` | Depends on 4b.3 |
| 4b.5 | Rebinding a control takes effect **without relaunching** | `[ ]` | The reason the editor is in-session at all |
| 4b.6 | Rebinding a control that is **held** does not leave the guest holding a key | `[ ]` | `setInputProfile` releases before it changes. The failure mode is a character that walks into a wall forever |
| 4b.7 | Setting the right stick to **Keys** makes its four rows appear and bind | `[ ]` | |
| 4b.8 | Setting a stick to **Look** moves the guest cursor | `[ ]` | Plan §9.1: **may not survive a game that grabs or warps the cursor.** Relative-mouse mode is what `WinHandler` provides for Winlator and it is not vendored. Must be measured before Phase 3's look pad is worth finishing |
| 4b.9 | A stick at **Look** hitting the edge of the 1280×720 desktop does not wedge | `[ ]` | Same measurement as 4b.8, the other half |
| 4b.10 | **Deadzone** at 0.40 stops a worn stick holding a key; at 0.10 the stick still reaches | `[ ]` | |
| 4b.11 | **Look speed** at 200 and at 2400 both feel like the number says | `[ ]` | |
| 4b.12 | **Capture** takes a key from a Bluetooth keyboard | `[ ]` | Plan §9.3. `TYPE_NULL` says it should |
| 4b.13 | Capture from the **device's own IME** — how much of a keyboard arrives | `[ ]` | Expected to be partial: a soft keyboard has no `Esc`, `Ctrl`, `Alt`, arrows or function keys, and `ACTION_MULTIPLE` carries no keycode. If it is useless, capture degrades to hardware-only and the catalogue carries the feature |
| 4b.14 | Six keys with no preinstalled keysym reach the guest — `Print`, `Scroll Lock`, `Numpad Enter`, both `Super`, `Menu` | `[ ]` | The server installs the keysym on first press and sends `MappingNotify`. Unit-tested that the catalogue carries the right numbers; **never watched arriving** |
| 4b.15 | A profile edited in a session is still there after the app is force-stopped | `[ ]` | Live edits persist immediately; `input-profiles.json` under `files/datastore/` |
| 4b.16 | A container pointed at a profile starts on it | `[ ]` | `SessionRuntime.prepare` resolves it and logs the name |
| 4b.17 | Deleting a profile a container names: the container starts on the default and **says so** | `[ ]` | And the container document is **not** rewritten. `adb shell run-as` the two JSON files to check |
| 4b.18 | The **first edit** on a container using the built-in default silently adopts a copy, and another container on the default is unaffected | `[ ]` | |
| 4b.19 | The gamepad glyph in the rail and the container sheet reads as a gamepad at 18 dp | `[ ]` | Drawn in `VIcons` rather than transcribed from Phosphor — the project vendors no Phosphor asset for `game-controller`. Never rendered |

## 5. Session lifecycle

| # | Action | State | Notes |
|---|---|---|---|
| 5.1 | Desktop paints the theme colour, not black | `[!]` | Was `#161826`, black in the latest run |
| 5.2 | Leave the desktop and return: pixels survive | `[ ]` | First fix failed; second (shader program) unwatched |
| 5.3 | Rotate: the session survives | `[ ]` | |
| 5.4 | Rail opens, graphs sample | `[ ]` | |
| 5.5 | Session log shows the run | `[ ]` | |

---

## Known blockers, in the order they gate other rows

1. ~~X input focus is never set when a window maps.~~ Fixed — `onMapWindow`
   focuses a real window now. 4.2 still un-retested.
2. ~~The virtual desktop registry does not reach a launched program.~~ Fixed by
   routing `launchProgram` through `explorer /desktop=`. Kept below because the
   reasoning is the useful part.

   **The original finding.** `prefix-seed.reg` on the device does carry the two `Explorer`
   keys, and the console still comes up with no caption. The keys are read by
   **`explorer.exe`**, which decides the desktop for the programs *it* starts —
   a plain `wine prog.exe` never launches explorer and so never consults them.
   The fix is to route `SessionRuntime.launchProgram` through
   `explorer /desktop=<name>,<geometry>`; because Wine desktops are named
   objects and the session's desktop already exists under that name, the second
   process joins it rather than creating the second full-size desktop window
   that `SessionRuntime`'s own comment warns about. Not attempted yet.
   Gates all of §1 and 2.5-2.9.
3. **The black desktop.** The texture-generation fix was watched failing; the
   shader-program fix that replaced it has not been watched at all. Gates 5.1
   and 5.2.
4. **No 3D at all** — Turnip is built `-Dplatforms=android` and has no X11 WSI,
   so every D3D probe dies at `vkCreateInstance`. Gates the whole graphics
   matrix in `TODO.md` §3.
