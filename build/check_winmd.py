#!/usr/bin/env python3
"""Check that a generated .winmd's SizeOfImage covers its sections.

patches/wine/0038 changes tools/widl so SizeOfImage describes the VIRTUAL
layout. Before it, widl derived SizeOfImage from a file-derived size rounded to
0x2000 while the single section sits at VirtualAddress 0x1000 with a VirtualSize
that rounds to 0x1000 -- so the image ended at 0x3000 and the header claimed
0x2000. ntdll's map_image_into_view does `goto done` when a section runs past
SizeOfImage rather than skipping just that section, so nothing beyond the
headers was ever mapped.

Run against the packaged file, not the build tree:

    python3 build/check_winmd.py share/wine/winmd/windows.networking.winmd

Exits non-zero, loudly, if SizeOfImage does not cover every section. A widl that
was not rebuilt leaves the old value in place and the build still exits 0, which
is the whole reason this is a separate check.

A file, not `python3 -c` and not a heredoc: Git Bash eats backslashes in both.
docs/DEBUGGING.md, "Tools that work here".
"""

import struct
import sys


def round_up(value, alignment):
    return (value + alignment - 1) & ~(alignment - 1)


def check(path):
    with open(path, "rb") as fh:
        data = fh.read()

    if data[:2] != b"MZ":
        raise SystemExit("%s: not a PE image (no MZ)" % path)

    e_lfanew = struct.unpack_from("<I", data, 0x3C)[0]
    if data[e_lfanew:e_lfanew + 4] != b"PE\0\0":
        raise SystemExit("%s: no PE signature at e_lfanew %#x" % (path, e_lfanew))

    coff = e_lfanew + 4
    num_sections = struct.unpack_from("<H", data, coff + 2)[0]
    opt_size = struct.unpack_from("<H", data, coff + 16)[0]
    opt = coff + 20

    magic = struct.unpack_from("<H", data, opt)[0]
    kind = {0x10B: "PE32", 0x20B: "PE32+"}.get(magic, "%#x" % magic)

    # SectionAlignment at +32, SizeOfImage at +56 in both PE32 and PE32+.
    section_alignment = struct.unpack_from("<I", data, opt + 32)[0]
    size_of_image = struct.unpack_from("<I", data, opt + 56)[0]

    sections = opt + opt_size
    print("%s" % path)
    print("  %s, SectionAlignment %#x, SizeOfImage %#x, %d section(s)"
          % (kind, section_alignment, size_of_image, num_sections))

    ok = True
    for i in range(num_sections):
        base = sections + i * 40
        name = data[base:base + 8].rstrip(b"\0").decode("ascii", "replace")
        virtual_size = struct.unpack_from("<I", data, base + 8)[0]
        virtual_addr = struct.unpack_from("<I", data, base + 12)[0]
        end = virtual_addr + round_up(virtual_size, section_alignment or 0x1000)
        verdict = "ok" if end <= size_of_image else "PAST THE END OF THE IMAGE"
        if end > size_of_image:
            ok = False
        print("    %-8s VA %#07x  VSize %#07x  -> ends %#07x  %s"
              % (name, virtual_addr, virtual_size, end, verdict))

    if not ok:
        print("  FAILURE: SizeOfImage does not cover every section. If this is a"
              " freshly built package, widl was not rebuilt -- check that"
              " build/wine.sh did NOT print 'reusing native tools'.")
        return 1

    print("  ok: SizeOfImage covers every section")
    return 0


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit("usage: check_winmd.py <file.winmd> [...]")
    sys.exit(max(check(p) for p in sys.argv[1:]))
