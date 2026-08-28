"""Texture flickering, which is not the same fault as a soft edge.

THE DISTINCTION THAT MATTERS. smear.py measures how far an EDGE is spread, and
answers a question about geometry. The complaint from the device is different
and was being answered with the wrong instrument: surface detail -- wood grain,
plaster, wall texture -- flickers between synthesised frames while the surface
itself is doing nothing but translate. That is a TEMPORAL fault on
high-frequency content, and a sharpness metric is blind to it: a frame can hold
exactly the right amount of gradient in exactly the right places and still be a
different picture from the one before it.

THE SCENE, AND WHY IT NEEDS NO GROUND TRUTH BEYOND ITS OWN MOTION. A textured
plane at constant velocity. Every synthesised frame should therefore be the
previous one translated, so if each output is translated BACK by the motion it
was drawn at, a correct pipeline produces the same picture every time and
anything else is the algorithm. Registering them first is what makes this a
measure of flicker rather than a measure of motion.

    flicker = mean temporal standard deviation, over registered frames,
              restricted to the textured pixels

WHAT IS BEING BLAMED, AND WHY THE SUSPECT IS `carry`. In InterpolateMaterial:

    ratio = fitStill / (fitStill + fitMoving + 1/2550)
    carry = smoothstep(0.3, 0.7, ratio)
    shown = mix(still, shown, carry)

`fitMoving` is the luma difference between the two fetch sites along the vector.
On flat or smoothly-shaded content a correct vector drives it to nearly zero and
`ratio` to nearly one, so `carry` sits pinned at 1 and the term does nothing.
On high-frequency texture it cannot: a vector that is right to within half a
pixel still lands the two fetches on different phases of the grain, so
`fitMoving` stays large no matter how good the field is. `ratio` then sits near
one half -- which is the steepest part of that smoothstep -- and image noise
swings `carry` across a large fraction of its range every frame. The pixel
slides between the motion-compensated answer and the static one, frame after
frame, and that is texture flicker.

This is the same shape as three faults already recorded here: the per-pixel
sign choice, the two-hypothesis chooser, and the decision margin. A binary or
near-binary decision taken from a cost that is frequently a near-tie flips on
noise, and every average-based diagnostic reads clean while it does so.

So each term is switched off in turn and the flicker re-measured. The one whose
removal collapses it is the one responsible; anything that changes nothing is
exonerated and can be left alone.

    python texflicker.py
"""
import numpy as np

import bench
import pyramid as P
from consensus import vector_median
from smear import BLOCK

NOISE = 0.004
N = 9
PHASE = 0.5


def smoothstep(a, b, x):
    t = np.clip((x - a) / (b - a), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def sequence(step, seed=5):
    """A textured plane translating at a constant rate, with per-frame grain."""
    rng = np.random.default_rng(seed)
    bg = P.background()
    return [np.clip(P.roll(bg, step[0] * i, step[1] * i)
                    + rng.normal(0, NOISE, bg.shape), 0, 1).astype(np.float32)
            for i in range(N)]


def interpolate(newer, older, field, phase=PHASE,
                use_carry=True, use_photometric=True):
    """InterpolateMaterial, with the two terms under test switchable.

    Mirrors the shader: the overlapped window, the mean vector, the photometric
    endpoint weights, and the carry blend back towards a stationary reading.
    """
    h, w = newer.shape[:2]
    gh, gw = field.shape[:2]
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)

    gx = (xs + 0.5) / BLOCK - 0.5
    gy = (ys + 0.5) / BLOCK - 0.5
    bx, by = np.floor(gx), np.floor(gy)
    wx = 0.5 - 0.5 * np.cos(np.pi * (gx - bx))
    wy = 0.5 - 0.5 * np.cos(np.pi * (gy - by))
    weights = [(1 - wx) * (1 - wy), wx * (1 - wy), (1 - wx) * wy, wx * wy]
    corners = ((0, 0), (1, 0), (0, 1), (1, 1))

    def block_vec(ox, oy):
        ix = np.clip(bx.astype(int) + ox, 0, gw - 1)
        iy = np.clip(by.astype(int) + oy, 0, gh - 1)
        return field[iy, ix]

    def sample(img, sx, sy):
        return img[np.round(sy).astype(int) % h, np.round(sx).astype(int) % w]

    vecs = [block_vec(ox, oy) for ox, oy in corners]
    mean = sum(w_[..., None] * v for w_, v in zip(weights, vecs))
    mx, my = mean[..., 0], mean[..., 1]

    ln, lo = bench.luma(newer), bench.luma(older)
    fit_still = np.abs(ln - lo)
    nx, ny = xs - mx * (1 - phase), ys - my * (1 - phase)
    ox_, oy_ = xs + mx * phase, ys + my * phase
    fit_moving = np.abs(sample(ln, nx, ny) - sample(lo, ox_, oy_))

    if use_photometric:
        still_new = 1.0 - smoothstep(2 / 255, 12 / 255,
                                     np.abs(sample(ln, nx, ny) - sample(lo, nx, ny)))
        still_old = 1.0 - smoothstep(2 / 255, 12 / 255,
                                     np.abs(sample(ln, ox_, oy_) - sample(lo, ox_, oy_)))
        moves = smoothstep(2 / 255, 12 / 255, fit_still)
        w_old = (1 - phase) * (1 - still_old * moves)
        w_new = phase * (1 - still_new * moves)
    else:
        w_old = np.full_like(fit_still, 1 - phase)
        w_new = np.full_like(fit_still, phase)
    both = w_old + w_new
    fallback = both < 1e-3
    w_old = np.where(fallback, 1 - phase, w_old)
    w_new = np.where(fallback, phase, w_new)

    out = np.zeros_like(newer)
    for w_, v in zip(weights, vecs):
        vx, vy = v[..., 0], v[..., 1]
        a = sample(older, xs + vx * phase, ys + vy * phase)
        b = sample(newer, xs - vx * (1 - phase), ys - vy * (1 - phase))
        q = (a * w_old[..., None] + b * w_new[..., None]) / np.maximum(
            (w_old + w_new)[..., None], 1e-4)
        out += w_[..., None] * q

    if use_carry:
        ratio = fit_still / (fit_still + fit_moving + 1.0 / 2550.0)
        carry = smoothstep(0.3, 0.7, ratio)[..., None]
        still = older * (1 - phase) + newer * phase
        out = still * (1 - carry) + out * carry
    return out, fit_moving, fit_still


