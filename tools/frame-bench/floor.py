"""What does `harm` read for answers that cannot be wrong in the way it means?

WHY THIS IS THE NEXT THING AND NOT ANOTHER MECHANISM. `fg truth` reports that
10 to 28% of the moving frame ends up further from the real frame than showing
the previous one would, and that number has now refused to move under every
condition tested:

    displacement, 20 px to 200 px      flat
    motion diversity                   -0.04
    contrast, darkness, blur           -0.22 and untestable, all footage dark
    brightness, RE9 against Metro      18.4% vs 17.5%
    search reach, 112 px vs 224 px     wider is worse
    occlusion                          0.14-0.27x against chance
    phase, field sign                  both correct

A number that will not move under any condition may not be measuring a
condition. Before proposing another mechanism, the metric itself has to be
asked what it reads for answers whose error is known.

WHAT IS SCORED, AND WHY EACH ONE IS A CONTROL.

  the warp                what ships
  a plain blend           no motion compensation at all. Cannot be "further
                          from the truth because the motion was wrong",
                          because it applies no motion.
  hold the older frame    the thing harm is DEFINED against. dSynth and dBase
                          are the same quantity here, so a correct metric must
                          read very close to zero. If it does not, the metric
                          is broken and every reading of it today is suspect.
  the real middle frame   the perfect answer, which the recording contains.
                          Harm must be exactly zero. This is the calibration.

READ THE LAST TWO FIRST. They are not opinions about the pipeline, they are
tests of the ruler. Only if they come out at zero does the warp's number mean
anything, and only if the blend also reads ~18% is the pipeline exonerated.

    python floor.py recording.mp4 [--triples 16]
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


def harm(shown, older, truth):
    """The device's metric, transcribed: worse than doing nothing, where moved."""
    d_synth = np.linalg.norm(shown - truth, axis=-1)
    d_base = np.linalg.norm(older - truth, axis=-1)
    moved = d_base > CHANGED
    if not moved.any():
        return None
    return float((d_synth[moved] > d_base[moved]).mean() * 100.0)


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    limit = 16
    if "--triples" in sys.argv:
        limit = int(sys.argv[sys.argv.index("--triples") + 1])
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    args = [a for a in args if a != str(limit)]

    got = []
    for path in args:
        try:
            got += gameplay.triples(path, limit, with_phase=True)
        except SystemExit:
            pass
    if not got:
        sys.exit("no usable triples")

    rows = {"the warp (what ships)": [], "a plain blend": [],
            "hold the older frame": [], "the real middle frame": []}
    # **Per triple, so the pair can be split by how far the scene went.** The
    # question a single average cannot answer is whether there is a displacement
    # below which the warp beats the blend -- that would be a shippable rule,
    # and the pipeline already has the field to key it on.
    paired = []
    for A, B, C, _, t in got:
        raw = bench.estimate(C, A, radius=P.WINDOW)
        field = consensus.vector_median(raw, raw, passes=MEDIAN_PASSES)
        older, newer = O.warp_parts(A, C, field, t=float(t))
        shown = older * (1.0 - float(t)) + newer * float(t)
        hw = harm(shown, A, B)
        hb = harm((A + C) * 0.5, A, B)
        if hw is not None and hb is not None:
            # Quarter resolution: reaches 448 px, so it cannot pin.
            d = float(np.median(np.linalg.norm(P.level(C, A, 4), axis=-1)))
            paired.append((d, hw, hb))
        for name, img in (("the warp (what ships)", shown),
                          ("a plain blend", (A + C) * 0.5),
                          ("hold the older frame", A),
                          ("the real middle frame", B)):
            h = harm(img, A, B)
            if h is not None:
                rows[name].append(h)

    print("%d triples from %d recording(s), scored at the true phase\n"
          % (len(got), len(args)))
    print("  %-26s %9s %9s %9s" % ("answer shown", "harm %", "median", "max"))
    for name in ("the real middle frame", "hold the older frame",
                 "a plain blend", "the warp (what ships)"):
        v = np.array(rows[name])
        if not v.size:
            continue
        print("  %-26s %9.1f %9.1f %9.1f"
              % (name, v.mean(), np.median(v), v.max()))
    print()
    print("  The real middle frame must read 0.0 -- it IS the truth. Holding the")
    print("  older frame must read near 0 -- it is what harm is defined against.")
    print("  If either is far off, the ruler is bent and nothing measured with")
    print("  it today means what it appeared to mean.")
    print()
    if paired:
        d = np.array([p[0] for p in paired])
        hw = np.array([p[1] for p in paired])
        hb = np.array([p[2] for p in paired])
        print("  IS THERE A SPEED AT WHICH THE WARP WINS?")
        print("  (halve the displacement to compare with one guest interval)")
        print("  %-16s %6s %10s %10s %10s"
              % ("moved A to C", "n", "warp %", "blend %", "warp wins"))
        cuts = [0, 60, 100, 140, 180, 1e9]
        for lo, hi in zip(cuts, cuts[1:]):
            m = (d >= lo) & (d < hi)
            if m.sum() < 2:
                continue
            print("  %-16s %6d %10.1f %10.1f %9.0f%%"
                  % ("%d-%d px" % (lo, hi) if hi < 1e9 else "%d+ px" % lo,
                     int(m.sum()), hw[m].mean(), hb[m].mean(),
                     100.0 * (hw[m] < hb[m]).mean()))
        print()
        print("  %-30s %6.0f%%" % ("triples where warp beats blend",
                                   100.0 * (hw < hb).mean()))
        print()
    print("  If the blend reads about what the warp reads, then ~18% is simply")
    print("  what this metric returns for any answer on this footage, and the")
    print("  pipeline has not been shown to be doing harm at all.")


if __name__ == "__main__":
    main()
