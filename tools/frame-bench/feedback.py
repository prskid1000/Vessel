"""Can the aiming guess come from the last frame's field instead of the coarse one?

WHY ASK. The refine aims the fine matcher at a guess and measures the residual,
so the motion it can express is the band [guess - window, guess + window]. The
guess is currently the mean of the COARSE field, which is itself a matcher
result and therefore bounded by the coarse window: about 226 full-resolution
pixels here. Measured on the device at 150 px, so it is not saturating yet -- but
the ceiling is fixed, and a faster pan reaches it.

The previous frame's FINISHED field has no such ceiling. It already contains
guess plus residual, so its mean can be larger than any window, and using it
would make reach grow with velocity rather than stop at a constant.

WHAT COULD GO WRONG, AND WHY IT NEEDS A SEQUENCE. A guess derived from a field
that was built using the guess is a feedback loop. If it is ever wrong it can
confirm itself: aim too far, measure a residual against the wrong place, feed
that back, aim further. A frame-pair test cannot see this at all -- every scene
in this directory except flicker.py is two frames, and a runaway needs a
sequence to appear.

So this runs a sequence and reports whether the guess CONVERGES on the truth or
walks away from it, at speeds inside and beyond the coarse pass's reach.

    python feedback.py
"""
import numpy as np

import bench
import pyramid as P
import flicker as F

WINDOW = P.WINDOW
COARSE_REACH = 2.0 * WINDOW      # what the coarse pass can express, doubled


def aim(newer, older, guess):
    """Shift the older frame by `guess`, match, and return guess + residual.

    The device's refine, with the guess passed in rather than derived, so the
    same code can be driven from the coarse field or from the last field.
    """
    shifted = P.roll(older, int(round(-guess[0])), int(round(-guess[1])))
    residual = bench.estimate(newer, shifted, radius=WINDOW)
    return guess + residual


def coarse_guess(newer, older):
    """What ships: the mean of the coarse field, bounded by the coarse window."""
    return np.median(P.level(newer, older, 2).reshape(-1, 2), axis=0)


def run(velocity, frames=8, damping=1.0, seed=5):
    """Both schemes over a sequence at constant velocity.

    `damping` blends the fed-back guess towards the coarse one; 1.0 is pure
    feedback, 0.0 is what ships.
    """
    rng = np.random.default_rng(seed)
    bg = P.background()
    seq = []
    for i in range(frames):
        f = P.roll(bg, velocity[0] * i, velocity[1] * i)
        seq.append(np.clip(f + rng.normal(0, F.NOISE, f.shape), 0, 1).astype(np.float32))

    truth = -np.array(velocity, dtype=np.float32)     # negated convention
    rows = []
    fed = np.zeros(2, dtype=np.float32)
    for i in range(1, len(seq)):
        newer, older = seq[i], seq[i - 1]
        cg = coarse_guess(newer, older)

        shipped = aim(newer, older, cg)
        blended = fed * damping + cg * (1.0 - damping)
        fedfield = aim(newer, older, blended)
        # Next frame's guess is this frame's answer.
        fed = np.median(fedfield.reshape(-1, 2), axis=0)

        rows.append((
            np.linalg.norm(np.median(shipped.reshape(-1, 2), axis=0) - truth),
            np.linalg.norm(np.median(fedfield.reshape(-1, 2), axis=0) - truth),
            np.linalg.norm(blended - truth),
        ))
    return np.array(rows)


def main():
    print(__doc__.split("\n\n")[0])
    print()
    for velocity in ((60, 18), (140, 40), (200, 60), (300, 90)):
        dist = float(np.hypot(*velocity))
        beyond = " BEYOND the coarse reach" if dist > COARSE_REACH else ""
        print("velocity %s -- %.0f px per frame%s" % (velocity, dist, beyond))
        print("  %-26s %11s %11s" % ("frame", "coarse guess", "fed back"))
        rows = run(velocity)
        for i, (a, b, _) in enumerate(rows, start=1):
            print("  %-26d %11.1f %11.1f" % (i, a, b))
        print("  %-26s %11.1f %11.1f\n"
              % ("mean error, px", rows[:, 0].mean(), rows[:, 1].mean()))
    print("A guess that runs away shows as the fed-back column growing frame on")
    print("frame. One that converges settles at or below the coarse column, and")
    print("should beat it outright once the velocity passes %.0f px." % COARSE_REACH)


if __name__ == "__main__":
    main()
