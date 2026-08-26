"""Where a region moves differently, is compensation still worth applying?

WHY THIS DECIDES WHETHER THERE IS A FIX. parallax.py establishes the fault:
with local speed held fixed, error runs 0.0266 to 0.0669 across bands of
residual speed -- how differently a pixel moves from the frame's dominant
motion. That is 2.52x, and it is the only candidate tested that RISES under the
speed control rather than collapsing, so it is acting on its own rather than
standing in for how fast the scene is going.

Establishing a fault is not the same as having somewhere to go. The pipeline
can only do three things at a pixel: warp it, blend the two frames, or hold the
older one. If the warp still beats a blend in the high-residual bands, then the
error there is the best available answer and falling back would make the
picture worse -- the fault would be real and irreducible. Only if the blend
wins there is there a decision worth changing.

The `carry` gate already implements exactly this fallback, but it keys on a
PHOTOMETRIC residual -- how well the compensated fetch matches -- and gatefix.py
found every reweighting of it gains 0.2% at best. Motion disagreement is a
different signal, available in the same field, and it has not been tried.

Both captures are scored, because a speed-conditioned gate developed on one of
them gained 2.8% and lost 0.7% on the other. One clip is not evidence.

    python residual.py a.mp4 [b.mp4 ...] [--triples 20]
"""
import sys

import numpy as np

import carry as K
import gatefix as G
import partial as PA


def measure(path, limit):
    trips = [t for t in PA.real_triples(path)
             if PA.X.rms(t[0], t[2]) > 0.05][:limit]
    W_, B_, H_, R_ = [], [], [], []
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
        dom = np.array([np.median(v[..., 0]), np.median(v[..., 1])], dtype=np.float32)
        W_.append(np.sqrt(((out - B) ** 2).mean(axis=2)).ravel())
        B_.append(np.sqrt((((A + C) * 0.5 - B) ** 2).mean(axis=2)).ravel())
        H_.append(np.sqrt(((A - B) ** 2).mean(axis=2)).ravel())
        R_.append(np.linalg.norm(v - dom, axis=-1).ravel())
    return (np.concatenate(W_), np.concatenate(B_),
            np.concatenate(H_), np.concatenate(R_), len(trips))


def main():
    limit = 20
    if "--triples" in sys.argv:
        limit = int(sys.argv[sys.argv.index("--triples") + 1])
    paths = [a for a in sys.argv[1:] if not a.startswith("--") and a != str(limit)]
    if not paths:
        sys.exit(__doc__)

    for path in paths:
        w, b, h, r, n = measure(path, limit)
        print("\n=== %s (%d triples) ===" % (path, n))
        print("  %-20s %8s %9s %9s %9s %11s"
              % ("residual |v-dom|", "share", "warp", "blend", "hold", "warp wins"))
        q = np.quantile(r, [0.0, 0.5, 0.75, 0.9, 0.97, 1.0])
        for lo, hi in zip(q, q[1:]):
            m = (r >= lo) & (r <= hi)
            if m.sum() < 1000:
                continue
            print("  %-20s %7.1f%% %9.4f %9.4f %9.4f %10.0f%%"
                  % ("%.0f-%.0f px" % (lo, hi), 100.0 * m.mean(),
                     w[m].mean(), b[m].mean(), h[m].mean(),
                     100.0 * (w[m] < b[m]).mean()))
    print()
    print("  If the warp still beats the blend in the top band, the damage in")
    print("  differently-moving regions is the best answer available and there")
    print("  is nothing to switch to. If the blend wins there, the pipeline is")
    print("  choosing the worse of two answers it already has in hand.")


if __name__ == "__main__":
    main()
