"""At what speed does motion compensation stop being worth doing?

THE PATTERN THAT PRODUCED THIS FILE. Every measurement on real gameplay says the
same thing, and it is not the thing the pipeline was built on:

    blend the two frames, no motion at all      11.89 rms   (locate.py)
    warp with the shipped 112 px field          17.57
    warp with an unbounded 224 px field         19.65       (reach.py)

Monotonic. The more the matcher is allowed to do, the worse the picture. Reach
is not the artefact -- widening the window made it worse, and the bounded window
turns out to be a regulariser rather than a limitation, because a matcher given
more range finds more distant wrong matches. Occlusion could not even be tested
until that was known, because the detector for it was reading saturation.

So the question is no longer which mechanism to add. It is whether motion
compensation is helping AT ALL at the speeds this game actually runs at, and if
it stops helping, where.

WHY THE EXISTING NUMBERS CANNOT ANSWER THAT. A triple is three consecutive REAL
frames, so A and C are two guest intervals apart and the displacement between
them is roughly twice what the pipeline ever sees -- gameplay.py says so in its
own header. Every result above is therefore scored on a harder problem than the
real one. It is entirely possible that motion compensation wins at the real
displacement and loses at twice it, and that all of the above is an artefact of
the test rather than of the pipeline.

That is a testable claim and this file tests it: score the same three answers
per triple, then split the triples by HOW FAR THE SCENE ACTUALLY MOVED and look
for the crossover.

MEASURING THE DISPLACEMENT WITHOUT THE THING BEING TESTED. Not from the fine
field: it pins at 112 px and 56% of it is pinned on this footage, so it cannot
report a distance it cannot represent. A quarter-resolution pass returns vectors
in quarter-resolution pixels, so the same window covers 448 px -- far past
anything here -- at a sixteenth of the search cost. That is the ruler.

READING THE RESULT ONTO THE DEVICE. A bucket here at D px covers two guest
intervals, so it corresponds to about D/2 px per interval on the device, which
is the number FG_LOG reports. The device reads 148-152 px per interval during
ordinary play. That assumes velocity is roughly constant across the two
intervals, which is true of a camera pan and false of a flick.

WHY THIS MATTERS RATHER THAN BEING TRIVIA. The pipeline can measure its own
displacement at runtime -- it already has the field -- so "stop warping and blend
above X px" is a shippable rule costing one comparison, not a new pass. If the
crossover sits below what the game does during play, then frame generation is
degrading the picture most of the time and the fix is to know when to stop.

    python regime.py recording.mp4 [--triples 24]
"""
import sys

import numpy as np

import bench
import consensus
import gameplay
import occlusion as O
import pyramid as P

BLOCK = P.BLOCK
MEDIAN_PASSES = 10

# Quarter resolution: the window reaches 4 x 112 = 448 full-resolution pixels,
# which nothing in this footage approaches, at a sixteenth of the search.
RULER = 4

# Two guest intervals per triple, so device-side displacement is about half.
INTERVALS = 2

BUCKETS = (40, 80, 120, 160, 1e9)


def displacement(A, C):
    """How far the scene went between the two real frames, in pixels.

    The median rather than the mean: a handful of blocks that lost a tie-break
    should not move the ruler, and a camera pan moves most of the frame together
    so the median is the camera.
    """
    field = P.level(C, A, RULER)
    return float(np.median(np.linalg.norm(field, axis=-1)))


def answers(A, C):
    """The three things that could be shown, given the two real frames."""
    fine = bench.estimate(C, A, radius=P.WINDOW)
    field = consensus.vector_median(fine, fine, passes=MEDIAN_PASSES)
    older, newer = O.warp_parts(A, C, field)
    pinned = (np.linalg.norm(fine, axis=-1) >= P.LIMIT).mean() * 100
    return {
        "hold the older frame": A,
        "blend, no motion": (A + C) * 0.5,
        "warp (what ships)": (older + newer) * 0.5,
    }, pinned


def score(img, B):
    err = np.abs(img - B).mean(axis=2)
    flat = np.sort(err.ravel())
    return (float(np.sqrt(((img - B) ** 2).mean()) * 255.0),
            float(flat[int(len(flat) * 0.99):].mean() * 255.0))


def verify():
    """The ruler must read the distance it is handed, or the buckets are noise."""
    for shift in ((60, 20), (160, 48), (240, 72)):
        C, A, _, s = P.scene(shift)
        got = displacement(A, C)
        want = float(np.hypot(*s))
        assert abs(got - want) < 0.25 * want + 8, \
            "ruler read %.0f px on a %.0f px roll" % (got, want)
        print("harness ok: %3.0f px roll reads %5.1f px" % (want, got))
    print()


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    limit = 24
    if "--triples" in sys.argv:
        limit = int(sys.argv[sys.argv.index("--triples") + 1])

    verify()
    # min_motion low, because the whole point is to see the slow end too.
    got = gameplay.triples(sys.argv[1], limit, min_motion=0.4)
    print("%d triples of consecutive real frames\n" % len(got))

    names = ("hold the older frame", "blend, no motion", "warp (what ships)")
    rows = []
    for A, B, C, _ in got:
        d = displacement(A, C)
        imgs, pinned = answers(A, C)
        rows.append((d, pinned, {k: score(v, B) for k, v in imgs.items()}))

    rows.sort(key=lambda r: r[0])
    print("  %-14s %4s %7s %6s   %s"
          % ("displacement", "n", "per int", "pinned",
             "   ".join("%-22s" % n for n in names)))
    print("  %-14s %4s %7s %6s   %s"
          % ("(px, A to C)", "", "px", "%",
             "   ".join("%-22s" % "rms / worst 1%" for _ in names)))

    lo = 0
    for hi in BUCKETS:
        got_b = [r for r in rows if lo <= r[0] < hi]
        if got_b:
            label = "%d-%d" % (lo, hi) if hi < 1e9 else "%d+" % lo
            d = np.mean([r[0] for r in got_b])
            pin = np.mean([r[1] for r in got_b])
            cells = []
            for n in names:
                rms = np.mean([r[2][n][0] for r in got_b])
                w = np.mean([r[2][n][1] for r in got_b])
                cells.append("%-22s" % ("%6.2f / %6.2f" % (rms, w)))
            print("  %-14s %4d %7.0f %6.0f   %s"
                  % (label, len(got_b), d / INTERVALS, pin, "   ".join(cells)))
        lo = hi

    print()
    print("  The crossover is the first row where `warp` stops beating `blend`.")
    print("  Halve the displacement column to compare with what FG_LOG reports")
    print("  on the device, which is one interval rather than two.")
    print()
    print("  If warp loses in every row, motion compensation is not paying for")
    print("  itself at any speed this footage contains, and the ~15%% of GPU it")
    print("  costs is buying a worse picture than averaging two frames.")


if __name__ == "__main__":
    main()
