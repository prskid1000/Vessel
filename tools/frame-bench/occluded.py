"""The other half of what a depth buffer does, which boundary.py did not test.

WHAT WAS CLAIMED TOO EARLY. boundary.py gave the algorithm each pixel's true
motion -- strictly more than depth could provide -- and every treatment came
back worse at the edges than the shipped window. That was reported as "a depth
buffer is not worth building", and it does not support that conclusion. It
tested WHICH VECTOR to use. It said nothing about WHICH SOURCE FRAME to trust,
and that is the half FSR3 actually spends its depth buffer on.

THE TWO ARE NOT THE SAME QUESTION. Where a moving object uncovers the
background, the content behind it exists in the newer frame and in neither the
older one nor any vector pointing into it. No choice of motion fixes that,
which is exactly why boundary.py found nothing. The fix is to stop reading the
older frame there at all -- full weight on the source that can see it. The
shader already attempts this: `stillAtNewer` and `stillAtOlder` compare luma at
the two fetch sites and pull the weights around. But that is a photometric
guess at a geometric fact, and two surfaces of similar brightness defeat it.

Depth answers it geometrically, and unlike the disocclusion mask FSR3 builds,
this use needs no motion vectors -- only the observation that the two fetch
sites sit at different depths, which means one of them is looking at the wrong
surface.

WHAT IS SCORED. The bilateral fetch is fixed and correct throughout; only the
weighting between the two sources changes.

  shipped weights       what InterpolateMaterial computes today
  older only            trust the older frame everywhere
  newer only            trust the newer frame everywhere
  ORACLE source         per pixel, whichever of the three lands closest to the
                        truth. This is the ceiling for perfect disocclusion
                        knowledge, and depth can only approximate it.
  decoy source          the same three-way minimum over three answers that
                        carry no information -- the shipped weights plus noise
                        at the spread of the real three. Whatever this takes
                        off is what taking a minimum of three always takes off,
                        and must be subtracted from the oracle before any of it
                        is believed. An earlier ceiling in this directory was
                        overstated by six points for want of exactly this.

    python occluded.py a.mp4 [b.mp4 ...] [--triples 24]
"""
import sys

import numpy as np

import candidates as CD
import carry as K
import partial as PA


def main():
    limit = 24
    if "--triples" in sys.argv:
        limit = int(sys.argv[sys.argv.index("--triples") + 1])
    paths = [a for a in sys.argv[1:] if not a.startswith("--") and a != str(limit)]
    if not paths:
        sys.exit(__doc__)

    names = ["shipped weights", "older only", "newer only",
             "ORACLE source", "decoy source (pure luck)"]
    for path in paths:
        trips = [t for t in PA.real_triples(path)
                 if PA.X.rms(t[0], t[2]) > 0.05][:limit]
        acc = {n: {"all": [], "edge": []} for n in names}

        for i, (A, B, C) in enumerate(trips):
            f = K.flow(A, C)
            H, W = f.shape[:2]
            yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
            grid = np.stack([xx, yy], axis=-1)
            v, best = None, None
            for cand in (-f, f):
                rec = (0.5 * K.sample(C, grid - cand * 0.5)
                       + 0.5 * K.sample(A, grid + cand * 0.5))
                e = float(np.sqrt(((rec - B) ** 2).mean()))
                if best is None or e < best:
                    best, v = e, cand

            # The bilateral fetch, unchanged. Only the weighting varies below.
            older = K.sample(A, grid + v * 0.5)
            newer = K.sample(C, grid - v * 0.5)

            # The shader's own photometric occlusion test, transcribed.
            ln, lo = K.luma(C), K.luma(A)
            mn, mo = grid - v * 0.5, grid + v * 0.5
            fit_still = np.abs(ln - lo)
            still_newer = 1.0 - K.smoothstep(2 / 255.0, 12 / 255.0,
                                             np.abs(K.sample(ln, mn) - K.sample(lo, mn)))
            still_older = 1.0 - K.smoothstep(2 / 255.0, 12 / 255.0,
                                             np.abs(K.sample(ln, mo) - K.sample(lo, mo)))
            moves = K.smoothstep(2 / 255.0, 12 / 255.0, fit_still)
            w_old = 0.5 * (1.0 - still_older * moves)
            w_new = 0.5 * (1.0 - still_newer * moves)
            dead = (w_old + w_new) < 1e-3
            w_old = np.where(dead, 0.5, w_old)[..., None]
            w_new = np.where(dead, 0.5, w_new)[..., None]

            outs = {}
            outs["shipped weights"] = (w_old * older + w_new * newer) / (w_old + w_new)
            outs["older only"] = older
            outs["newer only"] = newer

            three = np.stack([outs["shipped weights"], older, newer])
            d = np.linalg.norm(three - B[None], axis=-1)
            outs["ORACLE source"] = np.take_along_axis(
                three, np.argmin(d, axis=0)[None, ..., None], axis=0)[0]

            rng = np.random.default_rng(i)
            spread = float(np.linalg.norm(
                three - three.mean(axis=0, keepdims=True), axis=-1).mean())
            decoy = (outs["shipped weights"][None]
                     + rng.normal(0.0, spread / np.sqrt(3.0),
                                  (3,) + older.shape).astype(np.float32))
            dd = np.linalg.norm(decoy - B[None], axis=-1)
            outs["decoy source (pure luck)"] = np.take_along_axis(
                decoy, np.argmin(dd, axis=0)[None, ..., None], axis=0)[0]

            # **Where the two sources actually disagree.** Everywhere else they
            # show the same content and every row here is the same picture, so
            # a whole-frame mean dilutes the answer into nothing.
            edge = np.linalg.norm(older - newer, axis=-1) > 0.08

            for n in names:
                err = np.sqrt(((outs[n] - B) ** 2).mean(axis=2))
                acc[n]["all"].append(float(err.mean()))
                if edge.any():
                    acc[n]["edge"].append(float(err[edge].mean()))

        base = np.mean(acc["shipped weights"]["all"])
        bedge = np.mean(acc["shipped weights"]["edge"])
        print("\n=== %s (%d triples) ===" % (path, len(trips)))
        print("  %-28s %10s %9s %11s %9s"
              % ("", "overall", "vs base", "where they", "differ"))
        for n in names:
            a = np.mean(acc[n]["all"])
            e = np.mean(acc[n]["edge"])
            print("  %-28s %10.5f %8.1f%% %11.5f %8.1f%%"
                  % (n, a, 100.0 * (a / base - 1.0), e, 100.0 * (e / bedge - 1.0)))
    print()
    print("  Subtract the decoy row from the oracle row. What is left is what")
    print("  perfect knowledge of which source to trust is worth -- and that,")
    print("  not vector selection, is what a depth buffer would buy.")


if __name__ == "__main__":
    main()
