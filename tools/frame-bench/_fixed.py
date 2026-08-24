"""Present on a fixed refresh cadence and let the phase absorb the jitter."""
import collections
import numpy as np
import cadence as C

def run_fixed(every=2, seed=7, seconds=C.SECONDS):
    """Present every `every` refreshes, whatever the guest does.

    The display cadence stops depending on the guest entirely: gaps are `every`
    refreshes by construction. What the jitter moves is the PHASE STEP -- how far
    the shown moment advances each time -- because phase is elapsed/interval and
    the interval is what wanders.
    """
    rng = np.random.default_rng(seed)
    times = C.arrivals(rng, seconds)
    total = int(seconds * C.REFRESH_HZ)
    est, recent, i = 8.0, collections.deque(maxlen=9), 0
    last_arrival, steps, presents = times[0], [], 0
    prev_phase = None
    for refresh in range(0, total, every):
        while i + 1 < len(times) and times[i + 1] <= refresh:
            i += 1
            recent.append(times[i] - times[i - 1])
            est = float(np.median(recent))
            last_arrival = times[i]
            prev_phase = None            # a new pair; the phase restarts
        phase = min(1.0, 1.0 / C.MULTIPLE + (refresh - last_arrival) / est)
        if prev_phase is not None and phase > prev_phase:
            steps.append(round((phase - prev_phase) * 100))
        prev_phase = phase
        presents += 1
    return np.array(steps), presents / seconds

steps, rate = run_fixed()
print("fixed cadence, one present every 2 refreshes")
print("  gaps: 100%% at 2 by construction, %.1f presents/s" % rate)
print("  phase step, points (25 is even):")
h = collections.Counter(steps)
n = sum(h.values())
for k in sorted(h):
    if h[k] * 100.0 / n < 0.4: continue
    print("   %3d  %5.1f%%  %s" % (k, 100.0*h[k]/n, "#" * int(round(50.0*h[k]/n))))
print("  mean %.1f, std %.1f" % (steps.mean(), steps.std()))
