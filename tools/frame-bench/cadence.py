"""Test pacing rules against the guest's MEASURED arrival jitter.

WHY THIS REPLACES THE EARLIER MODELS. Three simulators were written for this
problem and all three were wrong the same way: each assumed the guest delivers
on a smooth rhythm with a few per cent of jitter, and each predicted a far more
even presented stream than the device produces -- 55% of pictures correctly
spaced against a measured 27%. One of them recommended a change that doubled the
stutter on the phone and had to be reverted.

The assumption was never checked, and it is false. Measured over 2300 real
frames at 4x, in whole refreshes of a 120 Hz panel:

    0:1%  1:1%  2:7%  3:1%  6:5%  7:14%  8:51%  9:13%  10:5%

Eight refreshes is the only spacing that divides into four presents of two, and
barely half the frames arrive on it. A schedule laid evenly across a predicted
interval cannot come out even in an interval that turns out to be seven
refreshes long, however carefully its slots are placed -- which is why two
corrections at the scheduling end changed nothing, and why the present latency
turned out to be a tenth of a refresh when both had assumed a whole one.

CALIBRATION FIRST, AS ALWAYS HERE. `calibrate()` runs the rule that ships and
compares its gap histogram against the device's. Nothing below it is evidence
until those agree.

    python cadence.py
"""
import collections

import numpy as np

REFRESH_HZ = 120.0
MULTIPLE = 4
SECONDS = 120.0

# From `fg guest` on the device: arrival spacing in refreshes, and its share.
ARRIVALS = {0: 1, 1: 1, 2: 7, 3: 1, 6: 5, 7: 14, 8: 51, 9: 13, 10: 5}

# From `fg latency`: a paced frame reaches the screen this long after the vsync
# its callback was handed. A tenth of a refresh, with almost no spread -- the
# quantity two reverted corrections were built to fight.
LATENCY = 0.1

# From `fg slots` on the device, over 4042 presents.
MEASURED_GAPS = {0: 3.2, 1: 13.4, 2: 63.2, 3: 18.7, 4: 1.4}
MEASURED_PER_INTERVAL = {2: 25.8, 3: 72.8}


def arrivals(rng, seconds=SECONDS):
    """Real frame arrivals, in refreshes, drawn from the measured distribution."""
    steps = np.array(sorted(ARRIVALS))
    weights = np.array([ARRIVALS[s] for s in steps], dtype=float)
    weights /= weights.sum()
    total = int(seconds * REFRESH_HZ)
    out, t = [], 0.0
    while t < total:
        out.append(t)
        step = rng.choice(steps, p=weights)
        # **Not whole refreshes.** The histogram is rounded because the display
        # has no finer resolution to report, but a guest frame arrives whenever
        # it is finished, anywhere within a refresh. Quantising the arrivals to
        # the same grid the presents land on removes a whole source of jitter and
        # was why the first version of this model came out at 87% even against a
        # measured 63%.
        t += max(1, step) + rng.uniform(-0.5, 0.5)
    return out


def run(rule, seed=7):
    """Present the stream and return (gaps, presents per interval).

    Models what the device does: the phase-1/K frame is drawn inline at the
    arrival, the rest are asked for by the pacer and land on a refresh, and a
    slot whose real frame has already been superseded is dropped -- which is
    where an interval that turns out short loses a present.
    """
    rng = np.random.default_rng(seed)
    times = arrivals(rng)
    presents, per_interval = [], []
    estimate = 8.0
    recent = collections.deque(maxlen=9)
    state = {"anchor": times[0]}

    for i, arrival in enumerate(times):
        if i:
            recent.append(arrival - times[i - 1])
            estimate = float(np.median(recent))
        nxt = times[i + 1] if i + 1 < len(times) else arrival + estimate

        count = 0
        for want in rule(arrival, estimate, MULTIPLE, state):
            # Superseded: the real frame this belonged to is no longer current.
            if want >= nxt:
                break
            landed = np.ceil(want + LATENCY)
            if landed >= nxt:
                break
            if presents and landed < presents[-1]:
                continue
            presents.append(landed)
            count += 1
        # The device counts INTERPOLATED presents per interval; the last slot of
        # the rule is the real frame itself.
        per_interval.append(max(0, count - 1))

    p = np.array(presents)
    return np.diff(p).astype(int), np.array(per_interval)


# ---- the rules --------------------------------------------------------------

def restart_each_arrival(arrival, estimate, k, state):
    """What ships: the schedule is laid out afresh from every arrival.

    The inline frame at the arrival itself, then a slot every estimate/k. This is
    the rule whose whole spacing depends on the interval turning out to be the
    length that was predicted.
    """
    slot = estimate / k
    for j in range(k):
        yield arrival + slot * j


def free_running(arrival, estimate, k, state):
    """Keep a clock of the pipeline's own, corrected slowly.

    **The mean interval is right; only the instantaneous spacing is wrong.** That
    is the case a free-running clock exists for. Instead of restarting the layout
    at each arrival, the anchor advances by the estimate and is nudged towards
    where the frames are actually landing, so a single short interval no longer
    resets the whole schedule.

    The correction is a fraction of the error rather than all of it: taking all
    of it is the rule above under another name, and taking none of it drifts.
    """
    anchor = state["anchor"] + estimate
    error = arrival - anchor
    if abs(error) > 3 * estimate:
        anchor = arrival                 # a real rate change, not jitter
    else:
        anchor += 0.15 * error
    state["anchor"] = anchor
    slot = estimate / k
    for j in range(k):
        yield anchor + slot * j


def histogram(values, top=6):
    n = len(values)
    return {k: 100.0 * (values == k).sum() / n for k in range(top)}


def calibrate():
    gaps, per = run(restart_each_arrival)
    model = histogram(gaps)
    print("calibration: the rule that ships, model against device")
    print("  %-10s %9s %9s" % ("gap", "model", "device"))
    worst = 0.0
    for k in sorted(MEASURED_GAPS):
        d = MEASURED_GAPS[k]
        m = model.get(k, 0.0)
        worst = max(worst, abs(m - d))
        print("  %-10d %8.1f%% %8.1f%%" % (k, m, d))
    three = 100.0 * (per == 3).sum() / len(per)
    two = 100.0 * (per == 2).sum() / len(per)
    print("  %-10s %8.1f%% %8.1f%%" % ("3 per iv", three, MEASURED_PER_INTERVAL[3]))
    print("  %-10s %8.1f%% %8.1f%%" % ("2 per iv", two, MEASURED_PER_INTERVAL[2]))
    print("  largest disagreement %.1f points -- %s\n"
          % (worst, "usable" if worst <= 12 else "NOT USABLE, the model is wrong"))
    return worst <= 12


def main():
    print(__doc__.split("\n\n")[0])
    print()
    ok = calibrate()
    print("  %-24s %7s %7s %7s %8s"
          % ("rule", "even", "short", "long", "presents"))
    for name, rule in (("restart each arrival", restart_each_arrival),
                       ("free-running clock", free_running)):
        gaps, per = run(rule)
        h = histogram(gaps)
        print("  %-24s %6.1f%% %6.1f%% %6.1f%% %7.1f/s"
              % (name, h.get(2, 0), h.get(0, 0) + h.get(1, 0),
                 100 - h.get(2, 0) - h.get(1, 0) - h.get(0, 0),
                 len(gaps) / SECONDS))
    if not ok:
        print()
        print("The model does not reproduce the device, so the ranking above is")
        print("not evidence. Fix the model first.")


if __name__ == "__main__":
    main()
