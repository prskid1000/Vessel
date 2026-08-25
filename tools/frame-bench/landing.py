"""Where do synthesised frames actually land between the two real ones?

WHY THIS IS NEEDED AND WHY IT IS NOT THE DEVICE'S OWN ANSWER. The `fg truth`
line now reports both the phase asked for and where the frame appears to have
landed, and on RE9 those two disagree in a way that looks like an inversion:

    asked 25%  ->  landed 88%, 84%, 85%
    asked 51%  ->  landed 67%
    asked 70%  ->  landed 53%
    asked 80%  ->  landed 38%
    asked 98%  ->  landed 10%

Fitted, that is landed ~= 111 - 1.04 x asked: the frame arrives at roughly one
minus the phase it was asked for.

That number comes out of the same shader family as the frame it is judging, and
two diagnostics have already been wrong today -- one comparing against a
hard-coded 50%, one reading a saturated search window and calling it occlusion.
So it is not evidence yet. This file re-asks the question from the recording,
where nothing but ffmpeg and arithmetic is involved.

WHY THE DISTRIBUTION CANNOT ANSWER IT AND THE ORDER CAN. At 4x the phases are
1/4, 2/4, 3/4. Inverted, they are 3/4, 2/4, 1/4 -- the same three numbers. Any
histogram of landing positions is therefore identical whether the pipeline is
correct or exactly backwards, which is presumably how this survived.

What is not symmetric is the ORDER. Within one interval the frames are presented
oldest-content-first, so the landing position must RISE across the interval. If
it falls, the picture is running backwards between every pair of real frames and
then jumping forward when the real frame arrives -- which is what judder is, and
what an object being torn in the wrong direction looks like.

HOW THE LANDING IS MEASURED, WITHOUT A MOTION FIELD. Between two real frames P
and L, the straight blend (1-a)P + aL traces a line through colour space. The
synthesised frame is not on that line -- it is warped, which is the entire point
-- but its projection onto the line is still the best available statement of how
far along it sits, and it is exactly what a least-squares fit returns:

    a = sum((S - P) . (L - P)) / sum((L - P) . (L - P))

No block matching, no assumption about what moved. Pixels where P and L are
identical contribute nothing to either sum, so still regions cannot drag the
estimate; the answer comes from what actually changed.

    python landing.py recording.mp4 [--intervals 40]
"""
import sys

import numpy as np

import gameplay
import scan


def group(path):
    """Yield (P, [synthesised...], L) a window at a time, streaming.

    Streamed because the obvious version is not affordable: gameplay.frames
    decodes the whole recording into one array, and ninety seconds at sixty
    frames a second of 1280x720 is about fifteen gigabytes. Only one interval is
    ever needed at once, so only one is ever held.
    """
    prev_real = None
    pending = []
    for f in scan.stream(path):
        if scan.marked(f):
            # Guard against an unbounded run if the stamp is ever missed.
            if prev_real is not None and len(pending) <= 10:
                pending.append(f.copy())
            continue
        if prev_real is not None and pending:
            yield prev_real, pending, f.copy()
        prev_real = f.copy()
        pending = []


def landing(P, S, L):
    """How far from P towards L the frame S sits, by least squares."""
    d = (L - P).reshape(-1)
    s = (S - P).reshape(-1)
    denom = float(d @ d)
    if denom < 1e-6:
        return None                      # nothing changed; the question is empty
    return float((s @ d) / denom)


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    want = 40
    if "--intervals" in sys.argv:
        want = int(sys.argv[sys.argv.index("--intervals") + 1])

    rising, falling, flat, seqs = 0, 0, 0, []
    seen = 0
    for Pr, mids, Lr in group(sys.argv[1]):
        seen += 1
        # Two or more synthesised frames, or there is no order to check.
        if len(mids) < 2:
            continue
        P = Pr.astype(np.float32) / 255.0
        L = Lr.astype(np.float32) / 255.0
        # Only intervals with real movement: if P and L are nearly the same the
        # fit is dominated by noise and says nothing about order.
        if float(np.abs(L - P).mean()) < 0.004:
            continue
        got = [landing(P, m.astype(np.float32) / 255.0, L) for m in mids]
        if any(g is None for g in got):
            continue

        # **Non-decreasing, not strictly increasing.** The first version demanded
        # every step be positive and put all forty intervals in "mixed", on
        # sequences that plainly rise: 0.23 0.23 0.53 0.53 0.53 0.82 0.82 0.82.
        # Repeats are expected -- the recording samples faster than new content
        # is produced -- and a repeat is not a reversal. What matters is whether
        # any step goes BACKWARDS.
        diffs = np.diff(got)
        if (diffs >= -0.01).all() and got[-1] - got[0] > 0.05:
            rising += 1
        elif (diffs <= 0.01).all() and got[0] - got[-1] > 0.05:
            falling += 1
        else:
            flat += 1
        seqs.append(got)
        if len(seqs) >= want:
            break

    if not seqs:
        sys.exit("no interval had two synthesised frames and real movement\n"
                 "(%d intervals seen; is the stamp on?)" % seen)

    print("  %d intervals with two or more synthesised frames and real motion\n"
          % len(seqs))
    print("  %-34s %6d" % ("landing rises across the interval", rising))
    print("  %-34s %6d" % ("landing FALLS across the interval", falling))
    print("  %-34s %6d" % ("neither, or mixed", flat))
    print()

    print("  the first %d sequences, oldest-content-first as presented"
          % min(12, len(seqs)))
    for s in seqs[:12]:
        print("    " + "  ".join("%6.2f" % v for v in s))
    print()

    first = np.array([s[0] for s in seqs])
    last = np.array([s[-1] for s in seqs])
    print("  %-34s %6.2f" % ("mean landing, first of interval", first.mean()))
    print("  %-34s %6.2f" % ("mean landing, last of interval", last.mean()))
    print()
    print("  Rising is correct: the interval walks from the older real frame")
    print("  towards the newer one. Falling means the picture runs backwards")
    print("  between every pair of real frames and snaps forward when the next")
    print("  one arrives, which no histogram of phases could ever have shown.")


if __name__ == "__main__":
    main()
