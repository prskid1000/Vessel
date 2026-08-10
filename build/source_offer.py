#!/usr/bin/env python3
"""Write the source offer that goes in the components release body.

**This is a licence obligation, not a nicety.** Vessel links the Winlator X
server under LGPL-2.1, and section 6(a) wants the Library's complete source
"including whatever changes were used in the work". The components release
publishes built binaries of Wine, FEX, DXVK, vkd3d and Mesa — every one of them
built from patched upstream trees — and until now its body said nothing about
where any of that source is. A repository that happens to be public is not an
offer; a page that hands out binaries has to say where their source lives.

Every fact here comes out of the packages themselves, from the provenance record
`build/common.sh` writes into each `.wcp`, or off the repository the offer points
at. That is the point: a hand-maintained list on a release page goes stale the
first time somebody bumps a pin, and a stale source offer is worse than none
because it looks like one.

    python3 build/source_offer.py --dist dist --repo owner/name \\
        --out release-notes.md --exclude git-2.55.0.3-arm64.wcp

The same two filters `gen_registry.py` applies apply here, for the same reason
and so that the offer and the index describe one set of packages: superseded
builds are dropped, and `--exclude` drops one that is built but not published.
An offer covering a package nobody can download is noise; an offer *missing* one
that anybody can is the licence failure.

**The offer refuses to be written when it cannot name a repository.** A row
reading `unknown` is not an offer for that component — 6(a) wants the source,
and a ref and a commit are useless without saying which repository they are
commits *of*. `sourceRepo` was added to provenance on 2026-08-09, so a package
built before that carries none, and the only honest fix is to rebuild it:
reading the repository out of `native/pins.env` instead would be a claim about
today's pin dressed up as a fact about the artefact, which is the failure the
paragraph above is about.
"""

from __future__ import annotations

import argparse
from pathlib import Path

from gen_registry import newest_per_type, read_profile

HEADER = """Rolling release of built `.wcp` component packages.

Each package below is built from upstream source with Vessel's patches applied.
The exact commit is named for each one, and every patch is listed underneath.
"""

MODIFICATIONS_HEADER = """
### Modifications to upstream

Every change Vessel makes to an upstream tree, in full. The directories are
named after the **source tree** rather than the package, because one tree can
produce more than one package: `patches/mesa/` is applied to the single Mesa
checkout that both Turnip and Zink are built from. A component whose tree is not
listed here is built from its upstream ref unmodified.
"""

FOOTER = """
### Source

Vessel is licensed under the GNU LGPL version 2.1 or later, and contains the
Winlator X server (Bruno Rodrigues and contributors) under the GNU LGPL version
2.1. Vessel's own complete source, including the build scripts that produced
every package above and the patches listed above, is at
https://github.com/{repo}.

`build/<component>.sh` is the script that applies the patches and builds a
component, and `native/pins.env` records which upstream ref each is built from.
"""


def modifications(root: Path) -> list[str]:
    """Every `patches/<tree>/` directory in the repository, and its patches.

    Enumerated off the filesystem rather than attributed per package, and that
    is the fix rather than a shortcut. The previous version put
    ``patches/<component>/`` on every row unconditionally, and for four of the
    six shipped components that named a directory which is not in the
    repository at all: dxvk and vkd3d carry no patches, and Turnip's and Zink's
    are in `patches/mesa/`. A broken pointer to the modifications is the
    substantive half of 6(a) got wrong, not a typo.

    Per-package attribution cannot be derived honestly anyway. The patch
    directory is keyed on the name `fetch_source` is called with, and
    `build/zink.sh` chooses between `mesa` and `mesa-zink` at build time from
    `ZINK_MESA_REF`, so no static rule over the scripts gets it right and the
    packages do not record it. A list of every directory is both complete and
    incapable of naming one that is not there.
    """
    lines = []
    for directory in sorted(p for p in (root / "patches").glob("*") if p.is_dir()):
        patches = sorted(p.name for p in directory.glob("*.patch"))
        if not patches:
            continue
        lines.append("")
        lines.append(f"**`patches/{directory.name}/`** — {len(patches)} patch(es)")
        lines.append("")
        lines.extend(f"- `{name}`" for name in patches)
    return lines


def rows(dist: Path, exclude: set[str]) -> list[dict]:
    """One record per published package, read from its own provenance."""
    out = []
    for wcp in sorted(dist.glob("*.wcp")):
        if wcp.stem in exclude:
            continue
        profile = read_profile(wcp)
        provenance = profile.get("vessel", {}).get("provenance", {})
        out.append({
            "id": wcp.stem,
            "type": profile["type"],
            "versionCode": profile["versionCode"],
            "component": provenance.get("component", wcp.stem),
            "version": profile.get("versionName", "?"),
            "repo": provenance.get("sourceRepo", "unknown"),
            "ref": provenance.get("sourceRef", "unknown"),
            "sha": provenance.get("sourceSha", "unknown"),
        })
    return out


def render(records: list[dict], repo: str, root: Path) -> str:
    lines = [HEADER, "| Component | Version | Upstream | Ref | Commit |",
             "|---|---|---|---|---|"]
    for r in records:
        upstream = f"[{_short(r['repo'])}]({r['repo']})"
        lines.append(
            f"| {r['component']} | {r['version']} | {upstream} | `{r['ref']}` | "
            f"`{r['sha'][:12]}` |"
        )
    lines.append(MODIFICATIONS_HEADER)
    lines.extend(modifications(root))
    lines.append(FOOTER.format(repo=repo))
    return "\n".join(lines) + "\n"


def _short(url: str) -> str:
    """`https://github.com/FEX-Emu/FEX.git` -> `FEX-Emu/FEX`."""
    trimmed = url.rstrip("/").removesuffix(".git")
    parts = trimmed.split("/")
    return "/".join(parts[-2:]) if len(parts) >= 2 else trimmed


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--dist", type=Path, default=Path("dist"))
    ap.add_argument("--repo", required=True, help="owner/name of this repository")
    ap.add_argument("--out", type=Path, default=Path("release-notes.md"))
    ap.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent,
                    help="the repository the offer points at; its patches/ are listed")
    ap.add_argument("--exclude", action="append", default=[], metavar="NAME",
                    help="a .wcp file name, or its id, that is built but not "
                         "published; repeatable")
    args = ap.parse_args()

    excluded = {name.removesuffix(".wcp") for name in args.exclude}
    unmatched = excluded - {wcp.stem for wcp in args.dist.glob("*.wcp")}
    if unmatched:
        raise SystemExit(
            "--exclude names no package in " + str(args.dist) + ": " + ", ".join(sorted(unmatched)))

    records = rows(args.dist, excluded)
    if not records:
        raise SystemExit(f"no .wcp files in {args.dist}")
    records, superseded = newest_per_type(records)

    unnamed = [r["id"] for r in records if not r["repo"].startswith("http")]
    if unnamed:
        raise SystemExit(
            "refusing to write a source offer that cannot say where the source is.\n"
            "  No sourceRepo in the provenance of: " + ", ".join(unnamed) + "\n"
            "  These were packaged before build/common.sh recorded it. Rebuild each\n"
            "  one. The offer is a licence obligation and a row reading 'unknown'\n"
            "  does not discharge it.")

    args.out.write_text(render(records, args.repo, args.root),
                        encoding="utf-8", newline="\n")
    print(f"wrote {args.out} covering {len(records)} component(s)")
    for r in records:
        print(f"  {r['component']:<8} {r['ref']:<16} {r['sha'][:12]}")
    for entry, winner in superseded:
        print(f"  superseded {entry['id']} by {winner['id']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
