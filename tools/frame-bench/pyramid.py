"""Test whether a coarse second pass extends the matcher's reach, on the laptop.

WHY THIS SCENE IS DIFFERENT FROM THE OTHERS. Every other bench scene here moves
the background by a few dozen pixels, which the matcher can see. This one moves
it FURTHER THAN THE SEARCH WINDOW, because that is the failure the device
reports and no existing scene reproduces it:

    mean 123 px, 95% at the search limit     <- camera swinging at full speed
    mean  63 px,  8% at the search limit     <- ordinary movement
    mean   0 px,  0%                         <- standing still

The mean plateaus around 120 and will not rise. That is not a measurement of
motion, it is the edge of the window being reported over and over. A pinned
vector under-compensates by however far the scene really went, so the picture
lags and then snaps to the real frame: judder at correct present timing, only at
high speed, only at the multiples wide enough to open a 67 ms gap.

WHAT IS BEING TESTED. Running the same matcher a second time on a
half-resolution pair returns vectors in half-resolution pixels, so the same
window covers twice the distance; doubling them gives a field that can describe
motion the fine pass cannot see at all. Free on device -- the matcher measures
0.001 ms -- but "free" is not "correct", and whether it recovers the true
displacement is answerable here in seconds.

WHY THIS SCENE ROLLS RATHER THAN PASTES. `bench.build` shifts by pasting onto a
blank image, which is right for a 48 px pan and catastrophic here: at 240 px a
fifth of the frame is black, those blocks have no texture to match, and the
garbage vectors plus the invalid region dominate every metric. The first version
of this file used it and reported that motion compensation was worse than a
plain blend at every distance and that an UNBOUNDED search was the worst field
of all -- which cannot be true of a pure translation, and was the tell. Rolling
on the torus gives every pixel an exact correspondence and an exact ground
truth, so the only thing left to measure is reach.

THE SELF-CHECK EXISTS BECAUSE OF THAT. `verify()` asserts that an unbounded
search recovers the known shift and that warping with it beats a blend. If the
harness is lying, those two fail first, and no row below gets to be believed.

SIGN CONVENTION, MEASURED NOT ASSUMED. `bench.estimate(newer, older)` returns
the NEGATED displacement: for a background rolled by (+60,+20) its median
vector is exactly (-60,-20). Every warp and error term here is written against
that, and `verify()` re-establishes it on each run rather than trusting this
paragraph.

THE REVERSED-ORDER ROW is not hypothetical. The coarse call shipped as
`(newest, oldest)` while the fine call is `(previous, latest)` -- opposite
temporal order, so every substituted vector pointed backwards, into 95% of
blocks during fast motion. It is measured here to establish what that costs,
because "it looked worse" is not a number.

    python pyramid.py
"""
import os

import numpy as np
from PIL import Image

import bench

BLOCK = bench.BLOCK

# The device's window, measured rather than declared: the extension does not
# report it, vectors reached 113 px in the first survey of this driver, and the
# field stops rising at about 120. A multiple of 4, the estimator's lattice step.
WINDOW = 112
LIMIT = 100.0     # where a vector stops being trustworthy. See MedianMaterial.


def background():
    return np.asarray(Image.open(os.path.join(bench.BENCH, "bg.png")).convert("RGB"),
                      dtype=np.float32) / 255.0


def roll(img, sx, sy):
    return np.roll(np.roll(img, sx, axis=1), sy, axis=0)


# How a colour fetch lands between texels. Every warp in this bench goes
# through sample(), and this is the one thing about the device it did not
# model: the colour targets there are GL_LINEAR, so a fetch displaced by a
# fraction of a texel is a box blur of that fraction's width, and the fraction
# changes with the phase -- soft, softer, soft, then a sharp real frame. The
# bench rounded every position to a texel, which is the device AFTER
# InterpolateMaterial.predict() started snapping its displacements, and never
# the device before. "linear" is what shipped; "nearest" is what ships now.
SAMPLING = "nearest"


