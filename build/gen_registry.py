#!/usr/bin/env python3
"""Generate registry/contents.json — the component index the app reads.

The app polls this file to learn which components exist, what versions are
available, and where to download them. It is generated from built packages
rather than hand-maintained so that a version can never be listed without a
matching artifact and hash.

Local use, after building some components — this is the exact invocation that
produced the committed ``registry/contents.json``:

    python3 build/gen_registry.py --dist dist \\
        --base-url https://github.com/prskid1000/Vessel/releases/download/components \\
        --exclude git-2.55.0.3-arm64.wcp

In CI this runs after packages are uploaded to a release, with --base-url
pointing at that release.

Two filters stand between "every .wcp in the directory" and "every .wcp the app
should be offered", and both exist because the directory is not the answer:

* **Superseded builds are dropped** (see :func:`newest_per_type`). ``dist/``
  accumulates every package the build scripts have ever produced and a GitHub
  release accumulates every one ever uploaded, so both hold ``wine-10.13``
  beside ``wine-11.14`` and both Turnip builds. Only the highest ``versionCode``
  of a type can ever be adopted — ``ComponentStore.adoptLatest`` takes it — so
  listing the others offers a download that nothing would use.
* **--exclude drops a package that is built but not published.** Today that is
  the repackaged Git ``Tools`` package: no workflow uploads it to the
  ``components`` release, so an entry for it would be a catalogue row pointing
  at a 404. A no-op in CI, where the input is the release itself.
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


def newest_per_type(entries: list[dict]) -> tuple[list[dict], list[tuple[dict, dict]]]:
    """Keep the highest ``versionCode`` of each ``type``.

    Returns the kept entries in the input order, and the (dropped, winner)
    pairs so the caller can say what it left out and why. Ordering is by
    ``versionCode`` alone because that is the number the device orders by:
    ``ComponentStore.adoptLatest`` picks the highest one installed, and
    ``package_wcp.py`` is where a build makes sure two packages of one type
    never derive the same code.
    """
    winners: dict[str, dict] = {}
    for entry in entries:
        current = winners.get(entry["type"])
        if current is None or entry["versionCode"] > current["versionCode"]:
            winners[entry["type"]] = entry

    kept, dropped = [], []
    for entry in entries:
        if entry is winners[entry["type"]]:
            kept.append(entry)
        else:
            dropped.append((entry, winners[entry["type"]]))
    return kept, dropped


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--dist", type=Path, default=Path("dist"))
    ap.add_argument("--base-url", required=True,
                    help="URL prefix the .wcp files are served from")
    ap.add_argument("--out", type=Path, default=Path("registry/contents.json"))
    ap.add_argument("--exclude", action="append", default=[], metavar="NAME",
                    help="a .wcp file name, or its id, that is built but not "
                         "published; repeatable")
    args = ap.parse_args()

    packages = sorted(args.dist.glob("*.wcp"))
    if not packages:
        raise SystemExit(f"no .wcp files in {args.dist}")

    excluded = {name.removesuffix(".wcp") for name in args.exclude}
    unmatched = excluded - {wcp.stem for wcp in packages}
    if unmatched:
        # A name that matches nothing is almost always a typo, and a typo here
        # silently publishes the thing it was meant to hold back.
        raise SystemExit(
            "--exclude names no package in " + str(args.dist) + ": " + ", ".join(sorted(unmatched)))
    packages = [wcp for wcp in packages if wcp.stem not in excluded]

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

    entries, superseded = newest_per_type(entries)

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
    # newline="\n" so the same dist/ produces the same bytes on Windows as in
    # CI. Without it `write_text` emits CRLF here, `.gitattributes` normalises
    # it back on commit, and every regeneration shows as a whole-file diff.
    args.out.write_text(json.dumps(registry, indent=2) + "\n", newline="\n")
    print(f"wrote {args.out} with {len(entries)} component(s)")
    for e in entries:
        print(f"  {e['type']:<8} {e['versionName']:<10} {e['sizeBytes'] / 1048576:.1f} MiB")

    # Said out loud rather than filtered quietly: a package built and then left
    # out of the index is exactly the thing somebody will go looking for.
    for name in sorted(excluded):
        print(f"  excluded {name}: named on the command line as not published")
    for entry, winner in superseded:
        print(f"  superseded {entry['id']} ({entry['versionCode']}) "
              f"by {winner['id']} ({winner['versionCode']})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
