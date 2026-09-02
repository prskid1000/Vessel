"""Global motion plus local deviation only where the block earns it.

fieldtest.py showed, on the device's own dumps, that ONE constant vector for
the whole frame gives a visibly cleaner picture than the 8x8 field the pipeline
builds: at 150 px per real frame the field's spatial structure is more noise
than parallax, and the median cannot tell the two apart because it never looks
at the picture. This does. For every block, the block's own vector and the
frame's dominant vector are both scored on the luma pair -- mean absolute
difference over the block between the older frame and the newer frame displaced
by the vector -- and the block keeps its own vector only when it explains the
block better than the global one by a margin. Everything else takes the global
vector. Then the median runs as it always did.

    python gate.py [NN ...]

Writes gate.png beside each dump: device | as dumped | constant | gated.
"""
import os
import sys

import numpy as np
from PIL import Image

import consensus
import dump as D
import interp
from fieldtest import endpoint_disagreement

BLOCK = 8


def block_sad(lo, ln, field):
    """Per block: mean |older(block) - newer(block + d)|, d in raw units
    (older(x) ~ newer(x + d)), bilinear at the displaced site."""
    gh, gw = field.shape[:2]
    h, w = lo.shape
    ys, xs = np.mgrid[0:gh * BLOCK, 0:gw * BLOCK].astype(np.float32)
    full = np.repeat(np.repeat(field, BLOCK, axis=0), BLOCK, axis=1)
    u = (xs + full[..., 0] + 0.5) / w
    v = (ys + full[..., 1] + 0.5) / h
    moved = interp.sample(ln[..., None], u, v)[..., 0]
    diff = np.abs(lo[:gh * BLOCK, :gw * BLOCK] - moved)
    return diff.reshape(gh, BLOCK, gw, BLOCK).mean(axis=(1, 3))


def gated(field, older, newer, margin=2.0 / 255.0):
    lo, ln = interp.luma(older), interp.luma(newer)
    glob = np.median(field.reshape(-1, 2), axis=0)
    constant = np.broadcast_to(glob, field.shape).astype(np.float32).copy()
    own = block_sad(lo, ln, field)
    base = block_sad(lo, ln, constant)
    keep = own + margin < base
    out = np.where(keep[..., None], field, constant)
    return out, keep, glob


def main():
    names = sys.argv[1:] or sorted(d for d in os.listdir(D.DUMP) if os.path.isdir(os.path.join(D.DUMP, d)))
    for n in names:
        folder = os.path.join(D.DUMP, n)
        meta, older, newer, shown, field, back = D.load(folder)
        if field.max() == 0:
            continue
        phase, sign = meta["phase"], meta["fieldSign"]
        rows = []
        g2, keep2, glob = gated(field, older, newer, 2.0 / 255.0)
        g6, keep6, _ = gated(field, older, newer, 6.0 / 255.0)
        constant = np.broadcast_to(glob, field.shape).astype(np.float32).copy()
        variants = [
            ("as dumped", field, back),
            ("one constant vector", constant, None),
            ("gated, margin 2 steps", consensus.vector_median(g2, g2, passes=10), None),
            ("gated, margin 6 steps", consensus.vector_median(g6, g6, passes=10), None),
        ]
        print("\n%s: global (%+.0f, %+.0f); blocks keeping their own vector: %.0f%% at margin 2, %.0f%% at 6"
              % (n, glob[0], glob[1], keep2.mean() * 100, keep6.mean() * 100))
        print("  %-28s %12s %10s" % ("field", "endpoints", "vs device"))
        outs = []
        for label, f, b in variants:
            out = interp.interpolate(newer, older, f, b, phase, sign)
            outs.append(out)
            print("  %-28s %12.2f %10.2f" % (label, endpoint_disagreement(newer, older, f, phase, sign),
                                            float(np.abs(out - shown).mean() * 255)))
        to8 = lambda a: (np.clip(a, 0, 1) * 255).astype(np.uint8)
        strip = np.concatenate([to8(shown)] + [to8(o) for o in outs], axis=1)
        Image.fromarray(strip[::-1]).save(os.path.join(folder, "gate.png"))
        print("  wrote gate.png")


if __name__ == "__main__":
    main()
