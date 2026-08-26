"""Displacement from the HARDWARE field, against harm, from one device log.

Every previous attempt to find the displacement at which motion compensation
stops paying estimated the displacement on the laptop with a stand-in block
matcher -- and that matcher is not the one that ships. SignMaterial's blue
channel now carries the real one, so the two numbers finally come from the
same field, on the same frame, on the device.

The two lines are emitted in the same once-a-second burst, so they pair by
their position in the burst, not by timestamp arithmetic.
"""
import re, sys
import numpy as np

moved, harm, invented, movingfrac = [], [], [], []
pend = None
for line in open(sys.argv[1], errors="ignore"):
    m = re.search(r"fg field:.*moved (\d+) px mean", line)
    if m:
        pend = int(m.group(1))
        continue
    m = re.search(r"fg truth: ([\d.]+)% of the moving frame is FURTHER.*?"
                  r"([\d.]+)% invented content, (\d+)% of frame moving", line)
    if m and pend is not None:
        moved.append(pend); harm.append(float(m.group(1)))
        invented.append(float(m.group(2))); movingfrac.append(int(m.group(3)))
        pend = None

d = np.array(moved, float); h = np.array(harm); iv = np.array(invented)
mf = np.array(movingfrac, float)
print("%d paired samples\n" % len(d))
print("  %-16s %5s %9s %9s %10s %9s"
      % ("field moved", "n", "harm %", "harm p90", "invented %", "moving %"))
cuts = [0, 1, 5, 20, 50, 90, 1e9]
for lo, hi in zip(cuts, cuts[1:]):
    m = (d >= lo) & (d < hi)
    if m.sum() < 2:
        continue
    print("  %-16s %5d %9.1f %9.1f %10.3f %9.0f"
          % ("%d-%d px" % (lo, hi - 1) if hi < 1e9 else "%d+ px" % lo,
             int(m.sum()), h[m].mean(), np.percentile(h[m], 90),
             iv[m].mean(), mf[m].mean()))
print()
for name, v in (("displacement", d), ("share of frame moving", mf)):
    if v.std() > 1e-9 and h.std() > 1e-9:
        print("  corr(%s, harm) = %+.2f" % (name, float(np.corrcoef(v, h)[0, 1])))
print()
print("  harm over 20%%: %d of %d samples; their displacement: median %.0f px, max %.0f"
      % (int((h > 20).sum()), len(h),
         np.median(d[h > 20]) if (h > 20).any() else -1,
         d[h > 20].max() if (h > 20).any() else -1))
print("  harm under 5%%:  %d of %d samples; their displacement: median %.0f px, max %.0f"
      % (int((h < 5).sum()), len(h),
         np.median(d[h < 5]) if (h < 5).any() else -1,
         d[h < 5].max() if (h < 5).any() else -1))
