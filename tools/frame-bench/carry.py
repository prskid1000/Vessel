"""How much of the motion correction does `carry` throw away?

THE MEASUREMENT THAT PROVOKED THIS. partial.py, using nothing but arithmetic on
the recording, finds that a shipped frame contains 73% of the correction a real
guest frame contains -- consistently, on 2x and 4x, across every separation
band. A content-dependent fault would vary with content. A fixed fraction says
something is multiplying the correction by a constant.

InterpolateMaterial does exactly that, in one line:

    ratio = fitStill / (fitStill + fitMoving + 1/2550)
    carry = smoothstep(0.3, 0.7, ratio)
    shown = mix(still, shown, carry)

`fitStill` is how badly an UNCOMPENSATED read matches; `fitMoving` is how badly
the compensated one does. The compensated read only earns full credit at
ratio >= 0.7, which means it must be 2.33 times better than doing nothing. One
that is merely twice as good keeps 93%. One that is equally good keeps HALF --
and is therefore dragged half way back to the cross-fade it just beat.

WHY THIS FILE CAN BE TRUSTED WHERE locate.py AND regime.py COULD NOT. Those
used bench.estimate, a hand-rolled block matcher that returns far-away matches
for flat regions -- 19% of blocks at 100+ px on a static scene -- so every
conclusion drawn about warping was drawn from a field the device never had.
This uses OpenCV's DIS flow, and, more importantly, VALIDATES it before using
it: the field must reconstruct the guest's own middle frame decisively better
than a blend does, on this footage, or the run aborts. A field that cannot beat
a blend has no standing to say what the shader's gate does to a good vector.

WHAT IS REPORTED. The mean of `carry` over pixels that actually moved is the
share of the correction that survives the gate. If it lands near 73%, the gate
is the leak and the 27% is not lost in the matcher, the median passes, or the
warp -- it is discarded on purpose, by a curve, after being computed correctly.

Candidate replacements are scored on the same pixels, so the cost of each is
visible before anything is built.

    python carry.py recording.mp4 [--triples 40]
"""
import sys

import numpy as np

import partial as PA

LUMA = np.array([0.299, 0.587, 0.114], dtype=np.float32)


def luma(img):
    return (img * LUMA).sum(axis=2)


def flow(a, b):
    """Dense flow from a to b, at the luma resolution the shader works in."""
    import cv2
    ga = (luma(a) * 255).astype(np.uint8)
    gb = (luma(b) * 255).astype(np.uint8)
    dis = cv2.DISOpticalFlow_create(cv2.DISOPTICAL_FLOW_PRESET_MEDIUM)
    return dis.calc(ga, gb, None)


def sample(img, xy):
    """Nearest-neighbour fetch; the shader bilinearly filters, this does not.

    Good enough here because what is being measured is the SHAPE of a gate
    against a residual, and a sub-pixel difference in the residual moves the
    gate by far less than the effect being looked for.
    """
    H, W = img.shape[:2]
    x = np.clip(np.round(xy[..., 0]).astype(np.int32), 0, W - 1)
    y = np.clip(np.round(xy[..., 1]).astype(np.int32), 0, H - 1)
    return img[y, x]


def gate_inputs(A, C, v, phase=0.5):
    """fitStill and fitMoving, exactly as InterpolateMaterial computes them."""
    lo, ln = luma(A), luma(C)
    H, W = lo.shape
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    grid = np.stack([xx, yy], axis=-1)
    mn = grid - v * (1.0 - phase)
    mo = grid + v * phase
    fit_still = np.abs(ln - lo)
    fit_moving = np.abs(sample(ln, mn) - sample(lo, mo))
    return fit_still, fit_moving


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def main():
    limit = 40
    if "--triples" in sys.argv:
        limit = int(sys.argv[sys.argv.index("--triples") + 1])
    path = [a for a in sys.argv[1:] if not a.startswith("--") and a != str(limit)][0]

    trips = [t for t in PA.real_triples(path)
             if PA.X.rms(t[0], t[2]) > 0.05][:limit]
    if len(trips) < 6:
        sys.exit("not enough moving real triples")

    # ---- validate the field before trusting a single number it produces ----
    warp_err, blend_err, held = [], [], []
    fields = []
    for A, B, C in trips:
        f = flow(A, C)
        # The shader's v is the negative of a prev->next flow: it reads the
        # newer frame at vUV - v(1-t) and the older at vUV + v*t, so a scene
        # moving right needs v pointing left. Verified rather than assumed --
        # both signs are scored and the better one is reported below.
        best, bv = None, None
        H, W = f.shape[:2]
        yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
        grid = np.stack([xx, yy], axis=-1)
        for cand in (-f, f):
            mn = grid - cand * 0.5
            mo = grid + cand * 0.5
            rec = 0.5 * sample(C, mn) + 0.5 * sample(A, mo)
            e = float(np.sqrt(((rec - B) ** 2).mean()))
            if best is None or e < best:
                best, bv = e, cand
        warp_err.append(best)
        blend_err.append(PA.X.rms((A + C) * 0.5, B))
        held.append(PA.X.rms(A, B))
        fields.append(bv)

    w, b, h = np.mean(warp_err), np.mean(blend_err), np.mean(held)
    print("%d moving triples from %s\n" % (len(trips), path))
    print("  FIELD VALIDATION -- reconstructing the guest's own middle frame")
    print("  %-34s %8.4f" % ("hold the older frame", h))
    print("  %-34s %8.4f" % ("blend, no motion applied", b))
    print("  %-34s %8.4f" % ("warped with this field", w))
    if not w < b * 0.95:
        sys.exit("\n  ABORT: this field does not beat a blend, so it cannot be used\n"
                 "  to judge what the shader's gate does to a good vector.")
    print("  %-34s %8.0f%%\n" % ("warp's advantage over the blend",
                                 100.0 * (1.0 - w / b)))

    # ---- what the shipped gate keeps ----
    keeps, cands = [], {}
    curves = {
        "smoothstep(0.30, 0.70)  SHIPPED": lambda r: smoothstep(0.30, 0.70, r),
        "smoothstep(0.20, 0.55)": lambda r: smoothstep(0.20, 0.55, r),
        "smoothstep(0.15, 0.50)": lambda r: smoothstep(0.15, 0.50, r),
        "smoothstep(0.10, 0.45)": lambda r: smoothstep(0.10, 0.45, r),
        "step at 0.5 (keep if better)": lambda r: (r > 0.5).astype(np.float32),
    }
    for name in curves:
        cands[name] = []
    for (A, B, C), v in zip(trips, fields):
        fs, fm = gate_inputs(A, C, v)
        ratio = fs / (fs + fm + 1.0 / 2550.0)
        moved = fs > 2.0 / 255.0          # the shader's own "this pixel moved"
        if not moved.any():
            continue
        keeps.append(float(ratio[moved].mean()))
        for name, fn in curves.items():
            cands[name].append(float(fn(ratio)[moved].mean()))

    print("  %-34s %8.2f" % ("mean ratio on moved pixels", np.mean(keeps)))
    print()
    print("  %-34s %10s" % ("gate curve", "kept"))
    for name in curves:
        print("  %-34s %9.0f%%" % (name, 100.0 * np.mean(cands[name])))
    print()
    print("  partial.py measures 73%% of the correction reaching the panel.")
    print("  If the shipped curve keeps about that, the gate IS the leak: the")
    print("  vector was computed, it was good enough to beat a blend by the")
    print("  margin shown above, and then a curve threw a quarter of it away.")


if __name__ == "__main__":
    main()
