"""Where is the two-stage forward field indexed -- on frame N-1, or on N?

THE QUESTION. probeBasepoint showed the matcher indexes its vectors on its
<ref>, and f74b9bc moved the shader's projection onto the N-1 site on that
basis. But the probe ran a single-pass estimate, whose ref is the older luma.
The two-stage search that actually runs once the sign has latched feeds the
fine pass a ref that is the OLDER FRAME WARPED FORWARD BY THE COARSE PRIOR --
an image in frame N's geometry. If the fine field is indexed on that, the
merged forward field is indexed on N, and the projection reads it one whole
displacement away from where it lives, at every motion boundary.

TWO TESTS, neither of which needs a device.

1. The device's own dumps carry both filtered fields. A forward field F and a
   backward field B are inverses of each other where nothing is occluded, but
   only when they are read at corresponding sites. If F is indexed on N-1 the
   round trip is |F(x) + B(x)| at one site x; if F is indexed on N it is
   |F(y) + B(y + F(y))|, reading B where F says the content came from. The
   hypothesis that is right about the indexing is the one whose round trip
   closes more tightly.

2. The bench scenes, with the forward field built the way the DEVICE builds
   it -- prior, warp the older frame forward, residual on the warped image,
   merged at the warped site -- rather than the way the bench has been
   building it (a single pass indexed on the older frame). Then the shader
   port is scored against the known midpoint with the projection on the N-1
   site, on the N site, and off.

    python indexing.py
"""
import os
import sys

import numpy as np

import bench
import dump
import interp
import occside
from consensus import vector_median

BLOCK = bench.BLOCK
SIGN = -1.0


def sample_field(field, at_x, at_y):
    """Bilinear read of a block field at fractional block coordinates."""
    gh, gw = field.shape[:2]
    x = np.clip(at_x, 0, gw - 1)
    y = np.clip(at_y, 0, gh - 1)
    x0, y0 = np.floor(x).astype(np.int32), np.floor(y).astype(np.int32)
    x1, y1 = np.minimum(x0 + 1, gw - 1), np.minimum(y0 + 1, gh - 1)
    fx, fy = (x - x0)[..., None], (y - y0)[..., None]
    return (field[y0, x0] * (1 - fx) * (1 - fy) + field[y0, x1] * fx * (1 - fy)
            + field[y1, x0] * (1 - fx) * fy + field[y1, x1] * fx * fy)


def round_trips(F, B, bx, by):
    """Mean round-trip residual in pixels under each indexing hypothesis.

    Raw matcher units: F is the displacement of content from N-1 to N; B is
    the displacement from N to N-1. Both should cancel where both frames see
    the surface.
    """
    gh, gw = F.shape[:2]
    ys, xs = np.mgrid[0:gh, 0:gw].astype(np.float32)
    # Hypothesis A: both indexed on N-1, same site.
    a = np.linalg.norm(F + B, axis=-1)
    # Hypothesis B: F indexed on N at y; the content came from y - F(y) in
    # N-1, which is where B (indexed on N-1) describes it.
    b_at = sample_field(B, xs - F[..., 0] / bx, ys - F[..., 1] / by)
    b = np.linalg.norm(F + b_at, axis=-1)
    # And the mirror: B indexed on N-1 at x; that content is at x + B(x)
    # in N, where an N-indexed F describes it. Should agree with B if B is
    # right, and fail with A if A is right -- a third witness.
    f_at = sample_field(F, xs + B[..., 0] / bx, ys + B[..., 1] / by)
    c = np.linalg.norm(B + f_at, axis=-1)
    moving = np.linalg.norm(F, axis=-1) > 2.0
    if moving.sum() < 20:
        return None
    return a[moving].mean(), b[moving].mean(), c[moving].mean(), moving.mean()


def photometric(older, newer, F, bx, by):
    """Which frame does the field reproduce when it is used as a gather?

    If F is indexed on N, then N(y) = N-1(y - F(y)) wherever the vector is
    right; if on N-1, then N-1(x) = N(x + F(x)). Both are exact under a pure
    translation, so the difference lives at motion boundaries -- which is
    exactly where the shader's projection is decided.
    """
    h, w = newer.shape[:2]
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    full = np.repeat(np.repeat(F, by, axis=0), bx, axis=1)[:h, :w]
    fullmag = np.linalg.norm(full, axis=-1)
    moving = fullmag > 2.0
    # N-indexed: gather N-1 at y - F(y), compare with N at y.
    a = interp.sample(older, (xs - full[..., 0] + 0.5) / w, (ys - full[..., 1] + 0.5) / h)
    err_n = np.abs(a - newer).mean(axis=-1)
    # N-1-indexed: gather N at x + F(x), compare with N-1 at x.
    b = interp.sample(newer, (xs + full[..., 0] + 0.5) / w, (ys + full[..., 1] + 0.5) / h)
    err_o = np.abs(b - older).mean(axis=-1)
    if moving.sum() < 1000:
        return None
    # Over the whole moving area, and over the pixels where the two readings
    # disagree most -- the boundaries -- which is the top decile of |err_n - err_o|.
    gap = np.abs(err_n - err_o)[moving]
    cut = np.percentile(gap, 90)
    hard = moving.copy()
    hard[moving] = gap >= cut
    return (err_n[moving].mean() * 255, err_o[moving].mean() * 255,
            err_n[hard].mean() * 255, err_o[hard].mean() * 255)


