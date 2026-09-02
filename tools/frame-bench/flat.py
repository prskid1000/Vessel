"""Does the device's field go wrong where the picture is flat?

On the first real dumps (150 px pan, 4x, 66 ms) the field's x component holds
blobs 40 to 80 px off the frame's dominant motion, and by eye they sit on the
dark wall and the shadowed floor. This puts a number on it: each block's
texture (mean gradient magnitude of the newer luma inside the block) against
its distance from the frame's median vector.

    python flat.py [NN ...]
"""
import json
import os
import sys

import numpy as np

import interp

HERE = os.path.dirname(os.path.abspath(__file__))
DUMP = os.path.join(HERE, "dump")


def texture(luma, block):
    gy, gx = np.gradient(luma)
    g = np.hypot(gx, gy)
    h, w = g.shape
    gh, gw = h // block, w // block
    return g[:gh * block, :gw * block].reshape(gh, block, gw, block).mean(axis=(1, 3))


def main():
    names = sys.argv[1:] or sorted(d for d in os.listdir(DUMP) if os.path.isdir(os.path.join(DUMP, d)))
    for n in names:
        f = os.path.join(DUMP, n)
        meta = json.load(open(os.path.join(f, "meta.json")))
        w, h, gw, gh = meta["width"], meta["height"], meta["gridWidth"], meta["gridHeight"]
        newer = np.fromfile(os.path.join(f, "newer.rgba"), np.uint8).reshape(h, w, 4)[..., :3] / 255.0
        fld = np.fromfile(os.path.join(f, "field.f32"), np.float32).reshape(gh, gw, 4)[..., :2]
        if fld.max() == 0:
            continue
        tex = texture(interp.luma(newer.astype(np.float32)), meta["blockX"])[:gh, :gw] * 255
        med = np.median(fld.reshape(-1, 2), axis=0)
        off = np.linalg.norm(fld - med, axis=-1)
        print("\n%s: median vector (%+.0f, %+.0f) px" % (n, med[0], med[1]))
        print("  %-26s %8s %10s %12s" % ("texture band (levels/px)", "blocks", "mean off", ">24 px off"))
        edges = [0, 1, 2, 4, 8, 16, 1e9]
        for lo, hi in zip(edges[:-1], edges[1:]):
            m = (tex >= lo) & (tex < hi)
            if m.sum() == 0:
                continue
            print("  %-26s %8d %8.1f px %11.0f%%"
                  % ("%g..%g" % (lo, hi) if hi < 1e9 else "%g+" % lo, m.sum(), off[m].mean(),
                     (off[m] > 24).mean() * 100))
        print("  overall: %.0f%% of blocks more than 24 px off the median; they hold %.0f%% of the frame's"
              " off-median error and have mean texture %.1f against %.1f elsewhere"
              % ((off > 24).mean() * 100, off[off > 24].sum() / max(off.sum(), 1e-6) * 100,
                 tex[off > 24].mean(), tex[off <= 24].mean()))


if __name__ == "__main__":
    main()
