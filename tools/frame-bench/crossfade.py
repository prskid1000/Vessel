"""Is the shipped frame a warp, or is it just a cross-fade?

WHAT PROVOKED THIS. The worst 4x frame in a RE9 capture shows the wall lamp at
two positions at once and the picture frame beside it doubled, during a lateral
camera pan. Doubling is not what a wrong vector looks like -- a wrong vector
puts content in one wrong place. Two copies at the two ENDPOINT positions is
what mix(older, newer, phase) looks like when there is no motion to apply.

WHY THIS TEST CAN BE TRUSTED. It compares the shipped frame against arithmetic,
not against an estimate. The cross-fade of two frames at a known phase needs no
block matcher, so the laptop stand-in that invalidated locate.py, regime.py and
floor.py cannot reach this result. Either the frame the device presented equals
that arithmetic or it does not.

HOW TO READ IT. `S vs blend` is how far the shipped frame sits from the pure
cross-fade; `A vs C` is how far apart the two sources are, which is the scale
the first number has to be judged against. Their ratio is the whole answer:

    near 0    the pipeline presented a cross-fade -- the field was not applied
    near 1    the pipeline moved content as far as the sources differ

Split by how far the scene actually went, because a still scene has no motion
to apply and its ratio is meaningless -- the two sources are the same picture,
so every answer is the same picture too.

    python crossfade.py recording.mp4
"""
import sys

import numpy as np

import damage
import scan


def real_control(path):
    """What ratio a PERFECT answer scores, from the guest's own frames.

    **Without this the ratio is uninterpretable and the first reading of it was
    nearly misread.** A correct interpolation is not the cross-fade either: on a
    pan the blend shows every edge twice and the truth shows it once, so a
    faithful frame also sits some distance from the blend. The question is
    whether the shipped frames sit as far from it as a real frame does.

    Three consecutive frames the guest actually rendered give exactly that: the
    middle one is the true answer for the pair around it, at phase 0.5, and it
    passed through no part of this pipeline.
    """
    prev, reals = None, []
    for raw in scan.stream(path):
        f = raw.astype(np.float32) / 255.0
        if prev is not None and float(np.abs(f - prev).mean()) < 0.004:
            continue
        prev = f
        if not scan.marked(raw):
            reals.append(f)
    out = []
    for i in range(len(reals) - 2):
        A, B, C = reals[i], reals[i + 1], reals[i + 2]
        out.append((rms(A, C), rms(B, (A + C) * 0.5)))
    return out


def rms(a, b):
    return float(np.sqrt(((a - b) ** 2).mean()))


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    path = sys.argv[1]

    rows = []
    for idx, A, S, C, t in damage.triples_shipped(path):
        blend = A * (1.0 - t) + C * t
        rows.append((rms(A, C), rms(S, blend), rms(S, A), rms(S, C), t, idx))
    if not rows:
        sys.exit("no shipped synthesised frames -- is the synthesis stamp on?")

    sep = np.array([r[0] for r in rows])
    dev = np.array([r[1] for r in rows])
    print("%d shipped synthesised frames from %s\n" % (len(rows), path))
    print("  %-24s %6s %10s %10s %12s"
          % ("how far A and C differ", "n", "A vs C", "S vs blend", "ratio"))
    cuts = [0.0, 0.02, 0.05, 0.10, 0.20, 1e9]
    for lo, hi in zip(cuts, cuts[1:]):
        m = (sep >= lo) & (sep < hi)
        if m.sum() < 3:
            continue
        print("  %-24s %6d %10.4f %10.4f %11.2f"
              % ("%.2f-%.2f" % (lo, hi) if hi < 1e9 else "%.2f+" % lo,
                 int(m.sum()), sep[m].mean(), dev[m].mean(),
                 float((dev[m] / np.maximum(sep[m], 1e-6)).mean())))
    print()
    moving = sep > 0.05
    if moving.any():
        r = dev[moving] / np.maximum(sep[moving], 1e-6)
        print("  %-40s %6.2f" % ("ratio where the scene really moved", r.mean()))
        print("  %-40s %6.0f%%"
              % ("frames within 20% of a pure cross-fade", 100.0 * (r < 0.20).mean()))
    ctrl = real_control(path)
    if ctrl:
        cs = np.array([c[0] for c in ctrl])
        cd = np.array([c[1] for c in ctrl])
        m = cs > 0.05
        if m.sum() >= 3:
            cr = cd[m] / np.maximum(cs[m], 1e-6)
            print("  %-40s %6.2f   (n=%d)"
                  % ("SAME RATIO FOR A REAL GUEST FRAME", cr.mean(), int(m.sum())))
            print("  %-40s %6.2f"
                  % ("  the shipped frames, for comparison",
                     float((dev[moving] / np.maximum(sep[moving], 1e-6)).mean())))
            print()
            print("  The control is the ceiling this pipeline could ever reach.")
            print("  A shipped ratio ABOVE it is not fidelity, it is displacement")
            print("  the real frame does not contain -- content moved somewhere")
            print("  the game never put it.")
    print()
    print("  A ratio near zero on moving frames means the presented picture IS")
    print("  the cross-fade: the motion field was estimated, filtered, and then")
    print("  had no effect on what reached the panel.")


if __name__ == "__main__":
    main()
