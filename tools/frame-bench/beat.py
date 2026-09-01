"""The sharpness beat between real and synthesised frames, on a real recording.

WHAT THIS ASKS. At 4x the presented stream is REAL, SYN, SYN, SYN, REAL. The
real frame is the guest's own pixels, blitted by FrameSynthesizer.presentLatest.
Every synthesised frame is a bilinear-fetched, phase-weighted blend of two of
them, written by InterpolateMaterial. Those are two different kinds of picture,
and if they do not carry the same amount of high-frequency detail then sharpness
is modulated periodically at a quarter of the presented rate -- which is a
15-30 Hz beat, localised to exactly the edges and texture where the complaint
lives, and present no matter how right the field is.

WHY THE EXISTING TOOL CANNOT SEE IT. artefacts.softness reports 3.9% on this
recording, and it is measuring through two filters that remove most of the
effect:

  - it decodes at 695x316, one quarter of the 2780x1264 capture. A 4x downsample
    is a low-pass, and the thing under test is the loss of high frequencies.
  - it averages gradient energy over the whole recording and compares two means.
    Thirty seconds of gameplay is not one scene, so a difference between the
    real and synthesised populations is confounded with whatever the camera was
    looking at when each was drawn.

WHAT THIS DOES INSTEAD. Full resolution, and every synthesised frame is scored
against ITS OWN two real neighbours rather than against a global mean. Within
one interval the scene is very nearly the same picture, so the ratio

    sharpness(synth) / mean(sharpness(real before), sharpness(real after))

has the content divided out of it. A ratio of 1.0 is a pipeline whose two kinds
of frame are indistinguishable in sharpness and therefore cannot beat.

THE PREDICTION THAT MAKES THIS FALSIFIABLE. If the mechanism is the resample,
the loss is largest where the fetch offset is furthest from a whole texel and
the two endpoint weights are most equal -- both of which peak in the MIDDLE of
the interval. So the middle synthesised frame of each group should be the
softest, and the ones nearest the real frames should be the sharpest. A flat
profile across the group means the softness is something else, and the
resample is exonerated.

TWO MEASURES, because they answer slightly different questions. Gradient energy
is what artefacts.py uses and is kept for comparability. Laplacian energy is a
narrower band and is the more sensitive probe for the small blur a bilinear
fetch applies.

WHAT THIS CANNOT ESTABLISH. The capture is H.264 and compression is its own
low-pass. It lands on both kinds of frame, so the within-group ratio survives
it, but an absolute sharpness here is not the compositor's.

    python beat.py recording.mp4
    python beat.py recording.mp4 --limit 400      # first 400 frames, for a check
"""
import subprocess
import sys

import numpy as np

# The stamp is sixteen guest pixels; see InterpolateMaterial's `mark` uniform
# and artefacts.marked for why the search region is the bottom-left of the
# frame rather than its corner -- the guest is letterboxed and its corner is
# not the frame's.
STAMP_ROWS = 64
STAMP_MIN_HITS = 12

# A captured frame differing from the one before it by less than this is the
# same picture held for another refresh. Same constant as artefacts.pacing.
CHANGED = 0.35


def probe(path):
    """Width and height of the recording, so nothing is decoded at a guess."""
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0",
         "-show_entries", "stream=width,height", "-of", "csv=p=0:s=x", path],
        capture_output=True, text=True)
    if out.returncode:
        sys.exit(out.stderr[:600])
    w, h = out.stdout.strip().split("x")
    return int(w), int(h)


def stream(path, w, h, limit=None):
    """Full-resolution frames, one at a time.

    Streamed rather than loaded: 2048 frames of 2780x1264 RGB is 21 GB, and
    every metric here is a scalar per frame.
    """
    p = subprocess.Popen(
        ["ffmpeg", "-v", "error", "-i", path, "-pix_fmt", "rgb24",
         "-f", "rawvideo", "-"],
        stdout=subprocess.PIPE, bufsize=1 << 24)
    size = w * h * 3
    n = 0
    while limit is None or n < limit:
        buf = p.stdout.read(size)
        if len(buf) < size:
            break
        yield np.frombuffer(buf, dtype=np.uint8).reshape(h, w, 3)
        n += 1
    p.stdout.close()
    p.terminate()


