#!/usr/bin/env python3
"""Generate registry/contents.json — the component index the app reads.

The app polls this file to learn which components exist, what versions are
available, and where to download them. It is generated from built packages
rather than hand-maintained so that a version can never be listed without a
matching artifact and hash.

Local use, after building some components:

    python3 build/gen_registry.py --dist dist \\
        --base-url https://github.com/<owner>/vessel/releases/download/components

In CI this runs after packages are uploaded to a release, with --base-url
pointing at that release.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path


def read_profile(wcp: Path) -> dict:
    """Extract profile.json from a .wcp without unpacking the whole archive."""
    with tempfile.TemporaryDirectory() as tmp:
        tar_path = Path(tmp) / "payload.tar"
        try:
            with open(tar_path, "wb") as out:
                subprocess.run(["zstd", "-dc", str(wcp)], stdout=out, check=True,
                               stderr=subprocess.DEVNULL)
        except (subprocess.CalledProcessError, FileNotFoundError):
            with open(tar_path, "wb") as out:
                subprocess.run(["xz", "-dc", str(wcp)], stdout=out, check=True)

        with tarfile.open(tar_path) as tar:
            member = tar.extractfile("profile.json")
            if member is None:
                raise SystemExit(f"{wcp.name} has no profile.json")
            return json.load(member)


def read_sha(wcp: Path) -> str | None:
    sidecar = wcp.with_suffix(wcp.suffix + ".sha256")
    if not sidecar.is_file():
        return None
    return sidecar.read_text().split()[0]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--dist", type=Path, default=Path("dist"))
    ap.add_argument("--base-url", required=True,
                    help="URL prefix the .wcp files are served from")
    ap.add_argument("--out", type=Path, default=Path("registry/contents.json"))
    args = ap.parse_args()

    packages = sorted(args.dist.glob("*.wcp"))
    if not packages:
        raise SystemExit(f"no .wcp files in {args.dist}")

    entries = []
    for wcp in packages:
        profile = read_profile(wcp)
        provenance = profile.get("vessel", {}).get("provenance", {})
        entries.append({
            "id": wcp.stem,
            "type": profile["type"],
            "name": profile.get("name", wcp.stem),
            "description": profile.get("description", ""),
            "versionName": profile["versionName"],
            "versionCode": profile["versionCode"],
            "sizeBytes": wcp.stat().st_size,
            "sha256": read_sha(wcp),
            "url": f"{args.base_url.rstrip('/')}/{wcp.name}",
            "target": provenance.get("target"),
            "sourceSha": provenance.get("sourceSha"),
            "cpuFlags": provenance.get("cpuFlags"),
        })

    missing = [e["id"] for e in entries if not e["sha256"]]
    if missing:
        raise SystemExit(
            "refusing to publish a registry without hashes for: " + ", ".join(missing))

    registry = {
        "schemaVersion": 1,
        "generator": "vessel/gen_registry.py",
        "components": entries,
    }

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(registry, indent=2) + "\n")
    print(f"wrote {args.out} with {len(entries)} component(s)")
    for e in entries:
        print(f"  {e['type']:<8} {e['versionName']:<10} {e['sizeBytes'] / 1048576:.1f} MiB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
