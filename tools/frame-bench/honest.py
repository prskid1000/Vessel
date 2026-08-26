"""How much of the oracle's 25% is real, and how much is fitting noise?

WHY THE ORACLE IS NOW UNDER SUSPICION. It chooses, per pixel, whichever of five
hypotheses lands closest to the frame the guest drew, and that is worth 25% of
the error at 4x. Six independent reliability signals then failed to capture
more than a tenth of it -- SAD, SAD normalised by local texture, a
light-invariant census transform, residual spread, endpoint asymmetry, and a
combination -- all landing within 0.1 points of each other on both captures.
When six unrelated formulations agree that precisely, the thing they have in
common is more likely the explanation than any of their differences.

The suspicion: on a pixel where every hypothesis is wrong, the oracle still
picks one, and picking the least-wrong of five wrong answers is worth something
in mean squared error while corresponding to nothing a shader could know. Five
draws from a noise distribution have a minimum below their mean by
construction. That is not headroom, it is the oracle fitting the residual.

THREE TESTS THAT SEPARATE SIGNAL FROM LUCK.

  per-pixel oracle      what candidates.py reported, free to change its mind
                        at every pixel.
  per-block oracle      one choice per 8x8 block, which is the granularity at
                        which the hypotheses actually differ, since they ARE
                        block vectors. A real structural gain survives this.
                        Noise-fitting does not, because independent per-pixel
                        luck cannot be spent once per block.
  shuffled oracle       the same per-pixel minimum, taken over five hypotheses
                        deliberately MISASSIGNED -- each pixel scored against
                        predictions belonging to other pixels' hypotheses.
                        Whatever this scores is pure minimum-of-five bias, and
                        it is the floor the real oracle has to clear to mean
                        anything at all.

If the block oracle collapses towards zero and the shuffled oracle sits near
the per-pixel one, then the 25% was never available, the -2% already shipped is
near the true ceiling for this class of method, and the honest answer to "can
this be driven to zero" is that it cannot be driven much below where it is.

    python honest.py a.mp4 [b.mp4 ...] [--triples 16]
"""
import sys

import numpy as np

import candidates as CD
import carry as K
import partial as PA


