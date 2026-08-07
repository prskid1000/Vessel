#!/usr/bin/env python3
"""Package a built component as a .wcp content package.

A .wcp is a compressed tar carrying a ``profile.json`` manifest. The format is
the one the Winlator ecosystem already uses, so packages Vessel produces remain
installable in other Winlator-family apps and vice versa.

Vessel's own build provenance is nested under a ``vessel`` key. Other apps
ignore unknown keys, so this stays compatible while letting our Components
screen answer "what exactly is this build, and from which source commit?".
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
from pathlib import Path

# Types the Winlator-family ContentProfile understands. Using a value outside
# this set produces a package the app will refuse to install, so it is checked
# here rather than discovered on the phone.
KNOWN_TYPES = {
    "Wine", "Proton", "Box64", "WOWBox64", "FEXCore",
    "DXVK", "VKD3D", "D8VK", "Turnip", "Tools",
    # Vessel addition: a desktop-OpenGL implementation installed as a DLL
    # override, i.e. a native opengl32.dll (Mesa/Zink). It is not a DXVK-shaped
    # thing — it replaces WGL rather than Direct3D — and labelling it "DXVK" to
    # reuse an existing type would make the Components screen lie about what is
    # installed. Winlator-family apps ignore package types they do not know, so
    # adding one costs compatibility nothing.
    "OpenGL",
}


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def version_code(version: str) -> int:
    """Derive a monotonic integer from a dotted version.

    ``0.4.4`` -> 4004, ``2.7.1`` -> 20701. Non-numeric parts are dropped, which
    is fine for ordering: FEX's ``FEX-2608`` style tags pass their digits
    through and stay ordered.
    """
    parts = []
    for chunk in version.replace("-", ".").split("."):
        digits = "".join(c for c in chunk if c.isdigit())
        if digits:
            parts.append(int(digits))
    if not parts:
        return 1
    code = 0
    for part in parts[:3]:
        code = code * 100 + min(part, 99) if part < 100 else code * 100000 + part
    return code


def collect_files(payload: Path) -> list[str]:
    files = []
    for root, _dirs, names in os.walk(payload):
        for name in names:
            rel = Path(root, name).relative_to(payload).as_posix()
            if rel != "provenance.json":
                files.append(rel)
    return sorted(files)


def compress(tar_path: Path, out_path: Path, method: str) -> None:
    if method == "zstd":
        exe = shutil.which("zstd")
        if not exe:
            raise SystemExit("zstd not found; install it or pass --compress xz")
        subprocess.run([exe, "-19", "-T0", "-q", "-f", str(tar_path), "-o", str(out_path)], check=True)
    elif method == "xz":
        exe = shutil.which("xz")
        if not exe:
            raise SystemExit("xz not found; install it or pass --compress zstd")
        # preset 9 with a 16 MiB dictionary, not plain -9.
        #
        # The dictionary size is written into the block header, and the decoder
        # must allocate it up front — so `xz -9` (64 MiB) makes every install on
        # the phone a 64 MiB allocation regardless of how carefully the app
        # streams the rest. 16 MiB cuts that fourfold and costs about 1% of
        # ratio on these payloads. Peak decode memory is a device constraint;
        # compression ratio is only a download size.
        with out_path.open("wb") as fh:
            subprocess.run(
                [exe, "--lzma2=preset=9,dict=16MiB", "-T0", "-c", str(tar_path)],
                stdout=fh, check=True,
            )
    else:
        raise SystemExit(f"unknown compression: {method}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--type", required=True, help=f"one of: {', '.join(sorted(KNOWN_TYPES))}")
    ap.add_argument("--name", required=True, help="human-readable name shown in the app")
    ap.add_argument("--version", required=True)
    ap.add_argument("--version-code", type=int, default=None)
    ap.add_argument("--payload", required=True, type=Path, help="staging directory to package")
    ap.add_argument("--provenance", type=Path, default=None)
    ap.add_argument("--description", default="")
    ap.add_argument("--out", required=True, type=Path)
    # xz, not zstd. The Android app has no zstd decoder — neither the platform
    # nor java.util.zip provides one, and shipping a native zstd would mean an
    # NDK dependency in the app for the sake of the packager's default. xz costs
    # one 110 KB pure-Java library, compresses these payloads slightly better
    # than zstd -19, and is what the .wcp ecosystem already uses. Passing
    # --compress zstd still works, but nothing we ship should use it.
    ap.add_argument("--compress", choices=("zstd", "xz"), default="xz")
    args = ap.parse_args()

    if args.type not in KNOWN_TYPES:
        raise SystemExit(f"unsupported type {args.type!r}; expected one of {sorted(KNOWN_TYPES)}")

    payload: Path = args.payload
    if not payload.is_dir():
        raise SystemExit(f"payload is not a directory: {payload}")

    files = collect_files(payload)
    if not files:
        raise SystemExit(f"payload directory is empty: {payload}")

    profile = {
        "type": args.type,
        "versionName": args.version,
        "versionCode": args.version_code if args.version_code is not None else version_code(args.version),
        "name": args.name,
        "description": args.description,
        "files": files,
        "vessel": {
            "builtAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "formatVersion": 1,
        },
    }

    if args.provenance and args.provenance.is_file():
        profile["vessel"]["provenance"] = json.loads(args.provenance.read_text())

    args.out.parent.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)
        (tmp_path / "profile.json").write_text(json.dumps(profile, indent=2))
        tar_path = tmp_path / "payload.tar"

        # Deterministic: sorted entries, zeroed mtimes and ownership, so an
        # identical source tree yields an identical archive.
        def normalize(ti: tarfile.TarInfo) -> tarfile.TarInfo:
            ti.uid = ti.gid = 0
            ti.uname = ti.gname = "root"
            ti.mtime = 0
            return ti

        with tarfile.open(tar_path, "w", format=tarfile.GNU_FORMAT) as tar:
            tar.add(tmp_path / "profile.json", arcname="profile.json", filter=normalize)
            for rel in files:
                tar.add(payload / rel, arcname=rel, filter=normalize)

        compress(tar_path, args.out, args.compress)

    digest = sha256_file(args.out)
    args.out.with_suffix(args.out.suffix + ".sha256").write_text(f"{digest}  {args.out.name}\n")

    size_mb = args.out.stat().st_size / (1024 * 1024)
    print(f"  {args.out.name}  {size_mb:.1f} MiB  sha256:{digest[:16]}...")
    print(f"  {len(files)} file(s), type={args.type}, version={profile['versionName']} "
          f"(code {profile['versionCode']})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
