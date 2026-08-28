"""Does 3DRS's temporal candidate steady the field, and at what cost?

WHAT IS BEING TESTED. `MedianMaterial` offers ten candidates per block: the
nine of a 3x3 neighbourhood and the matcher's own vector for this block, the
last of these being de Haan's spatial 3DRS candidate. 3DRS's other candidate is
temporal -- the vector this block carried at the previous frame -- and it was
never here. This measures whether adding it as an eleventh does what it is
supposed to do.

WHY THIS CAN BE ANSWERED ON A LAPTOP WHEN SO LITTLE ELSE CAN. Every previous
attempt to judge a field change here was contaminated by `bench.estimate`, a
stand-in for a hardware matcher it does not resemble: on a nearly-static scene
it put 19% of blocks at 100 pixels or more and scored a warp as four times
worse than a blend where the device's own `fg truth` read exactly 0.0% harm.

That objection does not apply to this test, because this test does not compare
the matcher against anything. It runs ONE matcher and TWO filters over its
output and reports the difference between the filters. Whatever the stand-in
gets wrong, both variants get wrong identically, and it is subtracted.

WHY THE SEQUENCE LOOKS LIKE THIS. Constant velocity with per-frame noise, from
flicker.py, and both halves of that matter:

  - constant velocity means the true field is IDENTICAL in every pair, so any
    frame-to-frame movement in the estimate is the algorithm's own instability
    and needs no ground truth to detect
  - noise is the artefact, not a detail. Where two offsets explain a block
    almost equally well the winner is decided by image noise, so a near-tie
    resolves one way this frame and the other way next. A block alternating
    between two vectors is a block alternating between two pictures, and that
    is the shimmer.

WHAT WOULD MAKE THIS A FAILURE. Two numbers have to move in opposite
directions, and reporting only the first would be the same mistake this
directory has already made four times:

  - `field px` must FALL. That is the point.
  - `lag` must stay near zero. The temporal candidate is offered, not blended,
    so it can only win by agreeing with the nine neighbours around it -- but if
    it is winning where the scene genuinely changed, the field is being dragged
    towards a stale answer and the filter has bought stability with correctness.
    Measured against a step change in velocity, which is the case that punishes
    exactly that.

    python temporal.py
"""
import numpy as np

import bench
import pyramid as P
from consensus import vector_median
from flicker import NOISE, sequence

FRAMES = 12


def fields_for(frames):
    """The matcher's raw output for each consecutive pair. Shared by both variants."""
    return [bench.estimate(frames[i], frames[i - 1], radius=P.WINDOW)
            for i in range(1, len(frames))]


def filter_plain(raw):
    """Ten candidates: the 3x3 neighbourhood and the block's own measurement."""
    return [vector_median(f, f) for f in raw]


def filter_temporal(raw):
    """Eleven: the same, plus what this block said one frame ago."""
    out, previous = [], None
    for f in raw:
        current = vector_median(f, f, extra=previous)
        out.append(current)
        previous = current
    return out


def churn(fields):
    """Mean distance a block's vector travels between consecutive real frames.

    The truth never changes in this sequence, so this is entirely the
    algorithm's own noise.
    """
    a = np.array(fields)
    return float(np.linalg.norm(a[1:] - a[:-1], axis=-1).mean())


def output_flicker(frames, fields):
    """Brightness reversing direction frame to frame, which is what the eye catches."""
    warped = np.array([P.warp(frames[i + 1], frames[i], fields[i], 0.5)
                       for i in range(len(fields))])
    d = np.diff(warped, axis=0)
    reversals = (np.sign(d[1:]) * np.sign(d[:-1]) < 0) & (np.abs(d[1:]) > 4.0 / 255)
    return float(reversals.mean() * 100)


