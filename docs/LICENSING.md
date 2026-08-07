# Licensing

This project is intended to be published publicly. That makes licensing a
correctness problem, not paperwork. This document records what applies and what
must be resolved **before** the repository goes public.

## Vessel's licence: LGPL-2.1-or-later

Decided 2026-08-07. `SPDX-License-Identifier: LGPL-2.1-or-later`; the notice is
in `LICENSE`, the full text in `LICENSE-LGPL-2.1`.

It was a forced move rather than a preference. The Winlator X server is vendored
into the APK (below), so the app itself contains LGPL-2.1 code and its own terms
have to be LGPL-compatible.

**"or later" is load-bearing.** `libadrenotools` is LGPL-3.0, and LGPL-2.1
*only* cannot be combined with it — the "or any later version" clause is what
permits that. Picking bare LGPL-2.1 would have quietly excluded a component this
project expects to link.

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
commit, keep its copyright headers, and note it below.

## Vendored into the app

Unlike the components below, this code ships **inside** the APK.

| What | Upstream | Commit | Licence |
|---|---|---|---|
| X server, GL compositor, socket connector, sysvshm server, `libwinlator` JNI | [`brunodev85/winlator-app`](https://github.com/brunodev85/winlator-app) | `ca3d735a60d653a787daf16d14fafef28d9c2c23` | LGPL-2.1 |

Located at `app/src/main/java/com/winlator/` and `app/src/main/cpp/winlator/`,
under upstream's package names. The full licence text is at
`LICENSE-LGPL-2.1` in the repo root; what was taken, what was left and every
local modification is recorded in `app/src/main/java/com/winlator/README.md`.
Local changes are marked in-source with `// VESSEL:`.

The LGPL-2.1 obligation this creates is section 6: a user must be able to
relink the app against a modified version of the LGPL part. Keeping the
vendored source in this public repository, unobfuscated and buildable, is how
that is met — which in practice means **the `com.winlator` packages must not be
renamed or stripped by R8**. If release minification ever starts touching them,
that is a licensing regression, not a build one.

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
| libadrenotools | LGPL-3.0 (verify at the commit used) | Copyleft — verify before linking into the app. |

Two consequences worth being explicit about:

- **Because Wine and vkd3d-proton are LGPL, our patches must stay public.**
  Keeping every patch in `patches/` and every version in `native/pins.env` is
  not just good process, it is how the obligation is met.
- **`libadrenotools` is LGPL-3.0 and would be linked into the APK**, unlike the
  components above. Verify its exact terms at the commit used and confirm the
  app's chosen license is compatible before shipping a binary.

## Trademarks

"Windows", "DirectX", "Snapdragon", "Adreno", "Motorola", and "Qualcomm" are
trademarks of their respective owners. Vessel is not affiliated with, endorsed
by, or sponsored by any of them. Avoid using these marks in the product name,
icon, or store listing.

## What Vessel does not do

Vessel does not distribute Microsoft Windows, any Microsoft system DLL, or any
game or application. Users supply their own software. No component of this
project circumvents copy protection.
