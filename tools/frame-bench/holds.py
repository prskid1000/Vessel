"""Print the hold-length histogram of a recording, which is what judder is.

A frame counter cannot see judder and neither can a mean. At 15 fps guest, 4x,
into a 120 Hz panel, an even stream holds every distinct picture for exactly two
refreshes; the frame rate reads 60/s whether it does that or alternates one and
three. So the thing to look at is the whole distribution.

This exists to give the pacing simulator something to be checked against. A model
that cannot reproduce the histogram the device actually produces is not evidence
about anything, and one of the models in this directory has already been wrong in
exactly that way -- see the note at the top of scheduling.py.

    python holds.py recording.mp4 [--ideal 2]
"""
import subprocess
import sys

import numpy as np

W, H = 695, 316


def frames(path):
    p = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", path, "-vf", "scale=%d:%d" % (W, H),
         "-pix_fmt", "rgb24", "-f", "rawvideo", "-"],
        capture_output=True)
    if p.returncode:
        sys.exit(p.stderr.decode()[:600])
    a = np.frombuffer(p.stdout, dtype=np.uint8)
    n = a.size // (W * H * 3)
    return a[:n * W * H * 3].reshape(n, H, W, 3).astype(np.int16)


def holds(a, threshold=0.35):
    """How many captured frames each distinct picture stayed up.

    The capture runs at the panel's rate, so a run of identical frames is a
    refresh the compositor had nothing new for.
    """
    d = np.abs(np.diff(a.astype(np.float32), axis=0)).mean(axis=(1, 2, 3))
    runs, run = [], 1
    for changed in d > threshold:
        if changed:
            runs.append(run)
            run = 1
        else:
            run += 1
    runs.append(run)
    return np.array(runs)


def describe(runs, ideal=2):
    n = len(runs)
    print("%d distinct pictures, mean hold %.3f refreshes, std %.3f"
          % (n, runs.mean(), runs.std()))
    print("an even stream would hold every one of them for %d\n" % ideal)
    print("  refreshes   count    share")
    for k in range(1, min(runs.max(), 14) + 1):
        c = int((runs == k).sum())
        if not c:
            continue
        bar = "#" * int(round(60.0 * c / n))
        print("  %9d %7d %6.1f%%  %s" % (k, c, 100.0 * c / n, bar))
    over = int((runs > 14).sum())
    if over:
        print("  %9s %7d %6.1f%%" % (">14", over, 100.0 * over / n))
    print()
    print("even  %.1f%%   too short %.1f%%   too long %.1f%%"
          % ((runs == ideal).mean() * 100, (runs < ideal).mean() * 100,
             (runs > ideal).mean() * 100))


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    ideal = 2
    if "--ideal" in sys.argv:
        ideal = int(sys.argv[sys.argv.index("--ideal") + 1])
    describe(holds(frames(sys.argv[1])), ideal)


if __name__ == "__main__":
    main()
