"""Which signal actually knows which motion hypothesis is right?

WHY THIS IS THE QUESTION AND NOT ANOTHER WEIGHTING. candidates.py measured the
ceiling: an oracle choosing perfectly among the five hypotheses the pipeline
can form takes 25% off the error at 4x and 36% at 2x. The best shippable
weighting takes 2.3%. The gap is not in how the weights are combined -- it is
that the signal being weighted barely knows the answer. SAD picks the oracle's
hypothesis 32% of the time against a 20% chance baseline.

So a better weighting of SAD is worth almost nothing, and a better SIGNAL is
worth up to ten times what the current change buys. That is what this file is
for.

WHY SAD IS WEAK, STRUCTURALLY. It is a photometric residual, and its magnitude
is dominated by how much texture the pixel has rather than by whether the
vector is right:

  in a FLAT region      every hypothesis fetches near-identical content, every
                        SAD is near zero, and argmin is decided by sensor and
                        compression noise. The choice does not matter there --
                        but the WEIGHT does, because a spuriously tiny SAD in a
                        flat area outvotes a legitimately larger one nearby.
  at a real EDGE        every hypothesis that is even slightly wrong produces a
                        large SAD, so the differences between candidates are
                        large but so is the noise floor, and a small mismatch
                        in a high-contrast region scores worse than a total
                        mismatch in a dull one.

Both failures are the same failure: SAD is not scale-free. The measures below
either normalise it or replace it with something that is.

WHAT IS SCORED. Two numbers per measure, and the second matters more:

  agreement   how often argmin of the measure equals argmin of the true error.
              Interpretable, with a known chance baseline, but it ignores
              magnitude -- being wrong between two nearly-identical hypotheses
              costs nothing.
  error       what the frame actually costs when the five predictions are
              weighted by that measure. This is the number that would ship.

    python reliability.py a.mp4 [b.mp4 ...] [--triples 16]
"""
import sys

import numpy as np

import candidates as CD
import carry as K
import partial as PA

EPS = 2.0 / 255.0


def census(gray, radius=2):
    """Sign of each pixel against its neighbours, packed as a float vector.

    A rank/census transform is invariant to any monotonic change in
    brightness, so a light source moving through the scene -- which RE9 does
    constantly, and which is half of what a residual on this footage is
    measuring -- does not register as a matching failure. This is the standard
    remedy in stereo matching for exactly that reason.
    """
    out = []
    for dy in (-radius, 0, radius):
        for dx in (-radius, 0, radius):
            if dx == 0 and dy == 0:
                continue
            out.append((np.roll(np.roll(gray, dy, 0), dx, 1) > gray).astype(np.float32))
    return np.stack(out, axis=-1)


def measures(v, A, C, ln, lo, grid, cen_n, cen_o, energy, phase=0.5):
    """Every reliability signal, for one hypothesis, at block resolution."""
    mn = grid - v * (1.0 - phase)
    mo = grid + v * phase
    a = K.sample(ln, mn)
    b = K.sample(lo, mo)
    sad = np.abs(a - b)

    out = {}
    out["SAD (shipped idea)"] = CD.box(sad, CD.BLOCK)

    # **Divided by how much texture is there to be wrong about.** A residual of
    # 0.02 across a flat wall is a total failure; the same residual across a
    # bookcase is a good match. Without this the two are indistinguishable.
    out["SAD / local texture"] = CD.box(sad, CD.BLOCK) / (CD.box(energy, CD.BLOCK) + EPS)

    # Census: how many of the eight neighbour comparisons disagree. Immune to
    # any monotonic brightness change.
    ca = np.stack([K.sample(cen_n[..., i], mn) for i in range(cen_n.shape[-1])], -1)
    cb = np.stack([K.sample(cen_o[..., i], mo) for i in range(cen_o.shape[-1])], -1)
    out["census (light-invariant)"] = CD.box(np.abs(ca - cb).mean(axis=-1), CD.BLOCK)

    # **How CONSISTENTLY wrong, not how wrong.** A vector that is right for a
    # surface produces a small residual everywhere on it. A vector that is
    # wrong but happens to land on similar content produces a small mean and a
    # large spread. The literature calls this the variance of interpolation
    # error along the trajectory.
    out["residual spread"] = np.sqrt(np.maximum(
        CD.box(sad * sad, CD.BLOCK) - CD.box(sad, CD.BLOCK) ** 2, 0.0))

    # Bilateral symmetry: does this vector explain the two ENDS equally? A
    # vector crossing an occlusion boundary explains one end and not the other,
    # and the asymmetry shows even when the mean residual does not.
    fwd = np.abs(K.sample(ln, mn) - K.sample(lo, mn))
    bwd = np.abs(K.sample(ln, mo) - K.sample(lo, mo))
    out["endpoint asymmetry"] = CD.box(np.abs(fwd - bwd), CD.BLOCK)

    out["texture-normalised census"] = (out["census (light-invariant)"]
                                        * (1.0 + out["SAD / local texture"]))
    return out


