"""Does the device's motion field mean what the shader assumes it means?

WHY ASK. `fg field` now reports the mean length of the field the hardware
matcher produced, and on Metro it reads 129 to 151 luma pixels. The search
window has been taken to be about 112 px throughout -- that figure is where the
"56% of the field is pinned" claim comes from, and it is what every reach
experiment was reasoned against.

A mean cannot exceed a maximum. So one of two things is true, and they lead
opposite ways:

  - the window is far larger than 112, in which case nothing was ever pinned,
    the saturation story was wrong, and the reach experiments were answering a
    question that did not exist; or
  - the vectors are not in luma pixels at all. InterpolateMaterial converts them
    with `motionScale = 1 / lumaSize`, which assumes they are. If they are in
    quarter-pixels, or scaled by block size, or anything else, then every warp
    displaces by a constant factor too far or too near -- a systematic geometric
    error on every synthesised frame, present at every speed, which is what
    distortion looks like.

WHAT SETTLES IT. The recording contains the guest's own frames, so the true
displacement between two of them is measurable here with no reference to the
device at all. Compare that against what the device said about the same footage.

    true 140 px, device 140 px   -> the field is in luma pixels; the window is
                                    simply wider than assumed
    true  35 px, device 140 px   -> the field is in quarter-pixels and the warp
                                    has been displacing four times too far

MEASURED WITH A RULER THAT CANNOT PIN. A quarter-resolution match reaches 448
full-resolution pixels, so it can report distances the fine pass could not
represent. The median rather than the mean, because a camera pan moves most of
the frame together and the median is the camera.

Report the device's figure alongside: read `fg field: ... moved N px mean` out
of the log for the same session.

    python scale.py recording.mp4 [--pairs 30]
"""
import sys

import numpy as np

import bench
import pyramid as P
import scan

RULER = 4


def pairs(path, limit=30, min_gap=1, max_gap=12):
    """Consecutive real frames, streamed, with the gap between them."""
    out, prev = [], None
    for i, raw in enumerate(scan.stream(path)):
        if scan.marked(raw):
            continue
        f = raw.astype(np.float32) / 255.0
        if prev is not None:
            gap = i - prev[0]
            if min_gap <= gap <= max_gap:
                out.append((prev[1], f, gap))
                if len(out) >= limit:
                    break
        prev = (i, f)
    return out


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    limit = 30
    if "--pairs" in sys.argv:
        limit = int(sys.argv[sys.argv.index("--pairs") + 1])

    got = pairs(sys.argv[1], limit)
    if not got:
        sys.exit("no consecutive real frames -- is the synthesis stamp on?")

    dists = []
    for older, newer, gap in got:
        field = P.level(newer, older, RULER)
        d = float(np.median(np.linalg.norm(field, axis=-1)))
        if d > 0.5:
            dists.append(d)

    if not dists:
        sys.exit("nothing moved between any pair of real frames")

    a = np.array(dists)
    print("%d pairs of consecutive real frames\n" % len(a))
    print("  TRUE DISPLACEMENT, measured here from the pictures")
    print("  %-24s %8.1f px" % ("median", np.median(a)))
    print("  %-24s %8.1f px" % ("mean", a.mean()))
    print("  %-24s %8.1f px" % ("90th percentile", np.percentile(a, 90)))
    print("  %-24s %8.1f px" % ("max", a.max()))
    print()
    print("  Compare against `fg field: ... moved N px mean` from the same")
    print("  session. Agreement means the field is in luma pixels and the search")
    print("  window is simply wider than the 112 assumed everywhere. A constant")
    print("  ratio means the shader's motionScale is wrong by that factor, and")
    print("  every synthesised frame has been displaced by it.")


if __name__ == "__main__":
    main()
