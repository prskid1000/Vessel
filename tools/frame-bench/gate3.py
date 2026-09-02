"""A gate that cannot go black, proven on the device's own dumps.

The step-5 gate handed EVERY flat block the frame's dominant motion with three
things missing, and each is what put black on the screen:

  1. no check the dominant motion is trustworthy -- a dark scene is mostly flat
     blocks, so when the dominant vector was noise, most of the frame took the
     noise vector at once;
  2. no bound on the dominant vector -- an implausible one was applied anyway;
  3. no on-frame check -- a flat block near an edge plus a 150 px vector
     fetches past the frame, clamps to the edge, and over a dark scene that is
     black.

This gate closes all three, and adds the one guarantee the old one lacked: a
block is NEVER handed a vector whose fetch leaves the frame. The metric that
matters here is not just endpoint disagreement -- it is the fraction of the
frame whose bilateral fetch lands off-frame, the black proxy, which must not
rise above the shipped field's.

    black rule: a block's chosen vector must keep BOTH its bilateral fetch
                sites inside the frame, or the block keeps its own vector; and
                the gate runs at all only when agreement is real and the
                dominant vector is within the search range.

    python gate3.py [NN ...]
"""
import json, os, sys
import numpy as np

import bench
import dump as D
import interp
from consensus import vector_median
from fieldtest import endpoint_disagreement

BLOCK = 8
GATE_FROM = 100.0        # px, dominant speed below which the field is trusted as is
MIN_AGREE = 0.50         # the dominant motion must actually be dominant
MAX_VEC = 260.0          # px, beyond the two-stage reach nothing is a real vector
FLAT = 1.5 / 255.0       # gradient below which a block measured nothing
MARGIN = 2.0 / 255.0


def block_grad(newer, gh, gw):
    l = interp.luma(newer)
    gy, gx = np.gradient(l)
    return np.hypot(gx, gy)[:gh * BLOCK, :gw * BLOCK].reshape(gh, BLOCK, gw, BLOCK).mean((1, 3))


def block_sad(lo, ln, field):
    gh, gw = field.shape[:2]
    h, w = lo.shape
    ys, xs = np.mgrid[0:gh * BLOCK, 0:gw * BLOCK].astype(np.float32)
    full = np.repeat(np.repeat(field, BLOCK, axis=0), BLOCK, axis=1)
    u = (xs + full[..., 0] + 0.5) / w
    v = (ys + full[..., 1] + 0.5) / h
    moved = interp.sample(ln[..., None], u, v)[..., 0]
    return np.abs(lo[:gh * BLOCK, :gw * BLOCK] - moved).reshape(gh, BLOCK, gw, BLOCK).mean((1, 3))


def on_frame(field, shape, phase):
    """Per block, True when BOTH bilateral fetch sites stay inside the frame."""
    h, w = shape
    gh, gw = field.shape[:2]
    ys, xs = np.mgrid[0:gh, 0:gw].astype(np.float32)
    px, py = xs * BLOCK + BLOCK / 2, ys * BLOCK + BLOCK / 2
    fx, fy = field[..., 0], field[..., 1]
    nx, ny = px - fx * (1 - phase), py - fy * (1 - phase)
    ox, oy = px + fx * phase, py + fy * phase
    inside = lambda X, Y: (X >= 0) & (X < w) & (Y >= 0) & (Y < h)
    return inside(nx, ny) & inside(ox, oy)


def dominant(field, grad):
    """Gradient-weighted mean-shift centre and the agreement about it."""
    v = field.reshape(-1, 2)
    w = np.clip((grad.reshape(-1) - FLAT) / (8 / 255 - FLAT), 0, 1)
    if w.sum() < 1e-6:
        return np.zeros(2, np.float32), 0.0
    c = (v * w[:, None]).sum(0) / w.sum()
    for _ in range(4):
        tol = max(16.0, 0.2 * np.linalg.norm(c))
        inside = np.linalg.norm(v - c, axis=-1) <= tol
        if (w * inside).sum() < 1e-6:
            break
        c = (v * (w * inside)[:, None]).sum(0) / (w * inside).sum()
    tol = max(16.0, 0.2 * np.linalg.norm(c))
    agree = (np.linalg.norm(v - c, axis=-1) <= tol).mean()
    return c, float(agree)