def run(path, limit):
    trips = [t for t in PA.real_triples(path)
             if PA.X.rms(t[0], t[2]) > 0.05][:limit]
    names, agree, err = None, {}, {}

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

        fld = CD.blocks(v).astype(np.float32)
        dom = np.array([np.mean(fld[..., 0]), np.mean(fld[..., 1])], dtype=np.float32)
        ln, lo = K.luma(C), K.luma(A)
        gy, gx = np.gradient(ln)
        energy = np.hypot(gx, gy)
        cen_n, cen_o = census(ln), census(lo)

        vs, ws = CD.four(fld, (H, W))
        hyp = vs + [np.broadcast_to(dom, (H, W, 2))]
        preds = [CD.predict(x, A, C, grid) for x in hyp]

        # The truth, for the agreement score and nothing else.
        stack = np.stack(preds, axis=0)
        oracle = np.argmin(np.linalg.norm(stack - B[None], axis=-1), axis=0)

        per = [measures(x, A, C, ln, lo, grid, cen_n, cen_o, energy) for x in hyp]
        if names is None:
            names = list(per[0].keys())
            agree = {n: [] for n in names}
            err = {n: [] for n in names}

        wts = [w[..., 0] for w in ws] + [0.25 * np.ones((H, W), np.float32)]
        for n in names:
            m = np.stack([p[n] for p in per], axis=0)
            agree[n].append(float((np.argmin(m, axis=0) == oracle).mean() * 100.0))
            iw = [wt / (mi + EPS) for wt, mi in zip(wts, m)]
            tot = sum(iw) + 1e-9
            out = sum((s / tot)[..., None] * p for s, p in zip(iw, preds))
            err[n].append(float(np.sqrt(((out - B) ** 2).mean())))

        base = sum(w[..., 0][..., None] * p for w, p in zip(ws, preds[:4]))
        err.setdefault("_base", []).append(float(np.sqrt(((base - B) ** 2).mean())))
    return trips, names, agree, err


def main():
    limit = 16
    if "--triples" in sys.argv:
        limit = int(sys.argv[sys.argv.index("--triples") + 1])
    paths = [a for a in sys.argv[1:] if not a.startswith("--") and a != str(limit)]
    if not paths:
        sys.exit(__doc__)

    for path in paths:
        trips, names, agree, err = run(path, limit)
        b = np.mean(err["_base"])
        print("\n=== %s (%d triples) ===" % (path, len(trips)))
        print("  %-30s %12s %10s %10s"
              % ("reliability signal", "agreement", "error", "vs shipped"))
        print("  %-30s %11s%% %10.5f %9s" % ("(shipped OBMC window)", "-", b, "0.0%"))
        for n in sorted(names, key=lambda k: np.mean(err[k])):
            e = np.mean(err[n])
            print("  %-30s %11.0f%% %10.5f %8.1f%%"
                  % (n, np.mean(agree[n]), e, 100.0 * (e / b - 1.0)))
    print()
    print("  Chance agreement is 20%. SAD reaches about 32%, and the oracle")
    print("  that agrees 100% of the time is worth -25%. A signal that lifts")
    print("  agreement substantially is worth building on; one that only")
    print("  reshuffles the error is not.")


if __name__ == "__main__":
    main()