def device_dumps():
    root = dump.DUMP
    names = sorted(d for d in os.listdir(root) if os.path.isdir(os.path.join(root, d)))
    print("device dumps: mean round-trip residual over moving blocks, in pixels")
    print("  %-10s %6s   %8s %8s %8s   %s" % ("dump", "moving", "N-1 idx", "N idx", "mirror", "field px"))
    wins = 0
    for name in names:
        folder = os.path.join(root, name)
        meta, older, newer, shown, field, back = dump.load(folder)
        if back is None:
            continue
        r = round_trips(field, back, meta["blockX"], meta["blockY"])
        if r is None:
            print("  %-10s   (still)" % name)
            continue
        a, b, c, moving = r
        wins += b < a
        print("  %-10s %5.0f%%   %8.2f %8.2f %8.2f   %.0f" % (name, moving * 100, a, b, c, meta["fieldMagnitude"]))
    print("  the N-indexed round trip closes tighter on %d dumps" % wins)

    print("\ndevice dumps: the field as a gather, mean |error| in levels over moving pixels")
    print("  reproduce N from N-1 assumes N-indexed; reproduce N-1 from N assumes N-1-indexed")
    print("  %-10s %12s %12s   %14s %14s" % ("dump", "N from N-1", "N-1 from N", "boundary N", "boundary N-1"))
    pwins = 0
    for name in names:
        folder = os.path.join(root, name)
        meta, older, newer, shown, field, back = dump.load(folder)
        r = photometric(older, newer, field, meta["blockX"], meta["blockY"])
        if r is None:
            continue
        pwins += r[2] < r[3]
        print("  %-10s %12.2f %12.2f   %14.2f %14.2f" % ((name,) + r))
    print("  N-indexed reproduces the boundaries better on %d dumps" % pwins)


# ---- the device's two-stage forward field, on the bench --------------------

def expand(field, shape):
    h, w = shape
    full = np.repeat(np.repeat(field, BLOCK, axis=0), BLOCK, axis=1)
    return full[:h, :w]


def warp_older_forward(older, prior):
    """WarpLumaMaterial, direction +1, at the device's sign: the destination
    pixel y reads older at y - prior(y). The prior is sampled at the
    DESTINATION, which is what the shader does, so the warped image sits in
    N's geometry."""
    h, w = older.shape[:2]
    full = expand(prior, (h, w))
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    u = (xs - full[..., 0] + 0.5) / w
    v = (ys - full[..., 1] + 0.5) / h
    return interp.sample(older, u, v)


def two_stage_forward(newer, older, radius=64):
    """The forward field as estimateTwoStage forms it. The coarse prior stands
    in for the quarter-size pass: here the bench matcher can already reach the
    whole motion, so the prior is the plain estimate filtered once, which is
    what a coarse pass upsampled would give on a scene this size."""
    prior = vector_median(bench.estimate(older, newer, radius=radius),
                          bench.estimate(older, newer, radius=radius), passes=4)
    warped = warp_older_forward(older, prior)
    # Fine pass: ref is the warped older frame, target the newer one.
    residual = bench.estimate(warped, newer, radius=16)
    # MergeFieldMaterial samples the coarse field at the residual's own site.
    merged = prior + residual
    return vector_median(merged, merged, passes=occside.PASSES)


def score_scene(name, older, newer, truth, masks, radius=64):
    print("\n%s" % name)
    fwd_old = occside.fields(newer, older)[0]          # the bench's N-1 field
    fwd_new = two_stage_forward(newer, older, radius)  # the device's N field
    _, bwd = occside.fields(newer, older)
    cols = list(masks)
    print("  %-46s" % "field / projection" + "".join("%10s" % c for c in cols))
    for fname, F in (("single-pass field (indexed on N-1)", fwd_old),
                     ("two-stage field (indexed on N)", fwd_new)):
        for proj in ("older", "newer", "none"):
            out = interp.interpolate(newer, older, F, bwd, occside.PHASE, SIGN, projection=proj)
            s = occside.score(out, truth, masks)
            print("  %-46s" % ("%s, read at %s" % (fname, {"older": "N-1 site", "newer": "N site", "none": "vUV"}[proj]))
                  + "".join("%10.2f" % s[c] for c in cols))
    print("  (mean levels of 255 against the known midpoint, lower is better)")


def main():
    np.set_printoptions(precision=2)
    if os.path.isdir(dump.DUMP):
        device_dumps()
    o, n, t, m = occside.occluder_scene()
    score_scene("occluder: background -24 px, object +48 px", o, n, t, m)
    o, n, t, m = occside.occluder_scene(bg_shift=(-40, 0), obj_shift=(-64, 0))
    score_scene("parallax: wall -40 px, cabinet -64 px", o, n, t, m)


if __name__ == "__main__":
    main()
