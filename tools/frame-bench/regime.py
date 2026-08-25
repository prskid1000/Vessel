"""At what speed does motion compensation stop being worth doing?

THE PATTERN THAT PRODUCED THIS FILE. On a rolled photograph at the right phase,
motion compensation is worth roughly a factor of two -- warp 8.16 rms against
blend 14.99. On real gameplay it loses, and it loses to answers that do no work
at all (phase.py, at the true phase, 24 triples):

    blend the two frames, no motion at all      15.91 rms      72.99 worst 1%
    hold the older frame                        17.44          80.33
    warp with the shipped 112 px field          18.51          93.99
    warp with an unbounded 224 px field         19.65          87.00   (reach.py)

Reach is not the artefact: widening the window made it worse, so the bound is a
regulariser rather than a limitation -- a matcher given more range finds more
distant wrong matches. Occlusion is not the artefact either; once its detector
was given a window wide enough not to pin, it scored 0.14-0.27x against chance
on the worst pixels, tracking texturelessness rather than anything geometric.

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

Several recordings can be given at once, and should be: one session of one game
is one camera style, and the slow end of the range may simply not be in it.

    python regime.py rec1.mp4 rec2.mp4 ... [--triples 24]
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

# Fine at the slow end, because that is where the question lives. The first run
# put twelve triples in one bucket and two in another, and those two were the
# only evidence about slow motion -- which is the regime the pipeline spends most
# of its time in and the one the two-interval doubling distorts most.
BUCKETS = (20, 40, 60, 80, 120, 160, 1e9)

# How many triples to score per bucket. The fine match is ~35 s each, so this is
# the whole runtime budget; five per bucket over seven buckets is half an hour.
PER_BUCKET = 5


def displacement(A, C):
    """How far the scene went between the two real frames, in pixels.

    The median rather than the mean: a handful of blocks that lost a tie-break
    should not move the ruler, and a camera pan moves most of the frame together
    so the median is the camera.
    """
    field = P.level(C, A, RULER)
    return float(np.median(np.linalg.norm(field, axis=-1)))


def answers(A, C, t):
    """The three things that could be shown, given the two real frames.

    Warped at the TRUE phase, not at 0.5. The first run of this file assumed the
    midpoint, which is right for only 31% of triples and costs the warp column
    about 15% of its error while leaving the other two untouched -- see phase.py
    and gameplay.triples. It did not change this file's conclusion, but it
    changed every number in it.
    """
    fine = bench.estimate(C, A, radius=P.WINDOW)
    field = consensus.vector_median(fine, fine, passes=MEDIAN_PASSES)
    older, newer = O.warp_parts(A, C, field, t=t)
    pinned = (np.linalg.norm(fine, axis=-1) >= P.LIMIT).mean() * 100
    return {
        "hold the older frame": A,
        "blend, no motion": (A + C) * 0.5,
        "warp (what ships)": older * (1.0 - t) + newer * t,
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
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if "--triples" in sys.argv:
        limit = int(sys.argv[sys.argv.index("--triples") + 1])
        args = [a for a in args if a != str(limit)]

    verify()
    # min_motion low, because the whole point is to see the slow end too.
    got = []
    for path in args:
        try:
            got += gameplay.triples(path, limit, min_motion=0.4, with_phase=True)
        except SystemExit as e:
            print("  skipped %s: %s"
                  % (path.replace("\\", "/").rsplit("/", 1)[-1], str(e)[:48]))
    if not got:
        sys.exit("no usable triples in any recording given")
    print("%d triples of consecutive real frames from %d recording(s)\n"
          % (len(got), len(args)))

    names = ("hold the older frame", "blend, no motion", "warp (what ships)")

    # **Measure first, score second.** The ruler is a quarter-resolution match
    # and costs a sixteenth of the fine one, so every triple can be measured
    # cheaply and only a BALANCED sample scored. Without this the sample is
    # whatever the footage happened to contain: the first run scored twelve fast
    # triples and two slow ones, and the slow end -- the part of the range the
    # answer turns on -- rested on two frames.
    measured = sorted((displacement(r[0], r[2]), i) for i, r in enumerate(got))
    print("  displacement spans %.0f to %.0f px over %d triples; sampling up to"
          " %d per bucket\n" % (measured[0][0], measured[-1][0], len(measured),
                                PER_BUCKET))

    chosen, lo = [], 0
    for hi in BUCKETS:
        here = [m for m in measured if lo <= m[0] < hi]
        # Spread across the bucket rather than taking the first few, which would
        # all come from one recording and one moment of one session.
        if len(here) > PER_BUCKET:
            idx = np.linspace(0, len(here) - 1, PER_BUCKET).round().astype(int)
            here = [here[i] for i in idx]
        chosen += here
        lo = hi

    rows = []
    for d, i in chosen:
        A, B, C, _, t = got[i]
        imgs, pinned = answers(A, C, float(t))
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
    print("  itself at any speed this footage contains, and the ~15% of GPU it")
    print("  costs is buying a worse picture than averaging two frames.")


if __name__ == "__main__":
    main()
