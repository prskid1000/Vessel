"""Does WHAT the region contains decide whether its motion survives?

THE REPORT. The same screen region reconstructs correctly for one object and
not for another. That rules out anything positional -- the search window, the
block grid, the layer geometry are all fixed to the screen and cannot treat two
things in the same place differently. It points at the content.

WHY THE STRUCTURE TENSOR IS THE RIGHT QUESTION AND CONTRAST IS NOT. A block
matcher recovers a two-component vector by minimising a cost over a patch, and
what that patch can determine depends on how its gradients are distributed, not
on how strong they are. The eigenvalues of the local structure tensor say it
exactly:

  both small          flat. No information in any direction. The tie is broken
                      arbitrarily, and ten median passes then propagate it.
  one large, one small an EDGE. Motion across it is recoverable; motion ALONG
                      it is not, at any contrast. This is the aperture problem,
                      and it is why a bright, sharply-defined pillar can be
                      matched worse than a dull patch of wallpaper.
  both large          a corner. Fully determined. The easy case.

A plain contrast or gradient-magnitude measure cannot separate the second case
from the third, and where.py's `image gradient` candidate is exactly that
measure -- it scored 2.90x uncontrolled and fell to 1.30x when speed was held
fixed. That is not evidence against content mattering; it is evidence that
contrast is the wrong summary of it.

AND THE AXIS, because "x but not y" may be literal. The field's two components
are produced by the same hardware and filtered by the same passes, so a
difference between horizontal and vertical error is not a tuning matter -- it
would mean one axis is being handled differently somewhere, which is a bug
rather than a limit.

Everything is scored inside a band of local speed AND a band of residual speed,
because both are already known to matter -- residual at 2.52x with speed held
fixed. A content effect has to survive both to be its own mechanism.

    python object.py recording.mp4 [--triples 20]
"""
import sys

import numpy as np

import carry as K
import gatefix as G
import partial as PA


def structure(gray, sigma=2):
    """Eigenvalues of the local structure tensor, smoothed over a block."""
    gy, gx = np.gradient(gray)
    def box(a):
        k = 2 * sigma + 1
        c = np.cumsum(np.cumsum(np.pad(a, k, mode="edge"), axis=0), axis=1)
        s = (c[k * 2:, k * 2:] - c[:-k * 2, k * 2:]
             - c[k * 2:, :-k * 2] + c[:-k * 2, :-k * 2]) / float(k * 2) ** 2
        return s[:a.shape[0], :a.shape[1]]
    jxx, jyy, jxy = box(gx * gx), box(gy * gy), box(gx * gy)
    tr = jxx + jyy
    det = jxx * jyy - jxy * jxy
    disc = np.sqrt(np.maximum(tr * tr - 4 * det, 0.0))
    return (tr + disc) * 0.5, (tr - disc) * 0.5


def main():
    limit = 20
    if "--triples" in sys.argv:
        limit = int(sys.argv[sys.argv.index("--triples") + 1])
    path = [a for a in sys.argv[1:] if not a.startswith("--") and a != str(limit)][0]

    trips = [t for t in PA.real_triples(path)
             if PA.X.rms(t[0], t[2]) > 0.05][:limit]
    if len(trips) < 6:
        sys.exit("not enough moving real triples")

    E, MAG, RES, ANIS, ENER, AXIS = [], [], [], [], [], []
    for A, B, C in trips:
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
        err = np.sqrt(((out - B) ** 2).mean(axis=2))
        l1, l2 = structure(K.luma(C))
        mag = np.linalg.norm(v, axis=-1)
        dom = np.array([np.median(v[..., 0]), np.median(v[..., 1])], dtype=np.float32)
        E.append(err.ravel())
        MAG.append(mag.ravel())
        RES.append(np.linalg.norm(v - dom, axis=-1).ravel())
        # 1 = a pure edge (one direction determined), 0 = a corner.
        ANIS.append(((l1 - l2) / np.maximum(l1 + l2, 1e-9)).ravel())
        ENER.append(l1.ravel())
        # Which way this pixel is travelling: 1 = horizontal, 0 = vertical.
        AXIS.append((np.abs(v[..., 0]) / np.maximum(
            np.abs(v[..., 0]) + np.abs(v[..., 1]), 1e-9)).ravel())

    e = np.concatenate(E); mag = np.concatenate(MAG); res = np.concatenate(RES)
    anis = np.concatenate(ANIS); ener = np.concatenate(ENER); ax = np.concatenate(AXIS)

    ms = np.quantile(mag, [0.40, 0.60]); mr = np.quantile(res, [0.40, 0.60])
    keep = (mag >= ms[0]) & (mag <= ms[1]) & (res >= mr[0]) & (res <= mr[1])
    print("%d moving triples from %s" % (len(trips), path))
    print("speed %.0f-%.0f px AND residual %.0f-%.0f px held fixed; %d pixels\n"
          % (ms[0], ms[1], mr[0], mr[1], int(keep.sum())))

    print("  %-34s %8s %8s %8s %10s"
          % ("candidate", "low", "mid", "high", "separation"))
    for name, x in (("edge-ness (aperture problem)", anis),
                    ("texture energy (contrast)", ener),
                    ("travelling horizontally", ax)):
        xk, ek = x[keep], e[keep]
        q = np.quantile(xk, [0.0, 0.33, 0.67, 1.0])
        cells = [ek[(xk >= lo) & (xk <= hi)].mean() for lo, hi in zip(q, q[1:])]
        print("  %-34s %s %9.2fx"
              % (name, " ".join("%8.4f" % c for c in cells),
                 max(cells) / max(min(cells), 1e-9)))
    print()

    # ---- the two axes, asked directly ----
    hor = ax[keep] > 0.8
    ver = ax[keep] < 0.2
    if hor.sum() > 1000 and ver.sum() > 1000:
        print("  %-34s %10.5f  (n=%d)"
              % ("moving mostly horizontally", e[keep][hor].mean(), int(hor.sum())))
        print("  %-34s %10.5f  (n=%d)"
              % ("moving mostly vertically", e[keep][ver].mean(), int(ver.sum())))
        print("  %-34s %9.1f%%"
              % ("vertical costs this much more",
                 100.0 * (e[keep][ver].mean() / e[keep][hor].mean() - 1.0)))
    print()
    print("  An edge-ness effect that survives BOTH controls is the aperture")
    print("  problem acting on its own, and it is the one thing here that could")
    print("  make the same screen region behave differently for two objects.")


if __name__ == "__main__":
    main()
