# App model

What "MSIX support" would actually mean for this container, which parts of it
Wine already has, which parts are stubs that lie, and which parts are large
enough that the honest answer is to route around them and never build them.

The rule from `docs/OPTIMIZATION.md`, `docs/BANDWIDTH.md` and `docs/DEVTOOLS.md`
carries over unchanged: **a claim without a measurement is marked as
unmeasured.** Every Wine statement below was read out of `native/wine/` in this
tree at `19d8528d64db0090f59779386b962e6a9ca40c38` and is cited `file:line`.
Every statement about what an MSIX *is* was measured on the Windows 11 host this
document was written on (2026-08-17) — a real `.msix` opened as a zip, a real
`AppxManifest.xml` read out of `WindowsApps`, a real registered package's
registry footprint dumped, and real Electron import tables run through
`dumpbin`. Nothing here has been run on the Android device. Everything else is
marked *unverified*.

This document does not restate `docs/DEVTOOLS.md` §6. It contradicts one
sentence of it, and the contradiction is the point.

---

## Verdict

**Worth attempting, but not the thing you think.** "The Windows app model" is
six separable subsystems, and only one of them is small. The expensive five —
deployment (`Add-AppxPackage`), activation (`IApplicationActivationManager`),
AppContainer, per-process package identity, WinRT `Windows.ApplicationModel`,
and the Store client — are each a multi-month Wine project, and **none of them
is on the path to running Claude Desktop or any other Electron/desktop-bridge
app.** The cheap one is: an MSIX is a plain zip, a full-trust MSIX's payload is
an ordinary Win32 directory tree, and Wine's *registry-backed* package lookup
APIs are already real implementations reading a key that nothing ever writes.
So the shape of the useful work is **an unzipper, a manifest parser, one
registry value per package, and about ten lines of Wine** — call it two to
three weeks including the UI — and the shape of the useless work is everything
that has the word "deployment" in it. `docs/DEVTOOLS.md:32` says the MSIX path
is "dead on arrival"; that is right about `Add-AppxPackage` and wrong about
MSIX, and §3 is the correction. The honest size of *real* app model support —
the thing that would run a genuine UWP app, activate it by protocol, and
sandbox it — is a year and it should not be attempted; §2's table says where
each wall is.

---

## 0. What was measured for this document

Because the rest of the document leans on these, they are stated first, with the
command that produced them.

