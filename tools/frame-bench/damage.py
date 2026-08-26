"""WHERE the shipped synthesised frames are damaged, not how much on average.

WHY A NEW MEASUREMENT. `harm` has just been shown to be a near-tie counter:
scored on this very clip, a plain blend -- which applies no motion at all and
therefore cannot be wrong about motion -- is charged with harming 30.8% of
barely-moved pixels and 2.6% of decisively-moved ones, while the two controls
read exactly 0.0 in every band. So harm is dominated by pixels sitting just
above its 0.008 floor, where any answer loses a coin flip. It is a whole-frame
average of coin flips, and the complaint is "distortion in SOME AREAS" -- a
localised, high-magnitude fault that such an average is constructed to hide.

WHAT IS SCORED, AND WHY IT NEEDS NO BLOCK MATCHER. This reads the SHIPPED
synthesised frames straight out of the recording -- not a laptop
reconstruction -- so the stand-in matcher that invalidated locate.py, regime.py
and floor.py is not involved anywhere.

A synthesised frame S sits between real frames A and C. Every pixel it is
entitled to show comes from A, from C, or from a blend of the two, so the
legitimate values at a pixel form the SEGMENT between A and C in colour space.
Distance from that segment is content that exists in neither source at that
position: invented. That is the definition already used by `fg truth`, but
resolved per pixel instead of averaged over the frame.

  invention   distance from the A-C segment, per pixel
  patches     how much of it clumps -- a coarse grid, so a few big torn
              regions are told apart from an even sprinkle of noise. This is
              the distinction the frame average cannot make and the eye makes
              instantly.

The worst frames are written out as PNG triples (A, S, C) so the fault can be
looked at rather than argued about.

    python damage.py recording.mp4 [--dump 6]
"""
import os
import sys

import numpy as np
import scan

GRID = 32                # patch size for clumping, in pixels
INVENTED = 0.06          # per-pixel distance from the segment that counts


def triples_shipped(path):
    """Every stamped frame, with the real frames either side of it.

    **Runs, not single frames.** At 4x the pipeline puts THREE synthesised
    frames between one real pair, so a detector that only accepts a stamped
    frame flanked by two unstamped ones finds nothing above 2x -- which is
    exactly the configuration being complained about. Each frame in the run is
    yielded against the same pair, together with where it sits in the run, so
    damage can be read against phase as well as against position.

    Consecutive duplicates are dropped first: the capture samples faster than
    the app presents, so one picture appears several times and would otherwise
    be counted as several frames.
    """
    prev = None
    seq = []
    for raw in scan.stream(path):
        f = raw.astype(np.float32) / 255.0
        if prev is not None and float(np.abs(f - prev).mean()) < 0.004:
            continue
        prev = f
        seq.append((scan.marked(raw), f))
    i = 0
    while i < len(seq):
        if seq[i][0] or i + 1 >= len(seq):
            i += 1
            continue
        j = i + 1
        while j < len(seq) and seq[j][0]:
            j += 1
        if j < len(seq) and j > i + 1:
            run = j - i - 1
            for k in range(run):
                yield (i + 1 + k, seq[i][1], seq[i + 1 + k][1], seq[j][1],
                       (k + 1) / float(run + 1))
        i = j


def invention(A, S, C):
    """Per-pixel distance from the segment of legitimate values."""
    d = C - A
    den = (d * d).sum(axis=2) + 1e-8
    t = np.clip(((S - A) * d).sum(axis=2) / den, 0.0, 1.0)[..., None]
    return np.linalg.norm(S - (A + d * t), axis=-1)


def main():
    dump = 6
    if "--dump" in sys.argv:
        dump = int(sys.argv[sys.argv.index("--dump") + 1])
    path = [a for a in sys.argv[1:] if not a.startswith("--") and a != str(dump)][0]

    rows = []
    for idx, A, S, C, ph in triples_shipped(path):
        inv = invention(A, S, C)
        bad = inv > INVENTED
        H, W = bad.shape
        gh, gw = H // GRID, W // GRID
        patch = bad[:gh * GRID, :gw * GRID].reshape(gh, GRID, gw, GRID).mean(axis=(1, 3))
        rows.append((idx, float(bad.mean() * 100.0), ph,
                     float((patch > 0.5).sum()), int(gh * gw), A, S, C, patch))

    if not rows:
        sys.exit("no shipped triples found -- is the synthesis stamp on?")

    share = np.array([r[1] for r in rows])
    clump = np.array([r[3] for r in rows])
    cells = rows[0][4]
    print("%d shipped synthesised frames, each between two real ones\n" % len(rows))
    print("  %-38s %8.2f%%" % ("pixels showing invented content, mean", share.mean()))
    print("  %-38s %8.2f%%" % ("  ... worst frame", share.max()))
    print("  %-38s %8.1f  of %d" % ("32px patches over half invented, mean",
                                    clump.mean(), cells))
    print("  %-38s %8.0f" % ("  ... worst frame", clump.max()))
    print()
    # **Split by phase, because at 4x the three synthesised frames in a run are
    # not equivalent.** The middle one is furthest from both sources and the
    # outer two lean on one of them, so if damage rises towards the middle the
    # fault is the interpolation reaching; if it is flat, the fault is the field.
    ph = np.array([r[2] for r in rows])
    if len(set(np.round(ph, 3))) > 1:
        print("  %-22s %7s %10s %10s" % ("phase in the run", "n", "share %", "patches"))
        for lo, hi in ((0.0, 0.34), (0.34, 0.66), (0.66, 1.0)):
            m = (ph > lo) & (ph <= hi)
            if m.sum() < 2:
                continue
            print("  %-22s %7d %10.2f %10.1f"
                  % ("%.0f-%.0f%% across" % (lo * 100, hi * 100),
                     int(m.sum()), share[m].mean(), clump[m].mean()))
        print()

    print("  %-22s %8s %10s %8s %10s" % ("frames ranked by clump", "share %",
                                         "patches", "phase", "recording ix"))
    order = np.argsort(-clump)
    for k in order[:min(dump, len(rows))]:
        r = rows[k]
        print("  %-22s %8.2f %10.0f %7.0f%% %10d" % ("", r[1], r[3], r[2] * 100, r[0]))

    outdir = "damage_" + os.path.splitext(os.path.basename(path))[0]
    os.makedirs(outdir, exist_ok=True)
    try:
        from PIL import Image
    except ImportError:
        print("\n  (Pillow not installed -- no frames written)")
        return
    for rank, k in enumerate(order[:min(dump, len(rows))]):
        idx, _, _, _, _, A, S, C, patch = rows[k]
        inv = invention(A, S, C)
        heat = np.zeros_like(A)
        heat[..., 0] = np.clip(inv * 4.0, 0, 1)
        strip = np.concatenate([A, S, C, np.clip(S * 0.35 + heat * 0.9, 0, 1)], axis=1)
        Image.fromarray((strip * 255).astype(np.uint8)).save(
            os.path.join(outdir, "worst%d_ix%d.png" % (rank, idx)))
    print("\n  wrote %d strips to %s/ -- older | SYNTHESISED | newer | invention map"
          % (min(dump, len(rows)), outdir))


if __name__ == "__main__":
    main()
