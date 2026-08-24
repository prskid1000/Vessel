"""How long each distinct picture stayed on screen, measured in time.

A frame counter cannot see judder and neither can a mean. At 15 fps guest, 4x,
into a 120 Hz panel, an even stream holds every distinct picture for exactly two
refreshes; the frame rate reads 60/s whether it does that or alternates one and
three. So the thing to look at is the whole distribution.

**THIS FILE PREVIOUSLY MEASURED FFMPEG'S FRAME DUPLICATION, AND EVERY NUMBER IT
PRODUCED ON 2026-08-24 WAS WRONG.** It counted decoded frames between changes and
called them refreshes. Two assumptions were buried in that, and both are false:

  - `screenrecord` does not capture at the panel's rate. It captures at about
    60 fps -- 1517 frames over 24.97 s -- while tagging the container 120/1.
  - Piping that to rawvideo without `-fps_mode passthrough` makes ffmpeg conform
    the stream to the tagged 120 fps by DUPLICATING frames, and 60.74 into 120 is
    not a clean doubling, so the duplication is uneven.

The tell was arithmetic and sat in the output the whole time: the histogram's own
counts summed to 2997 frame-slots on a 1517-frame file. A day of conclusions came
out of it -- five changes reverted, one shipped and pulled back -- while the
`fg slots` trace on the device, which counts real vsync indices and disagreed
with it, was assumed to be the broken one.

WHAT IT DOES NOW. Decodes with `-fps_mode passthrough`, so exactly the frames in
the file come out and none are invented; reads each frame's presentation
timestamp; and measures how long each distinct picture was on screen in
milliseconds. Refreshes are that duration divided by the panel period, which is a
real quantity rather than a decoder artefact.

**AND IT STILL CANNOT MEASURE PACING. NOTHING BUILT ON A SCREEN RECORDING CAN.**
Fixing the duplication made the output self-consistent -- 60.1 pictures a second
against 60 presents, mean hold 16.64 ms, which is exactly one capture interval --
and that self-consistency is what exposes the real problem:

    panel refresh      8.33 ms
    screenrecord       16.67 ms   (60 fps, measured, not assumed)
    our presents       ~16 ms

The capture samples at HALF the panel rate. A picture held two refreshes occupies
exactly one captured frame; a picture held one refresh may land in a capture or
fall between two and never appear. An even stream and an uneven one produce the
same recording. This is Nyquist, not a threshold or a decoder flag, and no
amount of work on this file reaches it.

So the "45% of pictures held too short" this reported was the capture's own
sampling. The device's `fg slots` trace, which counts real vsync indices and
disagreed with this file all day, gives 63% of presents correctly spaced.
**`fg slots` is the pacing instrument; this file is not, and every pacing
conclusion drawn from it on 2026-08-24 has to be re-taken.**

What a recording is still good for is the picture rather than its timing:
ghosting, invented content, smearing, and whether an artefact is visible at all.
Those need the frames, not their schedule.

The frame-average threshold is separately blind to a small moving object -- see
the README, and `--sensitive`.

    python holds.py recording.mp4 [--refresh 8.333] [--sensitive]
"""
import json
import subprocess
import sys

import numpy as np

W, H = 695, 316


def probe(path):
    """Every frame's presentation timestamp, in seconds, in decode order."""
    p = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
         "frame=pts_time", "-of", "json", path],
        capture_output=True)
    if p.returncode:
        sys.exit(p.stderr.decode()[:600])
    times = [float(f["pts_time"])
             for f in json.loads(p.stdout).get("frames", []) if "pts_time" in f]
    if len(times) < 3:
        sys.exit("no frame timestamps in %s" % path)
    return np.array(times)


def frames(path):
    """The frames themselves, exactly as stored -- none conformed or invented."""
    p = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", path,
         "-vf", "scale=%d:%d" % (W, H), "-pix_fmt", "rgb24",
         "-fps_mode", "passthrough", "-f", "rawvideo", "-"],
        capture_output=True)
    if p.returncode:
        sys.exit(p.stderr.decode()[:600])
    a = np.frombuffer(p.stdout, dtype=np.uint8)
    n = a.size // (W * H * 3)
    return a[:n * W * H * 3].reshape(n, H, W, 3).astype(np.int16)


def changed(a, sensitive=False):
    """Which frames differ from the one before them."""
    diff = np.abs(np.diff(a.astype(np.float32), axis=0))
    if sensitive:
        return np.percentile(diff.reshape(len(diff), -1), 99.9, axis=1) > 8.0
    return diff.mean(axis=(1, 2, 3)) > 0.35


def holds(path, sensitive=False):
    """Seconds each distinct picture was on screen, and the span covered."""
    times = probe(path)
    a = frames(path)
    n = min(len(a), len(times))
    if len(a) != len(times):
        print("note: %d frames decoded, %d timestamps -- using %d\n"
              % (len(a), len(times), n))
    a, times = a[:n], times[:n]

    marks = [times[0]]
    for i, c in enumerate(changed(a, sensitive)):
        if c:
            marks.append(times[i + 1])
    marks.append(times[-1])
    return np.diff(np.array(marks)), times[-1] - times[0]


def describe(seconds, span, refresh_ms=8.333):
    refreshes = seconds * 1000.0 / refresh_ms
    n = len(refreshes)
    print("%d distinct pictures over %.1f s -- %.1f/s" % (n, span, n / span))
    print("mean hold %.2f refreshes (%.2f ms), median %.2f, sd %.2f"
          % (refreshes.mean(), seconds.mean() * 1000, np.median(refreshes),
             refreshes.std()))
    print("an even stream at 60 presents/s into a %.2f ms refresh holds every"
          " picture 2\n" % refresh_ms)
    print("  refreshes   count    share")
    rounded = np.round(refreshes).astype(int)
    for k in range(0, min(int(rounded.max()), 12) + 1):
        c = int((rounded == k).sum())
        if not c:
            continue
        print("  %9d %7d %6.1f%%  %s"
              % (k, c, 100.0 * c / n, "#" * int(round(60.0 * c / n))))
    over = int((rounded > 12).sum())
    if over:
        print("  %9s %7d %6.1f%%" % (">12", over, 100.0 * over / n))
    print()
    print("even  %.1f%%   too short %.1f%%   too long %.1f%%"
          % ((rounded == 2).mean() * 100, (rounded < 2).mean() * 100,
             (rounded > 2).mean() * 100))


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    refresh = 8.333
    if "--refresh" in sys.argv:
        refresh = float(sys.argv[sys.argv.index("--refresh") + 1])
    sensitive = "--sensitive" in sys.argv
    if sensitive:
        print("sensitive: a high percentile per pixel, for small moving objects\n")
    seconds, span = holds(sys.argv[1], sensitive)
    describe(seconds, span, refresh)


if __name__ == "__main__":
    main()
