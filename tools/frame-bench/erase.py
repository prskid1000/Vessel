"""Do the ten median passes delete the motion of a differently-moving region?

THE CHAIN THIS TESTS. MedianMaterial states its own purpose: "a single 8x8
island pointing somewhere else is the search losing a tie, not an object moving
on its own". It runs ten anchored 3x3 passes, so influence propagates about ten
blocks -- roughly 80 pixels at 8x8. Any region narrower than that whose motion
differs from its surroundings is outvoted by construction, and the filter has
no way to tell the case it was built for from a real object, or from near
geometry sweeping past a far wall during a camera rotation.

island.py already argued this for a 32 px cursor on a flat desktop. That is a
model of the case, not the case: it is one small object on a background with no
texture, and two changes shipped on it came back worse from the device. This
runs the real filter over the real field of real gameplay frames.

HOW THE FIELD IS BUILT SO THAT THE FILTER IS THE ONLY VARIABLE. Dense DIS flow
is validated against the guest's own middle frame, then averaged down to one
vector per 8x8 block -- the resolution the hardware matcher actually delivers.
That block field is the input. The same field then goes through
consensus.vector_median, which is the shipped ten-pass filter transcribed.
Reconstruct the guest's frame from each, and the difference between them is the
filter and nothing else: same flow, same block size, same warp, same gate.

WHAT SPLITS THE ANSWER. Error is reported inside bands of residual speed --
|v - dominant|, how differently a pixel moves from the frame as a whole. If the
filter helps where residual is low and hurts where it is high, it is doing
exactly its stated job AND the damage it was built to prevent, and the fix is
not to remove it but to make it stop at region boundaries.

    python erase.py recording.mp4 [--triples 20]
"""
import sys

import numpy as np

import carry as K
import consensus
import gatefix as G
import partial as PA

BLOCK = 8


def to_blocks(v):
    """One vector per 8x8 block, as the hardware matcher delivers."""
    H, W = v.shape[:2]
    gh, gw = H // BLOCK, W // BLOCK
    return v[:gh * BLOCK, :gw * BLOCK].reshape(gh, BLOCK, gw, BLOCK, 2).mean(axis=(1, 3))


def to_pixels(f, shape):
    return np.repeat(np.repeat(f, BLOCK, axis=0), BLOCK, axis=1)[:shape[0], :shape[1]]


def main():
    limit = 20
    if "--triples" in sys.argv:
        limit = int(sys.argv[sys.argv.index("--triples") + 1])
    path = [a for a in sys.argv[1:] if not a.startswith("--") and a != str(limit)][0]

    trips = [t for t in PA.real_triples(path)
             if PA.X.rms(t[0], t[2]) > 0.05][:limit]
    if len(trips) < 6:
        sys.exit("not enough moving real triples")

    shrink, errs = [], {"unfiltered blocks": [], "after 10 median passes": []}
    resid_all = []
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

        raw = to_blocks(v).astype(np.float32)
        filt = consensus.vector_median(raw, raw, passes=10)

        dom = np.array([np.median(raw[..., 0]), np.median(raw[..., 1])], dtype=np.float32)
        r_raw = np.linalg.norm(raw - dom, axis=-1)
        r_filt = np.linalg.norm(filt - dom, axis=-1)
        hot = r_raw > np.percentile(r_raw, 80)
        if hot.any():
            shrink.append(float(r_filt[hot].mean() / max(r_raw[hot].mean(), 1e-6)))

        for name, fld in (("unfiltered blocks", raw), ("after 10 median passes", filt)):
            vp = to_pixels(fld, (H, W))
            out, _ = G.reconstruct(A, C, vp, lambda r: K.smoothstep(0.30, 0.70, r))
            errs[name].append(np.sqrt(((out - B) ** 2).mean(axis=2)).ravel())
        resid_all.append(to_pixels(r_raw, (H, W)).ravel())

    print("%d moving triples from %s\n" % (len(trips), path))
    print("  %-46s %6.2f" % ("residual motion kept in the top fifth of blocks",
                             float(np.mean(shrink))))
    print("  %-46s %6.0f%%\n" % ("  ... so this much of it was deleted",
                                 100.0 * (1.0 - float(np.mean(shrink)))))

    resid = np.concatenate(resid_all)
    a = np.concatenate(errs["unfiltered blocks"])
    b = np.concatenate(errs["after 10 median passes"])
    q = np.quantile(resid, [0.0, 0.5, 0.8, 0.95, 1.0])
    print("  error against the guest's own frame, by how differently the pixel moves\n")
    print("  %-28s %10s %10s %10s"
          % ("residual |v - dominant|", "unfiltered", "filtered", "filter helps"))
    for lo, hi in zip(q, q[1:]):
        m = (resid >= lo) & (resid <= hi)
        if m.sum() < 1000:
            continue
        print("  %-28s %10.5f %10.5f %9.1f%%"
              % ("%.0f-%.0f px" % (lo, hi), a[m].mean(), b[m].mean(),
                 100.0 * (1.0 - b[m].mean() / max(a[m].mean(), 1e-9))))
    print()
    print("  %-28s %10.5f %10.5f %9.1f%%"
          % ("whole frame", a.mean(), b.mean(),
             100.0 * (1.0 - b.mean() / max(a.mean(), 1e-9))))
    print()
    print("  A positive number means the filter improved that band. If it is")
    print("  positive where motion agrees and negative where it disagrees, the")
    print("  filter is both earning its place and causing this artefact, and")
    print("  the answer is a boundary it will not cross -- not deleting it.")


if __name__ == "__main__":
    main()
