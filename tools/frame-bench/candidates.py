"""If one vector per block cannot describe two depths, offer several.

WHAT THIS FOLLOWS FROM. parallax.py established the only mechanism that
survives a speed control: a region moving differently from the frame's dominant
motion carries 2.5x the error at identical local speed. residual.py then
established that there is nothing to switch TO -- the warp already beats both
the blend and the held frame in every residual band on both captures, so a
fallback would make each one worse. The damage is not a wrong choice between
answers the pipeline holds. It is that the answer it holds is single-valued
where the scene is not.

An 8x8 block spanning the edge of near geometry during a camera yaw contains
two motions. The matcher returns one vector, and InterpolateMaterial then
blends the four surrounding block vectors under overlapping windows -- in its
own words, "nothing is discarded and nothing is chosen". That blend is correct
where the field is smooth and is precisely wrong here: the average of the near
motion and the far motion matches neither surface.

WHY THIS IS CHEAP TO SHIP IF IT WORKS. The four vectors are ALREADY fetched,
and predict() is already evaluated four times. What changes is only how the
four results are combined -- weights, not fetches.

WHAT MUST BE MEASURED BESIDES THE MEAN, AND WHY. SignMaterial documents this
exact idea failing: a per-pixel binary choice between two hypotheses, scored on
a cost that is frequently a near-tie, flips on image noise and produced
hard-edged black speckle in detailed regions while every average-based
diagnostic read clean. So a hard pick is scored here alongside a soft one, and
both are scored for SPECKLE -- how much of the error sits at the highest
spatial frequency, where a neighbouring-pixel disagreement lives -- as well as
for mean error. A candidate that wins on the mean and raises speckle is that
bug returning.

  baseline            the shipped OBMC blend of four block vectors
  hard pick           the best-matching of the four, per pixel
  soft pick           the four, weighted by how well each matches
  soft + dominant     the four plus the frame's own dominant vector, which is
                      the far surface's motion during a rotation and is the
                      hypothesis a purely local set cannot contain

    python candidates.py a.mp4 [b.mp4 ...] [--triples 16]
"""
import sys

import numpy as np

import carry as K
import partial as PA

BLOCK = 8


def blocks(v):
    H, W = v.shape[:2]
    gh, gw = H // BLOCK, W // BLOCK
    return v[:gh * BLOCK, :gw * BLOCK].reshape(gh, BLOCK, gw, BLOCK, 2).mean(axis=(1, 3))


def four(field, shape):
    """The four block vectors around each pixel, and their bilinear weights.

    This is the addressing InterpolateMaterial performs: the pixel sits in a
    2x2 neighbourhood of block centres, and the weights are the standard
    overlapped-block window.
    """
    H, W = shape
    gh, gw = field.shape[:2]
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    bx = xx / BLOCK - 0.5
    by = yy / BLOCK - 0.5
    x0 = np.clip(np.floor(bx).astype(np.int32), 0, gw - 1)
    y0 = np.clip(np.floor(by).astype(np.int32), 0, gh - 1)
    x1 = np.clip(x0 + 1, 0, gw - 1)
    y1 = np.clip(y0 + 1, 0, gh - 1)
    fx = np.clip(bx - x0, 0.0, 1.0)[..., None]
    fy = np.clip(by - y0, 0.0, 1.0)[..., None]
    vs = [field[y0, x0], field[y0, x1], field[y1, x0], field[y1, x1]]
    ws = [(1 - fx) * (1 - fy), fx * (1 - fy), (1 - fx) * fy, fx * fy]
    return vs, ws


def box(a, k):
    """Mean over a k x k neighbourhood, by summed-area table."""
    p = np.pad(a, k, mode="edge")
    c = np.cumsum(np.cumsum(p, axis=0), axis=1)
    s = (c[k * 2:, k * 2:] - c[:-k * 2, k * 2:]
         - c[k * 2:, :-k * 2] + c[:-k * 2, :-k * 2]) / float(k * 2) ** 2
    return s[:a.shape[0], :a.shape[1]]


def cost_of(v, ln, lo, grid, phase=0.5):
    mn = grid - v * (1.0 - phase)
    mo = grid + v * phase
    return np.abs(K.sample(ln, mn) - K.sample(lo, mo))


def predict(v, A, C, grid, phase=0.5):
    return ((1.0 - phase) * K.sample(A, grid + v * phase)
            + phase * K.sample(C, grid - v * (1.0 - phase)))


def speckle(err):
    """Share of the error living at the highest spatial frequency.

    A neighbouring-pixel disagreement -- one pixel choosing hypothesis A while
    the pixel beside it chooses B -- shows here and nowhere else. A smoothly
    wrong region has a large error and almost no speckle.
    """
    d = np.abs(np.diff(err, axis=1)).mean() + np.abs(np.diff(err, axis=0)).mean()
    return float(d * 0.5)


