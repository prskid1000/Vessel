# Licensing

This project is intended to be published publicly. That makes licensing a
correctness problem, not paperwork. This document records what applies and what
must be resolved **before** the repository goes public.

## Open questions — resolve before publishing

1. **Vessel's own license is not yet chosen.** The choice is constrained by
   what we vendor (below), so it is decided after item 2, not before.

2. **The Winlator-lineage code we intend to vendor must have its license
   verified.** Vessel plans to reuse container setup, the built-in X server,
   and driver loading from the Winlator family rather than rewriting them.
   Licensing across that lineage has not been consistent over time: some
   repositories have carried no `LICENSE` file at all for parts of their
   history, and forks have not always preserved or clarified terms.

   Required before any of that code is committed here:
   - Identify the exact upstream repository and commit each file comes from.
   - Confirm that repository's license *at that commit*.
   - If a file has no clear license, treat it as **all rights reserved** and
     either obtain permission from the author or reimplement the functionality.
     "It was on GitHub" is not a license.

   Until this is done, no vendored Winlator-lineage source should be committed.
   Nothing in the repository today contains any.

## Component licenses

Vessel does not statically link these into the app; each is built separately
and distributed as a `.wcp` package. Obligations still apply to distribution.

| Component | License | Practical obligation |
|---|---|---|
| Wine | LGPL-2.1-or-later | Distribute source or a written offer; keep modifications' source available. Our patches live in `patches/wine/` in this repo, which satisfies this if the repo is public. |
| Proton | LGPL-2.1-or-later (Wine-derived) | As Wine. |
| FEX-Emu | MIT | Preserve copyright and license text. |
| Box64 | MIT | Preserve copyright and license text. |
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
