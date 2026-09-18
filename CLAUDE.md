# Vessel — working notes for Claude

## Distribution: the sideload APK, and nothing else

- **Never build, attach or mention a `play` APK.** The `play` flavour was removed.
  Play policy forbids executable code outside the package, so a Play build could
  neither bundle nor download Wine, FEX, DXVK or Turnip — a 4.8 MB APK that could
  run nothing. `sideload` is the only flavour (the flavour dimension is kept so
  task names stay the same).
- A release carries exactly one asset: `app-sideload-release.apk`.

## Making a release

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. `./gradlew.bat :app:assembleSideloadRelease`
3. Check that the `.wcp` packages inside the APK are the ones in `dist/`
   (compare SHA-256 of `assets/components/*.wcp` in the APK against `dist/`).
   `dist/` is what `bundledPackages` copies in; a stale file there ships stale.
4. Commit the bump, push, then
   `gh release create vX.Y.Z --target main --title "…" --notes-file <notes>
   app/build/outputs/apk/sideload/release/app-sideload-release.apk`

## Native components: the patch files are the source, not `native/`

- `native/wine`, `native/fex`, `native/mesa`, … are build trees. `build/*.sh`
  **resets them** (`checkout --force`, `reset --hard`) and re-applies every file
  in `patches/<component>/`. An edit made only in `native/` is lost on the next
  build, silently.
- So every native change must be written into a patch file in
  `patches/<component>/` (regenerate it with `git diff` from the tree, then check
  with `git apply --check -R`), with an entry in that directory's `README.md`.
  This has already bitten once: a Wine fix lived only in the tree, and the next
  Wine build quietly reverted it.
- Bump the component's revision in `native/pins.env` (`WINE_REVISION`,
  `FEX_REVISION`, `TURNIP_REVISION`, …) with a changelog comment. Devices adopt
  packages by version code, so a rebuilt package with the same code is never
  installed.
- Builds run in Docker from PowerShell or Git Bash:
  `docker run --rm -v "C:\Users\prith\Vessel:/src" -v vessel-work:/work vessel-build ./build/wine.sh`
  (prefix `MSYS_NO_PATHCONV=1` in Git Bash). Wine takes ~20 minutes.

## Vendored code

- `app/src/main/java/com/winlator/` is vendored. Every change to it is numbered
  in `app/src/main/java/com/winlator/README.md` (the list under *Local
  modifications*, plus the per-file table). Add the next number for any change.

## Checking work

- Unit tests: `./gradlew.bat :app:testSideloadDebugUnitTest`
- Device: `adb install -r app/build/outputs/apk/sideload/debug/app-sideload-debug.apk`.
  Session logs are under `run-as app.vessel` → `files/logs/<container>/`.