def _catmull_rom(img, sx, sy):
    """Sixteen-tap Catmull-Rom: sharp, position-exact, and the filter a shader
    can approximate with a handful of bilinear taps."""
    h, w = img.shape[:2]
    x0 = np.floor(sx)
    y0 = np.floor(sy)
    fx = (sx - x0).astype(np.float32)
    fy = (sy - y0).astype(np.float32)

    def weights(f):
        f2, f3 = f * f, f * f * f
        return (-0.5 * f3 + f2 - 0.5 * f,
                1.5 * f3 - 2.5 * f2 + 1.0,
                -1.5 * f3 + 2.0 * f2 + 0.5 * f,
                0.5 * f3 - 0.5 * f2)

    wx = weights(fx)
    wy = weights(fy)
    xi = [(x0.astype(int) + k - 1) % w for k in range(4)]
    yi = [(y0.astype(int) + k - 1) % h for k in range(4)]
    out = np.zeros(sx.shape + img.shape[2:], dtype=np.float32)
    for j in range(4):
        row = np.zeros_like(out)
        for i in range(4):
            wgt = wx[i] if img.ndim == 2 else wx[i][..., None]
            row += img[yi[j], xi[i]] * wgt
        out += row * (wy[j] if img.ndim == 2 else wy[j][..., None])
    return out


def sample(img, sx, sy, mode=None):
    """Read `img` at float positions, wrapped, with the device's filter."""
    mode = mode or SAMPLING
    h, w = img.shape[:2]
    if mode == "nearest":
        return img[np.round(sy).astype(int) % h, np.round(sx).astype(int) % w]
    if mode == "cubic":
        return _catmull_rom(img, sx, sy)
    if mode != "linear":
        raise ValueError(mode)
    x0 = np.floor(sx)
    y0 = np.floor(sy)
    fx = (sx - x0).astype(np.float32)
    fy = (sy - y0).astype(np.float32)
    if img.ndim == 3:
        fx, fy = fx[..., None], fy[..., None]
    x0 = x0.astype(int) % w
    y0 = y0.astype(int) % h
    x1 = (x0 + 1) % w
    y1 = (y0 + 1) % h
    top = img[y0, x0] * (1 - fx) + img[y0, x1] * fx
    bottom = img[y1, x0] * (1 - fx) + img[y1, x1] * fx
    return top * (1 - fy) + bottom * fy


