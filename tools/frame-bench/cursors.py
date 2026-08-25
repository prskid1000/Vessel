"""Where the cursor actually lands, frame by frame, against where it should be.

WHY THIS AND NOT ANOTHER SYNTHETIC SCENE. island.py measures a bright square on
a flat field, which is a model of the cursor rather than the cursor: a real
pointer is anti-aliased, carries a shadow, sits on a desktop that is not flat,
and moves at whatever speed and direction a hand moves it. Two changes shipped
on that model this session and both came back worse from the device, one of them
worse at the very speed it was meant to fix. The model is not wrong; it is not
the thing being complained about.

The recording is. With the synthesis stamp on, every frame is labelled real or
synthesised, and the guest's own frames give the pointer's true trajectory. So
each synthesised frame can be asked the only question that matters: is the
pointer where it should be at that instant, and if not, which way is it wrong.

HOW THE CURSOR IS FOUND WITHOUT KNOWING WHAT IT LOOKS LIKE. On a desktop the
cursor is the only thing moving, so the static background is the per-pixel
median of frames sampled across the clip -- anything that moves is a minority at
any given pixel. Whatever differs from that is the pointer and its ghosts,
whatever shape they are.

WHAT IS MEASURED PER FRAME.

  - lag: how far the ink's centre sits BEHIND where the pointer should be at
    that instant, measured along the direction of travel. Positive is behind,
    which is what "lagging" means; negative is ahead of itself. The expected
    position is linear between the two real frames either side, which is exactly
    what the pipeline is trying to produce.
  - ink: how much pointer-like substance is on screen, as a multiple of one
    clean pointer. Six copies at a third strength each is two units. This counts
    substance rather than shapes, so it does not need to segment the ghosts.
  - spread: the diagonal of the box holding it. One pointer is a few tens of
    pixels; a trail spans the distance travelled.

SPLIT BY SPEED AND BY DIRECTION, because the report is that they differ. A
single average over a clip containing both hides precisely the thing being
asked about, and direction dependence in particular would point somewhere very
specific -- the field's two axes are handled by the same code, so a difference
between horizontal and vertical is not a tuning matter.

    python cursors.py recording.mp4 [--sample 40]
"""
import sys

import numpy as np

import scan

THRESHOLD = 0.06        # how far from the desktop counts as "not the desktop"

# **The bottom strip is excluded, and the first run of this file is why.**
# The synthesis stamp is sixteen magenta pixels in the bottom-left corner and it
# appears on synthesised frames only -- so including it put a bright fixed point
# into the mask of exactly the frames being measured, and dragged every centroid
# towards one corner. The lag that came out flipped sign with heading (right
# -345 px, left +327, up -248, down +189), which is not a property of the
# pipeline but the signature of a centroid being pulled to a fixed place. The
# progress bar Vessel draws along the same edge would do it too.
STAMP_ROWS = 28
SECTORS = [("right", 0), ("down-right", 45), ("down", 90), ("down-left", 135),
           ("left", 180), ("up-left", 225), ("up", 270), ("up-right", 315)]


def background(path, want=40):
    """The desktop, as the per-pixel median of frames spread across the clip."""
    frames = []
    for i, f in enumerate(scan.stream(path)):
        if i % 25 == 0:
            frames.append(f.astype(np.float32) / 255.0)
            if len(frames) >= want:
                break
    if not frames:
        sys.exit("no frames decoded")
    return np.median(np.stack(frames), axis=0)