def stamped(frame):
    """Whether this frame carries the synthesis stamp."""
    patch = frame[-STAMP_ROWS:, :frame.shape[1] // 4, :]
    r = patch[..., 0].astype(np.int16)
    g = patch[..., 1].astype(np.int16)
    b = patch[..., 2].astype(np.int16)
    return int(((r > 150) & (b > 150) & (g < 90)).sum()) > STAMP_MIN_HITS


def sharpness(gray):
    """Gradient energy and Laplacian energy, over the whole picture.

    Both are means of an absolute difference, so both are in grey levels and
    neither has a threshold to be dominated by.
    """
    gx = np.abs(np.diff(gray, axis=1)).mean()
    gy = np.abs(np.diff(gray, axis=0)).mean()
    lap = np.abs(4.0 * gray[1:-1, 1:-1]
                 - gray[:-2, 1:-1] - gray[2:, 1:-1]
                 - gray[1:-1, :-2] - gray[1:-1, 2:]).mean()
    return gx + gy, lap


def collect(path, limit=None):
    """One record per DISTINCT picture: kind, and the two sharpness measures.

    The capture runs at the panel's rate, so a run of identical frames is a
    picture held over several refreshes. Counting those repeats would weight a
    held picture more heavily than a shown one for no reason connected to what
    is being measured.
    """
    w, h = probe(path)
    rows = []
    previous = None
    for frame in stream(path, w, h, limit):
        gray = frame.mean(axis=-1, dtype=np.float32)
        if previous is not None and np.abs(gray - previous).mean() <= CHANGED:
            previous = gray
            continue
        previous = gray
        grad, lap = sharpness(gray)
        rows.append((stamped(frame), grad, lap))
    return rows


def groups(rows):
    """Runs of synthesised pictures bounded by a real one on each side.

    The bounded requirement is what makes the comparison local. A run at either
    end of the recording has only one neighbour and is dropped rather than
    compared against a single frame.
    """
    out = []
    run = []
    before = None
    for kind, grad, lap in rows:
        if kind:
            run.append((grad, lap))
        else:
            if run and before is not None:
                out.append((before, list(run), (grad, lap)))
            run = []
            before = (grad, lap)
    return out


def report(rows):
    total = len(rows)
    synth = sum(1 for r in rows if r[0])
    print("PICTURES  %d distinct, %d synthesised, %d real, from the stamp"
          % (total, synth, total - synth))
    if synth == 0:
        print("          no stamp found -- record with FG_LOG=mark, or this")
        print("          tool has nothing to split and reports nothing")
        return
    if synth == total:
        print("          every picture is stamped, which cannot be right")
        return

    gs = groups(rows)
    if not gs:
        print("GROUPS    no synthesised run sits between two real frames")
        return

    sizes = np.array([len(r) for _, r, _ in gs])
    print("GROUPS    %d runs bounded by real frames on both sides; %d..%d "
          "synthesised each, %.2f mean"
          % (len(gs), sizes.min(), sizes.max(), sizes.mean()))
    print("          (at 4x a full interval is 3; short runs are presents the")
    print("           pacer dropped, which is expected and not an error here)")
    print()

    for name, idx in (("gradient", 0), ("laplacian", 1)):
        ratios = []
        for before, run, after in gs:
            anchor = 0.5 * (before[idx] + after[idx])
            if anchor <= 0:
                continue
            for value in run:
                ratios.append(value[idx] / anchor)
        ratios = np.array(ratios)
        print("%-10s synthesised sharpness as a fraction of its own two real"
              % name.upper())
        print("           neighbours: %.4f mean, %.4f median, %.4f..%.4f "
              "at the deciles"
              % (ratios.mean(), np.median(ratios),
                 np.quantile(ratios, 0.1), np.quantile(ratios, 0.9)))
        print("           %.2f%% of synthesised pictures are softer than the "
              "real frames around them"
              % (100.0 * (ratios < 1.0).mean()))
        print()

    # The falsifiable half. Only full-length runs, so position within the run
    # means the same thing in every group counted.
    for name, idx in (("gradient", 0), ("laplacian", 1)):
        full = [(b, r, a) for b, r, a in gs if len(r) == 3]
        if len(full) < 8:
            print("%-10s too few full 3-frame runs (%d) to profile position"
                  % (name.upper(), len(full)))
            continue
        prof = np.zeros(3)
        for before, run, after in full:
            anchor = 0.5 * (before[idx] + after[idx])
            for i in range(3):
                prof[i] += run[i][idx] / anchor
        prof /= len(full)
        print("%-10s within a full interval, by position -- the resample"
              % name.upper())
        print("           predicts the MIDDLE is softest:")
        print("           phase 1/4 %.4f   phase 2/4 %.4f   phase 3/4 %.4f "
              "  (%d runs)" % (prof[0], prof[1], prof[2], len(full)))
        print()


def main():
    args = [a for a in sys.argv[1:]]
    limit = None
    if "--limit" in args:
        i = args.index("--limit")
        limit = int(args[i + 1])
        del args[i:i + 2]
    if not args:
        sys.exit(__doc__)
    report(collect(args[0], limit))


if __name__ == "__main__":
    main()
