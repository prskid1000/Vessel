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
`build/common.sh` writes into each `.wcp`. That is the point: a hand-maintained
list on a release page goes stale the first time somebody bumps a pin, and a
stale source offer is worse than none because it looks like one.

    python3 build/source_offer.py --dist dist --repo owner/name \\
        --out release-notes.md
"""

from __future__ import annotations

import argparse
from pathlib import Path

from gen_registry import read_profile

HEADER = """Rolling release of built `.wcp` component packages.

Each package below is built from upstream source with Vessel's patches applied.
The exact commit and the patches used are named for each one.
"""

FOOTER = """
### Source

Vessel is licensed under the GNU LGPL version 2.1 or later, and contains the
Winlator X server (Bruno Rodrigues and contributors) under the GNU LGPL version
2.1. Vessel's own complete source, including the build scripts that produced
every package above and the patches applied to each upstream tree, is at
https://github.com/{repo}.

Patches for a component are in `patches/<component>/` in that repository, and
`build/<component>.sh` is the script that applies them and builds it. `native/
pins.env` records which upstream ref each component is built from.
"""


def rows(dist: Path) -> list[dict]:
    """One record per package, read from its own provenance."""
    out = []
    for wcp in sorted(dist.glob("*.wcp")):
        profile = read_profile(wcp)
        provenance = profile.get("vessel", {}).get("provenance", {})
        out.append({
            "component": provenance.get("component", wcp.stem),
            "version": profile.get("versionName", "?"),
            "repo": provenance.get("sourceRepo", "unknown"),
            "ref": provenance.get("sourceRef", "unknown"),
            "sha": provenance.get("sourceSha", "unknown"),
        })
    return out


def render(records: list[dict], repo: str) -> str:
    lines = [HEADER, "| Component | Version | Upstream | Ref | Commit | Patches |",
             "|---|---|---|---|---|---|"]
    for r in records:
        # A bare repository URL is a link; "unknown" is left as text rather than
        # linked to nothing, so a package built before provenance recorded the
        # repository is visibly missing it instead of quietly looking fine.
        upstream = r["repo"]
        upstream = f"[{_short(upstream)}]({upstream})" if upstream.startswith("http") else upstream
        patches = f"`patches/{r['component']}/`"
        lines.append(
            f"| {r['component']} | {r['version']} | {upstream} | `{r['ref']}` | "
            f"`{r['sha'][:12]}` | {patches} |"
        )
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
    args = ap.parse_args()

    records = rows(args.dist)
    if not records:
        raise SystemExit(f"no .wcp files in {args.dist}")

    args.out.write_text(render(records, args.repo))
    print(f"wrote {args.out} covering {len(records)} component(s)")
    for r in records:
        print(f"  {r['component']:<8} {r['ref']:<16} {r['sha'][:12]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