def edge_flicker(frames, fields, share=0.10):
    """The same reversals, counted ONLY where the picture has an edge.

    **The whole-frame mean is blind to the complaint.** The artefact reported
    from the device is a vertical edge -- a door frame, a wall corner --
    rendering as a smeared band that changes between frames. Edges are a few per
    cent of the pixels, so a mean over the frame moves by a point or two while
    the thing being looked at doubles.

    This directory has made that mistake four times and written it down each
    time. It also has the precedent that matters here: a decision margin once cut
    field flips almost in half and left whole-frame flicker at 36.8% either way,
    and on the phone that was "fewer switches, no less flashing".

    So: the top `share` of pixels by luma gradient, and the reversals among only
    those.
    """
    base = bench.luma(frames[0])
    gy, gx = np.gradient(base)
    grad = np.hypot(gx, gy)
    keep = grad >= np.quantile(grad, 1.0 - share)

    warped = np.array([P.warp(frames[i + 1], frames[i], fields[i], 0.5)
                       for i in range(len(fields))])
    d = np.diff(warped, axis=0)
    reversals = (np.sign(d[1:]) * np.sign(d[:-1]) < 0) & (np.abs(d[1:]) > 4.0 / 255)
    if reversals.ndim == 4:
        reversals = reversals.any(axis=-1)
    return float(reversals[:, keep].mean() * 100)


def lag_after_step(far, near, seed=11):
    """How far each filter is from the truth on the frame the velocity changes.

    **The control that decides whether the stability was bought honestly.** A
    filter that simply repeats last frame's answer scores perfectly on churn and
    is useless. So the velocity is changed halfway through, and the question is
    how much further from the new truth the temporal variant sits than the plain
    one on the frames immediately after.
    """
    rng = np.random.default_rng(seed)
    bg = P.background()
    half = FRAMES // 2
    out, truth = [], []
    x = y = 0.0
    for i in range(FRAMES):
        v = far if i < half else near
        out.append(np.clip(P.roll(bg, int(x), int(y))
                           + rng.normal(0, NOISE, bg.shape), 0, 1).astype(np.float32))
        truth.append(v)
        x += v[0]
        y += v[1]

    raw = fields_for(out)
    plain, temporal = filter_plain(raw), filter_temporal(raw)
    # The pairs that straddle and follow the change.
    idx = [half - 1, half, half + 1]
    idx = [i for i in idx if 0 <= i < len(raw)]

    def err(fields):
        e = []
        for i in idx:
            want = np.array(truth[i + 1], dtype=np.float32)
            e.append(np.linalg.norm(fields[i] - want, axis=-1).mean())
        return float(np.mean(e))

    return err(plain), err(temporal)


def main():
    print(__doc__.split("\n\n")[0])
    print()
    for far, near in (((16, 5), (40, -9)), ((30, 9), (8, 3))):
        frames, _ = sequence(far, near)
        raw = fields_for(frames)
        plain, temporal = filter_plain(raw), filter_temporal(raw)

        print("distant %s, near %s per frame -- the truth never changes"
              % (far, near))
        print("  %-28s %10s %10s %12s"
              % ("filter", "field px", "flicker", "at edges"))
        # **The floor.** One field, reused for every frame, so churn is zero by
        # construction. Whatever flicker survives that is not the field moving --
        # it is the input noise and the warp, and no amount of steadying vectors
        # can reach it. Without this row the two above cannot be read at all.
        frozen = [plain[0]] * len(plain)
        for name, f in (("ten candidates (shipped)", plain),
                        ("eleven, with temporal", temporal),
                        ("frozen field (churn = 0)", frozen)):
            print("  %-28s %10.3f %9.2f%% %11.2f%%"
                  % (name, churn(f), output_flicker(frames, f),
                     edge_flicker(frames, f)))
        a, b = churn(plain), churn(temporal)
        if a > 1e-9:
            print("  %-28s %9.1f%%" % ("change in field churn", (b - a) / a * 100))
        print()

    print("the control: error on the frames where the velocity CHANGES")
    print("  %-28s %10s %10s" % ("", "plain", "temporal"))
    for far, near in (((16, 5), (40, -9)), ((30, 9), (8, 3))):
        p, t = lag_after_step(far, near)
        print("  %-28s %10.3f %10.3f" % ("%s -> %s" % (far, near), p, t))


if __name__ == "__main__":
    main()
