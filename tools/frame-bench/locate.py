"""Find WHERE the interpolation is wrong, on real gameplay with a real answer.

WHY AVERAGES HAVE NOT FOUND IT. Ghosting, invented content and smearing are all
frame averages, and on the device they report that synthesised frames are BETTER
than real ones: ghosting 1.472% against 2.505%, invented 0.068% against 0.193%.
Meanwhile the artefacts reported from the screen are zigzag on straight edges and
objects breaking apart. Both are geometric and both are local -- a wavy edge holds
the same ink in nearly the same places, and a block that tears away from its
neighbours is a few hundred pixels in a million. An average cannot see either,
and four separate metrics in this directory have already been blind to the thing
they were pointed at.

WHAT THIS DOES. Takes three consecutive REAL frames from a recording -- the
synthesis stamp makes them recoverable -- interpolates the outer two at the
midpoint, and compares against the middle frame, which is a photograph of the
correct answer. Then it asks where the error lives:

  - on edges, against flat regions: zigzag is an edge phenomenon
  - at block boundaries, against block interiors: shattering is a block
    phenomenon, because the field is per-block and neighbouring blocks that
    disagree tear along the 8-pixel grid
  - and how much of it is concentrated in the worst one per cent of pixels,
    which separates "slightly wrong everywhere" from "very wrong somewhere"

The last is the one that matters. A pipeline that is 2% wrong everywhere looks
soft. A pipeline that is perfect over 99% of the frame and catastrophic on the
rest looks broken, and reports the same mean.

    python locate.py recording.mp4 [--triples 20]
"""
import sys

import numpy as np

import bench
import consensus
import gameplay
import pyramid as P

BLOCK = P.BLOCK
MEDIAN_PASSES = 10


def interpolate(a, c, filtered=True):
    """Our pipeline's answer for the midpoint of a and c.

    **Filtered, because the device filters.** The first version of this file
    warped with the raw block-matcher output and reported that interpolation was
    43% worse than a plain blend -- a number that says nothing about the shipped
    pipeline, which runs ten anchored median passes over the field before
    anything reads it. That filter is what took edge waviness from 9.54 px to
    0.013; leaving it out measures an algorithm nobody runs.
    """
    field = bench.estimate(c, a, radius=P.WINDOW)
    if filtered:
        field = consensus.vector_median(field, field, passes=MEDIAN_PASSES)
    return P.warp(c, a, field, 0.5)


def edges(frame, threshold=0.06):
    """Pixels with real gradient. Zigzag lives here and nowhere else."""
    g = bench.luma(frame)
    gx = np.abs(np.diff(g, axis=1, append=g[:, -1:]))
    gy = np.abs(np.diff(g, axis=0, append=g[-1:, :]))
    return (gx + gy) > threshold


def block_seams(shape):
    """Pixels on the 8-pixel field grid, where blocks that disagree tear."""
    h, w = shape
    ys, xs = np.mgrid[0:h, 0:w]
    return ((ys % BLOCK) == 0) | ((xs % BLOCK) == 0)


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    limit = 20
    if "--triples" in sys.argv:
        limit = int(sys.argv[sys.argv.index("--triples") + 1])

    got = gameplay.triples(sys.argv[1], limit)
    print("%d triples of consecutive real frames\n" % len(got))

    ours, raw, held, blended = [], [], [], []
    on_edge, off_edge, on_seam, off_seam, worst1 = [], [], [], [], []

    for A, B, C, _ in got:
        mid = interpolate(A, C)
        err = np.abs(mid - B).mean(axis=2)

        ours.append(np.sqrt((err ** 2).mean()) * 255)
        raw.append(np.sqrt(((interpolate(A, C, filtered=False) - B) ** 2).mean()) * 255)
        held.append(np.sqrt(((A - B) ** 2).mean()) * 255)
        blended.append(np.sqrt((((A + C) / 2 - B) ** 2).mean()) * 255)

        e = edges(B)
        on_edge.append(err[e].mean() * 255)
        off_edge.append(err[~e].mean() * 255)

        s = block_seams(err.shape)
        on_seam.append(err[s].mean() * 255)
        off_seam.append(err[~s].mean() * 255)

        flat = np.sort(err.ravel())
        worst1.append(flat[int(len(flat) * 0.99):].mean() * 255)

    def m(x):
        return float(np.mean(x))

    print("  %-34s %8s" % ("against the real middle frame", "rms"))
    print("  %-34s %8.2f" % ("show the old frame", m(held)))
    print("  %-34s %8.2f" % ("blend, no motion compensation", m(blended)))
    print("  %-34s %8.2f" % ("interpolated, raw field", m(raw)))
    print("  %-34s %8.2f" % ("interpolated, 10 median passes", m(ours)))
    print()
    print("  WHERE THE ERROR IS (mean absolute, levels)")
    print("  %-34s %8.2f" % ("on edges", m(on_edge)))
    print("  %-34s %8.2f" % ("off edges", m(off_edge)))
    print("  %-34s %8.1fx" % ("edge / flat ratio", m(on_edge) / max(m(off_edge), 1e-6)))
    print()
    print("  %-34s %8.2f" % ("on the 8 px block grid", m(on_seam)))
    print("  %-34s %8.2f" % ("inside blocks", m(off_seam)))
    print("  %-34s %8.2fx" % ("seam / interior ratio",
                              m(on_seam) / max(m(off_seam), 1e-6)))
    print()
    print("  %-34s %8.2f" % ("worst 1% of pixels", m(worst1)))
    print("  %-34s %8.1fx" % ("worst 1% / mean",
                              m(worst1) / max(m(on_edge) * 0.5 + m(off_edge) * 0.5, 1e-6)))
    print()
    print("  An edge ratio well above 1 is zigzag. A seam ratio well above 1 is")
    print("  blocks tearing from their neighbours. A large worst-1% against a")
    print("  small mean is the signature of an artefact an average cannot see.")


if __name__ == "__main__":
    main()
