"""Try scheduling algorithms against the measured stutter, keeping the frame rate.

**READ THIS FIRST: this model was wrong about the panel rate, on the device.**

Sweeping the refresh rate here said parity -- one refresh per frame -- was 100%
even where a doubled panel was 55%, consistently across six configurations and
every draw-jitter level. That was shipped and measured worse on the phone: stutter
roughly doubled, and scenes that had been smooth began stuttering where previously
only fast movement did. It was reverted.

What the model is missing is the *coupling* between presents. It rounds each
present to a refresh independently, so a late one simply lands later. On the
device a late present at parity has nowhere to go: the buffer queue has no spare
slot, the swap blocks, the GL thread stalls behind it, and the next draw starts
late as well. One miss propagates. The doubled panel leaves a spare refresh for
exactly that, which is what the original headroom reasoning was reaching for even
though the collision it cited had a different cause.

So the refresh-rate conclusion from this file is not to be trusted, and any
candidate that depends on presents being independent is suspect. The parts that
did hold up on the device -- that no schedule-side rule moves evenness, and that
draw-latency variance rather than its mean is what the spread is made of -- were
measured the same way and carry the same caveat until something confirms them.

CALIBRATION IS NOW MANDATORY HERE. Because of the above, this file no longer
reports a candidate until the model has been shown to reproduce what the device
actually does. `MEASURED` below is the hold histogram recovered from a 20 s
recording at 4x, and `calibrate()` prints the model's own histogram beside it. A
candidate is only evidence to the extent those two agree; when they do not, the
right conclusion is that the model is wrong again, not that the device is.

WHAT IS MODELLED. Guest arrivals on their own irregular rhythm; the pipeline's
median estimate of the interval; slots spread across that estimate; the
Choreographer landing each present on a real refresh; and the variable delay
between a present falling due and the GL thread having drawn and swapped it --
the piece the first model lacked, and which turned out to matter more than any
scheduling rule.

WHAT IS REPORTED. Hold lengths, because that is what judder is. An even stream
holds every picture the same number of refreshes. The frame rate is printed
beside it so that no candidate can buy evenness by presenting less: the first
attempt at this required a whole slot of spacing and declined any present that
arrived early, which converted short holds into long ones and cost a sixth of
the frame rate.
"""

import numpy as np

REFRESH = 120.0
GUEST = 15.0
MULTIPLE = 4
JITTER = 0.06          # measured: a 66 ms median wandering 62-70
DRAW_MS = 5.0          # GL thread wake, draw, swap
DRAW_JITTER = 3.0
SECONDS = 30.0


def run(schedule, seed=7):
    """`schedule(arrival, estimate, multiple, period, state)` yields due times."""
    rng = np.random.default_rng(seed)
    period = 1.0 / REFRESH
    interval = 1.0 / GUEST

    arrivals, t = [], 0.0
    while t < SECONDS:
        arrivals.append(t)
        t += interval * (1.0 + rng.normal(0, JITTER))

    presents, recent = [], []
    state = {"last": -1e9, "period": period, "drift": 0.0, "err": []}
    for i, arrival in enumerate(arrivals):
        if i:
            recent.append(arrival - arrivals[i - 1])
            recent = recent[-9:]
        estimate = float(np.median(recent)) if recent else interval
        nxt = arrivals[i + 1] if i + 1 < len(arrivals) else arrival + estimate

        for want, is_real in schedule(arrival, estimate, MULTIPLE, period, state):
            if want >= nxt and not is_real:
                break
            woke = np.ceil(want / period) * period
            delay = (DRAW_MS + abs(rng.normal(0, DRAW_JITTER))) / 1000.0
            landed = np.ceil((woke + delay) / period) * period

            # **Defer rather than drop.** A present that would share a refresh is
            # pushed to the next one instead of being thrown away, which is what
            # keeps the frame rate while removing the collision.
            if landed <= state["last"]:
                landed = state["last"] + period
            state["err"].append(landed - want)
            presents.append(landed)
            state["last"] = landed

    p = np.array(presents)
    holds = np.round(np.diff(p) / period).astype(int)
    holds = holds[holds > 0]
    return holds, len(p) / SECONDS, float(np.mean(state["err"]))


# ---- the candidates ---------------------------------------------------------

def plain(arrival, est, k, period, st):
    """What ships: slots evenly across the predicted interval, from this arrival."""
    slot = est / k
    for j in range(k):
        yield arrival + slot * j, (j == k - 1)


