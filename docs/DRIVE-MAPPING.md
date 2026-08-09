# Drive mapping

**Built.** Designed 2026-08-08, shipped 2026-08-09; the five steps at the bottom
are all done. What follows is the design as written, plus a closing section on
the two things it got wrong.

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

## What the design got wrong

### A symlink is enough for Wine to *resolve* a drive, not to *show* one

"A drive is a symlink in `dosdevices`, and that is the whole mechanism" is true
of path resolution and false of the shell. Wine also reads
`HKLM\Software\Wine\Drives`, whose values are `"d:"="hd"`, and without an entry
`GetDriveType` guesses from the target — a guess of `DRIVE_UNKNOWN` or
`DRIVE_REMOVABLE` is what makes Wine's own File Explorer list a perfectly good
drive as empty. This is the key `winecfg` writes for the same reason. Vessel's
browser never noticed because it reads the symlink directly.

**The entry is derived, not written by the mapper.** `PrefixRegistry.driveTypes`
reads `dosdevices` and declares whatever is there, so a drive gains its type by
existing rather than by every code path that can create one remembering to say
so. Two consequences worth knowing:

- The seed text now depends on the container, so the "already applied" marker
  had to stop being an integer version and become a hash of the rendered text
  (`PrefixRegistry.stampFor`). Two prefixes on the same seed version legitimately
  want different registry text, and an integer cannot say that.
- Shared storage is mapped **before** the seed is rendered in
  `ContainerProvisioner`, not after. Rendering first would describe the container
  as it was a moment earlier and leave `D:` undeclared for a whole launch.

A drive mapped during a running session still does not appear until relaunch.
That is Wine's, not ours — the drive table is built when a process starts.

### `/mnt/media_rw/<uuid>` is the same volume and unreadable

For an SD card or a USB drive, a SAF tree document id names a volume uuid, and
there are two paths to it. `/mnt/media_rw/<uuid>` is owned by the `media_rw`
group and an app cannot read it *even holding* `MANAGE_EXTERNAL_STORAGE`;
`/storage/<uuid>` is the one apps get. Resolve to the wrong one and the drive
maps, appears in the tab row, and is empty everywhere —
[Winlator-Ludashi#534](https://github.com/StevenMXZ/Winlator-Ludashi/pull/534)
is the same bug found independently. Vessel resolves `/storage` only; where that
PR falls back to `/mnt/media_rw` we return null and refuse the mapping, because
a fallback to an unreadable path *is* the empty drive rather than a fix for it.
