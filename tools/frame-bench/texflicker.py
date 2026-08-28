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

    sample = P.sample

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


def fshift(img, dx, dy):
    """Move an image by a fractional amount with no resampling blur at all.

    A phase ramp in the Fourier domain is an exact translation of a periodic
    image, and the scene is periodic by construction (it is built with roll).
    Registering with this rather than with any sampler is what keeps the
    measurement about the pipeline's sampler and not the bench's.
    """
    h, w = img.shape[:2]
    fy = np.fft.fftfreq(h)[:, None]
    fx = np.fft.fftfreq(w)[None, :]
    ramp = np.exp(-2j * np.pi * (fx * dx + fy * dy))
    if img.ndim == 3:
        ramp = ramp[..., None]
    return np.real(np.fft.ifft2(np.fft.fft2(img, axes=(0, 1)) * ramp,
                                axes=(0, 1))).astype(np.float32)


def sampler_flicker(step, phases=(0.25, 0.5, 0.75), share=0.25):
    """The alternation between synthesised and real frames, per sampler.

    THE TABLES BELOW CANNOT SEE THE SAMPLER. At phase 0.5 with an even step
    every fetch offset is a whole pixel, so linear and nearest return the same
    texel and the bench's rounding was harmless. The device runs 4x, whose
    phases put the same vector on three different fractions, and a step that
    is not a multiple of four puts every one of them between texels.

    So: 4x phases, an odd step, the TRUE field (so the field's own noise is
    not in the number), and a stack holding the real frames as well as the
    synthesised ones -- because the fault is the difference between them.
    Each is registered by its exact fractional position with fshift, and the
    temporal spread over textured pixels is the flicker.
    """
    frames = sequence(step)
    truth = np.zeros((frames[0].shape[0] // BLOCK, frames[0].shape[1] // BLOCK, 2),
                     dtype=np.float32)
    truth[...] = (-step[0], -step[1])
    base = bench.luma(frames[0])
    gy, gx = np.gradient(base)
    grad = np.hypot(gx, gy)
    keep = grad >= np.quantile(grad, 1.0 - share)

    result = {}
    for mode in ("linear", "nearest", "cubic"):
        P.SAMPLING = mode
        stack = []
        for i in range(1, N):
            stack.append(fshift(frames[i - 1], -step[0] * (i - 1), -step[1] * (i - 1)))
            for t in phases:
                out, _, _ = interpolate(frames[i], frames[i - 1], truth, phase=t)
                stack.append(fshift(out, -(step[0] * (i - 1 + t)),
                                    -(step[1] * (i - 1 + t))))
        sd = np.array(stack).std(axis=0).mean(axis=-1)
        result[mode] = float(sd[keep].mean() * 255)
    P.SAMPLING = "nearest"
    return result


def fractional_sequence(step, n, seed=5):
    """A textured plane translating by a FRACTIONAL amount each frame.

    P.roll cannot express this and sequence() therefore cannot either: every
    scene in this file so far has moved a whole number of pixels, which is the
    one case where an integer field is exactly right. Real motion is not
    integer, and the question below is what an integer field does to it.
    """
    rng = np.random.default_rng(seed)
    bg = P.background()
    return [np.clip(fshift(bg, step[0] * i, step[1] * i)
                    + rng.normal(0, NOISE, bg.shape), 0, 1).astype(np.float32)
            for i in range(n)]


def quantisation(step=(30.5, 10.5), phases=(0.25, 0.5, 0.75), n=7,
                 sigma=0.35, share=0.25, seed=11):
    """Is the flicker the field's PRECISION or the field's NOISE? Both.

    The hardware matcher quantises to whole pixels, and both stages of the
    two-stage search do, so the field that reaches the shader is integer. A
    surface truly moving 30.5 px/frame can only be described as 30 or 31.

    THE PREDICTION THAT WAS WRONG. The reasoning that motivated this test was
    that rounding ALONE could not flicker -- a constant integer field is
    consistently wrong, every frame is displaced by the same amount, and a
    consistent error registers away. Only a field flipping between 30 and 31
    frame to frame would show. That is false, and the table says so:

        step (30.5, 10.5), field wobble sigma 0.35 px
          true                1.30
          rounded             1.92     <- constant field, no noise at all
          noisy               1.56
          noisy + rounded     1.75

        step (30, 10) -- integer truth, so rounding is a no-op. The control.
          true                1.23
          rounded             1.23     <- identical, as it must be
          noisy               1.51
          noisy + rounded     1.53

    A CONSTANT integer field is the largest single term here, worth more than
    a third of a pixel of field noise. The mechanism is the phase, which the
    prediction forgot: the shader displaces by `v * phase`, so half a pixel of
    field error becomes 0.125, 0.25 and 0.375 px of position error at the
    three 4x phases and exactly 0 on the real frame that follows. One wrong
    vector therefore misplaces each frame of the cadence by a DIFFERENT
    sub-pixel amount. It does not register away, because there is nothing
    constant about it.

    That is also why this could only be seen here. Every other scene in this
    file moves a whole number of pixels, which is the one case where an
    integer field is exactly right, and the control row above is that case:
    rounding changes nothing, 1.23 against 1.23.

    NOISE DITHERS THE ROUNDING. `noisy + rounded` (1.75) sits BELOW `rounded`
    (1.92): sub-pixel noise either side of a rounding boundary decorrelates
    the error across blocks, which is dithering, and it is why the fault is
    milder on a real noisy field than this bench's cleanest row suggests.

    WHAT IT DECIDES. Sub-pixel refinement of the field is worth roughly
    1.92 -> 1.30 where the field is otherwise clean and 1.75 -> 1.56 where it
    is not, so it is the fix for the precision half and does nothing for the
    noise half. Both halves are real and the noise half is not small.

    Run with the device's sampler. Under `nearest` every fetch position is
    re-rounded and the difference being measured here is destroyed.
    """
    was = P.SAMPLING
    P.SAMPLING = "linear"
    try:
        frames = fractional_sequence(step, n)
        h, w = frames[0].shape[:2]
        shape = (h // BLOCK, w // BLOCK, 2)
        truth = np.zeros(shape, dtype=np.float32)
        truth[...] = (-step[0], -step[1])

        base = bench.luma(frames[0])
        gy, gx = np.gradient(base)
        grad = np.hypot(gx, gy)
        keep = grad >= np.quantile(grad, 1.0 - share)

        rng = np.random.default_rng(seed)
        # One wobble per real frame, shared by the variants so that the only
        # difference between the last two rows is the rounding itself.
        wobble = [rng.normal(0, sigma, shape).astype(np.float32)
                  for _ in range(n - 1)]

        def field_for(kind, i):
            if kind == "true":
                return truth
            if kind == "rounded":
                return np.round(truth)
            if kind == "noisy":
                return truth + wobble[i]
            if kind == "noisy+rounded":
                return np.round(truth + wobble[i])
            raise ValueError(kind)

        out = {}
        for kind in ("true", "rounded", "noisy", "noisy+rounded"):
            stack = []
            for i in range(1, n):
                stack.append(fshift(frames[i - 1],
                                    -step[0] * (i - 1), -step[1] * (i - 1)))
                for t in phases:
                    img, _, _ = interpolate(frames[i], frames[i - 1],
                                            field_for(kind, i - 1), phase=t)
                    stack.append(fshift(img, -(step[0] * (i - 1 + t)),
                                        -(step[1] * (i - 1 + t))))
            sd = np.array(stack).std(axis=0).mean(axis=-1)
            out[kind] = float(sd[keep].mean() * 255)
        return out
    finally:
        P.SAMPLING = was


def main():
    print(__doc__.split("\n\n")[0])
    print()
    print("PRECISION OR NOISE: a plane moving 30.5 px a frame, so the true field is")
    print("not an integer and the matcher's whole-pixel field cannot be right. Same")
    print("wobble in the last two rows -- the only difference is the rounding.")
    q = quantisation()
    for kind in ("true", "rounded", "noisy", "noisy+rounded"):
        print("  %-22s %10.2f" % (kind, q[kind]))
    print()

    print("THE SAMPLER, which none of the tables below can see: 4x phases, the true")
    print("field, real frames in the stack, registered exactly. linear is the device")
    print("as it shipped; nearest is the device with whole-texel fetches.")
    print("  %-22s %10s %10s %10s" % ("step", "linear", "nearest", "cubic"))
    for step in ((15, 5), (30, 10), (17, 7)):
        r = sampler_flicker(step)
        print("  %-22s %10.2f %10.2f %10.2f"
              % (step, r["linear"], r["nearest"], r["cubic"]))
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
