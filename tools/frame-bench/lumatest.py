"""What the matcher is given to see, scored on the device's own frames.

Every real dump says the wrong vectors live in dark, flat regions: an 8x8
block of near-constant luma matches equally well at every offset and the
search ties. The luma pass hands the matcher plain Rec.601 luma. This runs
the bench's matcher on the same frames three ways and asks how coherent the
field comes back:

    plain          Rec.601 luma, as LumaMaterial produces it
    gamma          luma ** 0.5 -- dark detail lifted, bright compressed
    local contrast (luma - local mean) / (local deviation + floor), a
                   window of 32 px -- every region normalised to its own
                   contrast, which is what census and rank transforms do
                   for block matchers

The matcher runs at half resolution so the 150 px pans fit its window; the
field is then doubled back to full-resolution units and an 8 px grid for the
port. Two readings per variant: agreement, the fraction of blocks within a
proportional tolerance of the field's median; and the synthesised frame's
endpoint disagreement, which does not depend on a phase and falls when the
vectors land content on content.

    python lumatest.py [NN ...]
"""
import os
import sys

import numpy as np

import bench
import dump as D
import interp
from consensus import vector_median
from fieldtest import endpoint_disagreement

RADIUS = 80        # half-resolution pixels: 160 px of full-resolution reach
FLOOR = 4.0 / 255.0


def halve(img):
    h, w = img.shape[0] // 2 * 2, img.shape[1] // 2 * 2
    a = img[:h, :w]
    return (a[0::2, 0::2] + a[1::2, 0::2] + a[0::2, 1::2] + a[1::2, 1::2]) / 4.0


def box(img, r):
    """Mean over a (2r+1)^2 window, via a summed-area table."""
    p = np.pad(img, r + 1, mode="edge")
    s = p.cumsum(0).cumsum(1)
    n = (2 * r + 1) ** 2
    out = (s[2 * r + 1:, 2 * r + 1:] - s[:-2 * r - 1, 2 * r + 1:]
           - s[2 * r + 1:, :-2 * r - 1] + s[:-2 * r - 1, :-2 * r - 1]) / n
    return out[:img.shape[0], :img.shape[1]]


def variants(rgb):
    l = interp.luma(rgb)
    mean = box(l, 16)
    dev = np.sqrt(np.maximum(box(l * l, 16) - mean * mean, 0.0))
    return {
        "plain": l,
        "gamma 0.5": np.sqrt(l),
        "local contrast": np.clip(0.5 + 0.25 * (l - mean) / (dev + FLOOR), 0.0, 1.0),
    }


def grey(l):
    return np.repeat(l[..., None], 3, axis=-1).astype(np.float32)


def agreement(field):
    med = np.median(field.reshape(-1, 2), axis=0)
    tol = max(16.0, 0.2 * np.linalg.norm(med))
    return (np.linalg.norm(field - med, axis=-1) <= tol).mean(), med


def main():
    names = sys.argv[1:] or sorted(d for d in os.listdir(D.DUMP) if os.path.isdir(os.path.join(D.DUMP, d)))
    print("%-8s %-16s %10s %12s %14s" % ("dump", "luma", "agreement", "endpoints", "median vector"))
    for n in names:
        folder = os.path.join(D.DUMP, n)
        meta, older, newer, shown, field, back = D.load(folder)
        if field.max() == 0:
            continue
        phase, sign = meta["phase"], meta["fieldSign"]
        vo, vn = variants(older), variants(newer)
        h, w = older.shape[:2]
        for label in vo:
            f = bench.estimate(grey(halve(vo[label])), grey(halve(vn[label])), radius=RADIUS) * 2.0
            # Half-resolution 8 px blocks are 16 px of frame: spread each over
            # a 2x2 of the port's 8 px grid.
            f = np.repeat(np.repeat(f, 2, axis=0), 2, axis=1)[:h // 8, :w // 8]
            f = vector_median(f, f, passes=10)
            agree, med = agreement(f)
            ep = endpoint_disagreement(newer, older, f, phase, sign)
            print("%-8s %-16s %9.0f%% %12.2f   (%+.0f, %+.0f)" % (n, label, agree * 100, ep, med[0], med[1]))
        print()


if __name__ == "__main__":
    main()
