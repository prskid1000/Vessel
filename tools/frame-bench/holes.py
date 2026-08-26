"""Where the shipped build puts black where there is nothing black.

WHY THIS EXISTS. The device now counts invented content in 32x32 cells rather
than averaging it over the frame, and the build currently shipped -- with the
hypothesis-weighting change reverted -- reads up to 31 cells of 880 with a
worst cell 99% invented, and is above ten cells in 28% of samples. So black
patches are not something a reverted experiment introduced. They are already
there, about a quarter of the time, in the path that has been shipping.

That number says how much and how often. It cannot say where, or on what, and
those are what a fix needs.

WHAT COUNTS AS A HOLE, AND WHY IT NEEDS NO MOTION ESTIMATE. A synthesised frame
sits between two real ones. Every pixel it may legitimately show comes from one
of them or from a mixture, so a pixel that is dark while BOTH neighbours are
bright at that position is showing something neither frame contains. That test
is arithmetic on three recorded frames -- no block matcher, no flow, nothing
that could be wrong in the way the laptop's stand-in estimator was wrong about
everything it was asked this month.

Connected regions are what the eye responds to, so the pixels are clumped into
cells the way the device clumps them, and the worst frames are written out with
the holes marked so the fault can be looked at rather than described.

    python holes.py recording.mp4 [--dump 6]
"""
import os
import sys

import numpy as np

import carry as K
import damage
import scan

DARK = 0.06          # luma below which a pixel reads as black on this footage
BRIGHT = 0.10        # luma above which a neighbour counts as having content
CELL = 32


def holes(A, S, C):
    """Dark in the synthesised frame, bright in both real ones."""
    ls, la, lc = K.luma(S), K.luma(A), K.luma(C)
    return (ls < DARK) & (la > BRIGHT) & (lc > BRIGHT)


def cells(mask):
    H, W = mask.shape
    gh, gw = H // CELL, W // CELL
    return mask[:gh * CELL, :gw * CELL].reshape(gh, CELL, gw, CELL).mean(axis=(1, 3))


def main():
    dump = 6
    if "--dump" in sys.argv:
        dump = int(sys.argv[sys.argv.index("--dump") + 1])
    path = [a for a in sys.argv[1:] if not a.startswith("--") and a != str(dump)][0]

    # ---- calibrate the detector before believing a word of it ----------
    #
    # **A dark object in motion trips this test legitimately.** A beam that sits
    # at the right of the older frame and left of the newer one belongs, at the
    # midpoint, exactly where BOTH real frames show bright wall -- so a perfect
    # interpolation puts dark pixels where neither neighbour has any, and this
    # detector calls that a hole. The first frame it ranked worst was that beam.
    #
    # The recording contains the answer. Three consecutive frames the guest
    # itself drew give a middle frame that passed through no part of this
    # pipeline, and scoring IT the same way says how much of any reading is the
    # detector rather than the renderer. Whatever the truth scores is the floor,
    # and it has to be subtracted from everything below.
    #
    # The device's own invented-content channel is built on the same comparison
    # -- darker than anything real within six pixels of either endpoint -- so
    # this floor applies to `fg patches` too, and the baseline it just reported
    # cannot be read until this number is known.
    reals = [f for m, f in
             ((scan.marked(r), r.astype(np.float32) / 255.0)
              for r in scan.stream(path)) if not m]
    truth = []
    for i in range(0, min(len(reals) - 2, 120)):
        A, B, C = reals[i], reals[i + 1], reals[i + 2]
        c = cells(holes(A, B, C))
        truth.append((int((c > 0.5).sum()), float(c.max() * 100.0)))
    if truth:
        tc = np.array([t[0] for t in truth], dtype=float)
        print("  CONTROL -- the guest's OWN middle frame, scored the same way")
        print("  %-40s %8.1f  (max %d)" % ("cells over half", tc.mean(), int(tc.max())))
        print("  %-40s %8.0f%%\n" % ("frames the detector accuses",
                                     100.0 * (tc > 0).mean()))

    rows = []
    for idx, A, S, C, ph in damage.triples_shipped(path):
        m = holes(A, S, C)
        c = cells(m)
        rows.append((idx, float(m.mean() * 100.0), int((c > 0.5).sum()),
                     float(c.max() * 100.0), c.size, ph, A, S, C, m))
    if not rows:
        sys.exit("no shipped synthesised frames -- is the synthesis stamp on?")

    share = np.array([r[1] for r in rows])
    count = np.array([r[2] for r in rows], dtype=float)
    worst = np.array([r[3] for r in rows])
    ph = np.array([r[5] for r in rows])
    print("%d shipped synthesised frames from %s\n" % (len(rows), path))
    print("  %-40s %8.3f%%" % ("pixels dark that should not be, mean", share.mean()))
    print("  %-40s %8.1f  of %d" % ("cells over half, mean", count.mean(), rows[0][4]))
    print("  %-40s %8.0f" % ("  ... worst frame", count.max()))
    print("  %-40s %8.0f%%" % ("frames with any cell over half",
                               100.0 * (count > 0).mean()))
    print()

    # **Split by phase, because it says which fetch is at fault.** A frame drawn
    # near phase 0 reads mostly from the older frame and one near 1 mostly from
    # the newer. If the holes cluster at one end, one of the two fetches is
    # landing somewhere it should not; if they peak in the middle, it is the
    # displacement itself, which is largest there.
    if len(set(np.round(ph, 2))) > 1:
        print("  %-24s %7s %10s %10s" % ("phase in the run", "n", "cells", "worst"))
        for lo, hi in ((0.0, 0.34), (0.34, 0.66), (0.66, 1.0)):
            k = (ph > lo) & (ph <= hi)
            if k.sum() < 2:
                continue
            print("  %-24s %7d %10.1f %9.0f%%"
                  % ("%.0f-%.0f%% across" % (lo * 100, hi * 100),
                     int(k.sum()), count[k].mean(), worst[k].mean()))
        print()

    order = np.argsort(-count)
    print("  %-22s %8s %9s %8s %12s"
          % ("worst frames", "cells", "worst", "phase", "recording ix"))
    for k in order[:min(dump, len(rows))]:
        r = rows[k]
        print("  %-22s %8d %8.0f%% %7.0f%% %12d" % ("", r[2], r[3], r[5] * 100, r[0]))

    outdir = "holes_" + os.path.splitext(os.path.basename(path))[0]
    os.makedirs(outdir, exist_ok=True)
    try:
        from PIL import Image
    except ImportError:
        print("\n  (Pillow not installed -- no frames written)")
        return
    for rank, k in enumerate(order[:min(dump, len(rows))]):
        idx, _, _, _, _, _, A, S, C, m = rows[k]
        marked = S.copy()
        marked[m] = np.array([1.0, 0.0, 0.0])
        strip = np.concatenate([A, S, C, marked], axis=1)
        Image.fromarray((strip * 255).astype(np.uint8)).save(
            os.path.join(outdir, "hole%d_ix%d.png" % (rank, idx)))
    print("\n  wrote %d strips to %s/ -- older | SYNTHESISED | newer | holes in red"
          % (min(dump, len(rows)), outdir))


if __name__ == "__main__":
    main()
