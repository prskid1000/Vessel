"""Simulate the present pipeline, so a pacing rule can be tested without a device.

WHY. Stutter is a timing property, not an image one, so the image bench cannot
see it -- and measuring it on the phone means build, install, launch, three
minutes of menus, play, record, analyse, for one changed inequality. But every
input is a number already measured: how often the guest delivers and how much
that varies, how fast the panel refreshes, how many slots the interval is divided
into. So the whole thing can be run here in a second.

WHAT IT MODELS. Real frames arrive on the guest's own irregular rhythm. Each
arrival presents the interval's first interpolated frame immediately and
schedules the rest evenly across the predicted interval. Every present lands on
the first vsync at or after it is due, because that is what the display does. A
guard may decline a present that is too close to the one before it.

WHAT IT REPORTS. Not the frame rate -- that is right even when the picture
judders. It reports how long each distinct picture stayed on screen, in
refreshes, which is what judder actually is: an even stream holds every picture
the same number of refreshes, and a spread means the picture is advancing at
8 ms, then 25, then 17.

The measured distribution this reproduces, from a 20 s recording at 4x on a
120 Hz panel: 38% of pictures held for one refresh, 32% for two, 30% for three or
more, where an even stream would hold every one of them for exactly two.
"""
import sys

import numpy as np


def simulate(rule, seconds=20.0, guest_hz=15.0, jitter=0.06, refresh_hz=120.0,
             multiple=4, seed=7):
    """Run the pipeline and return the hold length of every distinct picture.

    jitter is the standard deviation of the guest's interval as a fraction of
    itself. The default matches what the device shows: a 66 ms median wandering
    between about 62 and 70.
    """
    rng = np.random.default_rng(seed)
    period = 1.0 / refresh_hz
    interval = 1.0 / guest_hz

    # When the guest actually delivers. Irregular, which is the whole problem.
    arrivals, t = [], 0.0
    while t < seconds:
        arrivals.append(t)
        t += interval * (1.0 + rng.normal(0, jitter))

    # The pipeline's own estimate of the interval: a median over recent gaps,
    # which is what FrameSynthesizer keeps.
    presents = []
    last_present = -1e9
    recent = []

    for i, arrival in enumerate(arrivals):
        if i:
            recent.append(arrival - arrivals[i - 1])
            recent = recent[-9:]
        estimate = float(np.median(recent)) if recent else interval
        slot = estimate / multiple

        # Every present the interval will attempt: the composite's own frame at
        # once, then one per slot across the predicted interval.
        due = [arrival] + [arrival + slot * k for k in range(1, multiple)]
        for k, want in enumerate(due):
            # The next real frame supersedes anything still outstanding.
            if i + 1 < len(arrivals) and want >= arrivals[i + 1]:
                break
            # The display can only show it on a refresh.
            landed = np.ceil(want / period) * period
            # The last slot carries the real frame and is never withheld.
            is_real = (k == multiple - 1)
            if not is_real and not rule(landed, last_present, slot, period):
                continue
            if landed <= last_present:
                continue
            presents.append(landed)
            last_present = landed

    # How many refreshes each distinct picture stayed up.
    presents = np.array(presents)
    if len(presents) < 3:
        return np.array([1]), 0.0
    holds = np.round(np.diff(presents) / period).astype(int)
    return holds[holds > 0], len(presents) / seconds


# ---- the rules, in the order they were tried -------------------------------

def no_guard(landed, last, slot, period):
    return True


def one_refresh(landed, last, slot, period):
    """What shipped: never share a scanout. Permits the very next refresh."""
    return landed - last >= period * 0.999


def fraction_of_slot(f):
    """A tunable fraction of the slot -- the version with a number in it."""
    def rule(landed, last, slot, period):
        return landed - last >= slot * f
    return rule


def own_slot(landed, last, slot, period):
    """**Derived rather than fitted.** One present per slot, whichever refresh
    it lands on.

    The interval divides into `multiple` slots and each is entitled to one
    present. A present belongs to the slot nearest it, so two presents in one
    slot means one of them is early -- and there is no threshold to choose,
    because the slot boundaries are already there. The nearest-slot test is
    `landed - last >= slot / 2`: half a slot is the midpoint between two slot
    centres, which is where one stops and the next begins.
    """
    return landed - last >= slot * 0.5


def report(name, holds, rate, ideal):
    even = (holds == ideal).mean() * 100
    spread = holds.std()
    print("  %-26s %5.1f/s | held %d: %4.0f%% | spread %.2f | 1-refresh %3.0f%%"
          % (name, rate, ideal, even, spread, (holds == 1).mean() * 100))


def main():
    refresh, multiple, guest = 120.0, 4, 15.0
    ideal = int(round((refresh / guest) / multiple))
    print("guest %.0f fps at %dx into a %.0f Hz panel: an even stream holds every"
          % (guest, multiple, refresh))
    print("picture %d refreshes.\n" % ideal)
    print("  %-26s %6s | %-13s | %-12s | %s"
          % ("rule", "rate", "even", "spread", "worst case"))
    for name, rule in (
        ("no guard", no_guard),
        ("one refresh (shipped)", one_refresh),
        ("0.7 of a slot", fraction_of_slot(0.7)),
        ("half a slot (derived)", own_slot),
        ("0.9 of a slot", fraction_of_slot(0.9)),
    ):
        holds, rate = simulate(rule, refresh_hz=refresh, multiple=multiple,
                               guest_hz=guest)
        report(name, holds, rate, ideal)


if __name__ == "__main__":
    main()