def run(path, limit):
    trips = [t for t in PA.real_triples(path)
             if PA.X.rms(t[0], t[2]) > 0.05][:limit]
    names = ["baseline (shipped OBMC)", "hard pick", "soft pick", "soft + dominant",
             "soft + dominant, block-smoothed", "per-block fit (implementable)",
             "per-block fit, 3x3 on the field"]
    acc = {n: {"all": [], "hot": [], "spk": []} for n in names}

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

        fld = blocks(v).astype(np.float32)
        dom = np.array([np.median(fld[..., 0]), np.median(fld[..., 1])], dtype=np.float32)
        rb = np.linalg.norm(fld - dom, axis=-1)
        resid = np.repeat(np.repeat(rb, BLOCK, 0), BLOCK, 1)[:H, :W]
        hot = resid > np.percentile(resid, 85)

        ln, lo = K.luma(C), K.luma(A)
        fldp = np.repeat(np.repeat(fld, BLOCK, 0), BLOCK, 1)[:H, :W]
        vs, ws = four(fld, (H, W))
        costs = [cost_of(x, ln, lo, grid) for x in vs]
        preds = [predict(x, A, C, grid) for x in vs]

        outs = {}
        # **The baseline blends the four PREDICTIONS, not the four vectors,
        # and the first version of this file blended the vectors.** The shader
        # is explicit: q0..q3 are four separate predict() calls and `shown` is
        # their weighted sum. Averaging the vectors first and warping once is a
        # different and strictly worse operation -- it is a single fetch along
        # a direction no block reported -- so scoring against it credited these
        # candidates with beating something the pipeline never does. Every
        # improvement measured that way was inflated by the difference between
        # the real baseline and the straw one.
        outs["baseline (shipped OBMC)"] = sum(w[..., 0][..., None] * p
                                              for w, p in zip(ws, preds))

        cs = np.stack(costs, axis=0)
        pick = np.argmin(cs, axis=0)
        outs["hard pick"] = np.take_along_axis(
            np.stack(preds, axis=0), pick[None, ..., None], axis=0)[0]

        # Temperature is the shader's own noise floor: differences below two
        # 8-bit steps are not evidence, so hypotheses that close stay mixed.
        #
        # **Costs are shifted by the best of them before exponentiating, and
        # the first version of this file did not do it.** With T at 2/255, a
        # raw cost of 0.05 gives exp(-6.4) and one of 0.3 gives exp(-38); on
        # harder footage every candidate underflows together, the normaliser
        # collapses to its epsilon, and the weights become whatever floating
        # point noise survives. That is exactly what the numbers showed -- the
        # soft variants beat the baseline by 5-9% on the 2x capture and lost to
        # it by 17% on the 4x one, and the difference between the clips is
        # simply that the second is three times harder, so it underflowed and
        # the first did not. Subtracting the minimum is the standard softmax
        # stabilisation and changes no ratio between weights.
        T = 2.0 / 255.0
        dcost = cost_of(dom, ln, lo, grid)
        dpred = predict(dom, A, C, grid)

        floor4 = np.minimum.reduce(costs)
        sw = [w[..., 0] * np.exp(-(c - floor4) / T) for w, c in zip(ws, costs)]
        tot = sum(sw) + 1e-9
        outs["soft pick"] = sum((s / tot)[..., None] * p for s, p in zip(sw, preds))

        floor5 = np.minimum(floor4, dcost)
        sw2 = ([w[..., 0] * np.exp(-(c - floor5) / T) for w, c in zip(ws, costs)]
               + [0.25 * np.exp(-(dcost - floor5) / T)])
        tot2 = sum(sw2) + 1e-9
        outs["soft + dominant"] = sum((s / tot2)[..., None] * p
                                      for s, p in zip(sw2, preds + [dpred]))

        # **The same weights, decided at the resolution the hypotheses have.**
        # Every variant above gains about 1% of mean error on the harder
        # capture and raises speckle by 9 to 15%, which is the trade
        # SignMaterial documents: a per-pixel choice on a near-tie cost flips
        # on image noise, and the flip lives at the highest spatial frequency
        # where the eye is most sensitive and a frame average is blind.
        #
        # The candidates being chosen between are BLOCK vectors. There is no
        # information in the field at finer than block scale, so a selection
        # that varies from one pixel to the next is varying on noise by
        # construction. Averaging the costs over a block first keeps the
        # choice and removes the flicker, and costs one blur rather than any
        # extra fetch.
        smoothed = [box(c, BLOCK) for c in costs] + [box(dcost, BLOCK)]
        fl = np.minimum.reduce(smoothed)
        sw3 = [w[..., 0] * np.exp(-(c - fl) / T) for w, c in zip(ws, smoothed[:4])]
        sw3 = sw3 + [0.25 * np.exp(-(smoothed[4] - fl) / T)]
        tot3 = sum(sw3) + 1e-9
        outs["soft + dominant, block-smoothed"] = sum(
            (s / tot3)[..., None] * p for s, p in zip(sw3, preds + [dpred]))

        # **The form that can actually be written, which is not the form above.**
        # Block-smoothing a per-pixel cost needs a neighbourhood the fragment
        # shader has no way to gather. What it CAN have is a small pass at
        # field resolution -- 160x90, four orders of magnitude below the frame
        # -- where each block scores its own vector against its own pixels and
        # writes one number. The interpolation then reads those four numbers
        # alongside the four vectors it already fetches, and no extra work
        # happens at frame resolution at all.
        #
        # That is a DIFFERENT quantity from the smoothed per-pixel cost: it
        # asks "does this block's vector explain this block", not "does this
        # block's vector explain the pixel I am shading". They agree in the
        # interior of a surface and diverge exactly at the boundaries this is
        # meant to fix, so it has to be measured rather than assumed.
        own = np.abs(K.sample(ln, grid - fldp * 0.5) - K.sample(lo, grid + fldp * 0.5))
        fit = blocks(np.stack([own, own], axis=-1))[..., 0]
        fvs, _ = four(fit[..., None], (H, W))
        fdom = box(dcost, BLOCK)
        fl2 = np.minimum(np.minimum.reduce([f[..., 0] for f in fvs]), fdom)
        sw4 = [w[..., 0] * np.exp(-(f[..., 0] - fl2) / T) for w, f in zip(ws, fvs)]
        sw4 = sw4 + [0.25 * np.exp(-(fdom - fl2) / T)]
        tot4 = sum(sw4) + 1e-9
        outs["per-block fit (implementable)"] = sum(
            (s / tot4)[..., None] * p for s, p in zip(sw4, preds + [dpred]))

        # **One 3x3 blur on the fit map, because a per-block number is
        # piecewise constant and that is visible.** The variant above puts a
        # weight discontinuity on every block boundary -- the fit is one value
        # across a block and a different value across the next -- which is
        # blockiness by construction and is what its residual speckle is. The
        # field is 160x90, so a 3x3 blur there costs eight texture reads on a
        # texture four orders of magnitude smaller than the frame, and
        # MedianMaterial already does exactly this neighbourhood on exactly
        # this texture.
        fit3 = box(fit, 1)
        fvs3, _ = four(fit3[..., None], (H, W))
        fl3 = np.minimum(np.minimum.reduce([f[..., 0] for f in fvs3]), fdom)
        sw5 = [w[..., 0] * np.exp(-(f[..., 0] - fl3) / T) for w, f in zip(ws, fvs3)]
        sw5 = sw5 + [0.25 * np.exp(-(fdom - fl3) / T)]
        tot5 = sum(sw5) + 1e-9
        outs["per-block fit, 3x3 on the field"] = sum(
            (s / tot5)[..., None] * p for s, p in zip(sw5, preds + [dpred]))

        for n in names:
            err = np.sqrt(((outs[n] - B) ** 2).mean(axis=2))
            acc[n]["all"].append(float(err.mean()))
            acc[n]["hot"].append(float(err[hot].mean()))
            acc[n]["spk"].append(speckle(err))
    return trips, acc, names