def lead_by_latency(arrival, est, k, period, st):
    """Schedule earlier by the delay actually being observed.

    The pipeline knows how late its presents land -- it is the difference between
    when one was due and when it appeared. Subtracting the running mean of that
    aims at where the frame should be rather than where the request goes in, and
    costs nothing: no present is declined, only moved.
    """
    slot = est / k
    lead = np.mean(st["err"][-16:]) if len(st["err"]) >= 4 else 0.0
    for j in range(k):
        yield arrival + slot * j - lead, (j == k - 1)


def vsync_quantised(arrival, est, k, period, st):
    """Aim each slot at a distinct refresh, chosen up front.

    The display can only show a frame on a refresh, so a schedule in continuous
    time is a fiction that the rounding resolves unevenly. Rounding deliberately,
    to distinct refreshes spread across the interval, makes the spacing a whole
    number of refreshes by construction.
    """
    span = int(round(est / period))
    if span < k:
        span = k
    step = span / float(k)
    base = np.ceil(arrival / period)
    for j in range(k):
        yield (base + round(step * j)) * period, (j == k - 1)


def quantised_with_lead(arrival, est, k, period, st):
    """Both: distinct refreshes, aimed early by the observed delay."""
    span = max(int(round(est / period)), k)
    step = span / float(k)
    lead = np.mean(st["err"][-16:]) if len(st["err"]) >= 4 else 0.0
    base = np.ceil((arrival - lead) / period)
    for j in range(k):
        yield (base + round(step * j)) * period, (j == k - 1)


# From a 20 s recording at 4x, guest 15 fps, 120 Hz panel, via holds.py:
# 1128 distinct pictures, mean hold 2.125, and this distribution.
MEASURED = {1: 39.0, 2: 25.6, 3: 23.7, 4: 9.5, 5: 1.6}


def calibrate():
    """Show the model's histogram beside the device's. See the header.

    Nothing below this is worth reading if these two disagree badly. The failure
    this guards against is not subtle -- the previous version of this file was
    confidently recommending a change that made the device twice as bad -- and
    the only defence is to make the model state its prediction about something
    already known before it is asked about anything unknown.
    """
    holds, rate, _ = run(plain)
    print("calibration: the rule that ships, model against device")
    print("  %9s %10s %10s" % ("refreshes", "model", "device"))
    for k in sorted(set(list(MEASURED) + [1, 2, 3, 4, 5])):
        share = (holds == k).mean() * 100
        print("  %9d %9.1f%% %9.1f%%" % (k, share, MEASURED.get(k, 0.0)))
    print("  %9s %9.3f  %9.3f" % ("mean", holds.mean(), 2.125))
    print("  %9s %9.1f/s" % ("rate", rate))
    worst = max(abs((holds == k).mean() * 100 - MEASURED.get(k, 0.0))
                for k in (1, 2, 3, 4))
    print("  largest disagreement %.1f points -- %s\n"
          % (worst, "usable" if worst <= 10 else "NOT USABLE, the model is wrong"))
    return worst <= 10


def main():
    ideal = int(round((REFRESH / GUEST) / MULTIPLE))
    print("guest %.0f fps at %dx into a %.0f Hz panel." % (GUEST, MULTIPLE, REFRESH))
    print("An even stream holds every picture %d refreshes and presents %.1f/s.\n"
          % (ideal, GUEST * MULTIPLE))
    trustworthy = calibrate()
    print("  %-30s %7s %7s %7s %7s %8s"
          % ("algorithm", "rate", "even", "held 1", "held 3+", "late ms"))
    for name, fn in (("plain (ships today)", plain),
                     ("lead by observed latency", lead_by_latency),
                     ("vsync-quantised slots", vsync_quantised),
                     ("quantised + lead", quantised_with_lead)):
        holds, rate, err = run(fn)
        print("  %-30s %6.1f/s %6.0f%% %6.0f%% %6.0f%% %7.1f"
              % (name, rate, (holds == ideal).mean() * 100,
                 (holds == 1).mean() * 100, (holds >= 3).mean() * 100,
                 err * 1000))
    if not trustworthy:
        print()
        print("The model does not reproduce the device's own histogram, so none")
        print("of the above is evidence. Fix the model first.")


if __name__ == "__main__":
    main()
