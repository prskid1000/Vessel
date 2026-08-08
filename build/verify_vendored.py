#!/usr/bin/env python3
"""Diff the vendored com.winlator tree against upstream at its pinned commit.

LGPL-2.1 section 6(a) asks for the Library's complete source *including whatever
changes were used in the work*. Vessel records those changes two ways: a
``// VESSEL:`` marker in every file that carries one, and a table in
``app/src/main/java/com/winlator/README.md`` listing exactly those files.
``LicensingTest`` checks that the marker set and the table agree, offline, on
every build. It cannot check the thing that actually matters — that no file was
*edited without being marked* — because that needs upstream.

This script is that half. It fetches each vendored file from GitHub at the
commit the README pins and compares it byte for byte, then reports any file that
differs but carries no marker. Exit code 1 means the README is now wrong.

    python3 build/verify_vendored.py            # diff against the pinned commit
    python3 build/verify_vendored.py --verbose  # also list the identical files

Network required, which is why it is a script rather than a unit test. Responses
are cached under build/.vendored-cache/ so a re-run is instant.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
import urllib.error
import urllib.request

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
README = REPO_ROOT / "app/src/main/java/com/winlator/README.md"
CACHE = REPO_ROOT / "build/.vendored-cache"

# Both halves of the vendored tree, and where each lives upstream. The cpp half
# keeps its path; the java half does too. Listed rather than derived so an
# upstream reorganisation fails loudly instead of silently matching nothing.
VENDORED = (
    ("app/src/main/java/com/winlator", "app/src/main/java/com/winlator"),
    ("app/src/main/cpp/winlator", "app/src/main/cpp/winlator"),
)

# Not under either root above, so listed by hand. Upstream ships the cursor at a
# density-specific path; Vessel keeps it as -nodpi because it is a 16x16 X11 root
# cursor and must not be scaled.
EXTRA = (
    (
        "app/src/main/res/drawable-nodpi/cursor.png",
        "app/src/main/res/drawable-hdpi/cursor.png",
    ),
)

MARKER = b"VESSEL:"


def pinned() -> tuple[str, str]:
    """The upstream repo and commit, read out of the README rather than duplicated."""
    text = README.read_text(encoding="utf-8")
    repo = re.search(r"github\.com/([\w.-]+/[\w.-]+)", text)
    commit = re.search(r"\|\s*Commit\s*\|\s*`([0-9a-f]{40})`", text)
    if not repo or not commit:
        sys.exit(f"could not read the upstream repo and commit out of {README}")
    return repo.group(1), commit.group(1)


def fetch(repo: str, sha: str, path: str) -> bytes | None:
    cached = CACHE / sha / path.replace("/", "__")
    if cached.exists():
        return cached.read_bytes() or None
    url = f"https://raw.githubusercontent.com/{repo}/{sha}/{path}"
    try:
        with urllib.request.urlopen(url, timeout=60) as response:
            data = response.read()
    except urllib.error.HTTPError as e:
        if e.code == 404:
            data = b""
        else:
            raise
    cached.parent.mkdir(parents=True, exist_ok=True)
    cached.write_bytes(data)
    return data or None


def marked_files() -> set[str]:
    """Every vendored file carrying a ``VESSEL:`` marker, repo-relative."""
    out = set()
    for local, _ in VENDORED:
        for path in sorted((REPO_ROOT / local).rglob("*")):
            # README.md is Vessel's own note *about* the modifications and
            # naturally contains the marker string; it is not one of them.
            if path.name == "README.md" or not path.is_file():
                continue
            if MARKER in path.read_bytes():
                out.add(path.relative_to(REPO_ROOT).as_posix())
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--verbose", action="store_true", help="also list identical files")
    args = ap.parse_args()

    repo, sha = pinned()
    print(f"upstream {repo} @ {sha}")

    pairs: list[tuple[str, str]] = list(EXTRA)
    for local, remote in VENDORED:
        base = REPO_ROOT / local
        for path in sorted(base.rglob("*")):
            if not path.is_file() or path.name == "README.md":
                continue
            rel = path.relative_to(base).as_posix()
            pairs.append((path.relative_to(REPO_ROOT).as_posix(), f"{remote}/{rel}"))

    marked = marked_files()
    modified, identical, orphaned = [], [], []
    for ours, theirs in pairs:
        upstream = fetch(repo, sha, theirs)
        if upstream is None:
            orphaned.append((ours, theirs))
            continue
        # Line endings are normalised before comparing: they are a property of
        # whoever checked the file out, not a modification to the Library.
        if (REPO_ROOT / ours).read_bytes().replace(b"\r\n", b"\n") == upstream.replace(b"\r\n", b"\n"):
            identical.append(ours)
        else:
            modified.append(ours)

    print(f"\n{len(modified)} modified, {len(identical)} identical, {len(orphaned)} with no upstream file")
    for ours in modified:
        print(f"  {'marked  ' if ours in marked else 'UNMARKED'}  {ours}")
    for ours, theirs in orphaned:
        print(f"  NO UPSTREAM  {ours}  (looked for {theirs})")
    if args.verbose:
        for ours in identical:
            print(f"  identical    {ours}")

    problems = []
    unmarked = [f for f in modified if f not in marked]
    if unmarked:
        problems.append(
            "these files differ from upstream and carry no // VESSEL: marker, so the "
            "README's list of local modifications is incomplete:\n    "
            + "\n    ".join(unmarked),
        )
    stale = sorted(marked - set(modified))
    if stale:
        problems.append(
            "these files carry a // VESSEL: marker but are byte-identical to upstream, "
            "so the marker is stale:\n    " + "\n    ".join(stale),
        )
    if orphaned:
        problems.append(
            "these files have no counterpart upstream, so either they are Vessel's own "
            "and are in the wrong tree, or the pinned commit moved:\n    "
            + "\n    ".join(o for o, _ in orphaned),
        )

    if problems:
        print("\nFAIL")
        for problem in problems:
            print("  - " + problem)
        return 1
    print("\nOK — every difference from upstream is marked, and every marker is real.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
