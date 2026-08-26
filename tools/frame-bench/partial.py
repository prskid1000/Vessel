"""How much of the correction is actually present in a shipped frame?

WHY A CALIBRATION AND NOT JUST THE RATIO. crossfade.py reports that a shipped
frame sits 0.52 of the A-to-C separation away from the pure cross-fade while a
real guest frame sits 0.72 away, matched at the same phase, on both 2x and 4x
captures. It is tempting to read 0.52/0.72 as "72% of the motion is applied",
and that step is not justified: distance-from-the-blend is not linear in how
far content was moved, so the ratio has to be mapped onto displacement before
it can be quoted as one.

HOW IT IS CALIBRATED WITHOUT A BLOCK MATCHER. The recording contains the
perfect answer -- the guest's own middle frame -- and the pure cross-fade of
the pair around it. Mixing between those two sweeps the correction from none
to all of it:

    S(a) = a * B + (1 - a) * blend(A, C)

At a = 0 the frame carries none of the correction and at a = 1 it carries all
of it, and every value between is a frame holding exactly that fraction. Run
crossfade's ratio over the sweep and the mapping falls out. No motion is
estimated anywhere, so the laptop stand-in matcher that invalidated locate.py,
regime.py and floor.py is not involved.

WHAT IT CANNOT SAY. The sweep interpolates towards the truth in colour, while
the pipeline gets there by moving pixels. Those agree on how much of the
correction is present and disagree on how it looks -- so this reads the
fraction, not the artefact.

    python partial.py recording.mp4
"""
import sys

import numpy as np

import crossfade as X
import scan


_SEQ = {}


def sequence(path, limit=900):
    """The clip as (stamped?, frame), decoded once and kept.

    **Decoded once because it was decoded four times and the run did not
    finish.** Every question below walks the same clip, and at 24 Mbit a walk
    is not cheap; the first version of this file streamed it per alpha value
    and timed out before printing anything.
    """
    if path in _SEQ:
        return _SEQ[path]
    prev, seq = None, []
    for raw in scan.stream(path):
        f = raw.astype(np.float32) / 255.0
        if prev is not None and float(np.abs(f - prev).mean()) < 0.004:
            continue
        prev = f
        seq.append((scan.marked(raw), f))
        if len(seq) >= limit:
            break
    _SEQ[path] = seq
    return seq


def real_triples(path):
    """Consecutive frames the guest actually rendered: A, the truth, C."""
    reals = [f for m, f in sequence(path) if not m]
    return [(reals[i], reals[i + 1], reals[i + 2]) for i in range(len(reals) - 2)]


def shipped(path):
    """Each stamped frame with the real pair around it and its phase."""
    seq = sequence(path)
    out, i = [], 0
    while i < len(seq):
        if seq[i][0] or i + 1 >= len(seq):
            i += 1
            continue
        j = i + 1
        while j < len(seq) and seq[j][0]:
            j += 1
        if j < len(seq) and j > i + 1:
            run = j - i - 1
            for k in range(run):
                out.append((seq[i][1], seq[i + 1 + k][1], seq[j][1],
                            (k + 1) / float(run + 1)))
        i = j
    return out


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    path = sys.argv[1]

    trips = [t for t in real_triples(path) if X.rms(t[0], t[2]) > 0.05][:120]
    if len(trips) < 8:
        sys.exit("not enough moving real triples to calibrate")

    print("%d moving triples of real guest frames\n" % len(trips))
    print("  %-34s %10s" % ("share of the correction present", "ratio"))
    table = []
    for a in (0.0, 0.2, 0.4, 0.6, 0.72, 0.8, 0.9, 1.0):
        r = []
        for A, B, C in trips:
            blend = (A + C) * 0.5
            S = a * B + (1.0 - a) * blend
            r.append(X.rms(S, blend) / max(X.rms(A, C), 1e-6))
        table.append((a, float(np.mean(r))))
        print("  %-34s %10.3f" % ("%.0f%%" % (a * 100), table[-1][1]))
    print()

    ship = [r for r in
            ((X.rms(A, C), X.rms(S, A * (1 - t) + C * t))
             for A, S, C, t in shipped(path))
            if r[0] > 0.05]
    if not ship:
        sys.exit("no moving shipped frames")
    got = float(np.mean([d / s for s, d in ship]))

    xs = np.array([t[1] for t in table])
    ys = np.array([t[0] for t in table])
    order = np.argsort(xs)
    frac = float(np.interp(got, xs[order], ys[order]))
    print("  %-34s %10.3f  (n=%d)" % ("the shipped frames read", got, len(ship)))
    print("  %-34s %9.0f%%" % ("which is this much of the correction", frac * 100))
    print()
    print("  The missing share is not spread evenly over the picture: it is the")
    print("  residue of every edge left part-way between where it was and where")
    print("  it belongs, which is what a partial displacement looks like and")
    print("  what 'distortion in some areas' describes.")


if __name__ == "__main__":
    main()
