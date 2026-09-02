"""A temporal prior on the median, measured as flicker over a sequence.

Shimmer is alternation over time. The median offers last interval's vector as
one candidate among eleven, so it can break a tie towards continuity, but it
cannot stop a block that has two near-equal answers from picking one this
frame and the other the next -- and on a flat wall crossed by one object,
nearly every block is such a block. A temporal PRIOR counts the previous
vector `weight` times in every candidate's score, so a candidate that agrees
with last frame is cheaper and a flip has to be paid for by the neighbours.
At weight 1 it is exactly the candidate the device already has; above it the
prior pulls.

The scene is a textured plane with a flat band across it, translating at a
constant rate under per-frame grain, so the true field is one vector for the
whole sequence and any change in the filtered field is the filter. Each
synthesised frame is registered back by its known motion; the temporal spread
over the flat band's edge pixels is the flicker, and the field's own error
against the known vector is printed beside it so a prior that steadies the
field by making it wrong is caught.

    python temporal2.py
"""
import numpy as np

import bench
import interp
import pyramid as P

BLOCK = 8
N = 9
STEP = (-28, 6)
NOISE = 0.004


def sequence(seed=5):
    rng = np.random.default_rng(seed)
    bg = P.background().copy()
    h, w = bg.shape[:2]
    # A flat band with a soft object crossing it: the textureless case that
    # makes blocks tie, with edges to flicker.
    bg[h // 3:h // 3 + 120, :] = 0.42
    bg[h // 3 + 30:h // 3 + 90, 300:380] = 0.75
    return [np.clip(P.roll(bg, STEP[0] * i, STEP[1] * i)
                    + rng.normal(0, NOISE, bg.shape), 0, 1).astype(np.float32)
            for i in range(N)]


def median_prior(field, original, prev, weight, passes=10):
    """The anchored median with the previous vector counted `weight` times.
    prev is already read where this block's content came from."""
    current = field
    for _ in range(passes):
        padded = np.pad(current, ((1, 1), (1, 1), (0, 0)), mode="edge")
        h, w = current.shape[:2]
        cands = [padded[dy:dy + h, dx:dx + w] for dy in range(3) for dx in range(3)] + [original, prev]
        votes = np.array([1.0] * 10 + [weight], dtype=np.float32)
        stack = np.stack(cands, axis=0)
        d = np.abs(stack[:, None] - stack[None, :]).sum(axis=-1)
        cost = (d * votes[None, :, None, None]).sum(axis=1)
        best = np.argmin(cost, axis=0)
        current = np.take_along_axis(stack, best[None, ..., None], axis=0)[0]
    return current


def came_from(prev, field):
    """prev read at the block this block's content came from: p + b in the
    older frame, in blocks, b being the block's own vector (raw units)."""
    gh, gw = field.shape[:2]
    by, bx = np.mgrid[0:gh, 0:gw]
    # Raw units: older(x) ~ newer(x + d); content now at block (bx, by) sat
    # at x - d one frame earlier in the same convention as the device's
    # MedianMaterial (fieldSign -1: came = uv - d * texel).
    sx = np.clip(np.round(bx - field[..., 0] / BLOCK).astype(int), 0, gw - 1)
    sy = np.clip(np.round(by - field[..., 1] / BLOCK).astype(int), 0, gh - 1)
    return prev[sy, sx]


def main():
    frames = sequence()
    h, w = frames[0].shape[:2]
    # Raw units: older(x) ~ newer(x + d), and frame i is frame i-1 rolled by
    # STEP, so d is STEP itself.
    truth_raw = np.array([STEP[0], STEP[1]], dtype=np.float32)
    raws = [bench.estimate(frames[i - 1], frames[i], radius=40) for i in range(1, N)]
    base = bench.luma(frames[0])
    gy, gx = np.gradient(base)
    edge = np.hypot(gx, gy) >= np.quantile(np.hypot(gx, gy), 0.75)

    print("constant motion %s, %d pairs, raw field error %.2f px vs truth"
          % (STEP, len(raws), np.linalg.norm(raws[3] - truth_raw, axis=-1).mean()))
    print("  %-26s %10s %12s %12s" % ("median", "flicker", "field err", "field flips"))
    for label, weight in (("no temporal candidate", 0.0), ("candidate x1 (shipped)", 1.0),
                          ("prior x2", 2.0), ("prior x3", 3.0), ("prior x5", 5.0)):
        prev = None
        outs, errs, flips = [], [], []
        for i, raw in enumerate(raws):
            if prev is None or weight == 0.0:
                f = median_prior(raw, raw, raw, 0.0)
            else:
                f = median_prior(raw, raw, came_from(prev, raw), weight)
            if prev is not None:
                flips.append((np.abs(f - came_from(prev, raw)).sum(-1) > 0.5).mean() * 100)
            prev = f
            errs.append(np.linalg.norm(f - truth_raw, axis=-1).mean())
            out = interp.interpolate(frames[i + 1], frames[i], f, None, 0.5, -1.0)
            outs.append(P.roll(out, -(STEP[0] * i + STEP[0] // 2), -(STEP[1] * i + STEP[1] // 2)))
        sd = np.array(outs).std(axis=0).mean(axis=-1)
        print("  %-26s %10.3f %12.2f %11.1f%%" % (label, sd[edge].mean() * 255, np.mean(errs), np.mean(flips)))


if __name__ == "__main__":
    main()
