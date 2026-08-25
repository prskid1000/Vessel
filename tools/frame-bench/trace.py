"""Print the cursor's position frame by frame, so the pattern is visible."""
import sys
import numpy as np
import cursors, scan

bg = cursors.background(sys.argv[1], 40)
rows = [r for r in cursors.measure(sys.argv[1], bg) if r[2] is not None]
start = int(sys.argv[2]) if len(sys.argv) > 2 else 1200
print("  %-7s %-5s %9s %9s %9s" % ("frame", "kind", "x", "y", "step x"))
prev = None
for r in rows[start:start + 44]:
    step = "-" if prev is None else "%9.1f" % (r[2][0] - prev)
    print("  %-7d %-5s %9.1f %9.1f %9s"
          % (r[0], "syn" if r[1] else "REAL", r[2][0], r[2][1], step))
    prev = r[2][0]