**An `.msix` is a plain PK zip.** `MdOdrMcpFilter.msix` (16,831 B, shipped
inside Windows Defender's platform directory on this host) begins
`50 4B 03 04`, opens with `System.IO.Compression.ZipFile.OpenRead` with no
special handling, and contains exactly eight entries:

```
Assets/Square44x44Logo.png   Assets/StoreLogo.png   Assets/Square150x150Logo.png
resources.pri                AppxManifest.xml       AppxBlockMap.xml
[Content_Types].xml          AppxSignature.p7x
```

`AppxBlockMap.xml` is a per-64 KB-block SHA-256 list and `AppxSignature.p7x` is
a PKCS#7 blob over it. Both are *verification* artefacts. Neither is needed to
read the payload out.

**A full-trust MSIX's payload is an ordinary Win32 tree.** GIMP 3.2.44 is
installed on this host as `GIMP.43237F745459_3.2.44.0_x64__nq49gba4h4mx8`. Its
`AppxManifest.xml` (9,777 B, read in full) says:

```xml
<Application Id="GIMP" Executable="VFS\ProgramFilesX64\GIMP\bin\gimp-3.exe"
             EntryPoint="Windows.FullTrustApplication">
…
<Capabilities>
  <rescap:Capability Name="runFullTrust" />
  <rescap:Capability Name="unvirtualizedResources" />
</Capabilities>
```

`runFullTrust` is the whole story: **a full-trust package does not run in an
AppContainer.** The entire §2 row about LowBox tokens is therefore irrelevant to
this class of app, which is the class Claude Desktop belongs to.

**And that exe does not know it is packaged.** A byte scan of the 7,225,856-byte
`gimp-3.exe` for `kernel.appcore`, `api-ms-win-appmodel`, `GetCurrentPackageId`,
`GetCurrentPackageFullName`, `GetCurrentPackageFamilyName`,
`GetPackagePathByFullName`, `GetCurrentApplicationUserModelId`,
`AppPolicyGetWindowingModel`, `RoInitialize`, `RoGetActivationFactory` and
`combase.dll` found **none of them**. It is a plain MinGW Win32 binary that
happens to live inside a zip.

**Package shapes on this host, all 157 installed packages surveyed:**

| | count |
|---|---|
| packages with a readable `AppxManifest.xml` | 157 |
| of those, `EntryPoint="Windows.FullTrustApplication"` | **35** |
| of those 35, declaring a framework `<PackageDependency>` | **25** |
| of those 35, declaring a `uap5:AppExecutionAlias` | 14 |
| of those 35, using a `VFS\` redirection folder | **2** |

The framework dependencies that appear at all: `Microsoft.VCLibs.140.00`,
`Microsoft.VCLibs.140.00.UWPDesktop`, `Microsoft.NET.Native.Framework.2.2`,
`Microsoft.NET.Native.Runtime.2.2`, `Microsoft.UI.Xaml.2.8`,
`Microsoft.WindowsAppRuntime.{1.7,1.8,2}`, `Microsoft.Ink.Handwriting.*`. Note
that `VFS\` — the thing that makes a package's payload *not* a flat tree — is
used by 2 of 35. The default shape is flat.

**What "registered" means, in the only key Wine reads.** The registry footprint
of the GIMP package under
`HKLM\SOFTWARE\Classes\Local Settings\Software\Microsoft\Windows\CurrentVersion\AppModel\PackageRepository\Packages`
is **one subkey per package full name, containing exactly one value, `Path`,
and no child keys**:

```
GIMP.43237F745459_3.2.44.0_x64__nq49gba4h4mx8    Path = C:\Program Files\WindowsApps\GIMP…_x64__nq49gba4h4mx8
GIMP.43237F745459_3.2.44.0_neutral_~_nq49gba4h4mx8   Path = …_neutral_~_nq49gba4h4mx8
Microsoft.VCLibs.140.00_14.0.33519.0_x64__8wekyb3d8bbwe  Path = …
```

(321 subkeys total. The `_neutral_~_` form is the bundle-level entry; a
`.msixbundle` produces two.) This is the exact shape Wine's `GetPackagePath`
reads and it is the exact shape `loader/wine.inf.in:690-700` already writes by
hand for VCLibs — including an `arm64` variant at `wine.inf.in:700`.

**Two real Electron binaries, import tables dumped.** This is the measurement
that changes the plan, and it is in §3.

**What could not be measured here.** `claude.ai/api/desktop/win32/arm64/msix/latest/redirect`
returns **405** to `HEAD` and cannot be fetched unauthenticated;
`…/setup/latest/redirect` returns **403** (both probed 2026-08-17, matching
`docs/DEVTOOLS.md`'s item 9). So every statement in this document about Claude
Desktop's *own* package is inference from the class it belongs to, not
observation of the artefact. Getting that one file onto a build host is the
single highest-value thing anyone can do for this plan — see Phase 0.

---

## 1. What "the app model" decomposes into

Six things, which people say as one word:

1. **Package format and install** — the OPC zip, `AppxManifest.xml`, package
   identity strings, the package graph (framework dependencies), and where files
   land (`C:\Program Files\WindowsApps\<full name>`, staged vs registered).
2. **Package identity at runtime** — `GetCurrentPackageFullName` and friends.
   Splits cleanly in two: *am I packaged?* (per-process, needs a process
   context) and *where is package X?* (registry lookup, needs a registry key).
   Wine treats these completely differently and that asymmetry is the lever.
3. **Activation** — `IApplicationActivationManager`, protocol/URI activation,
   file-type activation, and the `WindowsApps` execution aliases that make
   `claude.exe` resolvable from `PATH`.
4. **AppContainer and the security model** — LowBox tokens, capability SIDs,
   per-package `LocalCache`/`RoamingState` isolation.
5. **WinRT** — `combase` activation plus the `Windows.*` projection surface.
6. **Registry and state** — where registration lives and what survives
   re-provisioning.

To which the user's addendum adds a seventh:

7. **The Store** — acquiring a package without the Microsoft Store client. This
   turns out to be a *networking* problem, not an app-model problem, and it is
   the one place where a genuinely new capability is cheap. See §5.

---

## 2. Capability by capability

`file:line` throughout is `native/wine/` unless prefixed. "Vessel adds" is what
this repo would have to build *given* Wine's state, not what Wine would have to
gain. Sizes are engineer-weeks, unverified estimates.

| Subsystem | Wine today | Vessel adds | Size |
|---|---|---|---|
| **MSIX container (zip/OPC)** | **Absent.** `grep -i msix` across `dlls/`, `programs/`, `include/` hits exactly four lines, all of them the unrelated Windows Installer property `Msix64` (`dlls/msi/package.c:820` and three tests). There is no zip reader for packages anywhere. | Read the zip. The app has a hardened tar extractor and **no zip reader**: `WcpArchive.kt:57-91` sniffs zstd/xz/gzip/ustar magic and a `PK` header falls through to `WcpCompression.UNKNOWN` (`:39`, `:91`) and is refused. `java.util.zip.ZipFile` appears zero times in `app/src/main/java`. The safety machinery in `WcpInstaller.kt:394-501` (traversal guard, symlink policy, free-space precheck) is format-agnostic and reusable. | **S** (1w) |
| **`AppxManifest.xml` parsing** | Absent. The only `AppxManifest.xml` in the tree is a *test fixture*, `dlls/windows.applicationmodel/tests/appxmanifest.xml`. | Parse six attributes: `Identity/@Name`, `@Publisher`, `@Version`, `@ProcessorArchitecture`, `Application/@Executable`, `Application/@Id`. Everything else is optional. Android has `XmlPullParser`. | **XS** (2d) |
| **Package identity — *lookup*** | **Implemented, and registry-backed.** `GetPackagesByPackageFamily` (`dlls/kernelbase/version.c:1829`) enumerates subkeys of `HKLM\…\AppModel\PackageRepository\Packages` (`version.c:163-164`) and parses each with `PackageIdFromFullName`. `GetPackagePath` (`version.c:1940`) opens `<key>\<full name>` and reads the `Path` value. `PackageIdFromFullName` (`version.c:1706`) and `PackageFullNameFromId` (`version.c:1796`) are complete string implementations. **Nothing in the tree ever writes that key** except one hardcoded VCLibs entry, `loader/wine.inf.in:690-700`. | Write the key. One subkey, one `Path` value, per installed package — measured in §0 as the whole footprint. | **XS** (1d) |
| **Package identity — *per-process*** | **Stubbed, and stubbed correctly.** `GetCurrentPackageFullName` (`version.c:1601`), `…FamilyName` (`:1591`), `…Id` (`:1611`), `…Info` (`:1620`), `…Path` (`:1629`), `GetPackageFullName(HANDLE)` (`:1639`), `GetPackageFamilyName(HANDLE)` (`:1649`), `GetPackagePathByFullName` (`:1658`) all `FIXME` and return `APPMODEL_ERROR_NO_PACKAGE` (15700). `GetCurrentApplicationUserModelId` (`:1582`) returns `APPMODEL_ERROR_NO_APPLICATION` (15703). **15700 is what Windows returns for an unpackaged process**, so a well-written app gets the right answer. | **Nothing, and that is the recommendation.** A real implementation needs a per-process package context, and there is nowhere in the tree to put one — no PEB field, no wineserver process state, nothing. It is a Wine-side project of months. | **XL** — don't |
| **AppPolicy family** | Stubbed, succeeding, with desktop answers: `AppPolicyGetWindowingModel` → `ClassicDesktop`, `…ProcessTerminationMethod` → `ExitProcess`, `…ThreadInitializationType` → `None`, `…ShowDeveloperDiagnostic` → `ShowUI`, `…MediaFoundationCodecLoading` → `All`, all `ERROR_SUCCESS` (`dlls/kernelbase/main.c:88,101,114,127,140`). `AppPolicyGetClrCompat`, `…CreateFileAccess`, `…LifecycleManagement` are **not exported at all** — `#`-commented at `kernelbase.spec:45-47`. | Nothing. This already answers the way a desktop app wants. | — |
| **Deployment (`Add-AppxPackage`, `IPackageManager`)** | **Present as an object, `E_NOTIMPL` as a function.** `dlls/appxdeploymentclient/` registers exactly one runtime class, `Windows.Management.Deployment.PackageManager` (`classes.idl:26`). `RoActivateInstance` on it **succeeds** (`package.c:395-413`). Then all 29 `IPackageManager`/`IPackageManager2` methods return `E_NOTIMPL` — `AddPackageAsync` at `package.c:102`, `RegisterPackageAsync` at `:130`, `StagePackageAsync` at `:123`, `FindPackages` at `:137`, and so on. 79 of the 82 flat exports in `appxdeploymentclient.spec` are `@ stub`. And the PowerShell that would drive it does not exist: `Launchable.kt:93-96` refuses `.ps1` because "Wine ships a stub PowerShell that cannot run scripts". | **Nothing.** Implementing `AddPackageAsync` means implementing everything below it. There is a path that never touches it — §4. | **XL** — don't |
| **Activation manager** | **Absent.** `IApplicationActivationManager` exists only as an interface declaration and a `coclass` line in `include/shobjidl.idl:3836` and `:4011`. Grepping `dlls/` for the coclass name or its CLSID returns **nothing**. No implementation, no registration, no `shell:AppsFolder` (zero hits tree-wide). | Nothing needed for launch. Vessel launches by path already (`SessionShellHost.kt:232-244`, `WineLaunch.kt:419-427`). | **XL** — don't |
| **App execution aliases** | **Absent.** `IO_REPARSE_TAG_APPEXECLINK` is `#define`d at `include/winnt.h:2617` and **referenced nowhere else in the tree** — no reader, no writer, no `WindowsApps` handling. | A `.cmd` shim or a `PATH` entry. The alias is a reparse point whose only job is "run this exe"; a two-line batch file does the same thing and Vessel already routes `.cmd` through `cmd.exe /c` (`SessionShellHost.kt:239`). | **XS** (1d) |
| **AppContainer / LowBox** | **Absent, and it lies about it.** `NtCreateLowBoxToken` (`dlls/ntdll/unix/security.c:757`) sets `*token_handle = NULL` and returns `STATUS_SUCCESS`; the wineserver has no lowbox request at all and `struct token` (`server/token.c:108`) has no container or capability fields. `TokenIsAppContainer` always 0 (`security.c:662`), `TokenAppContainerSid` always NULL (`:654`), `TokenCapabilities` and `TokenAppContainerNumber` fall to the `default` → `STATUS_NOT_IMPLEMENTED` (`:681`), `TokenIntegrityLevel` is hardcoded High for every process (`:634`) and the setter is a no-op (`:741`). `PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES` is not in `validate_proc_thread_attribute` (`dlls/kernelbase/process.c:2117-2152`) so `UpdateProcThreadAttribute` fails with `ERROR_NOT_SUPPORTED`; `PROC_THREAD_ATTRIBUTE_MITIGATION_POLICY` passes validation at `:2136` and is then **silently dropped** at `:898`. `userenv.spec` exports one AppContainer symbol out of six, `CreateAppContainerProfile` (line 3), and it is `E_NOTIMPL` (`dlls/userenv/userenv_main.c:691`). The only working pieces are capability-SID derivation (`dlls/ntdll/sec.c:1914`, a correct SHA-256 implementation) and restricted-token creation (`server/token.c:1383`). | **Nothing, and nothing is correct.** A `runFullTrust` package — measured in §0 as what desktop-bridge apps declare — never enters an AppContainer on real Windows either. | **XL** — don't |
| **WinRT `combase`** | **Real and thin.** `RoInitialize` (`dlls/combase/roapi.c:124`) is `CoInitializeEx`; `RoGetActivationFactory` (`:147`) resolves a class name via the SxS activation context (`:55`) then `HKLM\Software\Microsoft\WindowsRuntime\ActivatableClassId\<class>\DllPath` (`:65`) then `LoadLibrary` + `DllGetActivationFactory`. **There is no built-in class table** — activation is entirely registry-driven, which means it is extensible without touching Wine. `RoGetAgileReference` (`:380`) is genuinely implemented. `RoRegisterActivationFactories` (`:488`) returns `S_OK` and does nothing. `wintypes` provides `ApiInformation`, but `RoGetMetaDataFile` and `RoIsApiContractPresent` are `@ stub`. | Nothing, unless a specific app is measured to need a specific class. | **S** per class |
| **`Windows.ApplicationModel`** | **Present, and wrong in the dangerous direction.** `dlls/windows.applicationmodel/` registers `Package` and `DesignMode` only (`classes.idl:29-32`). `IPackageStatics::get_Current` (`package.c:499`) **allocates an object and returns `S_OK`** — where Windows returns `0x80073d54` (= `HRESULT_FROM_WIN32(15700)`) for an unpackaged process. Wine's own test says so: `tests/model.c:583-585` is `todo_wine ok( hr == 0x80073d54 …)`. Then `IPackage::get_Id` is `E_NOTIMPL` (`package.c:445`) and `IStorageItem::get_Path` (`package.c:323`) returns **the directory of the current exe**, not a package root. `Windows.Storage.ApplicationData` is the same shape: `get_Current` fake-succeeds (`applicationdata.c:294`, `tests/data.c:80-81` `todo_wine`), and `get_LocalFolder`/`get_RoamingFolder`/`get_TemporaryFolder` are all `E_NOTIMPL` (`:224`,`:230`,`:236`); the object carries only `IApplicationData_iface` (`:120`), so `IApplicationData2` — where `LocalCacheFolder` lives — cannot be obtained at all (`QueryInterface`, `:129`). | **A three-line correction is worth more than a feature.** An app that asks "am I packaged?" through WinRT gets *yes* and then falls over, while the same question through Win32 correctly gets *no*. Making `get_Current` return `0x80073d54` when the process is unpackaged is what Wine's own tests already assert. | **XS** (1d, upstreamable) |
| **`Windows.ApplicationModel.Core`** | Absent — `CoreApplication`, `CoreApplicationView`, `CoreWindow`, `AppInfo`, `PackageId`, `Windows.ApplicationModel.Activation.*` are registered nowhere. `RoGetActivationFactory` returns `REGDB_E_CLASSNOTREG`. | Nothing. A packaged *desktop* app never touches these. A real UWP app cannot start without them. | **XL** — don't |
| **Registry / state** | Wine reads one key (`version.c:163-164`) and seeds one fake package into it (`wine.inf.in:690-700`). There is no `StateRepository`, no `ActivatableClasses\Package` per-package hive, no `AppModel\Repository` SQLite. (Real Windows also keeps a `StateRepository-*.srd` SQLite database under `C:\ProgramData\Microsoft\Windows\AppRepository`; that directory was not readable without elevation on this host, so its contents are **unverified**.) | Write the one key. See §7 for why this does *not* have to go in the seed. | **XS** |

**Reading the table.** Every row that is expensive is also a row nothing on the
critical path touches. Every row on the critical path is XS or S. That is not a
coincidence — it is because Microsoft built the desktop bridge specifically so
that unmodified Win32 apps could ship as MSIX without being rewritten, and an
app that was not rewritten does not need the app model.

---

## 3. "Unpack and run" — the shortcut, and the two things it actually hits

**The claim.** An MSIX is a zip; extract it, put the payload somewhere, launch
the exe; identity queries return "not packaged", which is true and which the
app should tolerate.

**Verdict: viable, and measured to be viable for the class — but the failure
mode is not the one the brief expects, and it is not identity.** Two findings,
both measured on this host on 2026-08-17 with `dumpbin /IMPORTS`.

### 3.1 The package-identity imports are fine, and Wine already exports them

`Code - Insiders.exe` (Electron/Chromium, x64, 233,171,304 B) **statically
imports, from `KERNEL32.dll`**:

```
23C GetCurrentPackageFullName
2CA GetPackageFamilyName
2CF GetPackagePathByFullName
2D0 GetPackagesByPackageFamily
```

All four are exported by Wine's kernel32 — `dlls/kernel32/kernel32.spec:641`,
`:783`, `:787`, `:786`. Three of them are the stubs that return
`APPMODEL_ERROR_NO_PACKAGE`, which is the *correct* answer for an unpackaged
process, and the fourth is the real registry-backed one that will return zero
packages. **Chromium's package probe therefore gets a true answer and takes the
desktop path.** This is the good news and it is the reason the shortcut works at
all.

`Postman.exe` (Electron, x64, 206,389,888 B) imports only `GetPackageFamilyName`
of the four. Neither binary imports `kernel.appcore` or any
`api-ms-win-appmodel-*` apiset — and there is no `kernel.appcore.dll` in this
Wine tree, so that absence matters.

### 3.2 The import that actually bites is an AppContainer one, and it is a Wine hole

`Postman.exe` **statically imports `USERENV.dll` ordinal 8,
`DeriveAppContainerSidFromAppContainerName`.** Wine's `dlls/userenv/userenv.spec`
has 24 exports; exactly one of them is from the AppContainer family
(`CreateAppContainerProfile`, line 3). Real `userenv.dll` on this host exports
six:

```
108  2 CreateAppContainerProfile
111  5 DeleteAppContainerProfile
114  8 DeriveAppContainerSidFromAppContainerName
115  9 DeriveRestrictedAppContainerSidFromAppContainerSidAndRestrictedName
131 18 GetAppContainerFolderPath
132 19 GetAppContainerRegistryLocation
```

**What Wine does with an unresolvable static import is the load-bearing
detail, and it is not what people assume.** `dlls/ntdll/loader.c:1289-1295`
does *not* fail the load. It calls `allocate_stub()` (`loader.c:467`) and binds
the thunk to a generated stub whose entry point is `stub_entry_point`
(`loader.c:398-410`):

```c
    rec.ExceptionCode           = EXCEPTION_WINE_STUB;
    rec.ExceptionFlags          = EXCEPTION_NONCONTINUABLE;
    …
    for (;;) RtlRaiseException( &rec );
```

`#if defined(__i386__) || defined(__x86_64__) || defined(__arm__) || defined(__aarch64__)`
at `loader.c:392` covers this target.

So the consequence, precisely:

- The process **loads**. `--no-sandbox` is still reachable.
- The IAT slot is **non-NULL**, so no `if (!fn)` guard in the app can help.
- The instant the app calls it — which is when it builds an AppContainer
  sandbox token, i.e. exactly what `--no-sandbox` suppresses — it takes a
  **noncontinuable** exception and dies.

That reframes WineHQ 21232 for this stack. `--no-sandbox` is not merely
recommended; on a binary shaped like `Postman.exe` it is the difference between
running and a hard, uncatchable crash, and no amount of Chromium-side
defensiveness can change that because the symbol resolves to something callable.
**Unverified on the device** — this is predicted from the import table and
Wine's loader source, not observed. It is also cheap to fix: six lines adding
the five missing `userenv` exports as real stubs returning
`E_NOTIMPL`/`ERROR_CALL_NOT_IMPLEMENTED`. It must *not* be done as Wine `@ stub`
spec entries, because those raise `EXCEPTION_WINE_STUB` too — the same crash
with a tidier name.

`Code - Insiders.exe` **delay-loads** the same symbol (and `RoInitialize`,
`RoActivateInstance`, `RoGetActivationFactory`). Delay-load failure goes through
`LdrResolveDelayLoadedAPI` (`dlls/ntdll/loader.c:4030`), which on failure invokes
the image's own `dllhook` — so a delay-loaded miss is soft and the app can
recover. **Two Electron binaries, two different linkages.** Which one Claude
Desktop's arm64 build uses is unknown and is Phase 0's job to find out.

### 3.3 What else would break, in order of likelihood

1. **Framework package dependencies.** 25 of the 35 full-trust packages on this
   host declare one. If Claude Desktop declares `Microsoft.VCLibs.140.00`, the
   unpacked payload will look for a DLL that lives in *another* package. Wine's
   `wine.inf.in:690-700` already fakes a VCLibs registration pointing at
   `%SystemRoot%\system32` — including an arm64 variant at `:700` — which is
   precisely the hack this plan generalises. Electron apps typically bundle
   everything and declare nothing; **unverified for Claude Desktop.**
2. **`VFS\` redirection.** 2 of 35 use it. If present, the payload expects its
   files to appear at `C:\Program Files\…` rather than under the package root,
   and the fix is to copy `VFS\ProgramFilesX64\…` to the real path instead of
   leaving it in place. GIMP is one of the two, and the manifest names the
   redirect explicitly, so this is a parse-and-copy problem, not a mystery.
3. **`uap6:LoaderSearchPathOverride`.** GIMP declares one
   (`FolderPath="VFS\ProgramFilesX64\GIMP\bin"`), which is the packaged
   equivalent of "run with `bin` as the working directory". Vessel's
   `launchProgram` already takes a `workingDirectory`
   (`SessionRuntime.kt:468-555`), so this maps directly.
4. **The WinRT lie in §2.** If the app asks
   `Windows.ApplicationModel.Package.Current` instead of
   `GetCurrentPackageFullName`, Wine says yes and then fails on `get_Id`. This
   is a Wine bug with a `todo_wine` test already written against it and it is
   the single most likely *silent* misbehaviour of the unpack-and-run path.
5. **Signature and block map.** Ignored entirely by this plan. Nothing in Wine
   checks them; nothing needs to.
6. **Everything Chromium-shaped** — the sandbox, the multi-process model, ANGLE,
   memory. `docs/DEVTOOLS.md` §5 covers all of it and none of it changes here.
   Unpack-and-run does not make Electron work; it removes the *packaging* excuse
   so that the Chromium question can be asked cleanly.

### 3.4 A worked example the repo already has an opinion about: winget

`README.md:213-215` says: *"winget ships as an MSIX built on WinRT, and Wine
implements no part of the Windows app model — it cannot start, let alone
install."* The conclusion is right and the mechanism is not, and the difference
matters because it is the difference between "MSIX apps are impossible" and
"this particular MSIX app is impossible".

Measured on this host: `Microsoft.DesktopAppInstaller_1.29.280.0_x64__8wekyb3d8bbwe`
declares six `<Application>` entries, five of them
`EntryPoint="Windows.FullTrustApplication"`, including
`<Application Id="winget" Executable="winget.exe" …>`. `winget.exe` imports
**none** of `GetCurrentPackage*`, `GetPackage*`, `RoInitialize` or
`RoGetActivationFactory` — it is a thin launcher. So winget would unpack and
start perfectly well.

It would then do nothing, for three concrete reasons this document can name:

- Its declared capabilities are `packageManagement`, `packageQuery`,
  `storeAppInstall`, `appLicensing` — i.e. its entire job is
  `Windows.Management.Deployment`, every method of which is `E_NOTIMPL`
  (`dlls/appxdeploymentclient/package.c:102` onward).
- It declares three framework dependencies —
  `Microsoft.WindowsAppRuntime.1.8`, `Microsoft.VCLibs.140.00`,
  `Microsoft.VCLibs.140.00.UWPDesktop` — none of which exist in a Vessel prefix.
- Its `winget.exe` alias is a `uap5:ExecutionAlias`, which Wine has no reader
  for (`include/winnt.h:2617`, referenced nowhere).

**The lesson generalises.** "Does this MSIX app work?" is answered by reading
its manifest's `Capabilities` and `PackageDependency` lists, not by the fact
that it is an MSIX. An app whose capabilities are `runFullTrust` and
`internetClient` is a Win32 app in a zip. An app whose capabilities are
`packageManagement` and `appLicensing` is asking for the parts Wine does not
have, and no amount of unpacking helps it.

### 3.5 The honest position

For Claude Desktop specifically, the consumer `setup` installer remains the
better first target — it is smaller, it has no manifest, and
`docs/DEVTOOLS.md:817-819` already recommends it. **The value of the MSIX path
is not Claude Desktop. It is that it is a general capability**: it turns "this
app ships only as MSIX" from a wall (`TerminalProfile.kt:6-14`, `README.md:213`)
into a supported import, for every app in that shape, at a cost of about three
weeks. That is the user's actual ask and it is a good trade.

---

## 4. What `Add-AppxPackage` requires, and the path that avoids it

`Add-AppxPackage` is a PowerShell cmdlet over `Windows.Management.Deployment.PackageManager`.
Under this Wine, the chain fails at every link:

1. **There is no PowerShell.** Wine's `powershell.exe` is a stub;
   `Launchable.kt:93-96` refuses `.ps1` for exactly this reason, and
   `SessionShellHost.kt:228-230` explains that returning null "keeps the failure
   a refusal rather than a launch of Wine's stub PowerShell, which would appear
   to work". A real `pwsh.exe` is procurable (`docs/DEVTOOLS.md` §4 measures
   `PowerShell-7.6.5-win-arm64.zip` at 99,526,416 B) — but PowerShell 7 does not
   ship the `Appx` module's Windows-only cmdlets in a form that helps here, and
   even if it did:
2. **`PackageManager` activates and then does nothing.** `RoActivateInstance`
   succeeds (`dlls/appxdeploymentclient/package.c:395-413`); `AddPackageAsync`
   returns `E_NOTIMPL` (`:102`). Wine's own test file records the real Windows
   contract it violates (`tests/model.c:281-285`).
3. **`DISM /Add-ProvisionedAppxPackage`** needs `dism.exe` and a servicing
   stack, neither of which exists.
4. **`ms-appinstaller:` / App Installer** is itself an MSIX app. Zero hits for
   `ms-appinstaller` anywhere in the tree.

**The path that avoids all of it** is the one this plan takes: do the install
*outside* the guest. Vessel already owns the prefix from the Android side —
`SessionRuntime.installTools()` (`SessionRuntime.kt:2332-2377`) copies whole
trees into `drive_c` from Kotlin, `installToolTree()` (`:2379-2432`) does
staging-then-rename, and `TOOLS_LAYOUT` (`:3061-3064`) is a table of
"payload dir → prefix dir → sentinel". An MSIX importer is the same operation
with a zip on the front and four registry values on the back. **No guest-side
installer runs at any point**, which is the same reasoning `docs/DEVTOOLS.md`
uses to route around `msiexec` for Python, Node and Chrome.

---

## 5. Microsoft Store support

The user's addendum. Split it in two, because the two halves have wildly
different costs.

**Acquisition — cheap, and it is a networking problem.** The Store client is not
required to obtain a Store package. Microsoft's own delivery services answer
unauthenticated for free, DRM-free apps: the display catalog
(`displaycatalog.mp.microsoft.com`) maps a Store ID to a package family, and the
FE3 update service (`fe3.delivery.mp.microsoft.com`) returns time-limited direct
URLs to the `.appx`/`.msix`/`.msixbundle` blobs. This is the mechanism behind
every third-party Store downloader and behind `winget`'s `msstore` source. What
comes back is an ordinary zip of the shape measured in §0. **All of this
belongs on the build host or in Kotlin, never in the guest**, which means it
inherits none of Wine's problems. See §6 for the citation status of this
paragraph.

**What is *not* obtainable this way:** paid apps and anything license-gated;
and games delivered as `.msixvc`, which is a different, encrypted container
entirely. Do not plan for either.

**The Store client itself — do not attempt.** `winstore.app` is a WinUI/XAML
MSIX built on the app model, `Windows.Services.Store`, a Microsoft account
sign-in flow, and Delivery Optimization. Wine has `windows.ui.xaml` as a
194-line skeleton whose `DllGetActivationFactory` matches exactly one class
(`ColorHelper`), no `Windows.Services.Store` at all, and no
`Windows.ApplicationModel.Core`. `TerminalProfile.kt:6-14` already wrote down
the general form of this conclusion for Windows Terminal and it applies verbatim.

**So "Windows app store support" in this plan means: browse and fetch from the
Store's catalog in the Android UI, then run the resulting package through the
same importer as a local `.msix`.** That is a genuinely useful feature and it is
mostly HTTP and JSON. It is Phase 4 because it is worthless until the importer
in Phase 3 works.

---

## 6. What upstream is doing

Short answer: **nothing, and one person on GitHub already built the cheap half of
this plan.** That is worth knowing before writing a line.

**Wine itself.** The `appxdeploymentclient` DLL exists and is a stub surface —
[Wine's own API listing](https://source.winehq.org/WineAPI/appxdeploymentclient.html)
enumerates the functions and marks them stubs, which matches
`appxdeploymentclient.spec` in this tree (79 of 82 exports `@ stub`). There is no
MSIX work in progress: the string `msix` does not appear in the Wine source in
any app-model context (§2). The WinRT `windows.applicationmodel` and
`windows.storage.applicationdata` DLLs are being grown class by class with
`todo_wine` tests marking the gaps, which is why §2 can point at
`tests/model.c:583-585` — upstream knows `Package.Current` is wrong and has
written the test that says so.

**wine-staging** carries no MSIX/AppX/appmodel patchset
([wine-staging](https://github.com/wine-compholio/wine-staging/)). Nothing to
rebase onto.

**The prior art that matters** is
[`publicsite/appx_msix_wine`](https://github.com/publicsite/appx_msix_wine) —
"An installer for appx/msix files on Linux using wine". Its `installAppx.sh`
does, in order: extract with `7z`/`unzip`; parse `AppxManifest.xml` (or
`AppxBundleManifest.xml`) for name, version, publisher and architecture; resolve
`<PackageDependency>` entries against already-extracted packages and against
Wine's `WindowsApps` folder; copy the tree into
`$WINEPREFIX/drive_c/Program Files/WindowsApps/<derived name>`; clean up.
**It writes no registry keys and never invokes PowerShell.** That is Phase 3 of
this plan, minus the registration, written by someone else and known to work
well enough to publish — which is the strongest available evidence that
unpack-and-run is not a fantasy. Vessel's version differs in three ways that
matter: it runs on the Android side rather than as a shell script, it reuses
`WcpInstaller`'s traversal/symlink hardening on attacker-supplied zips, and it
*does* write the registration key, because §2 shows Wine's lookup APIs are real
and nobody has been feeding them.

**What that project says does not work is winget**, and it points at
[WineHQ bug 53354](https://bugs.winehq.org/show_bug.cgi?id=53354) — *"Wine
should provide `icu.dll`"*. Note what that is and is not: it is a **missing
ordinary DLL**, not an app-model failure. It reinforces §3.4's point from the
other direction — the thing that stops winget is not that it is packaged.
(Bugzilla search returns 403 to unauthenticated fetches from here, so the
by-keyword bug survey is **incomplete**; 53354 is the one bug this document can
cite by title with confidence.)

**Community consensus, low-quality sources, recorded as such.** The WineHQ
forums have a standing thread on
["How to bring UWP/APPX support to Wine"](https://forum.winehq.org/viewtopic.php?t=39125)
and one on
[installing `.appinstaller`/`.msix`](https://forum.winehq.org/viewtopic.php?t=36815);
the recurring advice in both is "you cannot install it, extract it with 7z". A
[winetricks issue](https://github.com/Winetricks/winetricks/issues/2225) asking
for a UWP/WinRT runtime verb to enable `.appx` installation is open and has not
been implemented. **These are forum posts, not bug reports or code**; they are
cited to show that no one is working on this, not to establish a technical fact.

**Store acquisition is well-trodden and open-source.**
[`LSPosed/MagiskOnWSALocal`'s `generateWSALinks.py`](https://github.com/LSPosed/MagiskOnWSALocal/blob/main/scripts/generateWSALinks.py)
is a working, readable implementation: three SOAP posts to
`https://fe3.delivery.mp.microsoft.com/ClientWebService/client.asmx` (cookie,
then WUID request, then `…/client.asmx/secured`), parsing `FileLocation`/`Url`
elements out of the response to get **direct download URLs**. It filters results
by filename regex for exactly the shapes this plan cares about — a
`.msixbundle` for the app and `Microsoft.UI.Xaml.*_<arch>_*.appx` /
`Microsoft.VCLibs.*.UWPDesktop_*_<arch>_*.appx` for the framework dependencies.
The returned files are plain packages. The encrypted variants are a *different
file extension* — `.eappx`, `.eappxbundle`, `.emsixbundle` — so the plan can
detect and refuse them by name rather than by trial. **Unverified from here** —
no request was made; this is read off someone else's source, and Phase 4's
experiment exists to confirm it still behaves that way.

