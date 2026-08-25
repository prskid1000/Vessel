"""Is the artefact occlusion? Ask the two frames, since there is no depth buffer.

WHY THIS IS THE SUSPECT. locate.py scored ten triples of real gameplay and ruled
out everything the artefact was assumed to be:

    edge / flat        1.5x     -> not zigzag
    seam / interior    1.00x    -> not blocks tearing on the 8 px grid
    worst 1% / mean    5.8x     -> 80 levels against a mean of 11

That last line is the whole finding. The pipeline is not slightly wrong
everywhere; it is right almost everywhere and catastrophic on a small number of
pixels. Something is being interpolated with total confidence and total error.

Occlusion is the one thing the pipeline has never modelled. Every synthesised
frame is a bilateral blend of two real ones -- warp backwards into the older,
forwards into the newer, average -- and that construction ASSUMES every output
pixel exists in both. Content revealed from behind an object exists only in the
newer frame; content swallowed by one exists only in the older. The matcher is
still obliged to return a vector for those blocks, so it returns whatever
minimises SAD, and the blend faithfully mixes a correct sample with a sample of
something else entirely. A few hundred pixels, wrong by a lot, at object
boundaries -- which is also what "objects shattering" looks like from the sofa.

HOW IT IS DETECTED WITHOUT DEPTH. Match both ways. For a pixel that exists in
both frames the two vectors are opposites: follow the forward vector into the
older frame, read the backward vector there, and the two must cancel. For a
pixel that exists in only one frame there is nothing to cancel against, and the
round trip fails. That is the entire test, it needs no depth and no scene
knowledge, and it costs one extra glTexEstimateMotionQCOM -- 0.001 ms.

AND THE PRECONDITION THAT MAKES OR BREAKS IT -- read verify() before any number
below. A vector pinned at the edge of the search window is not a measurement,
and two pinned vectors have no reason to cancel, so a saturated field fails the
round trip everywhere for reasons that have nothing to do with occlusion. On a
photograph rolled 167 px on a torus -- a scene containing no occluded pixel by
construction -- this test flags 85.4% of blocks. At 60 px it flags 9.9%.

The first run of this file was at the shipped 112 px window against real
gameplay that moves 148-152 px, so almost everything was pinned. It reported the
round trip failing on 80.8% of blocks and a lift of 0.35x -- BELOW chance, which
is the tell: the blocks that pin hardest are flat ones, where a wrong vector
fetches something that looks the same and the image error is therefore lowest.
It was measuring texturelessness with the sign flipped. See reach.py, which
exists because of that failure.

So the detector runs at a window wide enough not to pin, whatever the pipeline
ships, and verify() refuses the run if the premise does not hold.

WHAT WOULD MAKE THIS A FALSE POSITIVE, AND THE CONTROLS THAT CATCH IT. A mask
that covers 8% of the frame will contain 8% of the worst pixels by chance, so
coverage alone proves nothing; what matters is CAPTURE ABOVE COVERAGE. And a
round trip also fails wherever the match was meaningless to begin with -- a flat
wall matches everywhere, so its vectors are tie-breaks that need not agree. So
three rival predictors are scored on identical pixels:

    forward-backward inconsistency   the hypothesis
    neighbour field disagreement     free, and what the median filter already
                                     smooths -- if this explains it, no new pass
                                     is needed
    low texture                      the "it is just untextured blocks" objection
    random                           chance, which must score 1.0x lift

Six metrics in this directory have been wrong, one of them the metric every
pacing change was judged by. The random row is there so this one announces its
own failure.

THEN THE REPAIR, MEASURED RATHER THAN ARGUED. If the flagged pixels really are
occlusion, blending is the wrong operation there and taking one side is right.
Which side is not obvious, so all of them are scored: hold the older, hold the
newer, and pick per block by which sample the neighbourhood agrees with. A
repair that does not move the worst 1% is a repair of something else.

    python occlusion.py recording.mp4 [--triples 6]
"""
import sys