def gate(field, older, newer, phase):
    h, w = older.shape[:2]
    gh, gw = field.shape[:2]
    grad = block_grad(newer, gh, gw)
    centre, agree = dominant(field, grad)
    speed = np.linalg.norm(centre)
    # Fault 1 and 2: the gate runs only when a dominant motion really exists
    # and is a plausible vector. Otherwise the field is left exactly as it is.
    if agree < MIN_AGREE or speed < GATE_FROM or speed > MAX_VEC:
        return field, dict(ran=False, agree=agree, speed=speed)

    constant = np.broadcast_to(centre, field.shape).astype(np.float32).copy()
    lo, ln = interp.luma(older), interp.luma(newer)
    own_sad = block_sad(lo, ln, field)
    glob_sad = block_sad(lo, ln, constant)
    flat = grad < FLAT
    # A flat block measured nothing, so take the dominant motion -- unless its
    # own vector already explains it as well (a flat block that happens to have
    # the right vector). A textured block keeps its own unless the dominant one
    # clearly beats it.
    take = np.where(flat, glob_sad <= own_sad + MARGIN, glob_sad + MARGIN < own_sad)
    out = np.where(take[..., None], constant, field)
    # Fault 3, the black rule: no block may be left with a vector that fetches
    # off the frame if its OWN vector stays on. This cannot make the picture
    # worse than the shipped field, which is `field` itself.
    off_out = ~on_frame(out, (h, w), phase)
    on_own = on_frame(field, (h, w), phase)
    rescue = off_out & on_own
    out = np.where(rescue[..., None], field, out)
    return out, dict(ran=True, agree=agree, speed=speed, took=take.mean(),
                     rescued=rescue.mean())


def black_fraction(field, older, newer, phase, sign):
    """Share of the frame whose bilateral fetch lands off-frame -- the black
    proxy, measured on pixels not blocks."""
    diag = {}
    interp.interpolate(newer, older, field, None, phase, sign, diagnostics=diag)
    mean = diag["mean"]
    h, w = older.shape[:2]
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    uv = np.stack([(xs + 0.5) / w, (ys + 0.5) / h], -1)
    n = uv - mean * (1 - phase)
    o = uv + mean * phase
    off = ((n < 0) | (n > 1)).any(-1) | ((o < 0) | (o > 1)).any(-1)
    return off.mean() * 100


def main():
    names = sys.argv[1:] or sorted(d for d in os.listdir(D.DUMP) if os.path.isdir(os.path.join(D.DUMP, d)))
    print("%-8s %8s %7s %6s  %-24s %-24s" % ("dump", "agree", "speed", "ran",
                                             "endpoints ship / gate", "black% ship / gate"))
    for n in names:
        folder = os.path.join(D.DUMP, n)
        meta, older, newer, shown, field, back = D.load(folder)
        if field.max() == 0:
            continue
        merged = meta["merged"] if meta.get("merged") is not None else field
        phase, sign = meta["phase"], meta["sign"] if "sign" in meta else meta["fieldSign"]
        g, info = gate(merged, older, newer, phase)
        g = vector_median(g, g, passes=10)
        ep_s = endpoint_disagreement(newer, older, merged, phase, sign)
        ep_g = endpoint_disagreement(newer, older, g, phase, sign)
        bl_s = black_fraction(merged, older, newer, phase, sign)
        bl_g = black_fraction(g, older, newer, phase, sign)
        print("%-8s %7.0f%% %6.0f %6s  %9.2f / %-9.2f  %9.2f%% / %-9.2f%%"
              % (n, info["agree"] * 100, info["speed"], "yes" if info["ran"] else "no",
                 ep_s, ep_g, bl_s, bl_g))


if __name__ == "__main__":
    main()