---

## 7. Where Vessel's architecture helps, and where it fights

**It helps, more than expected, in three places.**

**The install path already exists and is Kotlin-side.** `installTools()`
(`SessionRuntime.kt:2332-2377`) → `installToolTree()` (`:2379-2432`) →
`TOOLS_LAYOUT` (`:3061-3064`) is exactly the shape an MSIX importer needs, down
to the staging-then-`renameTo` at `:2396-2402` and the free
`target.parentFile.mkdirs()` at `:2393`. A `WindowsApps` destination slots in
as another row.

**The extraction safety work is already done.** `WcpInstaller.extract`
(`WcpInstaller.kt:394-501`) refuses hard links (`:436`), refuses anything that
is not a regular file, a directory or a relative symlink (`:441`), and prechecks
free space (`:188`). An MSIX is attacker-supplied content in a way a `.wcp` from
Vessel's own registry is not, and it needs every one of those. Only the *format* layer
is missing: `WcpArchive.kt:57-91` sniffs magic bytes and has no `PK` branch.

**Registration does not have to live in the seed, and this dissolves the
`SEED_VERSION` problem.** `PrefixRegistry.SEED_VERSION` is **25**
(`PrefixRegistry.kt:180`) — one ahead of the 24 the brief quoted. The seed is
rendered whole (`ContainerProvisioner.kt:392-407`) and applied by `regedit` twice
(`SessionRuntime.kt:1267-1272`, `:1318-1325`), and `seedFor(letters)`
(`PrefixRegistry.kt:982`) takes no caller-supplied keys — there is no
extension point. But a `.reg` **merge adds and replaces; it never removes**
(`PrefixRegistry.kt:20-34`), and the seed does not name anything under
`AppModel\PackageRepository`. So a separate per-container `packages.reg`,
merged after the seed, survives every seed rewrite untouched, and a package
registration is not seed state at all. That is the single most useful
architectural fact in this document and it means **`SEED_VERSION` never has to
be bumped for this feature.** *Unverified on device* — the reasoning follows
from merge semantics the repo documents in three places, but nobody has watched
a `packages.reg` survive a `SEED_VERSION` bump.

