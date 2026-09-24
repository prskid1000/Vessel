# gbe_fork patches

Applied on top of the pinned `GBE_REF` / `GBE_COMMIT` (`native/pins.env`), in
filename order, by `build/gbe.sh` — `apply_patches` is keyed on the source name
(`gbe`). A patch that does not apply is a hard error; see `apply_patches` in
`build/common.sh`. Bump `GBE_REVISION` whenever a patch here changes what the
build produces: devices adopt the `Steam` component by version code.

## `0001-steam-input-load-action-manifests`

From the maniro-x pull request. Loads the game's own Steam Input action manifest
(the `controller_*.vdf` a game ships for Steam's configurator) from the absolute
path the game passes to `SetInputActionManifestFilePath`, and builds the
emulator's action sets from it, so a game that only speaks Steam Input gets its
digital and analog actions without a hand-written `steam_settings/controller/`
file per game.

## `0002-steam-input-device-connected-callbacks`

Vessel's own, and the one that made No Man's Sky's controller work.
`EnableDeviceCallbacks` was a `TODO` that returned. A game that enables device
callbacks waits for `SteamInputDeviceConnected_t` before it treats a pad as
present, and never received one, so input was read and ignored. Now enabling
them posts a connected callback for every pad already present, and later
changes post connected/disconnected as they happen, each measured against the
last state reported.

## `0003-overlay-renderer-spell-out-the-x86-export-for-clang`

32-bit only. `GameOverlayRenderer.dll` exports `VirtualFreeWrapper` undecorated
with `#pragma comment(linker, "/EXPORT:" __FUNCTION__ "=" __FUNCDNAME__)`, and
clang expands neither macro inside a pragma, so the MSVC-target build stopped
with "pragma comment requires parenthesized identifier". Under `__clang__` the
pragma names the two strings MSVC would have produced — the decorated name
taken from clang's own object file, `?VirtualFreeWrapper@@YGXPAX0000000000@Z`.
The built DLL's 13 exports are identical to upstream's MSVC build.
