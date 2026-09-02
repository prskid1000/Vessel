"""Is the device's field helping or hurting? Replay each dump with the field as
dumped, with one constant vector for the whole frame, and with the dumped field
re-filtered under texture confidence.

There is no ground truth in a dump, so two readings are taken: the device's own
`fg truth` criterion, and a photometric one that does not depend on phase --
the mean disagreement between the two endpoints every output pixel was built
from, which is zero when the vector is right and the surface is visible in both
frames. Pictures are written beside each dump as fieldtest.png:

    device output | port, dumped field | port, constant vector | port, texture-weighted

    python fieldtest.py [NN ...]
"""
import json
import os
import sys

import numpy as np
from PIL import Image

import consensus
import dump as D
import interp


def endpoint_disagreement(newer, older, field, phase, sign):
    """Mean |older(q + v t) - newer(q - v (1-t))| over the frame, using the
    projected mean vector the shader would use at each pixel."""
    diag = {}
    interp.interpolate(newer, older, field, None, phase, sign, diagnostics=diag)
    mean = diag["mean"]
    h, w = newer.shape[:2]
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    uv = np.stack([(xs + 0.5) / w, (ys + 0.5) / h], axis=-1)
    fo = np.clip(uv + mean * phase, 0, 1)
    fn = np.clip(uv - mean * (1 - phase), 0, 1)
    a = interp.sample(older, fo[..., 0], fo[..., 1])
    b = interp.sample(newer, fn[..., 0], fn[..., 1])
    return float(np.abs(a - b).mean() * 255)


def main():
    names = sys.argv[1:] or sorted(d for d in os.listdir(D.DUMP) if os.path.isdir(os.path.join(D.DUMP, d)))
    for n in names:
        folder = os.path.join(D.DUMP, n)
        meta, older, newer, shown, field, back = D.load(folder)
        if field.max() == 0:
            continue
        phase, sign = meta["phase"], meta["fieldSign"]
        med = np.median(field.reshape(-1, 2), axis=0)
        constant = np.broadcast_to(med, field.shape).astype(np.float32).copy()
        weights = consensus.textured_weights(newer)[:field.shape[0], :field.shape[1]]
        weighted = consensus.vector_median(field, field, passes=10, weights=weights)
        print("\n%s: phase %.3f, median vector (%+.0f, %+.0f), %.0f%% of blocks below full texture weight"
              % (n, phase, med[0], med[1], (weights < 1).mean() * 100))
        print("  %-28s %10s %12s %10s" % ("field", "truth", "endpoints", "vs device"))
        print("  %-28s %10.3f %12s %10s" % ("device output", D.truth_metric(shown, older, newer), "-", "-"))
        outs = []
        for label, f, b in (("as dumped", field, back), ("one constant vector", constant, None),
                            ("texture-weighted refilter", weighted, back)):
            out = interp.interpolate(newer, older, f, b, phase, sign)
            outs.append(out)
            print("  %-28s %10.3f %12.2f %10.2f"
                  % (label, D.truth_metric(out, older, newer),
                     endpoint_disagreement(newer, older, f, phase, sign),
                     float(np.abs(out - shown).mean() * 255)))
        to8 = lambda a: (np.clip(a, 0, 1) * 255).astype(np.uint8)
        strip = np.concatenate([to8(shown)] + [to8(o) for o in outs], axis=1)
        Image.fromarray(strip[::-1]).save(os.path.join(folder, "fieldtest.png"))
        print("  wrote fieldtest.png")


if __name__ == "__main__":
    main()
