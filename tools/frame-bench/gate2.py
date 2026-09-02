"""Dominant motion from the blocks that measured something, handed to the ones
that did not -- scored on the device's own dumps.

gate.py showed the idea works on a clean pan. On device the first two builds of
it failed for reasons that had nothing to do with the idea (a CPU readback of a
chaotic field; a scene cut). What was missing, and what this tests:

  * The dominant motion must come from TEXTURED blocks. On a ceiling nine
    blocks in ten are flat and their vectors are tie-breaks; a plain mean or
    median of the field is a mean of noise. Each block is weighted by its own
    luma gradient, normalised to the frame's 90th percentile so a dark game
    weighs the same as a bright one, and the centre is found by mean-shift
    from the weighted mean -- which walks into the dense cluster the beam and
    the cornice form and ignores the cloud around it.

  * A block takes the dominant motion only when it explains the block at
    least as well as the block's own vector, within a margin. A wrong
    dominant motion then loses every block and the field is what it was.

Estimated on a 4x4 box-reduction of the merged field, which stands in for the
coarse field the device would use.

    python gate2.py [NN ...]      writes gate2.png: device | as dumped | constant | gated
"""
import os
import sys

import numpy as np
from PIL import Image

import consensus
import dump as D
import interp
from fieldtest import endpoint_disagreement
from gate import block_sad

BLOCK = 8


def texture_weights(newer, gh, gw, block=BLOCK):
    l = interp.luma(newer)
    gy, gx = np.gradient(l)
    g = np.hypot(gx, gy)[:gh * block, :gw * block].reshape(gh, block, gw, block).mean(axis=(1, 3))
    return np.clip(g / max(np.percentile(g, 90), 1e-6), 0.0, 1.0)


def dominant(field, weights, iterations=4):
    """Texture-weighted mean-shift on a coarse reduction of the field."""
    gh, gw = field.shape[:2]
    ch, cw = gh // 4, gw // 4
    f = field[:ch * 4, :cw * 4].reshape(ch, 4, cw, 4, 2).mean(axis=(1, 3))
    w = weights[:ch * 4, :cw * 4].reshape(ch, 4, cw, 4).mean(axis=(1, 3))
    v = f.reshape(-1, 2)
    w = w.reshape(-1)
    centre = (v * w[:, None]).sum(0) / max(w.sum(), 1e-6)
    for _ in range(iterations):
        tol = max(16.0, 0.2 * np.linalg.norm(centre))
        inside = np.linalg.norm(v - centre, axis=-1) <= tol
        ww = w * inside
        if ww.sum() <= 1e-6:
            break
        centre = (v * ww[:, None]).sum(0) / ww.sum()
    tol = max(16.0, 0.2 * np.linalg.norm(centre))
    inside = np.linalg.norm(v - centre, axis=-1) <= tol
    agree_w = (w * inside).sum() / max(w.sum(), 1e-6)
    return centre, agree_w, inside.mean()


def gated(field, older, newer, centre, weights=None, margin=2.0 / 255.0, flat=0.1):
    """Flat blocks take the centre: they measured nothing. Textured blocks keep
    their own vector unless the centre explains them better by the margin --
    a near-tie is real parallax and stays."""
    lo, ln = interp.luma(older), interp.luma(newer)
    constant = np.broadcast_to(centre, field.shape).astype(np.float32).copy()
    own = block_sad(lo, ln, field)
    theirs = block_sad(lo, ln, constant)
    if weights is None:
        take = theirs <= own + margin
    else:
        take = (theirs + margin < own) | (weights < flat)
    return np.where(take[..., None], constant, field), take


def main():
    names = sys.argv[1:] or sorted(d for d in os.listdir(D.DUMP) if os.path.isdir(os.path.join(D.DUMP, d)))
    for n in names:
        folder = os.path.join(D.DUMP, n)
        meta, older, newer, shown, field, back = D.load(folder)
        if field.max() == 0:
            continue
        phase, sign = meta["phase"], meta["fieldSign"]
        merged = meta["merged"] if meta.get("merged") is not None else field
        gh, gw = merged.shape[:2]
        w = texture_weights(newer, gh, gw)
        centre, agree_w, agree = dominant(merged, w)
        plain = np.median(merged.reshape(-1, 2), axis=0)
        g, take = gated(merged, older, newer, centre)
        g = consensus.vector_median(g, g, passes=10)
        g2, take2 = gated(merged, older, newer, centre, weights=w)
        g2 = consensus.vector_median(g2, g2, passes=10)
        constant = np.broadcast_to(centre, merged.shape).astype(np.float32).copy()
        print("\n%s: dumped agreement %.0f%% | weighted centre (%+.0f,%+.0f) agree %.0f%% (unweighted %.0f%%)"
              " | speed %.0f px | taking the centre: tie rule %.0f%%, flat rule %.0f%%"
              % (n, meta.get("agreement", 0) * 100, centre[0], centre[1], agree_w * 100, agree * 100,
                 np.linalg.norm(centre), take.mean() * 100, take2.mean() * 100))
        rows = [("as dumped", field, back), ("constant weighted centre", constant, None),
                ("gated (tie -> centre) + median", g, None),
                ("gated (flat or clearly better)", g2, None)]
        outs = []
        for label, f, b in rows:
            out = interp.interpolate(newer, older, f, b, phase, sign)
            outs.append(out)
            print("  %-26s endpoints %6.2f   vs device %6.2f"
                  % (label, endpoint_disagreement(newer, older, f, phase, sign),
                     float(np.abs(out - shown).mean() * 255)))
        to8 = lambda a: (np.clip(a, 0, 1) * 255).astype(np.uint8)
        strip = np.concatenate([to8(shown)] + [to8(o) for o in outs], axis=1)
        Image.fromarray(strip[::-1]).save(os.path.join(folder, "gate2.png"))


if __name__ == "__main__":
    main()
