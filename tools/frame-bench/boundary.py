"""What is a depth buffer worth, before anyone builds the pipe to get one?

THE PROPOSAL. FSR3 resolves parallax with a depth buffer, and Vessel has none --
it composites a guest's final framebuffer. But the plumbing to get one exists:
DXVK is already patched at present time, Mesa's swapchain is already patched,
and the host already imports AHardwareBuffers as GL textures. A second image
alongside the colour buffer would travel the same road.

WHY DEPTH WOULD HELP HERE SPECIFICALLY, WHICH IS NOT WHY FSR3 USES IT. FSR3
wants disocclusion masks and has engine motion vectors as well. Vessel would
still have no motion vectors, so that use is closed. What depth gives on its
own is the thing that is actually broken: a depth discontinuity IS a motion
boundary. The confirmed artefact -- the only mechanism that survives a speed
control, at 2.52x -- is an 8x8 block spanning near geometry and a far wall
getting one vector for both, and OBMC then blending four such vectors into
something matching neither surface. Depth would say where those edges are.

WHY THIS FILE RUNS FIRST. That is a multi-week project across three patched
components, and it is worth nothing if boundary knowledge does not help. So the
upper bound is measured before the pipe is built, from data already on disk.

The dense flow validated on each clip gives each pixel's TRUE motion, which is
strictly better than depth: depth says which surface a pixel is on, this says
how that surface is actually moving. So the treatments below bound anything a
depth buffer could achieve -- if they gain nothing, depth gains nothing, and
several weeks are saved.

  baseline            the shipped OBMC window: four block vectors, weighted by
                      position only, nothing discarded and nothing chosen
  boundary-weighted   the same four, weighted also by how well each matches the
                      pixel's true motion. This is "do not blend across an
                      edge", stated softly.
  boundary-picked     the single closest of the four. The hard version, which
                      also says whether softness is doing any work.
  perfect segmentation the pixel's true motion applied directly, ignoring the
                      block grid entirely. Not implementable at any price -- it
                      is the ceiling for knowing everything about the motion,
                      and the gap between it and the two above is what the
                      block quantisation costs on top.

    python boundary.py a.mp4 [b.mp4 ...] [--triples 24]
"""
import sys

import numpy as np

import candidates as CD
import carry as K
import partial as PA


def main():
    limit = 24
    if "--triples" in sys.argv:
        limit = int(sys.argv[sys.argv.index("--triples") + 1])
    paths = [a for a in sys.argv[1:] if not a.startswith("--") and a != str(limit)]
    if not paths:
        sys.exit(__doc__)

    names = ["baseline (shipped OBMC)", "boundary-weighted", "boundary-picked",
             "perfect segmentation"]
    for path in paths:
        trips = [t for t in PA.real_triples(path)
                 if PA.X.rms(t[0], t[2]) > 0.05][:limit]
        acc = {n: {"all": [], "edge": []} for n in names}

        for A, B, C in trips:
            f = K.flow(A, C)
            H, W = f.shape[:2]
            yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
            grid = np.stack([xx, yy], axis=-1)
            v, best = None, None
            for cand in (-f, f):
                rec = (0.5 * K.sample(C, grid - cand * 0.5)
                       + 0.5 * K.sample(A, grid + cand * 0.5))
                e = float(np.sqrt(((rec - B) ** 2).mean()))
                if best is None or e < best:
                    best, v = e, cand

            fld = CD.blocks(v).astype(np.float32)
            vs, ws = CD.four(fld, (H, W))
            preds = [CD.predict(x, A, C, grid) for x in vs]

            # How far each block's vector is from what this pixel is actually
            # doing. Depth would give a coarser version of the same thing: same
            # surface or not, rather than how different.
            gap = [np.linalg.norm(x - v, axis=-1) for x in vs]

            # **Where the block grid cannot describe the scene.** A pixel whose
            # four surrounding blocks disagree with each other is on a boundary,
            # and it is the only place any of this can matter -- everywhere else
            # the four predictions coincide and every treatment below is the
            # baseline. Reported separately because a whole-frame mean would
            # dilute the effect into nothing, which is how three metrics went
            # wrong earlier in this session.
            spread = np.max(np.stack(gap), axis=0) - np.min(np.stack(gap), axis=0)
            edge = spread > 8.0

            outs = {}
            outs["baseline (shipped OBMC)"] = sum(
                w[..., 0][..., None] * p for w, p in zip(ws, preds))

            # Softmax over agreement with the true motion. The scale is one
            # block: below that a difference is not a boundary, it is a gradient.
            sw = [w[..., 0] * np.exp(-g / 8.0) for w, g in zip(ws, gap)]
            tot = sum(sw) + 1e-9
            outs["boundary-weighted"] = sum(
                (s / tot)[..., None] * p for s, p in zip(sw, preds))

            pick = np.argmin(np.stack(gap), axis=0)
            outs["boundary-picked"] = np.take_along_axis(
                np.stack(preds), pick[None, ..., None], axis=0)[0]

            outs["perfect segmentation"] = CD.predict(v, A, C, grid)

            for n in names:
                err = np.sqrt(((outs[n] - B) ** 2).mean(axis=2))
                acc[n]["all"].append(float(err.mean()))
                if edge.any():
                    acc[n]["edge"].append(float(err[edge].mean()))

        base = np.mean(acc[names[0]]["all"])
        bedge = np.mean(acc[names[0]]["edge"])
        print("\n=== %s (%d triples) ===" % (path, len(trips)))
        print("  %-28s %10s %9s %11s %9s"
              % ("", "overall", "vs base", "at edges", "vs base"))
        for n in names:
            a = np.mean(acc[n]["all"])
            e = np.mean(acc[n]["edge"])
            print("  %-28s %10.5f %8.1f%% %11.5f %8.1f%%"
                  % (n, a, 100.0 * (a / base - 1.0), e, 100.0 * (e / bedge - 1.0)))
    print()
    print("  The `at edges` column is the whole question. Everywhere else the")
    print("  four blocks agree and every row is the baseline by construction.")
    print("  If knowing the boundaries exactly buys little there, a depth")
    print("  buffer -- which would only approximate them -- buys less, and the")
    print("  DXVK and vkd3d work is not worth starting.")


if __name__ == "__main__":
    main()
