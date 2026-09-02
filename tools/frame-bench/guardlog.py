"""What the whole-frame guard did over a play session, from its own log lines.

FrameSynthesizer prints one `fg confidence` line a second under FG_LOG=pacing:
how many intervals it declined, the last agreement it saw, the last frame
difference and the dominant motion. This reads those lines -- from a saved
logcat or straight from the device -- and answers the tuning questions:

    how often does it decline at all, and in bursts or in isolation?
    where does agreement sit when it declines, and when it does not?
    did the frame-difference ceiling ever fire on its own?

    python guardlog.py                 # adb logcat -d, now
    python guardlog.py session.log     # a saved logcat
"""
import re
import subprocess
import sys

import numpy as np

LINE = re.compile(r"fg confidence: (\d+) intervals shown as real frames only this second"
                  r"(?:, (\d+) gated)?; last agreement (\d+)% \(floor (\d+)%\), frame difference (\d+) levels"
                  r" \(ceiling (\d+)\), dominant motion (\d+) px")


def main():
    if len(sys.argv) > 1:
        text = open(sys.argv[1], encoding="utf-8", errors="replace").read()
    else:
        text = subprocess.run(["adb", "logcat", "-d", "-s", "FrameSynthesizer:*"],
                              capture_output=True, text=True, errors="replace").stdout
    rows = [tuple(int(x) if x is not None else 0 for x in m.groups()) for m in LINE.finditer(text)]
    if not rows:
        print("no `fg confidence` lines; is FG_LOG=pacing set and the session running?")
        return
    a = np.array(rows)
    declined, agree, diff, speed = a[:, 0], a[:, 2], a[:, 4], a[:, 6]
    floor, ceiling = a[0, 3], a[0, 5]
    secs = len(a)
    print("%d seconds logged; floor %d%%, ceiling %d levels" % (secs, floor, ceiling))
    print("declined intervals: %d in %d seconds (%.0f%% of seconds had any); longest run of"
          " declining seconds %d" % (declined.sum(), secs, (declined > 0).mean() * 100,
                                     longest_run(declined > 0)))
    print("frame difference over the ceiling: %d seconds" % (diff > ceiling).sum())
    print("\nagreement when the second declined nothing / something:")
    for label, mask in (("clean seconds", declined == 0), ("declining seconds", declined > 0)):
        if mask.any():
            q = np.percentile(agree[mask], [5, 25, 50, 75, 95])
            print("  %-18s n=%4d  p5 %3d  p25 %3d  p50 %3d  p75 %3d  p95 %3d"
                  % (label, mask.sum(), *q))
    print("\nagreement histogram (all seconds):")
    edges = [0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 101]
    for lo, hi in zip(edges[:-1], edges[1:]):
        m = (agree >= lo) & (agree < hi)
        bar = "#" * int(m.sum() * 40 / max(1, secs))
        print("  %3d..%3d%%  %4d  %s" % (lo, min(hi, 100), m.sum(), bar))
    print("\ndominant motion when moving (>10 px): p50 %.0f px, p90 %.0f px, max %d px"
          % (np.median(speed[speed > 10]) if (speed > 10).any() else 0,
             np.percentile(speed[speed > 10], 90) if (speed > 10).any() else 0, speed.max()))
    near = ((agree >= floor - 10) & (agree < floor + 10)).sum()
    print("seconds within 10 points of the floor either side: %d (%.0f%%) -- the band where a"
          " different floor would change the answer" % (near, near * 100 / secs))


def longest_run(mask):
    best = run = 0
    for m in mask:
        run = run + 1 if m else 0
        best = max(best, run)
    return best


if __name__ == "__main__":
    main()
