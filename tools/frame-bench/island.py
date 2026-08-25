"""Does the median filter erase a small fast object's motion?

THE REPORT THAT PRODUCED THIS. Moving the desktop cursor quickly leaves four to
six copies of it trailing behind. The cursor is composited into the guest frame
before capture -- GLRenderer.renderCursor runs inside the composite -- so it goes
through the matcher and the warp like everything else.

THE SUSPECT IS A PASS THAT IS WORKING AS DESIGNED. MedianMaterial runs ten
anchored passes over the field, and its purpose in its own words is that "a
single 8x8 island pointing somewhere else is the search losing a tie, not an
object moving on its own". A cursor is about 32x32 -- sixteen blocks -- on a
static desktop where every neighbouring block correctly reports zero. It IS a
single island pointing somewhere else, and it is outnumbered. Ten passes of
agree-with-your-neighbours should drive its vector to zero.

With the motion erased the interpolation degenerates to mix(older, newer,
phase): a cross-fade, holding the cursor at its old position and its new one
simultaneously. Three synthesised frames per interval at 4x, each a different
mix, reads as a trail.

That is a mechanism, not a measurement, which is what this file is for.

WHAT IS SCORED. A small high-contrast square crossing a static background by a
known distance, so the correct answer is known exactly and the object's true
vector is known exactly. Then:

  - the object's own vector, before and after filtering, against the truth
  - the picture, filtered and unfiltered, against the correct midpoint
  - ghosting: how much ink lands where the object ISed and where it WILL be,
    rather than where it should be now. A cross-fade puts half at each end and
    nothing in the middle, which is precisely the visible artefact and is
    invisible to a whole-frame average.

The background is deliberately flat, because that is the desktop and it is what
makes the neighbours unanimous. bench.py's scene cannot show this: there the
whole frame moves together, so there is no island to outvote.

    python island.py
"""
import numpy as np

import bench
import consensus
import pyramid as P

BLOCK = P.BLOCK
SIZE = 32               # the cursor, in pixels
W, H = 640, 360


def scene(shift, background=0.35):
    """A small bright square crossing a flat field, and the true midpoint.

    Even components so the midpoint is a whole-pixel position and the ground
    truth carries no resampling error of its own.
    """
    sx, sy = int(shift[0]) & ~1, int(shift[1]) & ~1

    def frame(ox, oy):
        img = np.full((H, W, 3), background, dtype=np.float32)
        # A little texture, or the matcher has nothing to be right about
        # anywhere and the test degenerates for the wrong reason.
        img += (np.random.default_rng(3).random((H, W, 1)) - 0.5) * 0.02
        y0, x0 = H // 2 - SIZE // 2 + oy, W // 4 + ox
        img[y0:y0 + SIZE, x0:x0 + SIZE] = np.array([0.95, 0.95, 0.98])
        return np.clip(img, 0, 1)

    return frame(sx, sy), frame(0, 0), frame(sx // 2, sy // 2), (sx, sy)


def object_blocks(shift):
    """Which blocks the object occupies in the newer frame."""
    sx, sy = shift
    y0, x0 = H // 2 - SIZE // 2 + sy, W // 4 + sx
    gy0, gx0 = y0 // BLOCK, x0 // BLOCK
    gy1, gx1 = (y0 + SIZE) // BLOCK, (x0 + SIZE) // BLOCK
    m = np.zeros((H // BLOCK, W // BLOCK), dtype=bool)
    m[max(0, gy0):gy1, max(0, gx0):gx1] = True
    return m


def ghost_ends(out, truth, shift):
    """Ink at the two endpoint positions, kept apart.

    **Apart, because the two ends are different faults.** Ink at the position the
    object has LEFT is a trailing hole: the background behind it is revealed in
    the newer frame and exists nowhere in the older one, so a bilateral fetch has
    only one valid source and the blend fills the other half with the object that
    has gone. Ink at the position it is ARRIVING at is the opposite -- content
    covered between the two frames. Summing them, as the first version did,
    reports one number for two mechanisms and cannot say which is left.
    """
    sx, sy = shift
    out_ = []
    for ox, oy in ((0, 0), (sx, sy)):
        y0, x0 = H // 2 - SIZE // 2 + oy, W // 4 + ox
        patch = out[y0:y0 + SIZE, x0:x0 + SIZE]
        want = truth[y0:y0 + SIZE, x0:x0 + SIZE]
        out_.append(float(np.clip(patch - want, 0, None).mean()) * 255.0)
    return out_[0], out_[1]


def ghost(out, newer, older, truth, shift):
    a, b = ghost_ends(out, truth, shift)
    return a + b


def run(shift):
    newer, older, truth, shift = scene(shift)
    dist = float(np.hypot(*shift))
    raw = bench.estimate(newer, older, radius=P.WINDOW)
    filt = consensus.vector_median(raw, raw, passes=10)

    on = object_blocks(shift)
    want = -np.array(shift, dtype=np.float32)

    def vec_err(f):
        return float(np.linalg.norm(f[on] - want, axis=-1).mean())

    print("object crosses %.0f px %s -- it occupies %d of %d blocks (%.1f%%)"
          % (dist, shift, on.sum(), on.size, 100.0 * on.mean()))
    print("  %-28s %10s %10s %9s %9s"
          % ("field", "obj vec err", "image rms", "trailing", "leading"))
    weighted = consensus.vector_median(
        raw, raw, passes=10, weights=consensus.textured_weights(newer))
    for name, f in (("as the matcher returned it", raw),
                    ("after 10 median passes", filt),
                    ("10 passes, flat blocks muted", weighted)):
        out = P.warp(newer, older, f)
        tr, ld = ghost_ends(out, truth, shift)
        print("  %-28s %10.1f %10.2f %9.2f %9.2f"
              % (name, vec_err(f), P.rms(out, truth), tr, ld))
    blend = (newer + older) / 2.0
    btr, bld = ghost_ends(blend, truth, shift)
    print("  %-28s %10s %10.2f %9.2f %9.2f"
          % ("no compensation (blend)", "-", P.rms(blend, truth), btr, bld))
    print()


def main():
    print(__doc__.split("\n\n")[0])
    print()
    # Slow enough that the object is nearly its own neighbour, then fast.
    for shift in ((16, 0), (48, 0), (96, 0), (160, 0)):
        run(shift)
    print("  If the filtered vector error climbs towards the object's full")
    print("  displacement while the raw one stays small, the filter is erasing")
    print("  the object's motion, and the ghost column is the trail that leaves.")
    print("  A filtered result that matches the blend column IS a cross-fade.")


if __name__ == "__main__":
    main()
