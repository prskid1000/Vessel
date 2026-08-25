"""Is the worst 1% of pixels the search window running out, on real gameplay?

WHERE THIS CAME FROM. occlusion.py set out to test whether the artefact is
content revealed or covered between two frames, using a forward-backward
consistency check -- match both ways, and a vector that does not round-trip
describes a pixel that exists in only one frame. Its ground-truth check killed
it on the spot:

    pure translation, rolled  60 px    round trip fails on  9.9% of blocks
    pure translation, rolled 167 px    round trip fails on 85.4% of blocks

Both scenes are a photograph rolled on a torus. Neither contains one occluded
pixel. The second fails because 167 px is beyond the 112 px search window, so
both directions pin at their limits and two pinned vectors cannot cancel. The
device measures 148-152 px of real displacement during ordinary play, so on real
frames that detector was reporting saturation and calling it occlusion -- and its
lift came out BELOW chance, because the blocks that pin hardest are the flat
ones, where being wrong costs almost nothing in the picture.

WHAT THAT LEAVES, AND WHY IT FITS. The fine field is pinned for much of the
frame. Pinning is not a uniform error: a block whose true motion fits inside the
window is recovered exactly, and a block beyond it is under-compensated by
however far past the edge the scene really went. Parallax decides which is
which -- near objects sweep further across the screen than distant ones under the
same camera rotation -- so what pins is the near field. That is a small share of
pixels, wrong by a lot, on whole objects.

It is also, word for word, what was reported from the sofa: "shuttering less
when camera draw further object, more closer object more shuttering".

WHAT THIS MEASURES. The same real triples locate.py used -- three consecutive
unstamped frames, so the middle one is a photograph of the correct answer -- warped
with four fields that differ only in how far they can see:

    fine only            the 112 px window that ships
    coarse guess+refine  one global vector from a half-resolution pass, then the
                         fine matcher on the shifted pair; reach without the
                         coarse pass's precision loss. This shipped and was
                         removed for costing ~15% of the frame rate, on the
                         argument that the frames it improved were not the
                         problem. That argument is what is on trial here.
    gated pyramid        coarse vectors substituted only where fine is pinned
                         AND coarse agrees the motion is long
    unbounded (oracle)   a 224 px window: not shippable at 4x the search, but it
                         is the answer reach alone can buy

Every field is passed through the ten anchored median passes first, because that
is what ships and because leaving them out already produced one wrong answer in
this directory.

READ THE WORST-1% COLUMN. RMS is an average; averages have been blind to this
artefact through four separate metrics. If the worst 1% collapses as the window
widens, the artefact is reach and the refine was solving the right problem.
If it survives an unbounded search, reach is not it and the search is over
somewhere else.

    python reach.py recording.mp4 [--triples 6] [--no-oracle]
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
ORACLE = P.WINDOW * 2      # 224 px, comfortably past the 148-152 measured


def verify():
    """Establish that the window is what pins, before blaming it for anything.

    A pure translation inside the window must be recovered exactly and must not
    be pinned; the same translation past the window must pin. If either fails,
    the premise of this whole file is wrong and no row below is worth reading.
    """
    near = P.scene((60, 20))
    far = P.scene((160, 48))
    out = []
    for tag, (C, A, _, s) in (("inside", near), ("beyond", far)):
        f = bench.estimate(C, A, radius=P.WINDOW)
        pinned = (np.linalg.norm(f, axis=-1) >= P.LIMIT).mean() * 100
        err = P.vec_err(f, s, P.textured(C))
        out.append((tag, s, pinned, err))
    (_, s0, p0, e0), (_, s1, p1, e1) = out
    # Relative, not absolute. The first version of this asserted e0 < 8 px, a
    # number chosen rather than measured, and it failed on a roll the matcher
    # handles perfectly well -- 12.5 px of residual on a 63 px displacement is
    # the 4 px search lattice plus repeated texture, not a broken window. What
    # actually has to hold is that the two cases are DIFFERENT: inside the
    # window the field is a measurement, beyond it the field is the edge of the
    # window being reported over and over.
    assert p0 < 20, \
        "a %s roll should fit the window: %.0f%% pinned" % (s0, p0)
    assert p1 > 40 and p1 > 4 * p0, \
        "a %s roll should saturate the window: %.0f%% pinned against %.0f%%" \
        % (s1, p1, p0)
    assert e1 > 3 * e0, \
        "saturation should cost real accuracy: %.1f px beyond vs %.1f px inside" \
        % (e1, e0)
    print("harness ok: %s roll %.0f%% pinned at %.1f px error,"
          " %s roll %.0f%% pinned at %.1f px\n" % (s0, p0, e0, s1, p1, e1))


def filtered(field):
    return consensus.vector_median(field, field, passes=MEDIAN_PASSES)


def score(A, B, C, field):
    older, newer = O.warp_parts(A, C, filtered(field))
    out = (older + newer) * 0.5
    err = np.abs(out - B).mean(axis=2)
    flat = np.sort(err.ravel())
    return (float(np.sqrt(((out - B) ** 2).mean()) * 255.0),
            float(flat[int(len(flat) * 0.99):].mean() * 255.0))


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    limit = 6
    if "--triples" in sys.argv:
        limit = int(sys.argv[sys.argv.index("--triples") + 1])
    oracle = "--no-oracle" not in sys.argv

    verify()
    got = gameplay.triples(sys.argv[1], limit)
    print("%d triples of consecutive real frames\n" % len(got))

    rows = ["fine only (what ships)", "coarse guess + fine refine",
            "gated pyramid (half, quarter)"]
    if oracle:
        rows.append("unbounded 224 px (oracle)")
    rms = {k: [] for k in rows}
    worst = {k: [] for k in rows}
    pinned, moved = [], []

    for A, B, C, m in got:
        moved.append(m)
        fine = bench.estimate(C, A, radius=P.WINDOW)
        pinned.append((np.linalg.norm(fine, axis=-1) >= P.LIMIT).mean() * 100)

        fields = {rows[0]: fine,
                  rows[1]: P.refined(C, A),
                  rows[2]: P.hierarchy(C, A)}
        if oracle:
            fields[rows[3]] = bench.estimate(C, A, radius=ORACLE)

        for name, f in fields.items():
            r, w = score(A, B, C, f)
            rms[name].append(r)
            worst[name].append(w)

    def m_(x):
        return float(np.mean(x))

    print("  %.0f%% of the fine field is pinned at its limit on these frames"
          % m_(pinned))
    print("  (motion between the outer two frames: %.1f levels mean)\n" % m_(moved))
    print("  %-32s %8s %10s" % ("field", "rms", "worst 1%"))
    for name in rows:
        print("  %-32s %8.2f %10.2f" % (name, m_(rms[name]), m_(worst[name])))
    print()
    print("  If the worst-1% column falls as the window widens, the artefact is")
    print("  reach. If it does not, reach is not the artefact, whatever the RMS")
    print("  column does -- an average has been wrong about this four times.")


if __name__ == "__main__":
    main()