import numpy as np

import bench
import consensus
import gameplay
import pyramid as P

BLOCK = P.BLOCK
MEDIAN_PASSES = 10

# Round-trip error is scored against the size of the motion, not as a flat
# number of pixels: a 150 px vector that misses by 4 is a good match, the same
# miss on a 6 px vector is not. The constant is in the matcher's own units -- it
# searches on a 4 px lattice, so nothing below that is a disagreement at all.
REL = 0.05
FLOOR = 4.0

# The detector's window, which is NOT the pipeline's. 224 px covers the 148-152
# measured on the device with room to spare, so a failed round trip means the
# correspondence is absent rather than merely out of range. Four times the
# search of the shipped window, which is why this is a laptop diagnostic and not
# a shader.
DETECT = P.WINDOW * 2


def fields(A, C, radius=DETECT):
    """Both directions, unfiltered, at a window wide enough not to pin.

    Unfiltered on purpose. The shipped field runs ten median passes, and those
    passes exist precisely to make neighbouring vectors agree -- they would
    smooth away the disagreement this is trying to read. The warp below still
    uses the filtered field, because that is what ships; only the DETECTOR sees
    the raw one. Two different questions, two different inputs.

    Wide on purpose too, and for a reason that cost this file its first run: see
    the header. A pinned vector is not a measurement, and two of them cannot
    cancel.

    Block indices differ between the two: fwd is indexed by position in C, bwd
    by position in A. Everything downstream depends on remembering that.
    """
    fwd = bench.estimate(C, A, radius=radius)
    bwd = bench.estimate(A, C, radius=radius)
    return fwd, bwd


def verify():
    """Refuse to report unless the round trip can tell occlusion from range.

    Two rolled photographs, one inside the detector's window and one past the
    PIPELINE's window but still inside the detector's. Neither contains a single
    occluded pixel, so both must come back nearly clean. If the far one does not,
    the detector is reading saturation and every number below is about the search
    window rather than about occlusion -- which is exactly what happened the first
    time this ran.
    """
    for shift in ((60, 20), (160, 48)):
        C, A, _, s = P.scene(shift)
        _, flag = roundtrip(*fields(A, C))
        share = flag[P.textured(C)].mean() * 100
        assert share < 25, (
            "a pure %s roll has no occlusion, yet the round trip flags %.0f%% of"
            " textured blocks -- the detector is measuring its own window, not the"
            " picture" % (s, share))
        print("harness ok: pure %s roll flags %.0f%% of textured blocks" % (s, share))
    print()


def roundtrip(fwd, bwd):
    """How far the vector misses when followed there and back, per block.

    With newer(x) = older(x - s) the matcher returns -s, so a block at grid
    position p in C came from p + fwd[p] in A -- in pixels, hence the divide by
    BLOCK to land back on the grid. Read the backward vector there and the two
    must sum to zero for a pixel that exists in both frames.
    """
    gh, gw = fwd.shape[:2]
    ys, xs = np.mgrid[0:gh, 0:gw]
    ax = np.clip(np.round(xs + fwd[..., 0] / BLOCK).astype(int), 0, gw - 1)
    ay = np.clip(np.round(ys + fwd[..., 1] / BLOCK).astype(int), 0, gh - 1)
    back = bwd[ay, ax]
    miss = np.linalg.norm(fwd + back, axis=-1)
    scale = (np.linalg.norm(fwd, axis=-1) + np.linalg.norm(back, axis=-1)) * 0.5
    return miss, miss > (REL * scale + FLOOR)


