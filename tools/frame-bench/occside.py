"""Which source to drop when the two fields disagree, and what the frame border
costs -- scored on scenes where the answer is known.

WHY. After f74b9bc both motion fields are indexed on the OLDER frame, so the
shader's consistency test compares two estimates of the same N-1 site rather
than completing a round trip. When they disagree, which frame cannot see the
surface? The shipped shader drops the older source. The geometry argues the
other way: a surface that has an N-1 site but no consistent N site is one that
is COVERED in N, and the frame that cannot see it is the newer one. Neither
argument is worth a line of shader without a number, so this builds a scene
with an occluder of known motion over a background of known motion and scores
every variant on the pixels that are revealed, the pixels that are covered, the
occluder itself and everything else, separately -- because a mean over the
frame cannot see a few thousand pixels at a silhouette (see README).

AND THE BORDER. A second scene pans real content into the frame from one side,
with the ground truth known, so the band along the entering edge -- where one
fetch site leaves the frame and reads the clamped border texel -- can be scored
on its own. That is the band the shader's edge fades exist for.

The fields are built the way the device builds them, with the bench's block
matcher standing in for the hardware: the forward field indexed on the older
frame, the backward one from the newer frame warped onto the older frame's
geometry and matched against it, both filtered by the same anchored median.

    python occside.py
"""
import numpy as np

import bench
import interp
from consensus import vector_median

BLOCK = bench.BLOCK
RADIUS = 64          # the bench matcher's lattice reach; every motion below fits
PASSES = 10          # MEDIAN_PASSES
SIGN = -1.0          # what the device latches
PHASE = 0.5


def background():
    from PIL import Image
    import os
    bg = Image.open(os.path.join(bench.BENCH, "bg.png")).convert("RGB")
    return np.asarray(bg, dtype=np.float32) / 255.0


def expand(field, shape):
    """One vector per block, spread over the block's pixels."""
    h, w = shape
    full = np.repeat(np.repeat(field, BLOCK, axis=0), BLOCK, axis=1)
    return full[:h, :w]


def warp_newer_back(newer, prior):
    """WarpLumaMaterial, direction -1: the newer frame moved onto the older
    frame's geometry by the prior. With the device's sign the fetch is at
    x + c(x), which is exact for a field indexed on the older frame."""
    h, w = newer.shape[:2]
    full = expand(prior, (h, w))
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    u = (xs + full[..., 0] + 0.5) / w
    v = (ys + full[..., 1] + 0.5) / h
    return interp.sample(newer, u, v)


def fields(newer, older):
    """Forward and backward, as the device forms them, filtered."""
    fwd_raw = bench.estimate(older, newer, radius=RADIUS)
    fwd = vector_median(fwd_raw, fwd_raw, passes=PASSES)
    warped = warp_newer_back(newer, fwd)
    residual = bench.estimate(warped, older, radius=RADIUS)
    bwd_raw = -fwd + residual                      # MergeFieldMaterial, backward
    bwd = vector_median(bwd_raw, bwd_raw, passes=PASSES)
    return fwd, bwd


