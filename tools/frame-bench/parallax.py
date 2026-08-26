"""Is the damage where a region moves differently from the rest of the frame?

THE HYPOTHESIS, AND WHY IT IS NOT ONE OF THE ONES ALREADY CLOSED. where.py
tested `flow gradient`, which is a LOCAL measure -- how fast the field changes
from one pixel to the next -- and it collapsed from 4.05x to 1.64x once speed
was held fixed. That is a different claim. This one is about a REGION whose
speed differs from the frame's dominant motion, which is what parallax during a
camera rotation produces: near geometry sweeps across the view while the far
wall barely moves, and both are large, smooth, and internally consistent. A
local gradient measure sees almost nothing there except at the boundary between
them.

An earlier "motion diversity, -0.04" reading exists, and it is void: it used
bench.estimate, which returns 19% of blocks at 100+ px on a static scene.
Everything measured with that estimator has to be measured again.

WHY THE PIPELINE IS SUSPECTED OF EXACTLY THIS. MedianMaterial's ten anchored
passes exist to delete disagreement, in its own words: "a single 8x8 island
pointing somewhere else is the search losing a tie, not an object moving on its
own". Each pass is 3x3, so ten of them propagate roughly ten blocks -- about 80
pixels. A region that moves differently from its surroundings and is smaller
than that across is outvoted by construction, and the filter cannot tell the
case it was built for from the case it was not.

WHAT IS MEASURED, AND WHAT IT IS CONTROLLED FOR.

  residual speed   |v - dominant|, where dominant is the frame's median
                   vector. This is "how differently is this pixel moving from
                   the scene as a whole" -- zero for a pure camera pan however
                   fast, large for near geometry during a rotation.
  curl             the rotational part of the field, which is what a camera
                   ROLL produces and what a pure translation does not.
  divergence       the radial part: moving forward, or zooming.

Every one of them is scored again INSIDE a narrow band of local speed. Three
candidates already looked decisive at 4x separation and collapsed to 1.5x under
that control, because fast motion causes all of them. A candidate that keeps
its separation with speed held fixed is acting on its own; one that does not
was standing in for speed again.

    python parallax.py recording.mp4 [--triples 24]
"""
import sys

import numpy as np

import carry as K
import gatefix as G
import partial as PA


def field(A, B, C):
    """The validated flow in the shader's own convention, and its error map."""
    f = K.flow(A, C)
    H, W = f.shape[:2]
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    grid = np.stack([xx, yy], axis=-1)
    v, best = None, None
    for cand in (-f, f):
        rec = 0.5 * K.sample(C, grid - cand * 0.5) + 0.5 * K.sample(A, grid + cand * 0.5)
        e = float(np.sqrt(((rec - B) ** 2).mean()))
        if best is None or e < best:
            best, v = e, cand
    out, _ = G.reconstruct(A, C, v, lambda r: K.smoothstep(0.30, 0.70, r))
    return v, np.sqrt(((out - B) ** 2).mean(axis=2))


def main():
    limit = 24
    if "--triples" in sys.argv:
        limit = int(sys.argv[sys.argv.index("--triples") + 1])
    path = [a for a in sys.argv[1:] if not a.startswith("--") and a != str(limit)][0]

    trips = [t for t in PA.real_triples(path)
             if PA.X.rms(t[0], t[2]) > 0.05][:limit]
    if len(trips) < 6:
        sys.exit("not enough moving real triples")

    feats = {"residual speed |v-dominant|": [], "|curl| (rotation)": [],
             "|divergence| (zoom)": [], "local speed |v|": []}
    errs, per_frame = [], []
    for A, B, C in trips:
        v, err = field(A, B, C)
        mag = np.linalg.norm(v, axis=-1)
        dom = np.array([np.median(v[..., 0]), np.median(v[..., 1])], dtype=np.float32)
        resid = np.linalg.norm(v - dom, axis=-1)
        dudy = np.gradient(v[..., 0], axis=0)
        dvdx = np.gradient(v[..., 1], axis=1)
        dudx = np.gradient(v[..., 0], axis=1)
        dvdy = np.gradient(v[..., 1], axis=0)
        errs.append(err.ravel())
        feats["residual speed |v-dominant|"].append(resid.ravel())
        feats["|curl| (rotation)"].append(np.abs(dvdx - dudy).ravel())
        feats["|divergence| (zoom)"].append(np.abs(dudx + dvdy).ravel())
        feats["local speed |v|"].append(mag.ravel())
        per_frame.append((float(np.mean(mag)),
                          float(np.percentile(resid, 90)),
                          float(err.mean())))

    err = np.concatenate(errs)
    print("%d moving triples from %s\n" % (len(trips), path))

    print("  UNCONTROLLED -- error by decile of each candidate")
    print("  %-30s %8s %8s %8s %10s" % ("candidate", "low", "mid", "high", "separation"))
    for name, parts in feats.items():
        x = np.concatenate(parts)
        q = np.quantile(x, [0.0, 0.33, 0.67, 1.0])
        cells = [err[(x >= lo) & (x <= hi)].mean() for lo, hi in zip(q, q[1:])]
        print("  %-30s %s %9.2fx"
              % (name, " ".join("%8.4f" % c for c in cells),
                 max(cells) / max(min(cells), 1e-9)))
    print()

    mag = np.concatenate(feats["local speed |v|"])
    band = np.quantile(mag, [0.40, 0.60])
    inb = (mag >= band[0]) & (mag <= band[1])
    print("  SPEED HELD FIXED (%.0f-%.0f px, %d pixels)" % (band[0], band[1], int(inb.sum())))
    print("  %-30s %8s %8s %8s %10s" % ("candidate", "low", "mid", "high", "separation"))
    ranked = []
    for name in ("residual speed |v-dominant|", "|curl| (rotation)", "|divergence| (zoom)"):
        x = np.concatenate(feats[name])[inb]
        e = err[inb]
        q = np.quantile(x, [0.0, 0.33, 0.67, 1.0])
        cells = [e[(x >= lo) & (x <= hi)].mean() for lo, hi in zip(q, q[1:])]
        sep = max(cells) / max(min(cells), 1e-9)
        ranked.append((sep, name))
        print("  %-30s %s %9.2fx"
              % (name, " ".join("%8.4f" % c for c in cells), sep))
    print()

    # ---- and the same question asked of whole frames ----
    pf = np.array(per_frame)
    if len(pf) >= 8:
        speed, spread, fe = pf[:, 0], pf[:, 1], pf[:, 2]
        lo = speed < np.median(speed)
        print("  PER FRAME -- does a wide spread of speeds cost more?")
        print("  %-24s %6s %12s %12s" % ("frames", "n", "spread p90", "frame error"))
        for lab, m in (("slower half", lo), ("faster half", ~lo)):
            wide = spread[m] > np.median(spread[m])
            print("  %-24s %6d %12.1f %12.5f"
                  % (lab + ", narrow spread", int((~wide).sum()),
                     spread[m][~wide].mean(), fe[m][~wide].mean()))
            print("  %-24s %6d %12.1f %12.5f"
                  % (lab + ", wide spread", int(wide.sum()),
                     spread[m][wide].mean(), fe[m][wide].mean()))
    print()
    ranked.sort(reverse=True)
    print("  With speed held fixed the leader is %s at %.2fx." % (ranked[0][1], ranked[0][0]))
    print("  Anything near 1.0x is not a mechanism, however plausible. Compare")
    print("  against where.py, where three candidates that looked decisive at")
    print("  4x fell to 1.3-1.6x under this same control.")


if __name__ == "__main__":
    main()