def main():
    limit = 16
    if "--triples" in sys.argv:
        limit = int(sys.argv[sys.argv.index("--triples") + 1])
    paths = [a for a in sys.argv[1:] if not a.startswith("--") and a != str(limit)]
    if not paths:
        sys.exit(__doc__)

    for path in paths:
        trips, acc, names = run(path, limit)
        base = np.mean(acc[names[0]]["all"])
        bhot = np.mean(acc[names[0]]["hot"])
        print("\n=== %s (%d triples) ===" % (path, len(trips)))
        print("  %-26s %9s %9s %9s %9s %9s"
              % ("field combination", "overall", "vs base", "parallax", "vs base",
                 "speckle"))
        for n in names:
            a = np.mean(acc[n]["all"])
            h = np.mean(acc[n]["hot"])
            print("  %-26s %9.5f %8.1f%% %9.5f %8.1f%% %9.5f"
                  % (n, a, 100.0 * (a / base - 1.0), h, 100.0 * (h / bhot - 1.0),
                     np.mean(acc[n]["spk"])))
    print()
    print("  `parallax` is the top 15% of blocks by how differently they move")
    print("  from the frame -- the case this is for. `speckle` is the check")
    print("  SignMaterial's black-speckle bug demands: a per-pixel choice that")
    print("  wins on the mean and raises speckle is that bug returning.")


if __name__ == "__main__":
    main()