def disagreement(field):
    """Spread of a block's vector against its eight neighbours.

    The free rival: no second matcher call, and it is the quantity the median
    filter is already minimising. If this captures the worst pixels as well as
    the round trip does, then nothing new is needed -- the existing filter is
    simply not being trusted far enough.
    """
    padded = np.pad(field, ((1, 1), (1, 1), (0, 0)), mode="edge")
    gh, gw = field.shape[:2]
    n = [padded[dy:dy + gh, dx:dx + gw] for dy in range(3) for dx in range(3)]
    return np.stack([np.linalg.norm(field - v, axis=-1) for v in n]).mean(axis=0)


def flatness(frame):
    """Inverse gradient per block: the "it is just untextured blocks" control."""
    l = bench.luma(frame)
    h, w = l.shape
    gh, gw = h // BLOCK, w // BLOCK
    gx = np.abs(np.diff(l, axis=1, append=l[:, -1:]))
    gy = np.abs(np.diff(l, axis=0, append=l[-1:, :]))
    g = (gx + gy)[:gh * BLOCK, :gw * BLOCK]
    return -g.reshape(gh, BLOCK, gw, BLOCK).mean(axis=(1, 3))


def expand(block_map, shape):
    """Block resolution to pixel resolution, nearest, clipped to the frame."""
    h, w = shape
    full = np.repeat(np.repeat(block_map, BLOCK, axis=0), BLOCK, axis=1)
    if full.shape[0] < h or full.shape[1] < w:
        full = np.pad(full, ((0, max(0, h - full.shape[0])),
                             (0, max(0, w - full.shape[1]))), mode="edge")
    return full[:h, :w]


def warp_parts(A, C, field, t=0.5):
    """The bilateral warp, but with the two samples kept apart.

    Clamped rather than wrapped. pyramid.warp samples on the torus because its
    scenes ARE tori -- a rolled photograph -- and at 150 px of real motion that
    would fetch the far edge of the frame into the near one. The device's
    sampler is clamp-to-edge, so this is.

    Returns (older-side sample, newer-side sample). Their average is exactly
    what the pipeline presents; keeping them separate is what lets a repair take
    one side where the blend is the wrong operation.
    """
    h, w = A.shape[:2]
    fx = expand(field[..., 0], (h, w))
    fy = expand(field[..., 1], (h, w))
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)

    def sample(img, sx, sy):
        xi = np.clip(np.round(sx), 0, w - 1).astype(int)
        yi = np.clip(np.round(sy), 0, h - 1).astype(int)
        return img[yi, xi]

    older = sample(A, xs + fx * t, ys + fy * t)
    newer = sample(C, xs - fx * (1.0 - t), ys - fy * (1.0 - t))
    return older, newer


def err_of(img, B):
    return np.abs(img - B).mean(axis=2)


def rms_of(img, B):
    return float(np.sqrt(((img - B) ** 2).mean()) * 255.0)


def capture(score, err, coverage):
    """Of the worst 1% of pixels, what share does the top `coverage` of `score`
    contain -- and how does that compare with taking that share at random?"""
    n = err.size
    worst = np.zeros(n, dtype=bool)
    worst[np.argsort(err.ravel())[-int(n * 0.01):]] = True
    k = int(n * coverage)
    flagged = np.zeros(n, dtype=bool)
    flagged[np.argsort(score.ravel())[-k:]] = True
    got = float((worst & flagged).sum()) / max(worst.sum(), 1)
    return got / max(coverage, 1e-9)