def occluder_scene(bg_shift=(-24, 0), obj_shift=(48, 0), size=(160, 200),
                   at=(420, 260)):
    """Background panning one way, a textured object crossing it the other.

    Returns older, newer, truth and the four masks the score is split over.
    """
    bg = background()
    h, w = bg.shape[:2]
    oh, ow = size
    patch = bg[40:40 + oh, 60:60 + ow].copy()
    # A border so the object has a silhouette the matcher can lock onto, and
    # a tint so its pixels cannot be mistaken for the background it covers.
    patch[:, :] = patch * np.array([1.0, 0.85, 0.7], dtype=np.float32)
    patch[:3, :] = patch[-3:, :] = 1.0
    patch[:, :3] = patch[:, -3:] = 1.0

    def compose(bg_dx, obj_dx, obj_dy):
        frame = np.roll(bg, bg_dx, axis=1).copy()
        y, x = at[0] + obj_dy, at[1] + obj_dx
        frame[y:y + oh, x:x + ow] = patch
        mask = np.zeros((h, w), dtype=bool)
        mask[y:y + oh, x:x + ow] = True
        return frame, mask

    older, m_old = compose(0, 0, 0)
    newer, m_new = compose(bg_shift[0], obj_shift[0], obj_shift[1])
    truth, m_mid = compose(bg_shift[0] // 2, obj_shift[0] // 2, obj_shift[1] // 2)
    # Background pixels of the midpoint frame that the older frame cannot show
    # (the object was there) and that the newer frame cannot show (the object
    # is there now). The rest of the background moved by a plain pan.
    revealed = m_old & ~m_mid
    covered = m_new & ~m_mid
    rest = ~(m_old | m_new | m_mid)
    margin = np.zeros((h, w), dtype=bool)
    margin[60:-60, 100:-100] = True
    return older, newer, truth, dict(revealed=revealed & margin,
                                     covered=covered & margin,
                                     object=m_mid & margin, rest=rest & margin)


def border_scene(shift=48, view=(640, 1120), origin=(40, 80)):
    """Real content entering from the right: a window onto the background that
    slides by `shift`, so the newer frame holds `shift` columns the older one
    never had, and the truth holds half of them."""
    bg = background()
    vh, vw = view
    oy, ox = origin

    def crop(dx):
        return bg[oy:oy + vh, ox + dx:ox + dx + vw].copy()

    older, newer, truth = crop(0), crop(shift), crop(shift // 2)
    band = np.zeros((vh, vw), dtype=bool)
    band[40:-40, vw - shift:] = True           # the entering band, both halves
    interior = np.zeros((vh, vw), dtype=bool)
    interior[40:-40, 100:vw - shift - 40] = True
    return older, newer, truth, dict(band=band, interior=interior)


def score(out, truth, masks):
    err = np.abs(out - truth).mean(axis=-1) * 255.0
    return {k: float(err[m].mean()) for k, m in masks.items()}


VARIANTS = [
    ("shipped: window OBMC, drop older", dict()),
    ("fit-weighted OBMC", dict(obmc="fit")),
    ("drop NEWER instead", dict(drop="newer")),
    ("consistency off", dict(drop="none")),
    ("newer-side round trip off", dict(newer_side=False)),
    ("colour photometry", dict(photometry="colour")),
    ("border gate off (0.5.14)", dict(border_gate=False)),
    ("3x3 blocks, fit-weighted", dict(obmc="fit9")),
    ("3x3 blocks, fit floor 1 step", dict(obmc="fit9", obmc_floor=1.0 / 255.0)),
]


def run(name, older, newer, truth, masks, extra_rows=()):
    print("\n%s" % name)
    fwd, bwd = fields(newer, older)
    cols = list(masks)
    print("  %-38s" % "variant" + "".join("%11s" % c for c in cols))
    for label, kw in VARIANTS:
        out = interp.interpolate(newer, older, fwd, bwd, PHASE, SIGN, **kw)
        s = score(out, truth, masks)
        print("  %-38s" % label + "".join("%11.2f" % s[c] for c in cols))
    blend = older * (1 - PHASE) + newer * PHASE
    s = score(blend, truth, masks)
    print("  %-38s" % "no compensation (cross-fade)" + "".join("%11.2f" % s[c] for c in cols))
    print("  (mean levels of 255, lower is better)")


def main():
    np.set_printoptions(precision=2)
    o, n, t, masks = occluder_scene()
    run("occluder: background -24 px, object +48 px, phase 0.5", o, n, t, masks)
    # Parallax during a pan, which is what the device recordings show: a near
    # cabinet and a far wall both moving the camera's way, the near one faster.
    o, n, t, masks = occluder_scene(bg_shift=(-40, 0), obj_shift=(-64, 0))
    run("parallax: background -40 px, object -64 px, phase 0.5", o, n, t, masks)
    o, n, t, masks = occluder_scene(bg_shift=(-8, 0), obj_shift=(-56, 0))
    run("parallax: background -8 px, object -56 px (48 px apart), phase 0.5", o, n, t, masks)
    o, n, t, masks = border_scene()
    run("border: content entering from the right at 48 px", o, n, t, masks)


if __name__ == "__main__":
    main()
