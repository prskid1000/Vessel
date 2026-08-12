# Licensing

This project is intended to be published publicly. That makes licensing a
correctness problem, not paperwork. This document records what applies, what is
already true, and what must still be resolved **before** the repository goes
public — the last of those is [the checklist at the end](#before-this-repository-goes-public),
and every line of it is either a resolved item with the evidence named or an open
item with the specific thing that would close it.

**Most of this is checked by a test.** `app/src/test/java/app/vessel/licensing/LicensingTest.kt`
asserts the claims below that a machine can assert: that R8 keeps the vendored
LGPL packages, that the release build actually applies those rules, that the
licence text shipped in the APK matches the one in the repository root, that
resource shrinking cannot delete it, that the font in `res/font` really is Inter,
and that every locally modified vendored file is recorded. `build/verify_vendored.py`
covers the one claim that needs the network. Where this document says something
is true, that is where it is kept true.

## Vessel's licence: LGPL-2.1-or-later

Decided 2026-08-07. `SPDX-License-Identifier: LGPL-2.1-or-later`; the notice is
in `LICENSE`, the full text in `LICENSE-LGPL-2.1`, and a copy of that text ships
inside the APK at `res/raw/license_lgpl_2_1.txt`.

It was a forced move rather than a preference. The Winlator X server is vendored
into the APK (below), so the app itself contains LGPL-2.1 code and its own terms
have to be LGPL-compatible.

**"or later" was chosen partly on a wrong premise, and the choice still stands.**
This paragraph used to say that `libadrenotools` is LGPL-3.0 and that "or later"
was what permitted combining with it. **libadrenotools is BSD-2-Clause** —
checked at the vendored commit on 2026-08-08, both the top-level `LICENSE` and
`lib/linkernsbypass/LICENSE`, and every source file carries
`SPDX-License-Identifier: BSD-2-Clause`. It is permissive and imposes no
constraint on Vessel's licence at all. The claim appears to have been copied from
somewhere without being read.

The decision does not change: LGPL-2.1-**or-later** is still right, because the
vendored Winlator X server forces LGPL compatibility and "or later" keeps the
door open to future GPL-3-family components. But it was not forced by this one,
and a load-bearing reason that turns out to be false is worth recording rather
than quietly deleting.

**The same false claim survived in `LICENSE` for a day after it was retracted
here**, which is the argument for the test: two documents saying the same thing
means one of them can be wrong on its own. `LicensingTest` now asserts that
`LICENSE` contains no LGPL-3 claim at all.

GPL-3.0 was the other compatible option and was rejected as stricter than the
obligation requires: it would impose full copyleft on the whole application,
where the LGPL only asks that the LGPL parts stay free and relinkable.

### Why a fork's MIT claim does not help

Resolved 2026-08-07. The Winlator lineage's declared licences:

| Repository | Declared |
|---|---|
| `brunodev85/winlator` — the original | **LGPL-2.1** |
| `coffincolors/winlator` | MIT |
| `Pipetto-crypto/winlator` | MIT |
| `StevenMXZ/Winlator-Ludashi` | MIT |

**A fork cannot relicense inherited LGPL code as MIT.** Those MIT declarations
can only cover each fork's own additions; anything descended from the original
remains LGPL-2.1. Code taken from any of them is treated as LGPL-2.1.

Still required per file when code is taken: record the upstream repository and
commit, keep its copyright headers, and note it below. Upstream's own Java files
carry no per-file copyright header — checked across all 155 vendored files on
2026-08-08 — so there is none to preserve; the repository, commit and author are
recorded once in `app/src/main/java/com/winlator/README.md` instead, which is
where a reader will look for them.

## Vendored into the app

Unlike the components below, this code ships **inside** the APK.

| What | Upstream | Commit | Licence |
|---|---|---|---|
| X server, GL compositor, socket connector, sysvshm server, `libwinlator` JNI | [`brunodev85/winlator-app`](https://github.com/brunodev85/winlator-app) | `ca3d735a60d653a787daf16d14fafef28d9c2c23` | LGPL-2.1 |
| `libadrenotools` + its `android_dlopen_ext` hooks | [`bylaws/libadrenotools`](https://github.com/bylaws/libadrenotools) | `8fae8ce` | **BSD-2-Clause** |
| `linkernsbypass` (submodule of the above) | [`bylaws/liblinkernsbypass`](https://github.com/bylaws/liblinkernsbypass) | `aa39758` | **BSD-2-Clause** |
| Inter, as a variable font | [`rsms/inter`](https://github.com/rsms/inter) v4.1 | `InterVariable.ttf`, version 4.001 | **OFL-1.1** |
| JetBrains Mono, as a variable font | [`JetBrains/JetBrainsMono`](https://github.com/JetBrains/JetBrainsMono) v2.304 | `JetBrainsMono[wght].ttf`, version 2.304 | **OFL-1.1** |
| Phosphor Icons, transcribed as path data | [`phosphor-icons/core`](https://github.com/phosphor-icons/core) 2.1.1 | regular weight only | MIT |
| Snapdragon Game Super Resolution 1, as one fragment shader | [`SnapdragonStudios/snapdragon-gsr`](https://github.com/SnapdragonStudios/snapdragon-gsr) | `sgsr/v1/include/glsl/sgsr1_shader_mobile_edge_direction.frag` | **BSD-3-Clause** |

libadrenotools is at `app/src/main/cpp/adrenotools/`, with upstream's layout,
file names and SPDX headers intact and no modification to any upstream source
file; what was taken and what was left is in that directory's `README.md`.
BSD-2-Clause asks only that the copyright notice and the two-clause text travel
with the binary, which `LICENSE` and `lib/linkernsbypass/LICENSE` in that
directory satisfy. It imposes nothing on the rest of the APK.

SGSR is one GLSL fragment shader, carried as a string inside
`com/winlator/renderer/material/SGSRMaterial.java` rather than as a file, because
it is compiled at runtime at a `#version` the driver chooses. **It is
BSD-3-Clause and not Apache-2.0**, which is what it was believed to be when the
work was requested — the repository's `LICENSE` carries
`SPDX-License-Identifier: BSD-3-Clause`, Copyright (c) 2023 Qualcomm Innovation
Center, Inc. The difference is not academic: three-clause adds the no-endorsement
term, so Vessel may not use Qualcomm's name or its contributors' names to promote
the app, and the copyright notice must be retained in redistributed *source* as
well as in the binary. Both are done — the notice is reproduced verbatim above
the shader body, the full text ships as `res/raw/license_bsd_sgsr.txt`, and the
Licences screen lists it. `LicensingTest` asserts the SPDX line, the third
clause's presence and the in-source notice, so a truncated or substituted licence
file fails the build.

Nothing in the algorithm or its constants was changed. The four adaptations the
shader needed to compile at all are enumerated in that file and are all
mechanical: the `#version` line, a constant `textureGather` component, dropped
`layout` qualifiers, and the removal of a Vulkan-only uniform-block branch.

The Winlator code is at `app/src/main/java/com/winlator/` and `app/src/main/cpp/winlator/`,
under upstream's package names. What was taken, what was left and every local
modification is recorded in `app/src/main/java/com/winlator/README.md`. Local
changes are marked in-source with `// VESSEL:`.

### The section 6 obligation, in three parts

This is the central obligation and it is worth setting out exactly, because
"keep the source public" is only one third of it. LGPL-2.1 section 6, second
paragraph, says of a work that contains the Library:

> You must give prominent notice with each copy of the work that the Library is
> used in it and that the Library and its use are covered by this License. You
> must supply a copy of this License. […] Also, you must do one of these things:

**1. A copy of this License, supplied with the work — satisfied.** The APK now
ships `LICENSE-LGPL-2.1` verbatim as `res/raw/license_lgpl_2_1.txt`.
`LicensingTest` compares the two byte for byte, and `res/raw/keep.xml` stops
`isShrinkResources` deleting it from the release build — which it otherwise
would, silently, because nothing in the code references it.

**2. Prominent notice, with each copy — satisfied.** A line at the foot of the
container list, on every launch and in both the empty-device state and the full
one, reads *"Contains the Winlator X server, under the GNU LGPL 2.1"* and opens
`LicencesScreen`. That screen gives the notice in full — the Library, its
authors, the licence covering it, and that Vessel itself is LGPL-2.1-or-later
with its source at the named URL — and each of the five entries opens its own
licence text out of `res/raw`. The bottom bar rather than a row in the list
because a row would vanish on a device with no containers, which is the copy
most likely to be somebody's first.

`LicensingTest` asserts the words are in the screen's source, that home reaches
it, and that every `R.raw` the list names is a real non-empty file — the failure
that would otherwise ship is a row that opens onto "could not be read".

Adding this closed the reason **the APK should not be distributed**; a release
had already been published before it existed, which is recorded here rather than
quietly fixed.

**3. One of 6(a)–(e) — 6(a), satisfied for the app; still open for the
components release.** The components release publishes built binaries of Wine,
FEX, DXVK, vkd3d and Mesa, every one from a patched upstream tree, and for a
while its body said nothing about where any of that source is. A repository that
happens to be public is not an offer; a page that hands out binaries has to say
where their source lives. `build/source_offer.py` writes that body from the
packages' own provenance and `_component.yml` publishes it beside
`contents.json` — but two of the six packages predate the field that names their
upstream repository, so the renderer refuses rather than publishing a row that
says `unknown`. Blocker 9 in the checklist has the detail and what closes it.


6(a) wants the Library's complete source "including whatever changes were used in
the work", plus the work that uses it "as object code and/or source code, so that
the user can modify the Library and then relink". Vessel's whole source, the
Library included, is one public Gradle project: modifying `com/winlator/` and
running `./gradlew :app:assembleSideloadRelease` produces an APK containing the
modified Library. That is stronger than relinking. Three things have to stay true
for it, and each is now checked rather than assumed:

- *The vendored source must be complete and its changes recorded.*
  `build/verify_vendored.py` diffs every vendored file against upstream at the
  pinned commit. Run 2026-08-08: **13 modified, 143 byte-identical, 0 with no
  upstream counterpart**, all 13 carrying a `// VESSEL:` marker and all 13 listed
  in that tree's README. The README's list had been missing
  `cpp/winlator/CMakeLists.txt`, and its `project(Winlator C)` change was
  unmarked; both are fixed. `LicensingTest` keeps the marker set and the listed
  set equal from now on, offline.
- *R8 must not rename or strip `com.winlator`.* `app/proguard-rules.pro` keeps
  it, for this reason and for a JNI one. `LicensingTest` asserts both the keep
  rules and that the release build type actually applies that file — a keep rule
  in a file nothing loads is not a keep rule, and shrinking runs on release only,
  so nothing else in the project would ever notice.
- *A fresh clone must build.* `app/build.gradle.kts` produces an unsigned release
  when no keystore is present, deliberately, so this is testable without holding
  the key. The `sideload` flavour additionally needs `dist/*.wcp`, which are not
  in the repository; the `play` flavour builds from a clean clone alone.

## Bundled fonts

`docs/DESIGN.md` specifies Inter and JetBrains Mono as bundled variable fonts.
Both are under the **SIL Open Font License 1.1**, which asks for three things
when the fonts are distributed inside a larger work:

- the copyright notice and the licence must travel with them — they do, verbatim
  as upstream shipped them, at `res/raw/license_ofl_inter.txt` and
  `res/raw/license_ofl_jetbrains_mono.txt`, both kept by `res/raw/keep.xml`;
- the fonts must not be sold on their own — they are not sold at all;
- a *modified* version may not use the reserved font name. Neither file is
  modified: both are the release binaries, unaltered.

| File | Source | SHA-256 |
|---|---|---|
| `res/font/inter_variable.ttf` | `InterVariable.ttf` from [Inter 4.1](https://github.com/rsms/inter/releases/tag/v4.1) | `4989b125924991b90d05b2d16e0e388c48f7d5bb8b30539bbf9c755278d0ccaf` |
| `res/font/jetbrains_mono_variable.ttf` | `fonts/variable/JetBrainsMono[wght].ttf` from [JetBrains Mono 2.304](https://github.com/JetBrains/JetBrainsMono/releases/tag/v2.304) | `662a196d58f1183bf2d77428b6d5283fe3f45161ab021bea4036bc98e5cac016` |

Renamed on the way in because Android resource names may not contain `[`, `]` or
a capital letter. The italic cuts of both were left out: nothing in the type
scale is italic, and they are another 1.2 MB.

`LicensingTest` reads the `name` and `fvar` tables out of each file and asserts
the family, the copyright string, and that the weight axis actually spans the two
weights the design uses. That is deliberately a stronger check than a digest: a
digest proves the file has not changed, and the failure worth catching is a
*different* font committed under the name Inter.

## Component licenses

Vessel does not statically link these into the app; each is built separately
and distributed as a `.wcp` package. Obligations still apply to distribution.

| Component | License | Practical obligation |
|---|---|---|
| Wine | LGPL-2.1-or-later | Distribute source or a written offer; keep modifications' source available. Our patches live in `patches/wine/` in this repo, which satisfies this if the repo is public. |
| Proton | LGPL-2.1-or-later (Wine-derived) | As Wine. |
| FEX-Emu | MIT | Preserve copyright and license text. |
| Mesa / Turnip | MIT (plus other permissive licenses in-tree) | Preserve notices. |
| DXVK | zlib | Preserve notices. |
| vkd3d-proton | LGPL-2.1-or-later | As Wine. |

`libadrenotools` used to be listed here as an LGPL-3.0 component "to verify
before linking". It has been verified, it is BSD-2-Clause, and it is no longer a
component — it ships inside the APK and is recorded above.

Two consequences worth being explicit about:

- **Because Wine and vkd3d-proton are LGPL, our patches must stay public.**
  Keeping every patch in `patches/` and every version in `native/pins.env` is
  not just good process, it is how the obligation is met. That now includes
  `patches/wine/0006-win32u-load-vulkan-through-libadrenotools-on-android.patch`,
  which is a modification to an LGPL work.

- **"Our patches are all in `patches/`" is a claim about a working tree, and it
  was briefly false.** `native/wine` had a hand-deletion of `programs/winefile/`
  in it that no patch described, so a `.wcp` built from that tree could not have
  been reproduced from the repository — and the corresponding source for it would
  not have been what the repository says it is. `assert_pristine()` in
  `build/common.sh` now refuses to apply patches to a tree that is not clean, so
  that class of drift fails the build instead of shipping.

The provenance the packages themselves carry is the other half of this:
`write_provenance()` embeds `sourceRef` and `sourceSha` into every `.wcp`'s
`profile.json`, `build/gen_registry.py` copies them into the registry, and
`ComponentPackage` puts them on screen. So any installed package can already name
the upstream commit it was built from — except that `sourceRepo` was added later
than the other two, and the DXVK and vkd3d packages predate it and say `unknown`.
`build/source_offer.py` turns the release page's half of this into a generated
offer, and refuses to write one for a package whose provenance cannot name its
repository; see blocker 9 in the checklist.

## Trademarks

"Windows", "DirectX", "Snapdragon", "Adreno", "Motorola", and "Qualcomm" are
trademarks of their respective owners. Vessel is not affiliated with, endorsed
by, or sponsored by any of them.

The rule is that these marks must not appear in the product name, the icon, or a
store listing. Checked 2026-08-08: the product name is `Vessel`
(`PRODUCT_NAME` in `gradle.properties`, which is the only place it is set) and
the launcher icon carries no mark. Every use inside the interface is nominative —
"a Windows program", "your Adreno 829" — which is the permitted kind: naming the
thing the software is compatible with. `LicensingTest` asserts the product name.

## What Vessel does not do

Vessel does not distribute Microsoft Windows, any Microsoft system DLL, or any
game or application. Users supply their own software. No component of this
project circumvents copy protection.

## Before this repository goes public

**This gate has already been passed, which changes what the list is for.** This
section still said "the remote is set and nothing has been pushed" on
2026-08-10; `docs/TODO.md` §6 records that everything is pushed and that v0.2.0
is published with a signed APK carrying all six components. So the two items
below are not a gate any more, they are a **live gap in what has shipped** —
recorded here in those words rather than reworded, for the same reason §6's
"a release had already been published before this existed" is recorded above.

Eight of the ten are closed with the evidence named. Neither of the two open
ones affects the APK: both are about the *components release* page and its
index, and nothing about them is fixed by editing a document — 9 needs two
packages rebuilt and a CI run, 10 is a judgement about a moving target.

| # | Blocker | Status |
|---|---|---|
| 1 | The vendored LGPL source must be complete, with every local change recorded | **Closed.** Verified against upstream file by file; 13 modified, all marked and all listed. `build/verify_vendored.py` re-checks it; `LicensingTest` keeps the record honest offline. |
| 2 | R8 must not rename or strip `com.winlator` | **Closed.** Keep rules present, release build proven to apply them, both asserted. |
| 3 | The APK must supply a copy of the LGPL | **Closed.** `res/raw/license_lgpl_2_1.txt`, kept from the shrinker, asserted identical to the root copy. |
| 4 | `LICENSE` must not repeat the retracted libadrenotools claim | **Closed.** Rewritten, and asserted. |
| 5 | Bundled fonts must be recorded with their licence | **Closed.** Inter 4.001 and JetBrains Mono 2.304, OFL-1.1, licences shipped in the APK, identity asserted from the font tables. |
| 6 | Trademarks must not be in the product name or icon | **Closed.** Checked, and the name is asserted. |
| 7 | Our Wine/vkd3d patches must be the only difference from upstream | **Closed.** `assert_pristine()` fails the build on a drifted checkout; the `winefile` drift that prompted it is gone. |
| 8 | Prominent notice, in the interface, that the app contains LGPL code | **Closed.** A permanent line at the foot of home naming the X server and its licence, opening `LicencesScreen`; five entries, each with its full text out of `res/raw`. libadrenotools' BSD-2-Clause notice is in the APK now too, which it had not been. Asserted three ways in `LicensingTest`. |
| 9 | A source offer on the component release page | **Closed 2026-08-10.** DXVK and vkd3d rebuilt so their provenance names a source repository; the renderer covers all six published components with no `unknown`. Not yet published — the next component build carries it. See below. |
| 10 | A `README` that is true on the day | **Closed 2026-08-10.** The graphics narrative was the last stale part and it is rewritten against measurements rather than removed: the KGSL dma-buf sentence that called itself "the single thing between here and a triangle" is gone with a note saying it outlived its subject, DXVK's row now says it runs a game, presentation carries the measured 0.546 ms DRI3 figure, the Wine patch count is 15, and `ipconfig` is recorded as verified. What replaces the false blocker is the true one: an 8-12 fps cutscene that neither compute, GPU, present nor panel refresh explains. |

### 9, in detail: running the renderer is what found the hole

The item read "closed in code, unproven until a build runs". It was run on
2026-08-10 — over `dist/`, which holds the same packages the release does — and
the offer it would have published was wrong in three ways. None of them was
visible from reading the script.

- **Two of the six shipped components had no upstream repository in the offer at
  all.** `sourceRepo` entered provenance on 2026-08-09; `dxvk-2.7.1-canoe` and
  `vkd3d-3.0.1-canoe` were packaged on 2026-08-07 and carry none, so
  `provenance.get("sourceRepo", "unknown")` rendered the literal word `unknown`
  in the Upstream column. For vkd3d-proton, which is LGPL-2.1-or-later, that is
  a row that discharges nothing: the ref and the commit are useless without
  saying which repository they are commits *of*, which is the exact sentence
  this blocker was written around.
- **The `patches/` pointer was broken for four of the six.** The renderer wrote
  ``patches/<component>/`` on every row unconditionally. `patches/dxvk/`,
  `patches/vkd3d/`, `patches/turnip/` and `patches/zink/` do not exist —
  the first two because those components carry no patches, the last two because
  the patch directory is keyed on the *source tree* and both are built from one
  Mesa checkout, so theirs are in `patches/mesa/`. "The complete source
  including whatever changes were used in the work" is the obligation, and the
  changes were the half being pointed into empty space.
- **It covered packages that are not published**, including the superseded Wine
  10.13 and Turnip HAL builds and the repackaged Git that no workflow uploads.

Two of the three are fixed in the renderer. It now enumerates every
`patches/<tree>/` directory in the repository with its patch files listed in
full, rather than guessing a directory per row — complete, and incapable of
naming one that is not there — and it applies the same supersession and
`--exclude` filters as `gen_registry.py`, so the offer and the index describe
one set of packages.

The first cannot be fixed by editing anything. A package's provenance is
written when it is built, so **DXVK and vkd3d must be rebuilt** before an offer
covering them can name their source. Until they are, `source_offer.py` *refuses
to write the file* — the same shape as `gen_registry.py` refusing to publish a
registry without hashes. Reading the repository out of `native/pins.env`
instead was considered and rejected: that is a claim about today's pin dressed
up as a fact about the artefact, and a source offer that looks right and is not
is the failure mode this whole item exists to prevent.

**Closed 2026-08-10.** DXVK and vkd3d were rebuilt, and their provenance now
carries `sourceRepo` — `https://github.com/doitsujin/dxvk.git` at `v2.7.1` and
`https://github.com/HansKristian-Work/vkd3d-proton.git` at `v3.0.1`. The
renderer no longer refuses: it writes an offer covering all six published
components, every one naming a real upstream repository, ref and commit, with
the superseded Wine 10.13 and Turnip HAL builds and the unpublished Git package
filtered out. The *Modifications to upstream* section enumerates
`patches/mesa/` (6) and `patches/wine/` (15) from the filesystem, so every path
in it resolves by construction — the counts here track the directories rather
than being asserted, which is why adding `patches/wine/0016` needed no code
change to stay covered.

*What has still not happened, and it is the difference between the obligation
being dischargeable and discharged:* a component build has not run on `main`
since. The offer has been rendered locally over `dist/`, which holds the same
packages the release does; it has not been published. Nothing further is
required of the repository — the next component build carries it.

Two things that are *not* blockers and were checked so they can stop being
raised:

- **`Redesigning interfaces/`** is a design-canvas export — the Nocturne design
  system from the sibling project, reference screenshots, and a generated
  `support.js` from the author's own `dc-runtime`. Nothing in it is third-party
  licensed material. Whether to commit it is a taste question, in `docs/TODO.md`.
- **The signing key.** `release.jks` and `keystore.properties` are both in
  `.gitignore` and neither has ever been committed.
