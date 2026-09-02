"""Halve the block grid by estimating again on shifted luma, and score it where
the truth is known.

The hardware matcher is 8x8 and nothing can change that. But a block that
straddles a silhouette carries one vector for both sides of it, and the smear
that leaves is a block wide. Run the same matcher again on the pair shifted by
half a block and the second field's blocks sit on the first field's seams; two
such fields (0,0) and (4,4) give a quincunx of centres, four fields give a full
4 px grid. Each 4 px cell then takes the vector of the block whose centre is
nearest to it, so nothing is averaged and no vector is invented.

Scored on the occluder and parallax scenes, where the correct frame is known,
per region. The backward field is built the same way for each variant so the
consistency test sees fields of the same kind.

    python grid4.py
"""
import numpy as np

import bench
import interp
import occside
from consensus import vector_median


def block_sad(lo, ln, field):
    """Per block: mean |older(block) - newer(block + d)|, d in raw units."""
    gh, gw = field.shape[:2]
    h, w = lo.shape
    ys, xs = np.mgrid[0:gh * BLOCK, 0:gw * BLOCK].astype(np.float32)
    full = np.repeat(np.repeat(field, BLOCK, axis=0), BLOCK, axis=1)
    u = (xs + full[..., 0] + 0.5) / w
    v = (ys + full[..., 1] + 0.5) / h
    moved = interp.sample(ln[..., None], u, v)[..., 0]
    diff = np.abs(lo[:gh * BLOCK, :gw * BLOCK] - moved)
    return diff.reshape(gh, BLOCK, gw, BLOCK).mean(axis=(1, 3))

BLOCK = 8


def estimate_shifted(a, b, sx, sy, radius):
    """bench.estimate on the pair cropped by (sx, sy), so block (i, j) of the
    result is centred at (8i + 4 + sx, 8j + 4 + sy) in the uncropped frame."""
    h, w = a.shape[:2]
    gh, gw = (h - sy) // BLOCK, (w - sx) // BLOCK
    return bench.estimate(a[sy:sy + gh * BLOCK, sx:sx + gw * BLOCK],
                          b[sy:sy + gh * BLOCK, sx:sx + gw * BLOCK], radius=radius)


def fine_grid(fields, shifts, shape, older=None, newer=None):
    """The 4 px grid. Every cell is covered by one block of each shifted field
    and sits at the same distance from all their centres, so nearest-centre
    cannot choose; the cell takes the covering vector that explains its own
    16 pixels best, ties going to the unshifted field. That is the extra
    information the second estimate brings: which of two blocks straddling a
    seam the cell actually belongs to."""
    h, w = shape
    gh, gw = h // 4, w // 4
    cy, cx = np.mgrid[0:gh, 0:gw]
    lo, ln = interp.luma(older), interp.luma(newer)
    ys, xs = np.mgrid[0:gh * 4, 0:gw * 4].astype(np.float32)
    out = None
    best = None
    for f, (sx, sy) in zip(fields, shifts):
        fh, fw = f.shape[:2]
        bx = np.clip((cx * 4 + 2 - sx) // BLOCK, 0, fw - 1).astype(int)
        by = np.clip((cy * 4 + 2 - sy) // BLOCK, 0, fh - 1).astype(int)
        v = f[by, bx]
        full = np.repeat(np.repeat(v, 4, axis=0), 4, axis=1)
        moved = interp.sample(ln[..., None], (xs + full[..., 0] + 0.5) / w,
                              (ys + full[..., 1] + 0.5) / h)[..., 0]
        sad = np.abs(lo[:gh * 4, :gw * 4] - moved).reshape(gh, 4, gw, 4).mean(axis=(1, 3))
        if out is None:
            out, best = v.copy(), sad
        else:
            take = sad + 1.0 / 255.0 < best
            out[take] = v[take]
            best = np.where(take, sad, best)
    return out


def fields_for(newer, older, shifts, radius=occside.RADIUS):
    h, w = newer.shape[:2]
    fwd = [estimate_shifted(older, newer, sx, sy, radius) for sx, sy in shifts]
    if len(shifts) == 1:
        f = fwd[0]
    else:
        f = fine_grid(fwd, shifts, (h, w), older, newer)
    f = vector_median(f, f, passes=occside.PASSES)
    # Backward: warp the newer frame onto the older geometry by the prior, match
    # the warped frame against the older one, and merge -- as the device does.
    # Expanded per cell of whatever grid the forward field has.
    gh, gw = f.shape[:2]
    cell = h // gh
    full = np.repeat(np.repeat(f, cell, axis=0), cell, axis=1)[:h, :w]
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    warped = interp.sample(newer, (xs + full[..., 0] + 0.5) / w, (ys + full[..., 1] + 0.5) / h)
    bwd = [estimate_shifted(warped, older, sx, sy, radius) for sx, sy in shifts]
    r = bwd[0] if len(shifts) == 1 else fine_grid(bwd, shifts, (h, w), older, warped)
    b = -f + r
    b = vector_median(b, b, passes=occside.PASSES)
    return f, b


VARIANTS = [
    ("8 px grid, as shipped", [(0, 0)]),
    ("quincunx: (0,0) + (4,4)", [(0, 0), (4, 4)]),
    ("full 4 px: four shifts", [(0, 0), (4, 0), (0, 4), (4, 4)]),
]


def run(name, older, newer, truth, masks):
    print("\n%s" % name)
    cols = list(masks)
    print("  %-30s" % "grid" + "".join("%11s" % c for c in cols))
    for label, shifts in VARIANTS:
        f, b = fields_for(newer, older, shifts)
        out = interp.interpolate(newer, older, f, b, occside.PHASE, occside.SIGN)
        s = occside.score(out, truth, masks)
        print("  %-30s" % label + "".join("%11.2f" % s[c] for c in cols))


def main():
    o, n, t, m = occside.occluder_scene()
    run("occluder: background -24 px, object +48 px", o, n, t, m)
    o, n, t, m = occside.occluder_scene(bg_shift=(-40, 0), obj_shift=(-64, 0))
    run("parallax: background -40 px, object -64 px", o, n, t, m)
    o, n, t, m = occside.occluder_scene(bg_shift=(-8, 0), obj_shift=(-56, 0))
    run("parallax: background -8 px, object -56 px", o, n, t, m)


if __name__ == "__main__":
    main()
