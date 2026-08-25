"""Which interval estimate should the pacer space its slots across?

THE FAULT, measured from video in slotloss.py. Slots are scheduled at
`i * smoothedInterval / K` where smoothedInterval is the MEDIAN of the last nine
real-frame intervals. When the actual interval arrives shorter than that median,
the last slot has not fired by the time the next real frame lands and
`realFrameCount() != stamp` discards it. On 1348 Metro intervals, 39% were
missing at least one slot, the correlation between interval length and slots
delivered was 0.67, and the mean shortfall of 0.26 frames across 15 intervals a
second is the 53.7 to 61.4 swing in presented rate.

A median is by construction too long for half of all intervals. That is fine for
an estimate of what the guest is doing and wrong for a deadline.

WHAT THIS SIMULATES. The recorded interval sequence, replayed against several
estimators, asking one question per interval: with slots spaced across
`estimate`, how many of the K-1 of them fall inside the interval that actually
arrived. No device and no new recording -- the sequence is already in the file.

WHY NOT SIMPLY AIM EARLIER. FramePacer records that biasing slots towards the
start of the interval was tried and removed: it squeezed K phases into a
fraction of the interval, so motion ran fast and then stopped dead for the
remainder, every interval. This changes which estimate the slots are evenly
spaced ACROSS, not their evenness -- the spacing stays uniform.

AND WHY ONLY THE PACER. smoothedInterval also drives phaseFor, where `elapsed /
smoothedInterval` decides which moment to show. Shortening it there would make
content advance faster than wall time, which is the same judder by another
route. The phase must keep the median; only the deadline moves.

    python estimator.py recording.mp4 [--multiple 4]
"""
import sys

import numpy as np

import scan

WINDOW = 9          # what medianInterval keeps


def sequence(path):
    """Gaps between consecutive real frames, in recorded frames."""
    out, prev = [], None
    for i, raw in enumerate(scan.stream(path)):
        if scan.marked(raw):
            continue
        if prev is not None:
            gap = i - prev
            if 2 <= gap <= 40:
                out.append(gap)
        prev = i
    return np.array(out, dtype=np.float64)


def delivered(gaps, pct, multiple):
    """How many slots land inside the interval, per interval, for one estimator.

    The estimate is formed from the PRECEDING window only, exactly as the device
    forms it: it cannot see the interval it is about to schedule across.
    """
    got, total = [], multiple - 1
    for i in range(WINDOW, len(gaps)):
        past = np.sort(gaps[i - WINDOW:i])
        est = past[int(round((len(past) - 1) * pct))]
        # Slots at 1..K-1 of est/K; a slot counts if it fits in the real gap.
        fits = sum(1 for k in range(1, multiple)
                   if (est * k) / multiple < gaps[i])
        got.append(fits)
    return np.array(got, dtype=np.float64), total


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    multiple = 4
    if "--multiple" in sys.argv:
        multiple = int(sys.argv[sys.argv.index("--multiple") + 1])

    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    args = [a for a in args if a != str(multiple)]
    gaps = np.concatenate([sequence(p) for p in args])
    if len(gaps) < WINDOW + 10:
        sys.exit("not enough intervals")

    print("%d intervals, median %.1f recorded frames, %.0f to %.0f\n"
          % (len(gaps), np.median(gaps), gaps.min(), gaps.max()))
    print("  %-24s %10s %10s %12s"
          % ("estimator", "slots/int", "of " + str(multiple - 1), "intervals ok"))
    for name, pct in (("10th percentile", 0.10), ("25th percentile", 0.25),
                      ("40th percentile", 0.40), ("median (ships)", 0.50),
                      ("75th percentile", 0.75)):
        got, total = delivered(gaps, pct, multiple)
        print("  %-24s %10.2f %10d %11.0f%%"
              % (name, got.mean(), total, 100.0 * (got >= total).mean()))
    print()
    print("  A lower percentile is a shorter deadline, so more slots fit. What it")
    print("  costs is that in a long interval the slots finish early and the gap")
    print("  before the next real frame grows -- which the phase, still derived")
    print("  from the median, fills by holding the last moment rather than by")
    print("  running fast. Even spacing is preserved either way.")


if __name__ == "__main__":
    main()
