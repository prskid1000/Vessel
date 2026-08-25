"""Score EVERY frame of a recording and pull out the ones that are visibly wrong.

WHY SCAN RATHER THAN SAMPLE. Every other tool here takes a handful of triples
and reports an average over them. That is the right shape for comparing two
algorithms on identical input, and the wrong shape for finding an artefact: the
complaint is that objects break apart occasionally, and an occasional event in a
five-thousand-frame recording is invisible to any mean of twenty samples. Four
metrics in this directory have already been blind to the thing they were aimed
at, and three of those were blind because they averaged.

WHAT IT MEASURES, AND WHY IT NEEDS NO GROUND TRUTH. A recording is a sequence,
and a sequence carries its own expectation: whatever is happening, frame i
should sit between frame i-1 and frame i+1. Motion makes that approximate, not
false -- a pan moves everything smoothly, so the midpoint of the neighbours is
close to the frame. A frame that has torn or shattered does not sit between its
neighbours anywhere near the broken part, and it is the only kind of frame that
does not.

    anomaly(i) = |F(i) - (F(i-1) + F(i+1)) / 2|

Scored two ways per frame, because the two answer different questions:

    mean          how wrong the whole frame is -- catches a global failure
    worst 1%      how wrong the worst few hundred pixels are -- catches an object
                  that broke while the rest of the picture was fine

The second is the one the artefact lives in. locate.py measured the worst 1% at
80 levels against a mean of 11 on real footage, which is the signature of
something small being catastrophically wrong rather than everything being
slightly soft.

WHAT IT CANNOT DO. A real frame that follows a scene cut is not between its
neighbours either, and neither is one during a flash. So this ranks suspects, it
does not convict them -- which is why it writes the frames out. The triplet
before/at/after each suspect goes to disk as PNG so the thing can be looked at
rather than argued about.

Frames carrying the synthesis stamp are separated from the guest's own, so the
question "is the pipeline doing this, or is the game" gets an answer rather than
an assumption.

    python scan.py recording.mp4 [--dump 12] [--out DIR]
"""
import os
import subprocess
import sys

import numpy as np

import gameplay

W, H = gameplay.W, gameplay.H
CHUNK = 3 * W * H


def stream(path):
    """Frames one at a time, so a 120 MB recording does not need 15 GB of RAM."""
    p = subprocess.Popen(
        ["ffmpeg", "-v", "error", "-i", path, "-vf", "scale=%d:%d" % (W, H),
         "-pix_fmt", "rgb24", "-f", "rawvideo", "-"],
        stdout=subprocess.PIPE)
    while True:
        buf = p.stdout.read(CHUNK)
        if len(buf) < CHUNK:
            break
        yield np.frombuffer(buf, dtype=np.uint8).reshape(H, W, 3)
    p.stdout.close()
    p.wait()


def marked(frame):
    """The synthesis stamp, on one frame rather than a batch. See gameplay."""
    patch = frame[-16:, :W // 4, :].astype(np.int16)
    r, g, b = patch[..., 0], patch[..., 1], patch[..., 2]
    return int(((r > 150) & (b > 150) & (g < 90)).sum()) > 4


def anomaly(prev, cur, nxt):
    """How far this frame sits from the midpoint of its neighbours."""
    mid = (prev.astype(np.float32) + nxt.astype(np.float32)) * 0.5
    err = np.abs(cur.astype(np.float32) - mid).mean(axis=2)
    flat = np.sort(err.ravel())
    return float(err.mean()), float(flat[int(flat.size * 0.99):].mean())


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    dump = 12
    if "--dump" in sys.argv:
        dump = int(sys.argv[sys.argv.index("--dump") + 1])
    out = os.path.join(os.path.dirname(os.path.abspath(sys.argv[1])), "scan")
    if "--out" in sys.argv:
        out = sys.argv[sys.argv.index("--out") + 1]

    rows = []
    ring = []
    for i, f in enumerate(stream(sys.argv[1])):
        ring.append(f.copy())
        if len(ring) > 3:
            ring.pop(0)
        if len(ring) == 3:
            m, w = anomaly(ring[0], ring[1], ring[2])
            rows.append((i - 1, marked(ring[1]), m, w))

    if not rows:
        sys.exit("no frames decoded")

    idx = np.array([r[0] for r in rows])
    syn = np.array([r[1] for r in rows])
    mean = np.array([r[2] for r in rows])
    worst = np.array([r[3] for r in rows])

    print("%d frames scored (%d synthesised, %d real)\n"
          % (len(rows), syn.sum(), (~syn).sum()))

    print("  %-26s %9s %9s %9s %9s"
          % ("", "mean", "worst 1%", "p99 of", "max"))
    print("  %-26s %9s %9s %9s %9s"
          % ("", "levels", "levels", "worst 1%", "worst 1%"))
    for tag, sel in (("synthesised frames", syn), ("the guest's own frames", ~syn)):
        if sel.sum() == 0:
            continue
        print("  %-26s %9.2f %9.2f %9.2f %9.2f"
              % (tag, mean[sel].mean(), worst[sel].mean(),
                 np.percentile(worst[sel], 99), worst[sel].max()))
    print()

    # The suspects: worst 1% far above what this recording usually does. Ranked
    # on the tail rather than the mean, because an object that shattered leaves
    # the mean almost where it was.
    cut = np.percentile(worst, 99.5)
    hits = np.flatnonzero(worst >= cut)
    print("  %d frames above the 99.5th percentile of the tail (%.1f levels)"
          % (len(hits), cut))
    order = hits[np.argsort(-worst[hits])][:dump]
    print("  %-8s %-12s %9s %9s" % ("frame", "kind", "mean", "worst 1%"))
    for j in order:
        print("  %-8d %-12s %9.2f %9.2f"
              % (idx[j], "synthesised" if syn[j] else "real", mean[j], worst[j]))

    # Write them out, with a neighbour either side, so the ranking can be
    # checked by eye instead of believed.
    if dump:
        from PIL import Image
        os.makedirs(out, exist_ok=True)
        want = set()
        for j in order:
            want.update((idx[j] - 1, idx[j], idx[j] + 1))
        written = 0
        for i, f in enumerate(stream(sys.argv[1])):
            if i in want:
                kind = "syn" if marked(f) else "real"
                Image.fromarray(f).save(
                    os.path.join(out, "f%05d_%s.png" % (i, kind)))
                written += 1
        print("\n  %d frames written to %s" % (written, out))
    print()
    print("  A synthesised frame far above the real frames' tail is the pipeline")
    print("  breaking something. A real frame up there is a scene cut or a flash,")
    print("  and is the control -- if both sit at the same level, this recording")
    print("  does not contain the artefact and there is nothing here to fix.")


if __name__ == "__main__":
    main()