**It fights, in four places.**

**One `Tools` component per container.** `ComponentStore.referencesOf`
(`ComponentStore.kt:443-449`) is a `wire → versionCode` map with no list, and
`SessionRuntime.kt:2316` records that this is why Python and Node had to
ride inside the Tools payload rather than arrive separately. **An imported MSIX
must therefore not be a component at all.** It is user content, like a game — it
lives in the prefix, it is installed by the app, and it never touches
`provisioned.json`. Trying to make packages components would reproduce the
version-code collision `docs/DEVTOOLS.md` §7.3 describes.

**There is no window manager.** `SessionEnvironment.kt:335` (`MANAGED_DESKTOP`),
and "the vendored server is a compositor with no window manager in it" at both
`PrefixRegistry.kt:549` and `SessionRuntime.kt:503-508`. Irrelevant to installing
a package; very relevant to whether the Electron app that comes out of it is
usable.

**`.msix` is not in the launch tables.** `SessionShellHost.commandFor`
(`SessionShellHost.kt:232-244`) and `Launchable.launchabilityOf`
(`Launchable.kt:70-99`) must agree — their own KDoc says so
(`SessionShellHost.kt:222-226`). Adding `.msix`/`.appx`/`.msixbundle` means a
new row in both, and unlike every existing row it is an **import**, not a
launch: there is no guest command to build. `Launchable`'s vocabulary
(`Runs`/`Refused`/`NotAProgram`, `:33-60`) has no case for "installable", so
this is a small but real UI-model change.

