"""Do the frames drawn late in an interval land further from the truth?

THE LEAD. `fg truth` now subtracts what the phase alone accounts for and
reports the residue -- how much further from the real frame the synthesis
landed than a perfect interpolation at that same phase would have. Over one 30
second capture that residue was not flat:

    drawn at 0-34%     3.6 points
    drawn at 35-59%    8.9
    drawn at 60-84%   18.4
    drawn at 85-100%  10.2

with eight and nine samples in the last two bands. That is a lead, not a
result, and it matters more than its size suggests: the pipeline chooses the
phases. If late frames really are disproportionately wrong, the remedy is in
how the interval is divided, which is a lever nothing else found this month
touches -- every other mechanism identified so far lives in the field, and the
field is not something this code can improve.

WHY THIS CAN SETTLE IT WITHOUT MORE DEVICE TIME. The device samples one frame a
second. The recording holds every frame, each labelled real or synthesised by
the stamp, so a thirty second capture carries over a thousand synthesised
frames with their phases recoverable from their position in the run. That is
twenty times the samples, already on disk.

WHAT IS COMPUTED, AND WHY IT NEEDS NO GROUND TRUTH. Exactly what the shader
does: the distance from the synthesised frame to the newer real frame, against
the distance the older real frame already was, over the pixels that changed. A
perfect interpolation at phase t sits at (1-t) of that distance by geometry, so
the excess over (1-t) is the pipeline's own contribution. No middle frame is
needed and no motion is estimated, so nothing here depends on the flow
estimator that invalidated half this directory.

    python latephase.py a.mp4 [b.mp4 ...]
"""
import sys

import numpy as np

import damage

CHANGED = 0.008


def excesses(path):
    out = []
    for _, A, S, C, t in damage.triples_shipped(path):
        d_base = np.linalg.norm(A - C, axis=-1)
        moved = d_base > CHANGED
        if moved.mean() < 0.05:
            continue
        d_synth = np.linalg.norm(S - C, axis=-1)
        ratio = float(np.minimum(d_synth[moved], 1.0).mean()
                      / max(np.minimum(d_base[moved], 1.0).mean(), 1e-6))
        out.append((float(t), ratio, ratio - (1.0 - float(t)), float(moved.mean())))
    return out


def main():
    paths = [a for a in sys.argv[1:] if not a.startswith("--")]
    if not paths:
        sys.exit(__doc__)

    for path in paths:
        rows = excesses(path)
        if len(rows) < 20:
            print("\n=== %s: only %d usable frames ===" % (path, len(rows)))
            continue
        t = np.array([r[0] for r in rows])
        ratio = np.array([r[1] for r in rows])
        ex = np.array([r[2] for r in rows])

        print("\n=== %s (%d synthesised frames) ===" % (path, len(rows)))
        print("  corr(ratio, 1-phase) = %+.2f   -- how much of it is just geometry"
              % float(np.corrcoef(ratio, 1.0 - t)[0, 1]))
        print()
        print("  %-16s %7s %10s %12s %10s"
              % ("drawn at", "n", "reads", "phase says", "excess"))
        cuts = [0.0, 0.35, 0.60, 0.85, 1.01]
        for lo, hi in zip(cuts, cuts[1:]):
            m = (t >= lo) & (t < hi)
            if m.sum() < 5:
                continue
            print("  %-16s %7d %9.0f%% %11.0f%% %9.1f"
                  % ("%.0f-%.0f%%" % (lo * 100, hi * 100), int(m.sum()),
                     ratio[m].mean() * 100, (1.0 - t[m]).mean() * 100,
                     ex[m].mean() * 100))
        print()
        # **The trend, tested rather than eyeballed.** Four means in four bands
        # can be read as rising by anyone who expects them to rise; the
        # correlation over every frame cannot.
        if t.std() > 1e-9:
            print("  corr(phase, excess) = %+.2f over all %d frames"
                  % (float(np.corrcoef(t, ex)[0, 1]), len(rows)))
    print()
    print("  A positive correlation confirms the lead and points at how the")
    print("  interval is divided, which the pipeline controls. Near zero says")
    print("  the four bands in the device log were small-sample noise, and the")
    print("  49 samples behind them were always too few to say otherwise.")


if __name__ == "__main__":
    main()
