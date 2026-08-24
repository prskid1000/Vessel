"""Measure temporal stability over a SEQUENCE, which a frame pair cannot show.

WHY THIS EXISTS. Every other scene in this directory is two frames and a ground
truth between them. That is the right shape for accuracy -- is this vector
correct, is this edge straight, is this subtitle ghosted -- and it is blind by
construction to anything that only appears over time.

It let a real artefact through. `ChooseMaterial` scores two motion fields per
block and keeps the better, and on a single pair it measured as a clear
improvement: 10.54 image RMS to 9.55, and it repaired the far layer the global
guess had damaged. Shipped to the device it produced visible flashing, because
where the two hypotheses explain a block almost equally well the winner is
decided by image noise, and it flips from one frame to the next. A block
alternating between two different vectors is a block alternating between two
different pictures.

That is the same failure the interpolation had when it settled the field's SIGN
per pixel, documented at length in SignMaterial: a binary decision taken from a
cost that is frequently a near-tie. The codebase already knew this and the bench
could not see it.

WHAT IT MEASURES. A sequence at constant velocity, with per-frame noise, so the
true field is identical in every pair and any variation in the estimate is the
algorithm's own instability:

  - how often a block's chosen hypothesis changes, when the truth did not
  - how much the field moves between consecutive pairs
  - flicker in the output: brightness reversing direction frame to frame, which
    is what the eye actually catches

Noise is the point, not a detail. Without it the two costs are exactly tied or
exactly separated and nothing flips; with it, near-ties resolve differently each
frame, which is the artefact.

    python flicker.py
"""
import numpy as np

import bench
import pyramid as P

BLOCK = P.BLOCK
FRAMES = 12
NOISE = 0.004          # about one level in 255, before the game's own grain


def sequence(far, near, split=0.55, seed=3):
    """Frames at constant velocity, each with its own noise.

    The motion never changes, so a correct pipeline produces the same field
    every time and anything else is instability.
    """
    rng = np.random.default_rng(seed)
    bg = P.background()
    row = int(bg.shape[0] * split)
    out = []
    for i in range(FRAMES):
        frame = P.roll(bg, far[0] * i, far[1] * i).copy()
        frame[row:] = P.roll(bg, near[0] * i, near[1] * i)[row:]
        out.append(np.clip(frame + rng.normal(0, NOISE, frame.shape), 0, 1)
                   .astype(np.float32))
    return out, row


def cost(newer, older, field, taps=4):
    """The shader's own scoring: a subsampled SAD per block at the midpoint."""
    h, w = newer.shape[:2]
    ln, lo = bench.luma(newer), bench.luma(older)
    gh, gw = field.shape[:2]
    step = BLOCK // taps
    total = np.zeros((gh, gw), dtype=np.float32)
    ys, xs = np.arange(gh) * BLOCK, np.arange(gw) * BLOCK
    for dy in range(0, BLOCK, step):
        for dx in range(0, BLOCK, step):
            py, px = ys[:, None] + dy, xs[None, :] + dx
            a = ln[py % h, px % w]
            sy = (py + np.round(field[..., 1]).astype(int)) % h
            sx = (px + np.round(field[..., 0]).astype(int)) % w
            total += np.abs(a - lo[sy, sx])
    return total


def choose(newer, older, aimed, plain, margin):
    """Keep the plain field only where it wins by `margin`.

    margin = 1.0 is a bare comparison, which is what shipped and what flashed.
    """
    take = cost(newer, older, plain) < cost(newer, older, aimed) * margin
    return np.where(take[..., None], plain, aimed), take


def run(margin, far, near):
    frames, row = sequence(far, near)
    fields, takes = [], []
    for i in range(1, len(frames)):
        newer, older = frames[i], frames[i - 1]
        aimed = P.refined(newer, older)
        plain = bench.estimate(newer, older, radius=P.WINDOW)
        field, take = choose(newer, older, aimed, plain, margin)
        fields.append(field)
        takes.append(take)

    takes = np.array(takes)
    fields = np.array(fields)

    # The truth never changes, so every flip is the algorithm's own noise.
    flips = (takes[1:] != takes[:-1]).mean() * 100
    # And every pixel of field movement is too.
    jitter = np.linalg.norm(fields[1:] - fields[:-1], axis=-1).mean()

    # What the eye catches: a warped frame that steps forward then back.
    warped = np.array([P.warp(frames[i + 1], frames[i], fields[i], 0.5)
                       for i in range(len(fields))])
    d = np.diff(warped, axis=0)
    reversals = (np.sign(d[1:]) * np.sign(d[:-1]) < 0) & (np.abs(d[1:]) > 4.0 / 255)
    return flips, jitter, reversals.mean() * 100, takes.mean() * 100


def main():
    print(__doc__.split("\n\n")[0])
    print()
    for far, near in (((16, 5), (40, -9)), ((30, 9), (8, 3))):
        print("distant %s, near %s per frame -- the truth never changes"
              % (far, near))
        print("  %-22s %8s %9s %9s %9s"
              % ("margin", "flips", "field px", "flicker", "plain used"))
        for margin in (1.0, 0.95, 0.9, 0.8):
            flips, jitter, flicker, used = run(margin, far, near)
            label = "1.0 (bare <, shipped)" if margin == 1.0 else "%.2f" % margin
            print("  %-22s %7.2f%% %9.2f %8.2f%% %8.1f%%"
                  % (label, flips, jitter, flicker, used))
        print()
    print("flips     blocks changing hypothesis when the motion did not")
    print("field px  how far the estimate moves between identical pairs")
    print("flicker   output pixels reversing direction frame to frame")
    print("plain     share of blocks taking the unaimed field at all")


if __name__ == "__main__":
    main()