def flicker(frames, outs, step, share=0.25):
    """Temporal spread of the registered outputs, over textured pixels only.

    Each output is rolled back by the motion it was drawn at, so a correct
    pipeline puts identical content in every slot.
    """
    reg = []
    for i, o in enumerate(outs):
        sx = -(step[0] * i + step[0] // 2)
        sy = -(step[1] * i + step[1] // 2)
        reg.append(P.roll(o, sx, sy))
    reg = np.array(reg)

    base = bench.luma(frames[0])
    gy, gx = np.gradient(base)
    grad = np.hypot(gx, gy)
    keep = grad >= np.quantile(grad, 1.0 - share)
    sd = reg.std(axis=0).mean(axis=-1)
    return float(sd[keep].mean() * 255)


def main():
    print(__doc__.split("\n\n")[0])
    print()
    for step in ((16, 6), (30, 10)):
        frames = sequence(step)
        raw = [bench.estimate(frames[i], frames[i - 1], radius=P.WINDOW)
               for i in range(1, N)]
        fields, prev = [], None
        for f in raw:
            c = vector_median(f, f, extra=prev)
            fields.append(c)
            prev = c
        truth = np.zeros_like(raw[0])
        truth[...] = (-step[0], -step[1])

        print("plane translating %s per frame -- every output should be the last, moved"
              % (step,))
        print("  %-34s %10s" % ("configuration", "flicker"))
        cfgs = (("shipped (carry + photometric)", True, True, fields),
                ("carry OFF", False, True, fields),
                ("photometric OFF", True, False, fields),
                ("both OFF (plain OBMC)", False, False, fields),
                ("shipped, but TRUE field", True, True, [truth] * len(fields)),
                ("both OFF, TRUE field", False, False, [truth] * len(fields)))
        for name, c, p, F in cfgs:
            outs = [interpolate(frames[i + 1], frames[i], F[i],
                                use_carry=c, use_photometric=p)[0]
                    for i in range(len(F))]
            print("  %-34s %9.2f" % (name, flicker(frames, outs, step)))

        _, fit_moving, fit_still = interpolate(frames[1], frames[0], fields[0])
        ratio = fit_still / (fit_still + fit_moving + 1.0 / 2550.0)
        base = bench.luma(frames[0])
        gy, gx = np.gradient(base)
        keep = np.hypot(gx, gy) >= np.quantile(np.hypot(gx, gy), 0.75)
        print("  ratio on textured pixels: mean %.2f, and %.0f%% of them sit in"
              " the 0.3-0.7 ramp" % (ratio[keep].mean(),
                                     100 * ((ratio[keep] > 0.3) & (ratio[keep] < 0.7)).mean()))
        print()


if __name__ == "__main__":
    main()