def main():
    limit = 16
    if "--triples" in sys.argv:
        limit = int(sys.argv[sys.argv.index("--triples") + 1])
    paths = [a for a in sys.argv[1:] if not a.startswith("--") and a != str(limit)]
    if not paths:
        sys.exit(__doc__)

    for path in paths:
        trips = [t for t in PA.real_triples(path)
                 if PA.X.rms(t[0], t[2]) > 0.05][:limit]
        rows = {"shipped OBMC": [], "per-pixel oracle": [],
                "per-block oracle": [], "decoy oracle (pure luck)": [],
                "decoy oracle, per block": [], "spread between hypotheses": []}
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
            dom = np.array([np.mean(fld[..., 0]), np.mean(fld[..., 1])], np.float32)
            vs, ws = CD.four(fld, (H, W))
            hyp = vs + [np.broadcast_to(dom, (H, W, 2))]
            preds = np.stack([CD.predict(x, A, C, grid) for x in hyp], axis=0)

            base = sum(w[..., 0][..., None] * p for w, p in zip(ws, preds[:4]))
            rows["shipped OBMC"].append(float(np.sqrt(((base - B) ** 2).mean())))

            # **Select the PICTURE, then score it the same way as every other
            # row.** The first version scored sqrt(mean(min_d**2)) where d was
            # a per-pixel norm across the three colour channels, while the
            # baseline row scored sqrt(mean((x-B)**2)) across pixels AND
            # channels. Those differ by a factor of sqrt(3), which made the
            # oracle read WORSE than the baseline it selects from -- an
            # impossibility that is the only reason the bug was caught.
            d = np.linalg.norm(preds - B[None], axis=-1)
            pick = np.argmin(d, axis=0)
            chosen = np.take_along_axis(preds, pick[None, ..., None], axis=0)[0]
            rows["per-pixel oracle"].append(float(np.sqrt(((chosen - B) ** 2).mean())))

            # One choice per block: sum the error over each block, pick once.
            gh, gw = H // CD.BLOCK, W // CD.BLOCK
            db = d[:, :gh * CD.BLOCK, :gw * CD.BLOCK].reshape(
                5, gh, CD.BLOCK, gw, CD.BLOCK).mean(axis=(2, 4))
            choice = np.argmin(db, axis=0)
            big = np.repeat(np.repeat(choice, CD.BLOCK, 0), CD.BLOCK, 1)
            big = np.pad(big, ((0, H - big.shape[0]), (0, W - big.shape[1])), "edge")
            blocked = np.take_along_axis(preds, big[None, ..., None], axis=0)[0]
            rows["per-block oracle"].append(float(np.sqrt(((blocked - B) ** 2).mean())))

            # **Five decoys that carry no information, minimised the same way.**
            #
            # The first attempt rolled each hypothesis's error map to a
            # different part of the image and took the minimum. That destroyed
            # the pixel's own difficulty along with the hypothesis assignment,
            # so the minimum was sampling the easy tail of the whole frame and
            # scored -88%, which is not a floor but a different question
            # entirely.
            #
            # This keeps the pixel and removes only the information. Each decoy
            # is the shipped answer plus independent noise scaled to the real
            # spread between hypotheses, so a chooser has exactly as much to
            # choose from and exactly nothing worth choosing. Whatever the
            # minimum takes off here is what taking a minimum of five always
            # takes off, and is available to no shader.
            rng = np.random.default_rng(len(rows["per-pixel oracle"]))
            spread = float(np.linalg.norm(
                preds - preds.mean(axis=0, keepdims=True), axis=-1).mean())
            decoy = base[None] + rng.normal(
                0.0, spread / np.sqrt(3.0), (5,) + base.shape).astype(np.float32)
            dd = np.linalg.norm(decoy - B[None], axis=-1)
            dpick = np.argmin(dd, axis=0)
            dchosen = np.take_along_axis(decoy, dpick[None, ..., None], axis=0)[0]
            rows["decoy oracle (pure luck)"].append(
                float(np.sqrt(((dchosen - B) ** 2).mean())))

            # **The same decoys, chosen once per block, because subtracting the
            # per-pixel floor from the per-block row was wrong.**
            #
            # A per-pixel minimum exploits per-pixel noise. A per-block one
            # cannot: averaging 64 pixels before choosing shrinks the spread
            # being exploited by sqrt(64), so the luck available collapses. The
            # earlier reading took 6 points off the per-block oracle on the
            # strength of a control measured per pixel, which understated the
            # real headroom. This is the control that belongs to that row.
            ddb = dd[:, :gh * CD.BLOCK, :gw * CD.BLOCK].reshape(
                5, gh, CD.BLOCK, gw, CD.BLOCK).mean(axis=(2, 4))
            dchoice = np.argmin(ddb, axis=0)
            dbig = np.repeat(np.repeat(dchoice, CD.BLOCK, 0), CD.BLOCK, 1)
            dbig = np.pad(dbig, ((0, H - dbig.shape[0]), (0, W - dbig.shape[1])), "edge")
            dblocked = np.take_along_axis(decoy, dbig[None, ..., None], axis=0)[0]
            rows["decoy oracle, per block"].append(
                float(np.sqrt(((dblocked - B) ** 2).mean())))

            rows["spread between hypotheses"].append(
                float(np.linalg.norm(preds - preds.mean(axis=0, keepdims=True),
                                     axis=-1).mean()))

        b = np.mean(rows["shipped OBMC"])
        print("\n=== %s (%d triples) ===" % (path, len(trips)))
        print("  %-32s %10s %10s" % ("", "error", "vs shipped"))
        for n in ("shipped OBMC", "per-pixel oracle", "per-block oracle",
                  "decoy oracle (pure luck)", "decoy oracle, per block"):
            e = np.mean(rows[n])
            print("  %-32s %10.5f %9.1f%%" % (n, e, 100.0 * (e / b - 1.0)))
        print("  %-32s %10.5f" % ("(how far the five hypotheses differ)",
                                  np.mean(rows["spread between hypotheses"])))
    print()
    print("  The decoy row is the floor: whatever it takes off is what")
    print("  taking a minimum of five numbers always takes off, and is")
    print("  available to nothing. Subtract it from the per-pixel row before")
    print("  believing any of it. The per-block row is the part a shader")
    print("  could in principle reach, because it is the resolution at which")
    print("  the hypotheses are actually different from one another.")


if __name__ == "__main__":
    main()
