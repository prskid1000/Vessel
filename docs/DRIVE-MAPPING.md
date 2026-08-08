# Drive mapping

**Not built. This is the design.** Written because the gap was found by
inspection on 2026-08-08 and the shape of the answer is clear enough to record
before anyone starts.

## What a container has today

```
prefix/dosdevices/
  c: -> ../drive_c
  z: -> /
```

Both are Wine's own defaults from `wineboot`. Nothing points at Android storage,
so a user with `Internal shared storage/Games/Metro Redux` on the phone cannot
reach it from the guest at all — not by browsing, not by launching. Getting a
file in means Vessel's import, which copies it into `drive_c`, and a 40 GB game
is not something to copy.

## What it should be

A **drive is a symlink in `dosdevices`**, and that is the whole mechanism. Wine
resolves `D:\Games\x.exe` through `dosdevices/d:` and reads whatever is on the
other side with the app's own uid. So mapping Android storage is one symlink and
one permission — there is no filesystem driver to write.

```
  d: -> /storage/emulated/0            <- the user-visible part of the phone
  e: -> /storage/XXXX-XXXX             <- an SD card or a USB drive, when present
  f: -> /storage/emulated/0/Games      <- a folder the user pinned
```

### The permission is the real decision

Wine runs as the app, so it can read exactly what the app can. Reaching
`/storage/emulated/0` needs `MANAGE_EXTERNAL_STORAGE`, which is a Play-policy
sensitive permission and a system settings toggle rather than a dialog. The
`sideload` flavour can ask for it; the `play` flavour probably cannot ship with
it. That split already exists in the build and is where this belongs:

- **sideload** — request `MANAGE_EXTERNAL_STORAGE`, offer the whole of shared
  storage as `D:`.
- **play** — SAF only. A picked folder gives a `content://` tree, which is *not*
  a path and cannot be symlinked. It would have to be copied, or reached through
  a FUSE mount the app cannot create. Honest answer for that flavour: pinned
  folders are unavailable, and the UI should say so rather than offer a picker
  that half works.

## The interface

**The C: browser becomes a drive browser, opening on *This PC*.** That is the
Windows model and it is the right one here: the question "where is my file" is
answered by a list of drives, not by being dropped inside one of them.

```
┌────────────────────────────────────────────┐
│  This PC                                   │
├────────────────────────────────────────────┤
│  [C:]  [D:]  [Z:]  [+]                     │   <- permanent tabs, one per drive
├────────────────────────────────────────────┤
│  ▸ Windows            Folder               │
│  ▸ Program Files      Folder               │
│  ▸ users              Folder               │
└────────────────────────────────────────────┘
```

- **One tab per mapped drive**, always present, in letter order. A tab is a
  drive, not a history entry — switching does not lose where you were in the
  other one.
- **`+` adds a mapping.** Opens Android's folder picker, takes the next free
  letter, writes the symlink, and the tab appears. Removing one is a long press
  on the tab; it unlinks and nothing else, because a mapping is a pointer and
  deleting it must never delete what it pointed at. That needs saying in the
  confirmation.
- **Import and export stay on `C:` and `Z:` only.** They exist because those
  live inside the prefix and Android has no other way in. A mapped Android drive
  is already the phone's own storage — copying a file *into* it from the phone
  is a copy from a place to itself, and offering the button would imply
  otherwise.

## Order of work

1. `dosdevices` reader — list what is mapped, which is also what the tabs are
   built from. Pure, testable, no UI.
2. Symlink writer, with the letter allocator. `A:`/`B:` skipped, `C:` and `Z:`
   reserved.
3. `MANAGE_EXTERNAL_STORAGE` request in the sideload flavour, with the settings
   deep link, and a stated refusal in `play`.
4. The tab row in `FilesScreen`, opening on This PC.
5. The picker and the `+`.

Steps 1 and 2 are worth doing first on their own: with `D:` mapped by hand, the
launcher can already add a program from Android storage, and that is the whole
point of the feature. The tabs are how it stops being a hidden trick.