def scene(shift):
    """The pair and the frame that belongs exactly between them.

    Both components are kept even so the middle frame is a whole-pixel roll and
    the ground truth carries no resampling error of its own.
    """
    sx, sy = int(shift[0]) & ~1, int(shift[1]) & ~1
    bg = background()
    return roll(bg, sx, sy), bg, roll(bg, sx // 2, sy // 2), (sx, sy)


def halve(img):
    """Box-downsample by two, standing in for the sampler's own filtering."""
    h, w = img.shape[:2]
    h, w = (h // 2) * 2, (w // 2) * 2
    a = img[:h, :w]
    return (a[0::2, 0::2] + a[1::2, 0::2] + a[0::2, 1::2] + a[1::2, 1::2]) / 4.0


def coarse_field(newer, older, radius=WINDOW):
    """Estimate on the half-size pair, in full-size pixels.

    The doubling is the whole point: 100 half-resolution pixels is 200
    full-resolution pixels, which no fine pass with this window can express.
    """
    return bench.estimate(halve(newer), halve(older), radius=radius) * 2.0


def upsample(field, shape):
    """Nearest-neighbour. A vector field at block resolution has no meaningful
    interpolation, and averaging two disagreeing vectors invents a third that
    matches neither."""
    gh, gw = shape
    sh, sw = field.shape[:2]
    yi = np.minimum((np.arange(gh) * sh) // gh, sh - 1)
    xi = np.minimum((np.arange(gw) * sw) // gw, sw - 1)
    return field[yi][:, xi]


def substitute(fine, coarse, limit=LIMIT):
    """Take the coarse vector wherever the fine one is at its limit.

    The asymmetry is deliberate: a coarse block covers sixteen full-resolution
    pixels rather than eight, so it cannot separate two motions as finely. It is
    preferred only where the fine field has stopped being a measurement.
    """
    out = fine.copy()
    at_limit = np.linalg.norm(fine, axis=-1) >= limit
    up = upsample(coarse, fine.shape[:2])
    out[at_limit] = up[at_limit]
    return out, at_limit.mean() * 100


def level(newer, older, shrink, radius=WINDOW):
    """Estimate on the pair shrunk by `shrink`, returned in full-size pixels.

    One function for every rung, because the reasoning is the same at each: the
    window is fixed in the pass's own pixels, so halving the image doubles the
    distance it covers, and scaling the result back up expresses motion the
    finer rung cannot represent at all.
    """
    a, b = newer, older
    for _ in range(int(np.log2(shrink))):
        a, b = halve(a), halve(b)
    return bench.estimate(a, b, radius=radius) * float(shrink)


def gated(fine, coarse, limit=LIMIT):
    """Substitute only where the coarse pass ALSO reports long motion.

    A fine vector at its limit is not proof that the scene moved that far -- a
    textureless block can land there by tie-break. If the coarse pass, which can
    see three times as far, reports something short for the same place, then the
    fine reading was spurious and replacing it with a coarser guess trades
    precision for nothing. Requiring both to agree that the motion is long is
    what makes the extension free in the rows that never needed it.
    """
    out = fine.copy()
    up = upsample(coarse, fine.shape[:2])
    take = ((np.linalg.norm(fine, axis=-1) >= limit)
            & (np.linalg.norm(up, axis=-1) >= limit))
    out[take] = up[take]
    return out, take.mean() * 100


def hierarchy(newer, older, shrinks=(2, 4), limit=LIMIT):
    """The gated rule applied up a pyramid, coarsest rung last.

    Each rung is only consulted where the one below it is still pinned, so the
    finest field that can express the motion is the one that survives.
    """
    field = bench.estimate(newer, older, radius=WINDOW)
    for shrink in shrinks:
        field, _ = gated(field, level(newer, older, shrink), limit=limit)
    return field


def refined(newer, older, limit=LIMIT):
    """Coarse for reach, then the FINE pass again for precision.

    Substitution alone buys range at the cost of accuracy: a coarse vector is
    quantised to sixteen full-resolution pixels and its block cannot separate
    two motions. That shows up as a floor -- at a 167 px displacement the
    substituted field stops at 93 px of error while an unbounded full-resolution
    search reaches 43.

    The fix is the step a real pyramid takes and plain substitution skips:
    REFINE. Shift one image by the motion already found, so what remains is a
    small residual, and run the fine matcher on the shifted pair -- it now only
    has to find that residual, which is comfortably inside its window. The final
    vector is the guess plus the correction, and it carries the fine pass's
    precision at the coarse pass's range.

    This is implementable on the device even though `glTexEstimateMotionQCOM`
    accepts no search-centre hint, because the shift can be applied to the
    texture instead of the search: one extra blit at an offset, then one more
    matcher call at 0.001 ms. What is used as the guess is the MEDIAN of the
    coarse field rather than its per-block values -- a single global shift, which
    is what a camera rotation actually is, and which cannot be wrong per-block
    in the way a noisy coarse vector can. Local motion is then whatever the fine
    residual finds on top of it, still to the full width of the window.
    """
    coarse = level(newer, older, 2)
    guess = np.median(coarse.reshape(-1, 2), axis=0)
    # The field is the negated displacement, so the image must be rolled the
    # other way for the residual to come out near zero.
    shifted = roll(older, int(round(-guess[0])), int(round(-guess[1])))
    residual = bench.estimate(newer, shifted, radius=WINDOW)
    return guess + residual


def textured(newer, threshold=0.02):
    """Which blocks carry enough gradient for a match to mean anything.

    This exists to answer "why is the vector error not zero". A flat block -- sky,
    a painted wall -- matches equally well at every offset, so its vector is a
    tie-break and no matcher can recover the true one. Those blocks are also the
    ones where a wrong vector costs nothing in the image, because what it fetches
    looks the same as what it should have fetched. Reporting the error over
    textured blocks separately separates the error that is a defect from the
    error that is a property of the picture.
    """
    l = bench.luma(newer)
    h, w = l.shape
    gh, gw = h // BLOCK, w // BLOCK
    gx = np.abs(np.diff(l, axis=1, append=l[:, -1:]))
    gy = np.abs(np.diff(l, axis=0, append=l[-1:, :]))
    g = (gx + gy)[:gh * BLOCK, :gw * BLOCK]
    per = g.reshape(gh, BLOCK, gw, BLOCK).mean(axis=(1, 3))
    return per >= threshold


def warp(newer, older, field, t=0.5):
    """Bilateral motion compensation at phase t, the pipeline's own scheme.

    Symmetric sampling -- backwards into the older frame and forwards into the
    newer one along the same vector -- so every output pixel has two sources by
    construction and holes are impossible.

    Written for the negated convention: with newer(x) = older(x - s) the field
    is f = -s, so the older frame is read at x + f*t and the newer at
    x - f*(1-t). Sampled with wrap, matching the scene's own torus.
    """
    h, w = newer.shape[:2]
    full = np.repeat(np.repeat(field, BLOCK, axis=0), BLOCK, axis=1)
    if full.shape[0] < h or full.shape[1] < w:
        full = np.pad(full, ((0, max(0, h - full.shape[0])),
                             (0, max(0, w - full.shape[1])), (0, 0)), mode="edge")
    full = full[:h, :w]

    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    fx, fy = full[..., 0], full[..., 1]

    a = sample(older, xs + fx * t, ys + fy * t)
    b = sample(newer, xs - fx * (1.0 - t), ys - fy * (1.0 - t))
    return a * (1.0 - t) + b * t


def vec_err(field, shift, mask=None):
    """Mean distance from the true vector, in pixels, in the negated convention."""
    truth = -np.array(shift, dtype=np.float32)
    d = np.linalg.norm(field - truth, axis=-1)
    return float(d[mask].mean() if mask is not None else d.mean())


def rms(a, b):
    return float(np.sqrt(((a - b) ** 2).mean()) * 255.0)


def verify():
    """Establish that the harness and the sign convention are sound.

    Everything below depends on this. An unbounded search must recover a pure
    translation, and warping with it must beat a blend; if either fails, the
    scene or the convention is wrong and no result is worth reading.
    """
    newer, older, truth, shift = scene((60, 20))
    field = bench.estimate(newer, older, radius=WINDOW)
    med = np.median(field.reshape(-1, 2), axis=0)
    expect = -np.array(shift, dtype=np.float32)
    assert np.allclose(med, expect), \
        "sign convention: median %s, expected %s" % (med, expect)
    warped = rms(warp(newer, older, field), truth)
    blended = rms((newer + older) / 2.0, truth)
    assert warped < blended, \
        "warp %.2f did not beat blend %.2f -- harness is wrong" % (warped, blended)
    print("harness ok: convention %s, warp %.2f beats blend %.2f\n"
          % (med, warped, blended))


def run(shift):
    newer, older, truth, shift = scene(shift)
    dist = float(np.hypot(*shift))

    fine = bench.estimate(newer, older, radius=WINDOW)
    pinned = (np.linalg.norm(fine, axis=-1) >= LIMIT).mean() * 100

    coarse = coarse_field(newer, older)
    # The bug that shipped: the coarse pair handed over in the opposite temporal
    # order from the fine pair.
    backwards = coarse_field(older, newer)

    fixed, share = substitute(fine, coarse)
    gate, gshare = gated(fine, coarse)
    deep = hierarchy(newer, older)
    fix2 = refined(newer, older)
    broken, _ = substitute(fine, backwards)
    tex = textured(newer)
    oracle = bench.estimate(newer, older, radius=WINDOW * 2)

    print("true displacement %.0f px %s -- %.0f%% of the fine field is pinned,"
          " %.0f%% substituted, %.0f%% when gated"
          % (dist, shift, pinned, share, gshare))
    print("  %-30s %9s %9s %9s"
          % ("field", "vec err", "textured", "image rms"))
    for name, f in (("fine only (bounded)", fine),
                    ("+ coarse, substituted", fixed),
                    ("+ coarse, gated on agreement", gate),
                    ("+ half and quarter, gated", deep),
                    ("+ coarse guess, FINE refine", fix2),
                    ("+ coarse, REVERSED order", broken),
                    ("unbounded window (oracle)", oracle)):
        print("  %-30s %9.1f %9.1f %9.2f"
              % (name, vec_err(f, shift), vec_err(f, shift, tex),
                 rms(warp(newer, older, f), truth)))
    print("  %-30s %9s %9s %9.2f"
          % ("no compensation (blend)", "-", "-",
             rms((newer + older) / 2.0, truth)))
    print("  (%.0f%% of blocks carry enough gradient for a match to mean"
          " anything)" % (tex.mean() * 100))
    print()


def layered(far, near, split=0.55):
    """A scene at two depths, which is the objection to a single global guess.

    The refine step is guided by ONE vector for the whole frame -- the median of
    the coarse field -- on the argument that a camera rotation moves everything
    together. Parallax breaks that argument: what is close moves further than
    what is distant, so a global guess is wrong for at least one of them.

    It should still work, because the guess is only a starting point: the fine
    residual pass has its whole window available on top of it, so any layer
    within about 113 px of the global motion is recovered exactly. This scene
    exists to check that claim rather than assert it, with the layers far enough
    apart that no single vector can flatter both.
    """
    bg = background()
    row = int(bg.shape[0] * split)

    def compose(fx, fy, nx, ny):
        out = roll(bg, fx, fy).copy()
        out[row:] = roll(bg, nx, ny)[row:]
        return out

    newer = compose(far[0], far[1], near[0], near[1])
    truth = compose(far[0] // 2, far[1] // 2, near[0] // 2, near[1] // 2)
    return newer, bg, truth, row


def run_layered(far, near):
    newer, older, truth, row = layered(far, near)
    gh = newer.shape[0] // BLOCK
    band = row // BLOCK
    tex = textured(newer)

    fine = bench.estimate(newer, older, radius=WINDOW)
    coarse = level(newer, older, 2)
    gate, _ = gated(fine, coarse)
    fix2 = refined(newer, older)
    guess = np.median(coarse.reshape(-1, 2), axis=0)

    print("two depths: distant %s, near %s -- the one global guess was %s"
          % (far, near, np.round(-guess).astype(int)))
    print("  %-30s %9s %9s %9s"
          % ("field", "far err", "near err", "image rms"))
    for name, f in (("fine only (bounded)", fine),
                    ("+ coarse, gated on agreement", gate),
                    ("+ coarse guess, FINE refine", fix2)):
        print("  %-30s %9.1f %9.1f %9.2f"
              % (name, vec_err(f[:band], far, tex[:band]),
                 vec_err(f[band:gh], near, tex[band:gh]),
                 rms(warp(newer, older, f), truth)))
    print("  %-30s %9s %9s %9.2f"
          % ("no compensation (blend)", "-", "-",
             rms((newer + older) / 2.0, truth)))
    print()


def main():
    print(__doc__.split("\n\n")[0])
    print()
    verify()
    # Below the window, astride it, and well past it. The first is the control:
    # if the coarse pass harms that row it is not free, whatever it does above.
    for shift in ((60, 20), (160, 48), (240, 72)):
        run(shift)
    # A hundred pixels apart in x, so no single vector describes both layers and
    # the residual pass has to do the work.
    run_layered((160, 48), (60, 20))
    run_layered((60, 20), (170, -40))


if __name__ == "__main__":
    main()
