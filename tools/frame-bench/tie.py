"""Is `harm` counting real damage, or is it counting coin flips?

WHY THIS, AND WHY NOW. The device log from this session finally carries the
displacement measured by the HARDWARE matcher -- SignMaterial's blue channel --
alongside harm, on the same frame. Paired over the clip:

    corr(displacement, harm)          +0.05
    corr(share of frame moving, harm) +0.75

Displacement does not predict harm. The size of the DENOMINATOR does. That is
not a statement about motion; it is a statement about the metric.

THE MECHANISM BEING TESTED. harm is a SIGN test:

    moved = |older - truth| > 0.008
    harm  = share of moved pixels where |shown - truth| > |older - truth|

Both comparisons are strict inequalities on magnitude and neither is weighted.
A pixel that moved by 0.009 and lands 0.010 away counts exactly as much as one
that moved by 0.01 and lands 0.8 away. So every pixel sitting just above the
0.008 floor is a coin flip, and any answer -- including one with no motion
compensation at all -- should lose about half of them.

WHY THE BLEND IS THE PROBE. It applies no motion, so it cannot be "wrong
because the motion was wrong", and crucially it needs no block matcher, so this
result does not depend on the laptop stand-in that invalidated locate.py,
regime.py and floor.py. If blend harm is ~50% among barely-moved pixels and
near zero among decisively-moved ones, the metric is a near-tie counter and
every 10-28% reading taken this month is noise about the floor, not damage.

    python tie.py recording.mp4 [--triples 24]
"""
import sys

import numpy as np
import gameplay

CHANGED = 0.008          # InterpolateMaterial's own floor for "this pixel moved"


def split(shown, older, truth):
    """Harm, banded by how decisively the pixel actually changed."""
    d_synth = np.linalg.norm(shown - truth, axis=-1)
    d_base = np.linalg.norm(older - truth, axis=-1)
    out = []
    cuts = [CHANGED, 0.02, 0.05, 0.12, 0.30, 1e9]
    for lo, hi in zip(cuts, cuts[1:]):
        m = (d_base > lo) & (d_base <= hi)
        out.append((float(m.mean() * 100.0),
                    float((d_synth[m] > d_base[m]).mean() * 100.0) if m.any() else None))
    return out


def main():
    limit = 24
    if "--triples" in sys.argv:
        limit = int(sys.argv[sys.argv.index("--triples") + 1])
    path = [a for a in sys.argv[1:] if not a.startswith("--") and a != str(limit)][0]

    got = gameplay.triples(path, limit, with_phase=True)
    if not got:
        sys.exit("no usable triples")

    names = ["blend (no motion at all)", "hold the older frame", "the real middle frame"]
    acc = {n: [] for n in names}
    for A, B, C, _, t in got:
        for n, img in zip(names, ((A + C) * 0.5, A, B)):
            acc[n].append(split(img, A, C if False else B))

    bands = ["0.008-0.02", "0.02-0.05", "0.05-0.12", "0.12-0.30", "0.30+"]
    print("%d triples from %s\n" % (len(got), path))
    print("  how far the pixel ACTUALLY moved, and what share of them each")
    print("  answer is scored as having HARMED\n")
    print("  %-26s %11s %11s %11s %11s %11s"
          % ("answer shown", *bands))
    for n in names:
        rows = np.array([[(-1 if b[1] is None else b[1]) for b in r] for r in acc[n]],
                        dtype=float)
        vals = [np.nanmean(np.where(rows[:, i] < 0, np.nan, rows[:, i]))
                for i in range(len(bands))]
        print("  %-26s %s" % (n, " ".join("%10.1f%%" % v for v in vals)))
    share = np.array([[b[0] for b in r] for r in acc[names[0]]], dtype=float).mean(axis=0)
    print("  %-26s %s" % ("share of moved pixels here",
                          " ".join("%10.1f%%" % v for v in share)))
    print()
    print("  If the blend loses about half of the leftmost band and almost none")
    print("  of the rightmost, harm is a coin flip weighted by how many pixels")
    print("  sit just above the floor -- which is what the +0.75 correlation")
    print("  with 'share of frame moving' says on the device.")


if __name__ == "__main__":
    main()
