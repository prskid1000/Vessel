"""Measure every artefact class in a screen recording, each with its own metric.

WHY ONE TOOL WITH SEVERAL METRICS. The artefacts this pipeline produces are
mutually invisible, and each one has already been missed by a metric that was
looking straight at it:

  - RMS error cannot see waviness. A wavy edge and a straight one hold the same
    ink in almost the same places; the difference is geometry, not intensity.
  - A waviness metric cannot see ghosting. A displaced duplicate of a subtitle is
    perfectly straight.
  - A frame counter cannot see a dropped frame. Two presents into one refresh are
    both counted and only one is shown.
  - And a mean over the frame cannot see any of them, because every one of these
    lives in a few per cent of the pixels.

So this reports five numbers rather than one, and says which it could not
establish rather than averaging its way past them.

THE MARKER. Real and synthesised frames cannot be told apart by inference. It
was tried: screenrecord captures at the panel's rate while the compositor
presents at its own, so the two are not locked and "every Nth captured frame" is
not the real one. Over a 30-frame window the 4x periodicity looked clean, at 10%
sharper; over the whole 1216-frame recording it washed out to 0.4% and the
classification was worthless.

`FG_LOG=mark` makes the interpolation shader stamp sixteen magenta pixels in one
corner, which no real frame carries. With that on, classification is exact. This
tool detects the stamp and says so; without it, every metric that needs the split
is reported as unavailable rather than guessed.

    python artefacts.py recording.mp4
"""
import subprocess
import sys

import numpy as np

W, H = 695, 316          # quarter scale: enough for every metric here, 16x cheaper


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


def marked(a):
    """Which frames carry the synthesis stamp. See the header."""
    # The stamp is magenta in the guest's bottom-left, which lands bottom-left
    # here too. A generous patch, because the guest is letterboxed into the
    # capture and the corner is not at pixel zero.
    patch = a[:, -12:, :24, :]
    r, g, b = patch[..., 0], patch[..., 1], patch[..., 2]
    hit = ((r > 150) & (b > 150) & (g < 90)).sum(axis=(1, 2))
    return hit > 4


def pacing(a):
    """How long each distinct picture stayed up, in captured frames.

    The capture runs at the panel's rate, so a run of identical frames is a
    refresh the compositor had nothing new for, and a picture held for one
    refresh when its neighbours are held for two is one that nearly was not
    shown. Judder is the *spread* of this distribution, not its mean.
    """
    d = np.abs(np.diff(a.astype(np.float32), axis=0)).mean(axis=(1, 2, 3))
    changed = d > 0.35
    runs, run = [], 1
    for c in changed:
        if c:
            runs.append(run)
            run = 1
        else:
            run += 1
    runs.append(run)
    runs = np.array(runs)
    return runs


def softness(a, synth):
    """Gradient energy, which falls when a frame is smeared or double-exposed."""
    gray = a.mean(-1)
    e = (np.abs(np.diff(gray, axis=2)).mean(axis=(1, 2))
         + np.abs(np.diff(gray, axis=1)).mean(axis=(1, 2)))
    return e[~synth].mean(), e[synth].mean()


def ghosting(a, synth):
    """A displaced duplicate of the frame's own content, found by self-similarity.

    A ghost is the frame containing a shifted copy of part of itself, so the
    correlation between the frame and a shifted version of it has a second peak
    away from zero. Measured on the horizontal axis only, which is where a camera
    pan puts it, and normalised so a flat scene cannot score.
    """
    gray = a.mean(-1)
    gray = gray - gray.mean(axis=(1, 2), keepdims=True)
    best = np.zeros(len(a))
    zero = (gray * gray).mean(axis=(1, 2)) + 1e-6
    for shift in range(6, 40, 2):
        c = (gray[:, :, shift:] * gray[:, :, :-shift]).mean(axis=(1, 2))
        best = np.maximum(best, c / zero)
    return best[~synth].mean(), best[synth].mean()


def invented(a, synth):
    """Pixels darker than anything nearby in the frames either side of them.

    Content that is in neither neighbour has nowhere to have come from. Compared
    against the frames before and after rather than against a threshold, so it
    means the same thing in a dark corridor and in daylight.
    """
    gray = a.mean(-1)
    lo = np.minimum(gray[:-2], gray[2:])
    darker = (gray[1:-1] < lo - 18).mean(axis=(1, 2))
    s = synth[1:-1]
    return darker[~s].mean() * 100, darker[s].mean() * 100


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    a = frames(sys.argv[1])
    synth = marked(a)
    n = len(a)
    print("%d frames" % n)

    runs = pacing(a)
    held1 = (runs == 1).mean() * 100
    print()
    print("STUTTER   %d distinct pictures; held for %.2f refreshes on average,"
          % (len(runs), runs.mean()))
    print("          %.0f%% for a single refresh, longest %d"
          % (held1, runs.max()))
    print("          (an even stream holds every picture the same number of"
          " refreshes;")
    print("           a spread is judder, whatever the frame rate reads)")

    if not synth.any():
        print()
        print("SPLIT     no synthesis stamp found, so real and synthesised frames")
        print("          cannot be separated. Softness, ghosting and invented")
        print("          content all need that split and are NOT reported.")
        print("          Set FG_LOG=mark and record again -- inference does not")
        print("          work here: the capture rate and the present rate are not")
        print("          locked, and a 4x periodicity that looks clean over thirty")
        print("          frames washes out to nothing over a thousand.")
        return

    print()
    print("SPLIT     %d synthesised, %d real, from the stamp" % (synth.sum(), (~synth).sum()))
    real_e, synth_e = softness(a, synth)
    print()
    print("SMEARING  gradient energy real %.3f, synthesised %.3f (%.1f%% softer)"
          % (real_e, synth_e, 100 * (1 - synth_e / real_e)))
    print("          (some of this is the game's own motion blur, which lands on"
          " both)")

    real_g, synth_g = ghosting(a, synth)
    print()
    print("GHOSTING  self-similarity at an offset: real %.4f, synthesised %.4f"
          % (real_g, synth_g))
    print("          (%.1f%% higher on synthesised frames -- a displaced copy of"
          % (100 * (synth_g / max(real_g, 1e-6) - 1)))
    print("           the frame's own content is what a ghost is)")

    real_i, synth_i = invented(a, synth)
    print()
    print("INVENTED  content darker than either neighbour: real %.3f%%,"
          " synthesised %.3f%%" % (real_i, synth_i))


if __name__ == "__main__":
    main()
