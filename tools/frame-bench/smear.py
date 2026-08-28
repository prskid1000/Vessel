"""Reproduce the smeared edge, then measure it.

THE ARTEFACT. Photographed from the device: a vertical edge -- a door frame, a
wall corner -- renders in the synthesised frames as a smeared band rather than
an edge. It is not a wrong displacement and it is not a hole; the edge is in the
right place and is simply soft, and it changes frame to frame, which is what is
seen as shimmer.

WHY NOTHING HERE HAS EVER SEEN IT. `pyramid.warp` is what every scene in this
directory interpolates with, and it is a BLOCKY warp: `np.repeat` of the field
per block, one vector per 8x8, hard seams between them. The device does not do
that. `InterpolateMaterial` overlaps the blocks -- four predictions per pixel
under a raised-cosine partition of unity -- and the smear is a property of that
overlap, so a bench built on `warp` cannot produce it however the field is
filtered. Three separate attempts to explain the shimmer were measured against a
model that structurally could not contain it.

WHY THE SMEAR IS THERE AT ALL, AND WHY IT IS NOT SIMPLY A BUG. Every pixel is a
weighted sum of FOUR predictions, one per surrounding block. Where those four
vectors agree the sum is one picture. Where they disagree -- at a depth
discontinuity, at an object silhouette -- the four predictions fetch from four
different places and the sum is all of them at once. That is the smear, and it
is deliberate: 196d7d3 measured boundary-weighted, boundary-picked and
PERFECTLY-SEGMENTED blending all worse than the plain window, because the
softness is a regulariser. It turns a wrong vector into a soft ghost instead of
a hard displacement.

So the question this file exists to answer is not "how do we sharpen it" -- that
is answered, and the answer is don't. It is: how much of the softness is the
window doing its job, and how much is the field being wrong underneath it?

THE SCENE. `pyramid.layered` -- two depths moving at different speeds, which is
what makes neighbouring blocks disagree -- with the exact correct middle frame
known by construction. Parallax rather than a global pan, because a global pan
is the case a block matcher gets right and it produces no disagreement to smear.

THE METRIC, AND WHY NOT RMS OR FLICKER. This directory has recorded four times
that the obvious metric is blind to the artefact, and two more were found today:
whole-frame flicker reads 45% on a field frozen to a constant, so it is
measuring input grain; and RMS averages a soft edge against eight hundred
thousand untouched pixels. A soft edge is a GEOMETRIC fact -- the transition is
spread over more pixels than it should be -- so it is measured as gradient
retained at the places the truth has an edge:

    sharpness = mean |grad(output)| / mean |grad(truth)|   over the truth's edges

1.0 is an edge as crisp as the real one. 0.5 is an edge spread to twice its
width. It cannot be flattered by getting flat regions right.

    python smear.py
"""
import numpy as np

import bench
import pyramid as P
from consensus import vector_median

BLOCK = P.BLOCK


def obmc(newer, older, field, phase=0.5):
    """InterpolateMaterial's overlapped blend, which `pyramid.warp` is not.

    Mirrors the shader: `grid = vUV * vectorSize - 0.5`, `base = floor(grid)`,
    a raised cosine of the fractional part, and the four block centres at
    base+(0.5,0.5) .. base+(1.5,1.5). The photometric weights are left out --
    wOlder = 1-phase, wNewer = phase -- so that what is measured is the overlap
    itself and not the still/moves terms sitting on top of it.
    """
    h, w = newer.shape[:2]
    gh, gw = field.shape[:2]

    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    gx = (xs + 0.5) / BLOCK - 0.5
    gy = (ys + 0.5) / BLOCK - 0.5
    bx, by = np.floor(gx), np.floor(gy)
    fx, fy = gx - bx, gy - by

    # The window. Flat at both ends, and the four sum to one everywhere.
    wx = 0.5 - 0.5 * np.cos(np.pi * fx)
    wy = 0.5 - 0.5 * np.cos(np.pi * fy)
    weights = [(1 - wx) * (1 - wy), wx * (1 - wy), (1 - wx) * wy, wx * wy]

    def block_vec(ox, oy):
        ix = np.clip(bx.astype(int) + ox, 0, gw - 1)
        iy = np.clip(by.astype(int) + oy, 0, gh - 1)
        return field[iy, ix]

    def sample(img, sx, sy):
        return img[np.round(sy).astype(int) % h, np.round(sx).astype(int) % w]

    out = np.zeros_like(newer, dtype=np.float32)
    for weight, (ox, oy) in zip(weights, ((0, 0), (1, 0), (0, 1), (1, 1))):
        v = block_vec(ox, oy)
        vx, vy = v[..., 0], v[..., 1]
        a = sample(older, xs + vx * phase, ys + vy * phase)
        b = sample(newer, xs - vx * (1 - phase), ys - vy * (1 - phase))
        q = a * (1 - phase) + b * phase
        out += weight[..., None] * q
    return out


def sharpness(out, truth, share=0.05):
    """Gradient retained where the truth has an edge. 1.0 is as crisp as real."""
    t, o = bench.luma(truth), bench.luma(out)
    def grad(g):
        gy, gx = np.gradient(g)
        return np.hypot(gx, gy)
    gt, go = grad(t), grad(o)
    keep = gt >= np.quantile(gt, 1.0 - share)
    return float(go[keep].mean() / max(gt[keep].mean(), 1e-9))


def main():
    print(__doc__.split("\n\n")[0])
    print()
    for far, near in (((16, 5), (40, -9)), ((30, 9), (8, 3))):
        newer, older, truth, _ = P.layered(far, near)
        raw = bench.estimate(newer, older, radius=P.WINDOW)
        filtered = vector_median(raw, raw)

        # The control that says how much of the softness is unavoidable: the
        # TRUE field, perfectly known, put through the same overlapped blend.
        # Anything the window costs even with a correct field is the price of the
        # regulariser, not a fault to be chased.
        gh, gw = raw.shape[:2]
        ideal = np.zeros_like(raw)
        row = int(newer.shape[0] * 0.55) // BLOCK
        ideal[:row] = (-far[0], -far[1])
        ideal[row:] = (-near[0], -near[1])

        print("distant %s, near %s -- exact middle frame known" % (far, near))
        print("  %-34s %11s %10s" % ("interpolation", "edge sharp", "rms"))
        rows = (
            ("blocky warp (what the bench used)", P.warp(newer, older, filtered)),
            ("overlapped, filtered field", obmc(newer, older, filtered)),
            ("overlapped, TRUE field", obmc(newer, older, ideal)),
            ("no motion at all (blend)", 0.5 * newer + 0.5 * older),
        )
        for name, out in rows:
            print("  %-34s %10.3f %10.4f"
                  % (name, sharpness(out, truth), P.rms(out, truth)))
        print()


if __name__ == "__main__":
    main()
