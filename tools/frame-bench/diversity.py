"""Does harm track how MANY motions are present, rather than how big they are?

WHY THIS QUESTION AND NOT THE OTHER ONE. Every hypothesis this session has been
about the SIZE of the motion, and every one failed against a measurement:

    a 224 px window scores worse than the 112 px one   87.00 vs 80.43 worst-1%
    a half-and-quarter pyramid is neutral              79.20
    aiming the fine pass with a coarse guess           88.73
    harm against displacement, 20 px to 200 px         flat
    halving the multiple when the field is large       no improvement on device

And scale.py closed the story they were all built on. The device reports a mean
field of 124-151 px and the same footage measures 136.7 px median from the
pictures, so the field is in luma pixels and it is accurate at 150. The search
window is not 112 -- that number came from a comment, and "56% pinned" was
`|v| >= 100`, which means moving fast rather than saturated. Nothing was ever
running out of range, which is exactly why more range made it worse.

So magnitude is not the variable. The remaining candidate is DIVERSITY: not how
far the scene moved, but how many different ways it moved at once. Under a fast
pan, near geometry sweeps much further than distant geometry, and one vector per
8x8 block with a filter that drives every block towards its neighbours can carry
one motion well and two badly. That is also what was reported from the sofa long
before any of this was measured -- "shuttering less when camera draw further
object, more closer object more shuttering" -- and it is the same mechanism that
deletes the cursor, at parallax scale instead of object scale.

HOW DIVERSITY IS MEASURED. The mean distance from each block's vector to the
field's own median vector. A pure camera pan is one motion and scores near zero
however fast it is going; a scene with near and far layers scores the difference
between them. It is deliberately independent of magnitude -- a 150 px pan with no
parallax and a 20 px pan with none both score zero.

HARM IS THE DEVICE'S OWN DEFINITION so the numbers are comparable to `fg truth`:
of the pixels that changed between the two real frames, the share where the
synthesised frame ends up FURTHER from the truth than simply showing the old
frame. Scored at the true phase, which is not 0.5 -- see gameplay.triples.

READ THE TWO TABLES AGAINST EACH OTHER. If harm rises with diversity while
staying flat against magnitude, then the artefact has a variable at last, and it
is one nothing in the pipeline currently measures or acts on.

    python diversity.py recording.mp4 [--triples 24]
"""
import sys

import numpy as np

import bench
import consensus
import gameplay
import occlusion as O
import pyramid as P

MEDIAN_PASSES = 10
CHANGED = 0.008          # InterpolateMaterial's own floor for "this pixel moved"


def diversity(field):
    """How far the field departs from a single global motion, in luma pixels.

    The median rather than the mean as the centre, so a minority of wild blocks
    describes the scene's second motion rather than moving the reference.
    """
    flat = field.reshape(-1, 2)
    centre = np.median(flat, axis=0)
    return float(np.linalg.norm(flat - centre, axis=-1).mean())


def contrast(frame):
    """Mean gradient per block: how much a block has to match ON.

    **Metro is a game set in dark tunnels and the luma target is GL_R8.** The
    matcher compares 8-bit luma, so a block whose pixels all sit within a few
    levels of each other offers almost nothing to distinguish one offset from
    another, however fast the scene is moving. That would make matching
    unreliable for a reason with nothing to do with speed -- which is exactly the
    shape of "harm is flat against displacement".
    """
    l = bench.luma(frame)
    gx = np.abs(np.diff(l, axis=1, append=l[:, -1:]))
    gy = np.abs(np.diff(l, axis=0, append=l[-1:, :]))
    return float((gx + gy).mean() * 255.0)


def darkness(frame):
    """Mean luma in 8-bit levels. How much of R8's range the scene occupies."""
    return float(bench.luma(frame).mean() * 255.0)


def sharpness(frame):
    """High-frequency energy: what motion blur takes away.

    A blurred frame has less of it, and a block match on blurred content has a
    flatter cost surface -- the minimum sits in the right place but shallow, so
    noise moves it. Metro renders with camera motion blur, strongest during
    exactly the fast pans where the artefact is worst.
    """
    l = bench.luma(frame)
    lap = (l[:-2, 1:-1] + l[2:, 1:-1] + l[1:-1, :-2] + l[1:-1, 2:]
           - 4.0 * l[1:-1, 1:-1])
    return float(np.abs(lap).mean() * 255.0)


