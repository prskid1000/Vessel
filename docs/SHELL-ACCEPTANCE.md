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

Most of this was written without a device — see `docs/plans/input-mapping.md`.
The rows now ticked were watched on the phone; **the pad rows were driven by
`adb shell input gamepad`, not by a real controller** — no pad was paired to the
device at the time, and a row driven synthetically says `[~]` rather than `[x]`
however convincing it looked.

| # | Action | State | Notes |
|---|---|---|---|
| 4b.1 | The rail holds **five** tools in one row at 421 dp landscape, without scrolling | `[x]` | Watched. Touch / Keys / Input / Files / Log in one row, Pause and Stop below it, nothing clipped and no scrollbar. The rail went 212 → 260 dp for this; plan §9.2 is answered |
| 4b.2 | Input opens a panel beside the rail; the guest stays visible to its right | `[x]` | Watched. 260 + 560 = 820 dp of a 927 dp window, and the last hundred is guest |
| 4b.3 | Pressing a control on a Bluetooth pad lights its row | `[~]` | `input gamepad keyevent --duration 1200 96` reaches `GamepadTranslator` and resolves to `A`. **No Bluetooth pad was paired**, so the row stays partial: what is proven is the path from Android's input pipeline inward, not that a real pad enters that pipeline the same way |
| 4b.4 | With **Learn** on, a press opens that control's picker | `[~]` | Watched with the same synthetic press: the picker for `A`, "now: Space", opened. A *zero-duration* injection does not work and that is the injection's fault — `heldControls` goes empty again within the same frame, so nothing observes it |
| 4b.4a | A pad event reaches the guest while the **shell** has focus | `[x]` | The bug behind "the controller does nothing". Measured: with the panel's *Filter keys* field focused, `MainActivity.dispatchKeyEvent` logs `view hadFocus=false` — the compositor view was not going to be given the event at all. It now gets pad events first, whatever holds focus |
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
| 4b.19 | The gamepad glyph in the rail and the container sheet reads as a gamepad at 18 dp | `[x]` | Watched in both places. It reads as a pad |

## 4c. The touch overlay, and the three tabs

Phases 3–5 of `docs/plans/input-mapping.md`, against the design comp
`Vessel Input Mapping.dc.html`. Everything ticked here was watched on the phone.

| # | Action | State | Notes |
|---|---|---|---|
| 4c.1 | The Input screen has **Pad / Touch / Profiles**, from both entries | `[x]` | Watched cold from the container sheet at 421 dp, and in session at 560 dp. One `InputEditor`; the width decides one column or two |
| 4c.2 | The container sheet's Input row shows the profile as a chip | `[x]` | `Input · Pad bindings and the touch overlay. 20 controls mapped, 6 on the overlay.` with a `Keyboard and mouse` chip |
| 4c.3 | The header carries back, title, `<container> · <resolution>` and a profile picker | `[x]` | Watched. The picker briefly rendered a UUID before the profile list arrived; fixed by seeding the name map with the profile being edited |
| 4c.4 | Touch draws a preview at the session's aspect with every control in place | `[x]` | Watched, six controls. **The preview is the shape of the screen, not of the guest desktop** — see the note below |
| 4c.5 | `N CONTROLS ON THE OVERLAY`, each with kind, metrics and a binding chip | `[x]` | `Stick · 11% · 66% · 102 dp · W A S D` |
| 4c.6 | An empty overlay offers the stock layouts instead of an empty canvas | `[x]` | Watched; `WASD and look` adopted and persisted to `input-profiles.json` |
| 4c.7 | **The overlay is drawn over a running guest** | `[x]` | Watched. Stick, look pad, Space, E, Left Shift, Esc, over the desktop at 35% |
| 4c.8 | Show / hide has a control the user can find | `[x]` | Rail → Input → Touch → *Show the overlay*. Watched turning it on mid-session |
| 4c.9 | A control on the overlay sends its key to the guest | `[ ]` | Needs a guest program that shows keys; the desktop is still black (5.1) |
| 4c.10 | Holding an on-screen button and tapping elsewhere is still a **left** click | `[ ]` | `PointerRouterTest` pins it off the device. Not watched |
| 4c.11 | Edit mode: drag moves, the corner resizes, the guest receives nothing | `[ ]` | Not watched |
| 4c.12 | Profiles lists, creates, renames, duplicates, deletes and picks | `[x]` | Watched: three profiles listed with counts, the active one ringed, delete removed one, the radio moved the container |
| 4c.13 | Import refuses a file from another schema, with a sentence | `[ ]` | `InputProfileImportTest` pins the refusal; the SAF round trip is not watched |
| 4c.14 | Export writes a file the user picked a place for | `[ ]` | Not watched |
| 4c.15 | The built-in default is a **whole controller**, and every control on it is a pad control | `[ ]` | `TouchLayoutTest` pins the layout, the links and the resolution. **Not yet watched on the device** — the screenshots above were taken against the WASD default it replaced |
| 4c.16 | Rebinding `A` in the Pad tab moves the glass `A` button too | `[ ]` | `InputProfile.overlay` resolves it; pinned in a test, not watched |
| 4c.17 | The built-in default cannot be renamed or deleted, and Duplicate is the way out | `[ ]` | The name field is disabled and says so; Delete is disabled on that row. Not watched |

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