def measure(path, bg):
    """Per frame: is it synthesised, where is the ink, how much, how spread."""
    out = []
    for i, raw in enumerate(scan.stream(path)):
        f = raw.astype(np.float32) / 255.0
        d = np.abs(f - bg).mean(axis=2)
        d[-STAMP_ROWS:, :] = 0.0
        mask = d > THRESHOLD
        if not mask.any():
            out.append((i, scan.marked(raw), None, 0.0, 0.0))
            continue
        ys, xs = np.nonzero(mask)
        w = d[mask]
        centre = (float((xs * w).sum() / w.sum()), float((ys * w).sum() / w.sum()))
        spread = float(np.hypot(ys.max() - ys.min(), xs.max() - xs.min()))
        out.append((i, scan.marked(raw), centre, float(w.sum()), spread))
    return out


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    want = 40
    if "--sample" in sys.argv:
        want = int(sys.argv[sys.argv.index("--sample") + 1])

    bg = background(sys.argv[1], want)
    rows = measure(sys.argv[1], bg)
    reals = [r for r in rows if not r[1] and r[2] is not None]
    if len(reals) < 3:
        sys.exit("fewer than three real frames carried a pointer -- was it moving,\n"
                 "and is the synthesis stamp on?")

    unit = float(np.percentile([r[3] for r in reals], 10)) or 1.0
    print("%d frames, %d real with a pointer; one pointer = %.2f units of ink\n"
          % (len(rows), len(reals), unit))

    # Each synthesised frame belongs to the interval between the real frames
    # either side of it, and its expected position is linear between them.
    samples = []
    for k in range(len(reals) - 1):
        a, b = reals[k], reals[k + 1]
        gap = b[0] - a[0]
        if gap < 2 or gap > 24:
            continue
        vx, vy = b[2][0] - a[2][0], b[2][1] - a[2][1]
        dist = float(np.hypot(vx, vy))
        if dist < 2.0:
            continue
        for r in rows[a[0] + 1:b[0]]:
            if not r[1] or r[2] is None:
                continue
            t = (r[0] - a[0]) / float(gap)
            ex = a[2][0] + vx * t
            ey = a[2][1] + vy * t
            # Along the direction of travel: positive means behind.
            lag = -((r[2][0] - ex) * vx + (r[2][1] - ey) * vy) / dist
            ang = np.degrees(np.arctan2(vy, vx)) % 360.0
            samples.append((dist, ang, lag, r[3] / unit, r[4]))

    if not samples:
        sys.exit("no synthesised frame sat between two real frames with motion")

    s = np.array([x[0] for x in samples])
    ang = np.array([x[1] for x in samples])
    lag = np.array([x[2] for x in samples])
    ink = np.array([x[3] for x in samples])
    spread = np.array([x[4] for x in samples])

    print("%d synthesised frames sit between two real ones with real motion\n"
          % len(samples))

    print("  BY SPEED -- how far the pointer moved between real frames")
    print("  %-14s %6s %10s %10s %8s %8s %8s"
          % ("moved", "n", "lag px", "lag p90", "misplaced", "ink", "spread"))
    cuts = [2, 20, 60, 150, 400, 1e9]
    for lo, hi in zip(cuts, cuts[1:]):
        m = (s >= lo) & (s < hi)
        if m.sum() < 3:
            continue
        # **The share that landed nowhere near right.** A frame whose pointer
        # sits more than a quarter of the interval's travel behind has not been
        # compensated at all -- a cross-fade puts the ink half way back, so this
        # counts frames where the motion was lost rather than merely imprecise.
        # The median hides it completely: it is zero in every band.
        bad = float((lag[m] > 0.25 * s[m]).mean() * 100.0)
        print("  %-14s %6d %10.1f %10.1f %7.1f%% %8.2f %8.0f"
              % ("%d-%d px" % (lo, hi) if hi < 1e9 else "%d+ px" % lo,
                 int(m.sum()), np.median(lag[m]), np.percentile(lag[m], 90),
                 bad, np.median(ink[m]), np.median(spread[m])))
    print()

    print("  BY DIRECTION -- only where the pointer moved more than 20 px")
    print("  %-14s %6s %10s %9s %9s" % ("heading", "n", "lag px", "ink", "spread"))
    fast = s >= 20
    for name, centre in SECTORS:
        d = np.abs(((ang - centre + 180.0) % 360.0) - 180.0)
        m = fast & (d <= 22.5)
        if m.sum() < 3:
            continue
        print("  %-14s %6d %10.1f %9.2f %9.0f"
              % (name, int(m.sum()), np.median(lag[m]), np.median(ink[m]),
                 np.median(spread[m])))
    print()
    print("  Lag is measured along the direction of travel, so positive is behind")
    print("  where it should be. Ink above 1.0 is more than one pointer's worth of")
    print("  substance on screen. A direction that differs from its opposite is not")
    print("  a tuning matter -- both axes go through the same code.")


if __name__ == "__main__":
    main()
