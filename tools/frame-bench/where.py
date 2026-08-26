"""What do the damaged patches sit on?

WHY THE SEARCH MOVED HERE. Two whole-frame explanations have now been closed by
measurement rather than by argument. `harm` turned out to be a near-tie counter:
scored on this footage a plain blend, which applies no motion at all, is charged
with harming 30.8% of barely-moved pixels while both controls read exactly 0.0.
And the `carry` gate, which partial.py showed keeps only 73% of the correction,
turns out to be keeping the right 73%: every looser curve scored against the
guest's own frames gains 0.2% overall and LOSES on moving pixels.

Meanwhile damage.py, which resolves invented content per pixel instead of
averaging it, finds up to 271 of 880 patches ruined in a single 4x frame while
the frame mean sits at 7%. The fault is local. So the question is not how much
is wrong, it is what the wrong places have in common.

WHAT IS TESTED, AND WHY EACH CANDIDATE IS A REAL MECHANISM.

  image gradient      the aperture problem. A block on a long straight edge
                      has no information along it; every candidate scores the
                      same and the tie is broken arbitrarily.
  flow magnitude      the search window. Beyond its reach the matcher cannot
                      find the true match at all, and returns something else.
  flow divergence     occlusion. Where the field spreads, background is being
                      uncovered and exists in only one of the two frames; where
                      it converges, content is being covered. A bilateral fetch
                      has no valid source for either.
  flow gradient       motion boundaries. One 8x8 block straddling two objects
                      moving differently gets one vector, and it is wrong for
                      both -- and then ten anchored median passes propagate
                      whichever side won.

Each is scored against the reconstruction error of a transcription of the
shipped shader, on the frames the guest actually drew, using a field that was
validated first. The separation ratio -- worst band over best band -- is what
ranks them. A mechanism that does not separate is not the cause, whatever it
sounds like.

    python where.py recording.mp4 [--triples 30]
"""
import sys

import numpy as np

import carry as K
import gatefix as G
import partial as PA


def divergence(v):
    dudx = np.gradient(v[..., 0], axis=1)
    dvdy = np.gradient(v[..., 1], axis=0)
    return dudx + dvdy


def grad_mag(a):
    gy, gx = np.gradient(a)
    return np.hypot(gx, gy)


def main():
    limit = 30
    if "--triples" in sys.argv:
        limit = int(sys.argv[sys.argv.index("--triples") + 1])
    path = [a for a in sys.argv[1:] if not a.startswith("--") and a != str(limit)][0]

    trips = [t for t in PA.real_triples(path)
             if PA.X.rms(t[0], t[2]) > 0.05][:limit]
    if len(trips) < 6:
        sys.exit("not enough moving real triples")

    feats = {"image gradient": [], "flow magnitude": [],
             "|flow divergence|": [], "flow gradient": []}
    errs = []
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
        mag = np.linalg.norm(v, axis=-1)
        errs.append(err.ravel())
        feats["image gradient"].append(grad_mag(K.luma(C)).ravel())
        feats["flow magnitude"].append(mag.ravel())
        feats["|flow divergence|"].append(np.abs(divergence(v)).ravel())
        feats["flow gradient"].append(grad_mag(mag).ravel())

    err = np.concatenate(errs)
    print("%d moving triples from %s; %d pixels\n" % (len(trips), path, err.size))
    print("  reconstruction error, split by decile of each candidate\n")
    print("  %-22s %8s %8s %8s %8s %8s %10s"
          % ("candidate", "d1 low", "d3", "d5", "d8", "d10 high", "separation"))
    rank = []
    for name, parts in feats.items():
        x = np.concatenate(parts)
        q = np.quantile(x, [0.0, 0.2, 0.4, 0.5, 0.7, 0.9, 1.0])
        cells = []
        for lo, hi in zip(q, q[1:]):
            m = (x >= lo) & (x <= hi)
            cells.append(err[m].mean() if m.sum() > 100 else np.nan)
        sep = np.nanmax(cells) / max(np.nanmin(cells), 1e-9)
        rank.append((sep, name))
        pick = [cells[0], cells[2], cells[3], cells[4], cells[5]]
        print("  %-22s %s %9.2fx"
              % (name, " ".join("%8.4f" % c for c in pick), sep))
    print()
    rank.sort(reverse=True)
    print("  ranked by how far they separate good pixels from bad:")
    for sep, name in rank:
        print("    %-24s %6.2fx" % (name, sep))
    # ---- disentangle, because the three motion candidates are one story ----
    #
    # **Fast motion CAUSES large divergence and large flow gradients**, so all
    # three separating at about the same strength is one mechanism seen three
    # ways, or three mechanisms, and the ranking above cannot tell which. Fix
    # the speed and ask the other two again: whatever still separates inside a
    # band of constant displacement is acting on its own.
    mag = np.concatenate(feats["flow magnitude"])
    band = np.quantile(mag, [0.45, 0.55])
    inband = (mag >= band[0]) & (mag <= band[1])
    print("  HOLDING SPEED FIXED (%.1f-%.1f px, %d pixels)\n"
          % (band[0], band[1], int(inband.sum())))
    print("  %-22s %8s %8s %8s %10s"
          % ("candidate", "low", "mid", "high", "separation"))
    for name in ("|flow divergence|", "flow gradient", "image gradient"):
        x = np.concatenate(feats[name])[inband]
        e = err[inband]
        q = np.quantile(x, [0.0, 0.33, 0.67, 1.0])
        cells = []
        for lo, hi in zip(q, q[1:]):
            m = (x >= lo) & (x <= hi)
            cells.append(e[m].mean() if m.sum() > 100 else np.nan)
        print("  %-22s %s %9.2fx"
              % (name, " ".join("%8.4f" % c for c in cells),
                 np.nanmax(cells) / max(np.nanmin(cells), 1e-9)))
    print()
    print("  A candidate that keeps its separation here is a mechanism in its")
    print("  own right. One that collapses towards 1.0x was only ever standing")
    print("  in for how fast the scene was moving.")
    print()
    print("  The top one is where the damage lives. A candidate near 1.0x does")
    print("  not distinguish damaged pixels from clean ones and is not the")
    print("  mechanism, however plausible it sounds.")


if __name__ == "__main__":
    main()