5. **A gamepad is not a gamepad inside the guest, and now there is a number for
   it.** Reported as "I connected a controller and the game does not pick it up".
   Measured with `tools/input/run-guest-pad.sh`, which runs a PE probe in the
   live container and asks all three APIs a Windows game can ask:

   ```
   VESSEL-GUESTPAD xinput dll=xinput1_4.dll load=ok caps=yes state=yes
   VESSEL-GUESTPAD xinput dll=xinput1_4.dll slot=0 caps=rc1167
   …the same for 1_3, 9_1_0, 1_2, 1_1, on all four slots…
   VESSEL-GUESTPAD dinput enum hr=0x00000000 devices=0
   VESSEL-GUESTPAD winmm slots=16
   VESSEL-GUESTPAD summary xinput=0 dinput=0 winmm=0
   ```

   `1167` is `ERROR_DEVICE_NOT_CONNECTED`. So **every XInput DLL is present and
   working and there is no device behind any of them**, DirectInput enumerates no
   game controller, and winmm has none attached. This is not a fault; it is
   `docs/plans/input-mapping.md` §1 exactly as written, now measured rather than
   asserted.

   What the pad *does* reach is the X server, as keys and a pointer — 4b.3 and
   4b.4 above. A game that reads the keyboard therefore plays; a game that calls
   `XInputGetState` sees nothing, and no amount of remapping changes that.

   **What would fix it, in the order the evidence points.** Wine's own
   `xinput1_1..1_4`, `xinputuap`, `dinput`, `dinput8`, `hid`, `hidclass`,
   `winexinput` and `winebus` all ship inside `dist/wine-*.wcp` already. The
   missing piece is a *device*: `build/wine.sh:263` configures
   `--without-sdl --without-udev --without-usb`, so `winebus.sys` has no backend
   and enumerates nothing. Two shapes are available.

   - **A `winebus` backend fed by the app** — a Wine patch adding a bus that
     takes HID reports over a socket, the way `patches/wine/0005` takes shared
     memory over `WINE_SYSVSHM_SOCKET`, with `enable_winebus_drv=yes` forcing it
     on the way line 258 already forces `wineoss`. This is the *correct* answer:
     one device description reaches `hidclass` and `winexinput`, and XInput,
     DirectInput, winmm and raw HID all light up at once, with rumble available
     back down the same pipe. It costs a Wine patch, a full rebuild and a new
     `.wcp`.
   - **Our own `xinput1_3.dll` and friends**, talking UDP to the app over
     loopback, which is what Winlator does with `winhandler.exe`. Cheaper and
     narrower: it covers XInput only, needs one PE per guest architecture, and
     leaves DirectInput and winmm exactly as dead as they are now — which for a
     title that predates XInput is the whole problem again.

   Neither is started. `tools/input/padwin.c` is the probe either would be
   measured with; run it before and after.