def blur(x, pad=3):
    out = np.zeros_like(x)
    for dy in range(-pad, pad + 1):
        for dx in range(-pad, pad + 1):
            out += np.roll(np.roll(x, dy, axis=0), dx, axis=1)
    return out / float((2 * pad + 1) ** 2)


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    limit = 6
    if "--triples" in sys.argv:
        limit = int(sys.argv[sys.argv.index("--triples") + 1])

    verify()
    got = gameplay.triples(sys.argv[1], limit)
    print("%d triples of consecutive real frames" % len(got))
    print("detector and warp both at a %d px window, so nothing below is the\n"
          "shipped window running out -- that question belongs to reach.py\n"
          % DETECT)

    covers = (0.02, 0.05, 0.10)
    names = ("forward-backward", "neighbour disagreement", "low texture", "random")
    lifts = {k: {c: [] for c in covers} for k in names}
    share, inside, outside = [], [], []
    repairs = ("blend, unbounded field", "hold the older", "hold the newer",
               "pick the agreed side")
    scores = {k: [] for k in repairs}
    worsts = {k: [] for k in repairs}

    rng = np.random.default_rng(7)

    for A, B, C, _ in got:
        fwd, bwd = fields(A, C)
        miss, occluded = roundtrip(fwd, bwd)
        field = consensus.vector_median(fwd, fwd, passes=MEDIAN_PASSES)

        older, newer = warp_parts(A, C, field)
        blend = (older + newer) * 0.5
        err = err_of(blend, B)

        m = expand(occluded.astype(np.float32), err.shape) > 0.5
        share.append(occluded.mean() * 100)
        inside.append(err[m].mean() * 255 if m.any() else np.nan)
        outside.append(err[~m].mean() * 255)

        rivals = {
            "forward-backward": expand(miss, err.shape),
            "neighbour disagreement": expand(disagreement(fwd), err.shape),
            "low texture": expand(flatness(C), err.shape),
            "random": rng.random(err.shape),
        }
        for name, s in rivals.items():
            for c in covers:
                lifts[name][c].append(capture(s, err, c))

        # Which side to keep, where the round trip failed. Judged by which
        # sample the surrounding picture agrees with: the correct side is
        # continuous with its neighbourhood, the side that fetched the wrong
        # object is not. Read from the output's own surroundings, so it uses
        # nothing the device would not have.
        local = blur(blend)
        keep_newer = (np.abs(newer - local).mean(axis=2)
                      < np.abs(older - local).mean(axis=2))[..., None]
        picked = np.where(m[..., None], np.where(keep_newer, newer, older), blend)

        for name, img in (("blend, unbounded field", blend),
                          ("hold the older", np.where(m[..., None], older, blend)),
                          ("hold the newer", np.where(m[..., None], newer, blend)),
                          ("pick the agreed side", picked)):
            e = err_of(img, B)
            scores[name].append(rms_of(img, B))
            flat = np.sort(e.ravel())
            worsts[name].append(flat[int(len(flat) * 0.99):].mean() * 255)

    def m_(x):
        return float(np.nanmean(x))

    print("  THE ROUND TRIP FAILS ON %.1f%% OF BLOCKS" % m_(share))
    print("  %-34s %8.2f" % ("error there, levels", m_(inside)))
    print("  %-34s %8.2f" % ("error everywhere else", m_(outside)))
    print("  %-34s %8.2fx" % ("ratio", m_(inside) / max(m_(outside), 1e-6)))
    print()

    print("  SHARE OF THE WORST 1% OF PIXELS EACH PREDICTOR CATCHES,")
    print("  as a multiple of what the same coverage would catch at random")
    print("  %-26s %10s %10s %10s" % ("predictor", "top 2%", "top 5%", "top 10%"))
    for name in names:
        print("  %-26s %9.2fx %9.2fx %9.2fx"
              % (name, m_(lifts[name][0.02]), m_(lifts[name][0.05]),
                 m_(lifts[name][0.10])))
    print()
    print("  Random must read 1.00x. Anything else that reads 1.00x is not a")
    print("  predictor. A predictor worth acting on beats the free rivals.")
    print()

    print("  WHAT A REPAIR IS WORTH, on the flagged blocks only")
    print("  %-34s %8s %10s" % ("", "rms", "worst 1%"))
    for name in repairs:
        print("  %-34s %8.2f %10.2f" % (name, m_(scores[name]), m_(worsts[name])))
    print()
    print("  The worst-1% column is the one that matters. RMS is an average and")
    print("  averages are what hid this artefact for four metrics running.")


if __name__ == "__main__":
    main()