**Per-launch environment does not exist.** `launchProgram`
(`SessionRuntime.kt:468-555`) takes `program`, `arguments`, `workingDirectory`
and uses the session environment verbatim (`:518-533`). If a packaged app needs
a per-app variable, that is a new parameter threaded to `ProcessSpec` plus a
field on `AppShortcut`.

---

## 8. TODO #17: how to tell an MSI failure from an MSIX one

`docs/TODO.md:370-376` records a measured, open, unexplained failure: `msiexec`
loads `msi.dll`, `cabinet.dll`, `wintrust.dll` and `comctl32.dll`, draws a
window, and the payload never lands in `C:\Program Files`.

**It tells us nothing about MSIX, and the plan does not need it resolved.** MSI
and MSIX share a prefix and nothing else:

- MSI is a **COM structured-storage database** with tables, a sequence of
  custom actions, and cabinet payloads, executed by `msi.dll` inside the guest.
- MSIX is a **zip with an XML manifest**, and in this plan it is never executed
  by anything — it is unpacked from Kotlin, on the Android side, by code that
  already exists for `.wcp`.

They share no Wine code: `dlls/appxdeploymentclient/Makefile.in` declares
`IMPORTS = combase` and nothing else, and the only `msix` string in the whole
Wine tree is the unrelated Windows Installer property `Msix64`
(`dlls/msi/package.c:820`).