def harm(shown, older, truth):
    """The device's metric: worse than having done nothing, among moved pixels."""
    d_synth = np.linalg.norm(shown - truth, axis=-1)
    d_base = np.linalg.norm(older - truth, axis=-1)
    moved = d_base > CHANGED
    if not moved.any():
        return None
    return float((d_synth[moved] > d_base[moved]).mean() * 100.0)


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    limit = 24
    if "--triples" in sys.argv:
        limit = int(sys.argv[sys.argv.index("--triples") + 1])

    # **Every recording, because one is a single operating point.** The first
    # run took twenty triples from ninety seconds of one session and put all of
    # them in one displacement bucket and seventeen of twenty in one diversity
    # bucket. A correlation needs something to vary.
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    args = [a for a in args if a != str(limit)]
    got = []
    for path in args:
        try:
            got += gameplay.triples(path, limit, with_phase=True)
        except SystemExit:
            pass
    if not got:
        sys.exit("no usable triples in any recording given")
    rows = []
    for A, B, C, _, t in got:
        raw = bench.estimate(C, A, radius=P.WINDOW)
        field = consensus.vector_median(raw, raw, passes=MEDIAN_PASSES)
        older, newer = O.warp_parts(A, C, field, t=float(t))
        shown = older * (1.0 - float(t)) + newer * float(t)
        h = harm(shown, A, B)
        if h is None:
            continue
        mag = float(np.median(np.linalg.norm(raw, axis=-1)))
        stats = (contrast(C), darkness(C), sharpness(C))
        # **Diversity of the FILTERED field.** The raw one is full of tie-breaks
        # from flat blocks, which are noise rather than a second motion, and they
        # dominate the very quantity being tested -- the first run measured 84 px
        # of "diversity" on footage that is mostly one camera pan.
        rows.append((mag, diversity(field), h) + stats)

    if not rows:
        sys.exit("no triple had enough movement to score")

    mag = np.array([r[0] for r in rows])
    div = np.array([r[1] for r in rows])
    h = np.array([r[2] for r in rows])

    print("%d triples from %d recording(s), scored at the true phase\n"
          % (len(rows), len(args)))

    def table(title, key, cuts, unit):
        print("  BY %s" % title)
        print("  %-16s %6s %10s %12s %12s" % (unit, "n", "harm %", "median mag", "median div"))
        for lo, hi in zip(cuts, cuts[1:]):
            m = (key >= lo) & (key < hi)
            if m.sum() < 2:
                continue
            print("  %-16s %6d %10.1f %12.0f %12.1f"
                  % ("%d-%d" % (lo, hi) if hi < 1e9 else "%d+" % lo,
                     int(m.sum()), np.median(h[m]), np.median(mag[m]),
                     np.median(div[m])))
        print()

    table("HOW FAR IT MOVED", mag, [0, 40, 80, 120, 160, 1e9], "displacement px")
    table("HOW MANY WAYS IT MOVED", div, [0, 20, 40, 60, 90, 1e9], "diversity px")

    # One number for the whole question, so it does not rest on bucket edges.
    def corr(x):
        if x.std() < 1e-6 or h.std() < 1e-6:
            return float("nan")
        return float(np.corrcoef(x, h)[0, 1])

    con = np.array([r[3] for r in rows])
    dark = np.array([r[4] for r in rows])
    sharp = np.array([r[5] for r in rows])

    print("  CORRELATION WITH HARM, over every triple")
    print("  %-24s %8.2f" % ("displacement", corr(mag)))
    print("  %-24s %8.2f" % ("diversity", corr(div)))
    print("  %-24s %8.2f" % ("contrast", corr(con)))
    print("  %-24s %8.2f" % ("darkness (mean luma)", corr(dark)))
    print("  %-24s %8.2f" % ("sharpness (blur)", corr(sharp)))
    print()
    print("  %-24s %8.1f to %.1f" % ("contrast range", con.min(), con.max()))
    print("  %-24s %8.1f to %.1f" % ("mean luma range", dark.min(), dark.max()))
    print("  %-24s %8.1f to %.1f" % ("sharpness range", sharp.min(), sharp.max()))
    print()
    print("  Diversity clearly ahead is the finding: the artefact is how many")
    print("  motions are present, not how large they are, and nothing in the")
    print("  pipeline measures that. Both near zero would mean neither is the")
    print("  variable and the cause is not in the field at all.")


if __name__ == "__main__":
    main()
