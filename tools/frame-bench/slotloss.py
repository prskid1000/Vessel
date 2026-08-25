"""Are synthesised frames being lost, and does it track the interval?

THE QUESTION. `fg pacing` reports the presented rate swinging between 53.7 and
61.4 frames a second while the guest's real rate sits at 15.0. Collisions are no
longer the cause -- the arrival deferral took those to zero -- so either the
source is producing uneven intervals and the synthesis is faithfully following
them, or the pacer's slots are being superseded and dropped.

The device cannot currently tell those apart: `skipped` counts a different path
and nothing counts a slot that was scheduled and then found its interval already
over. But the recording can, and it needs no new instrumentation at all.

HOW. The stamp labels every synthesised frame, so between one real frame and the
next the recording holds exactly the synthesised frames that reached the panel.
At a multiple of K the pipeline intends K-1 of them. Fewer means a slot was lost.

COUNTING DISTINCT PICTURES, NOT RECORDED FRAMES. The capture runs at about 120
frames a second against a present rate near 60, so every presented frame appears
in the file roughly twice and a naive count would double everything. Consecutive
stamped frames are therefore collapsed when they are the same picture -- a
repeat is the recorder sampling twice, not the pipeline presenting twice.

READ IT AGAINST THE INTERVAL. If short intervals lose slots and long ones do
not, the pacer is scheduling against a smoothed estimate and losing whatever has
not fired when the next real frame arrives. If the loss is flat across interval
lengths, the slots are firing and the swing is the source's own jitter.

    python slotloss.py recording.mp4 [--expect 3]
"""
import sys

import numpy as np

import scan

SAME = 0.004        # mean abs difference below which two frames are one picture


def intervals(path):
    """Per interval: the gap in recorded frames, and the distinct pictures in it."""
    out, pending, prev_real = [], [], None
    for i, raw in enumerate(scan.stream(path)):
        f = raw.astype(np.float32) / 255.0
        if scan.marked(raw):
            if prev_real is not None:
                pending.append(f)
            continue
        if prev_real is not None:
            distinct = 0
            last = None
            for s in pending:
                if last is None or float(np.abs(s - last).mean()) > SAME:
                    distinct += 1
                last = s
            out.append((i - prev_real, distinct, len(pending)))
        prev_real = i
        pending = []
    return out


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    expect = 3
    if "--expect" in sys.argv:
        expect = int(sys.argv[sys.argv.index("--expect") + 1])

    rows = [r for r in intervals(sys.argv[1]) if 2 <= r[0] <= 40]
    if not rows:
        sys.exit("no usable intervals -- is the synthesis stamp on?")

    gap = np.array([r[0] for r in rows])
    got = np.array([r[1] for r in rows])

    print("%d intervals between consecutive real frames\n" % len(rows))
    print("  recorded frames per interval: median %.0f, %d to %d"
          % (np.median(gap), gap.min(), gap.max()))
    print("  distinct synthesised frames:  median %.0f, expected %d\n"
          % (np.median(got), expect))

    print("  %-22s %7s %12s %10s" % ("interval", "n", "synthesised", "short by"))
    cuts = [2, 6, 8, 10, 12, 1000]
    for lo, hi in zip(cuts, cuts[1:]):
        m = (gap >= lo) & (gap < hi)
        if m.sum() < 3:
            continue
        print("  %-22s %7d %12.2f %10.2f"
              % ("%d-%d recorded frames" % (lo, hi - 1) if hi < 1000
                 else "%d+ recorded frames" % lo,
                 int(m.sum()), got[m].mean(), expect - got[m].mean()))
    print()

    short = int((got < expect).sum())
    print("  %-34s %6d  (%.1f%%)"
          % ("intervals with a slot missing", short, 100.0 * short / len(rows)))
    print("  %-34s %6.2f" % ("mean synthesised per interval", got.mean()))
    print("  %-34s %6.2f" % ("mean shortfall", expect - got.mean()))
    print()
    if gap.std() > 1e-6 and got.std() > 1e-6:
        print("  %-34s %6.2f" % ("correlation, interval vs count",
                                 float(np.corrcoef(gap, got)[0, 1])))
    print()
    print("  A positive correlation means short intervals lose slots, which is")
    print("  the pacer scheduling against a smoothed estimate and running out of")
    print("  interval. Near zero means the slots fire and the swing in presented")
    print("  rate is the source's own jitter, faithfully followed.")


if __name__ == "__main__":
    main()