**So the discriminator is trivial and should be written into #17 when someone
picks it up:** if an unpacked MSIX payload runs and an `.msi` still does not,
the fault is `msi.dll` or the test package and it is confined there. If neither
runs, the fault is upstream of both — the prefix, the launch path, or the PE
loader — and it is a different investigation. Phase 3 below produces that
discriminator as a side effect at no extra cost.

---

## 9. The plan, ordered by value ÷ risk

Each phase names the **cheapest decisive experiment** — the one thing to try
first that proves or kills the phase before real work goes in. No phase begins
until the previous phase's experiment is green. Phase 0 needs no build and no
device.

### Phase 0 — Read Claude Desktop's own package. *One session. No build, no device.*

Everything in §3 is measured on *analogues*. The motivating target has never
been looked at, because `claude.ai/api/desktop/win32/arm64/msix/latest/redirect`
returns 405 to an unauthenticated `HEAD` and 403 on the `setup` sibling. Get the
file onto a build host with a signed-in browser, then do a pure desk exercise:

> **Experiment.**
> 1. `unzip -l` it. Confirm it is a PK zip and note whether it is a
>    `.msixbundle` (two-level) or a flat `.msix`.
> 2. Read `AppxManifest.xml`. Record: `Identity/@ProcessorArchitecture` (is
>    there really an arm64 payload?), `Application/@Executable` (is it under
>    `VFS\`?), `EntryPoint` (is it `Windows.FullTrustApplication`?), every
>    `<PackageDependency>`, and whether `uap6:LoaderSearchPathOverride` or
>    `virtualization:FileSystemWriteVirtualization` appear.
> 3. Run `dumpbin /IMPORTS` over every PE in the payload and diff the imported
>    symbol set against Wine's export set for `kernel32`, `kernelbase`,
>    `userenv`, `ntdll` and `combase`. **Every static import that Wine does not
>    export is a future noncontinuable crash** (`dlls/ntdll/loader.c:1289-1295`,
>    `:398-410`); every delay-load miss is survivable
>    (`loader.c:4030`). This list *is* the work item for Phase 1.
>
> **Cost:** one download, one hour, zero code.
>
> **Kills the phase if:** there is no arm64 payload, or `EntryPoint` is not
> `Windows.FullTrustApplication` — in which case Claude Desktop's MSIX is a real
> packaged app and unpack-and-run is off the table for it (though not for the
> capability in general).

### Phase 1 — Close the Wine holes Phase 0 finds. *~10 lines. Upstreamable.*

Two are already known without Phase 0:

- **`dlls/userenv/userenv.spec`** — add real stubs for
  `DeriveAppContainerSidFromAppContainerName`, `DeleteAppContainerProfile`,
  `GetAppContainerFolderPath`, `GetAppContainerRegistryLocation`,
  `DeriveRestrictedAppContainerSidFromAppContainerSidAndRestrictedName`,
  returning `E_NOTIMPL`. **Not `@ stub` entries** — those raise
  `EXCEPTION_WINE_STUB` and reproduce the crash. The derivation itself is
  already implemented in `dlls/ntdll/sec.c:1914` if a real answer is ever wanted.
- **`dlls/windows.applicationmodel/package.c:499`** and
  **`dlls/windows.storage.applicationdata/applicationdata.c:294`** — return
  `HRESULT_FROM_WIN32(APPMODEL_ERROR_NO_PACKAGE)` when unpackaged, which is what
  `tests/model.c:583-585` and `tests/data.c:80-81` already assert with
  `todo_wine`. This stops an app being told it is packaged when it is not.

> **Experiment.** Build the patched Wine, launch any Electron binary with
> `--no-sandbox`, and watch for `EXCEPTION_WINE_STUB` in the log. Before the
> patch it should appear at the moment the sandbox is built; after, it should
> not.
>
> **Kills the phase if:** the crash is somewhere else entirely — in which case
> Phase 0's diff was incomplete and the answer is in the log, not in this plan.

### Phase 2 — Fake registration by hand. *One session. No code.*

Prove the registry lever before building anything on it. Wine already ships the
template: `loader/wine.inf.in:690-700`.

> **Experiment.** In a container, merge a `.reg` adding
> `HKLM\SOFTWARE\Classes\Local Settings\Software\Microsoft\Windows\CurrentVersion\AppModel\PackageRepository\Packages\Test.Package_1.0.0.0_arm64__8wekyb3d8bbwe`
> with `Path` = some directory that exists. Then run a two-line program (or
> `wine winedbg`) that calls `GetPackagesByPackageFamily(L"Test.Package_8wekyb3d8bbwe", …)`
> and `GetPackagePath`. Both are real implementations
> (`dlls/kernelbase/version.c:1829`, `:1940`).
>
> **Also, in the same session:** apply the `.reg`, force a `SEED_VERSION`
> re-provision, and check the key is still there. That is the claim in §7 that
> nobody has watched happen.
>
> **Cost:** one session, no build, no shipping-code change.
>
> **Kills the phase if:** the lookup does not answer, or the seed rewrite eats
> the key. The second would mean registration has to live in the seed after all,
> which is a much worse design and should be known before Phase 3 starts.

### Phase 3 — The importer. *The actual feature. ~2-3 weeks.*

Only after 0-2. The work, in order:

- **`WcpArchive.kt:57-91`** — a `PK\x03\x04` branch and a `ZIP` member on
  `WcpCompression`, feeding a `ZipInputStream` iterator into the existing
  `WcpInstaller.extract` safety checks (`WcpInstaller.kt:394-501`), which are
  format-agnostic and must be reused rather than reimplemented.
- **A manifest parser** — `XmlPullParser` over six attributes (§2). Reject
  anything whose `ProcessorArchitecture` the container cannot run, using
  `Launchable.translationFor` (`Launchable.kt:131-136`) as the authority.
- **A destination** — `drive_c/Program Files/WindowsApps/<full name>/`, the same
  place `appx_msix_wine` picked (§6), created the way `installToolTree` creates
  `TOOLS_LAYOUT` destinations (`SessionRuntime.kt:2379-2432`). Handle `VFS\` by
  copying to the real path rather than leaving it in place.
- **`packages.reg`** — one key, one `Path` value per package, merged after the
  seed. Per §7 this is *not* seed state and `SEED_VERSION` does not move.
- **An `AppShortcut`** pointing at `Application/@Executable` resolved against
  the package root, with `workingDirectory` set from
  `uap6:LoaderSearchPathOverride` if present.
- **An execution-alias shim** — for each `uap5:ExecutionAlias`, a one-line
  `.cmd` in a directory already on `PATH`. Not a reparse point;
  `IO_REPARSE_TAG_APPEXECLINK` is defined at `include/winnt.h:2617` and
  implemented nowhere.
- **Launch-table rows** — `.msix`/`.appx`/`.msixbundle` in both
  `SessionShellHost.commandFor` (`SessionShellHost.kt:232-244`) and
  `Launchable.launchabilityOf` (`Launchable.kt:70-99`), which must agree
  (`SessionShellHost.kt:222-226`). This needs a new `Launchable` case: these
  files are *installable*, not runnable, and the existing vocabulary
  (`Launchable.kt:33-60`) has no word for that.

> **Experiment.** Import a small full-trust MSIX — GIMP is a fine test subject
> and is 100% not Electron, so it isolates the packaging question from the
> Chromium one — and check that the app appears in the tile grid and launches
> with no path typed.
>
> **Watch for:** a package that declares a framework dependency and then cannot
> find its DLL. That is the `wine.inf.in:690-700` hack generalising, and it is
> the one part of this phase that could grow.

### Phase 4 — Store acquisition. *Optional. Pure networking.*

Only after 3. Resolve a Store ID through the public catalog and delivery
endpoints, download the resulting `.msix`/`.msixbundle`, hand it to Phase 3's
importer. Entirely Android-side; touches no Wine, no prefix, no guest.

> **Experiment.** Before any UI, fetch one known free app's package URL from a
> desktop with `curl` and confirm the bytes are a plain zip with an
> `AppxManifest.xml`. If they are licence-wrapped, the phase is dead and the
> feature becomes "import a file you already have".
>
> **Do not attempt:** paid apps, `.msixvc` game packages, or the Store client.

### Phase 5 — Real per-process package identity. *Do not schedule.*

`GetCurrentPackage*` returning a real answer requires a per-process package
context that has nowhere to live in this tree — no PEB field, no wineserver
state. It is a months-long Wine project. **The only reason to start it is a
measured app that refuses to run without it**, and no such app has been
measured. If one appears, the cheapest hack is an environment variable read by
the kernelbase stubs, which is wrong but small; the right fix is upstream's
problem.

### Never

AppContainer (§2 — Wine returns `STATUS_SUCCESS` with a NULL handle and there is
no server object to build on), `IApplicationActivationManager` (an IDL
declaration and nothing else), `Windows.Management.Deployment` (29 methods, all
`E_NOTIMPL`, and nothing needs them), `Windows.ApplicationModel.Core`, and the
Microsoft Store client. Each is a wall, each is named above with the line that
proves it, and building any of them delays the useful three weeks by a year.

---

## 10. What could not be determined without a device or a build

Each names the experiment that would close it.

1. **Claude Desktop's arm64 MSIX contents.** The download endpoints refuse
   unauthenticated access (405 on `msix`, 403 on `setup`, measured 2026-08-17).
   Every statement about it here is class-inference. → Phase 0.
2. **Whether an unpacked MSIX payload runs at all in this prefix.** The whole
   plan turns on it and it has never been tried. → Phase 3's experiment, or
   cheaper, any full-trust MSIX unzipped by hand.
3. **Whether the `userenv` import really crashes Electron here.** §3.2 is
   derived from `Postman.exe`'s import table plus `dlls/ntdll/loader.c:1289-1295`
   and `:398-410`. It has not been observed. → Phase 1.
4. **Which linkage Claude Desktop uses for `DeriveAppContainerSidFromAppContainerName`.**
   Two Electron binaries measured; one static, one delay-load. Static is fatal,
   delay-load is survivable. → Phase 0.
5. **Whether a `packages.reg` survives a `SEED_VERSION` bump.** The merge
   semantics say yes (`PrefixRegistry.kt:20-34`) and nobody has watched it. →
   Phase 2.
6. **Whether Claude Desktop declares framework dependencies.** Measured across
   this host's 35 full-trust packages, 25 do. → Phase 0.
7. **Whether Store delivery endpoints return unlicensed bytes for a free app
   today.** The mechanism is well attested by third-party tooling; it has not
   been exercised from here. → Phase 4's experiment.
8. **What `StateRepository-*.srd` contains and whether anything needs it.** The
   directory was not readable without elevation on this host. Wine has no
   equivalent and no app is known to require one, but "no app is known" is not
   "no app does".
9. **TODO #17's root cause.** Still open, still unexplained, and this plan
   deliberately routes around it rather than resolving it. §8 gives the
   discriminator; running it is a side effect of Phase 3, not a prerequisite.
10. **Everything in `docs/DEVTOOLS.md` §5 about Chromium under Wine.** Unpacking
    a package does not make Electron render a frame. If Chrome cannot paint,
    this whole document delivers a working installer for an app that does not
    start — which is still a real capability for non-Electron MSIX apps, but the
    motivating target would be gone.
