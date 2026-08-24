"""Parse `fg slots` traces from logcat and say what the cadence actually is.

The trace records, for every present, which display refresh it landed on and
what moment it was showing:

    fg slots: 25 +2 46 +2 73 +2 99 +1 R +1 R +2 25 ...

`+n` is refreshes since the previous present, `!` means it shared a refresh with
the one before it and was therefore never seen, and `R` is the real frame.

READ THIS RATHER THAN A SUMMARY. Mean present interval, frame rate, collision
count and mean hold length are all consistent with a perfectly even stream on a
recording where only a quarter of the pictures are evenly spaced. Two pacing
corrections were built on inferences from those summaries, changed nothing, and
were reverted.

What this reports:

  - the gap histogram, which is the cadence directly
  - how many presents each interval actually contains, against the multiple,
    since an extra present in an interval is a picture crowded out of its slot
  - the phase sequence, since evenly spaced presents showing unevenly spaced
    moments is judder just as surely as the reverse

    adb logcat -d -s FrameSynthesizer | python slots.py
"""
import collections
import re
import sys

TOKEN = re.compile(r"(!|\+(\d+):)?(R|\d+)")


def parse(line):
    """Return [(gap_in_refreshes, phase_or_None), ...] for one trace line."""
    body = line.split("fg slots:", 1)[1]
    out = []
    for gap, digits, value in TOKEN.findall(body):
        if gap == "!":
            step = 0
        elif digits:
            step = int(digits)
        else:
            step = None          # the first entry has no predecessor
        out.append((step, None if value == "R" else int(value)))
    return out


def main():
    lines = [l for l in sys.stdin if "fg slots:" in l]
    if not lines:
        sys.exit("no `fg slots` lines on stdin -- is FG_LOG=all set?")

    gaps = collections.Counter()
    per_interval = collections.Counter()
    phase_steps = collections.Counter()
    reals_in_a_row = collections.Counter()

    for line in lines:
        events = parse(line)
        count, run_of_reals, previous_phase = 0, 0, None
        for step, phase in events:
            if step is not None:
                gaps[step] += 1
            if phase is None:
                # An interval ends at the real frame. Count what it held.
                if count:
                    per_interval[count] += 1
                count = 0
                run_of_reals += 1
                previous_phase = None
            else:
                if run_of_reals:
                    reals_in_a_row[run_of_reals] += 1
                    run_of_reals = 0
                count += 1
                if previous_phase is not None and phase > previous_phase:
                    phase_steps[round((phase - previous_phase) / 5.0) * 5] += 1
                previous_phase = phase
        if run_of_reals:
            reals_in_a_row[run_of_reals] += 1

    total = sum(gaps.values())
    print("%d presents over %d traces\n" % (total, len(lines)))

    print("GAPS   refreshes between one present and the next")
    print("       (an even stream at 4x into 120 Hz is every gap = 2)")
    for k in sorted(gaps):
        label = "shared a refresh, never seen" if k == 0 else ""
        print("  %2d  %5d  %5.1f%%  %s %s"
              % (k, gaps[k], 100.0 * gaps[k] / total,
                 "#" * int(round(50.0 * gaps[k] / total)), label))

    n = sum(per_interval.values())
    print("\nPER INTERVAL   interpolated presents between two real frames")
    print("               (at 4x this should be exactly 3)")
    for k in sorted(per_interval):
        print("  %2d  %5d  %5.1f%%  %s"
              % (k, per_interval[k], 100.0 * per_interval[k] / n,
                 "#" * int(round(50.0 * per_interval[k] / n))))

    if reals_in_a_row:
        m = sum(reals_in_a_row.values())
        print("\nREAL FRAMES IN A ROW   more than one is the same picture sent twice")
        for k in sorted(reals_in_a_row):
            print("  %2d  %5d  %5.1f%%" % (k, reals_in_a_row[k],
                                           100.0 * reals_in_a_row[k] / m))

    if phase_steps:
        s = sum(phase_steps.values())
        print("\nPHASE STEPS   how far the shown moment advances per present")
        print("              (at 4x this should be 25 points every time)")
        for k in sorted(phase_steps):
            print("  %3d  %5d  %5.1f%%  %s"
                  % (k, phase_steps[k], 100.0 * phase_steps[k] / s,
                     "#" * int(round(50.0 * phase_steps[k] / s))))


if __name__ == "__main__":
    main()
