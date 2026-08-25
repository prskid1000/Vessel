"""Build a ground-truthed bench out of REAL gameplay, from a screen recording.

WHY THIS BEATS EVERY SYNTHETIC SCENE HERE. The other scenes translate a
photograph by a known amount. That gives an exact ground truth and a motion the
matcher finds easily, and it has repeatedly proved things that did not survive
contact with the device: a coarse-vector substitution that measured better and
looked worse, a two-hypothesis chooser that improved image RMS by ten per cent
and flashed on screen, three pacing corrections that measured neutral. A rolled
photograph has one global motion, no occlusion, no shading change, no motion
blur, no compression, and no noise -- and every one of those is present in the
picture the pipeline actually has to interpolate.

The recordings already contain the real thing, and the synthesis stamp makes it
recoverable. `FG_LOG=mark` puts sixteen magenta pixels in one corner of every
interpolated frame, so the unstamped frames in a recording are exactly the
guest's own frames. Three consecutive real frames A, B, C give a pair to
interpolate and a REAL PHOTOGRAPH of the answer: interpolate A to C at the
midpoint and compare against B.

That is the standard way frame interpolation is evaluated, and it needs no
assumption about what moved or how far.

WHAT IT CANNOT DO. B is one guest frame later than A, so a triple spans two
guest intervals -- twice the displacement the pipeline normally sees, which
makes it a harder case than the real one rather than an easier one. The frames
are also post-upscale and video-compressed. Neither stops one algorithm being
compared against another on identical input, which is the whole job.

    python gameplay.py recording.mp4 [--triples 40]
"""
import subprocess
import sys

import numpy as np

W, H = 1280, 720


def frames(path, width=W, height=H):
    p = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", path, "-vf", "scale=%d:%d" % (width, height),
         "-pix_fmt", "rgb24", "-f", "rawvideo", "-"],
        capture_output=True)
    if p.returncode:
        sys.exit(p.stderr.decode()[:600])
    a = np.frombuffer(p.stdout, dtype=np.uint8)
    n = a.size // (width * height * 3)
    return a[:n * width * height * 3].reshape(n, height, width, 3)


def stamped(a):
    """Which frames carry the synthesis mark. See artefacts.marked."""
    patch = a[:, -16:, :a.shape[2] // 4, :].astype(np.int16)
    r, g, b = patch[..., 0], patch[..., 1], patch[..., 2]
    return ((r > 150) & (b > 150) & (g < 90)).sum(axis=(1, 2)) > 4


def triples(path, limit=40, min_motion=1.5, with_phase=False):
    """Consecutive real frames, three at a time, with the middle as the truth.

    Only triples that actually move are kept: a still triple is trivially
    interpolated by any algorithm and would flatter all of them equally.

    **B is not at the midpoint, and assuming it is invalidated four measurements.**
    This filter accepts any run of three real frames whose gaps are each under
    twelve recorded frames; it has never required the two gaps to be EQUAL, and
    on real footage they usually are not:

        true phase of B between A and C -- mean 0.495, sd 0.276
        within 0.45-0.55   31.1% of triples
        beyond 0.35-0.65   57.1% of triples

    The mean is 0.495, which is why nothing caught it: the error cancels
    perfectly in aggregate and is large in every individual frame. Scoring an
    interpolation at t=0.5 against a truth at t=0.11 displaces every moving
    object by four fifths of an interval, and it does so ASYMMETRICALLY -- a warp
    commits each object to a position and is punished for the phase being wrong,
    while a blend commits to no position and barely notices. That difference is
    enough on its own to make motion compensation look worse than averaging, a
    wide search window look worse than a narrow one, and the residual land on
    the fastest-moving pixels in the frame.

    The device does not make this mistake; it computes phase = 1/K +
    elapsed/interval and warps there. Only the bench did. `with_phase` returns
    the real one, from the recorded frame indices, so a test can score where the
    truth actually is.
    """
    a = frames(path)
    mark = stamped(a)
    if not mark.any():
        sys.exit("no synthesis stamp in this recording -- real and synthesised\n"
                 "frames cannot be separated, so there is no ground truth.\n"
                 "Set FG_LOG=mark (or all) in the container and record again.")
    real = np.flatnonzero(~mark)

    out = []
    for i in range(len(real) - 2):
        x, y, z = real[i], real[i + 1], real[i + 2]
        # Adjacent in the real stream, not either side of a gap.
        if y - x > 12 or z - y > 12:
            continue
        A = a[x].astype(np.float32) / 255.0
        B = a[y].astype(np.float32) / 255.0
        C = a[z].astype(np.float32) / 255.0
        moved = np.abs(C - A).mean() * 255.0
        if moved < min_motion:
            continue
        out.append((A, B, C, moved, (y - x) / float(z - x)) if with_phase
                   else (A, B, C, moved))
        if len(out) >= limit:
            break
    if not out:
        sys.exit("no usable triples: every run of three real frames was static")
    return out


def report(path, limit=40):
    got = triples(path, limit)
    print("%d triples of consecutive real frames from %s"
          % (len(got), path.rsplit("\\", 1)[-1].rsplit("/", 1)[-1]))
    moved = np.array([m for _, _, _, m in got])
    print("  motion between the outer two: %.1f to %.1f levels, mean %.1f\n"
          % (moved.min(), moved.max(), moved.mean()))

    # The two answers that need no motion estimation at all, as the floor every
    # algorithm has to beat. A pipeline that cannot beat the blend is doing
    # arithmetic for nothing.
    def rms(x, y):
        return float(np.sqrt(((x - y) ** 2).mean()) * 255.0)

    hold = np.mean([rms(A, B) for A, B, C, _ in got])
    blend = np.mean([rms((A + C) / 2.0, B) for A, B, C, _ in got])
    print("  %-28s %8s" % ("baseline", "rms"))
    print("  %-28s %8.2f" % ("show the old frame", hold))
    print("  %-28s %8.2f" % ("blend the two, no motion", blend))
    print()
    print("  Any motion-compensated result has to come in under both of these")
    print("  on this input before it is worth anything. Import `triples` from")
    print("  here to score one; the middle frame is a real photograph, so the")
    print("  comparison needs no assumption about what moved.")


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    limit = 40
    if "--triples" in sys.argv:
        limit = int(sys.argv[sys.argv.index("--triples") + 1])
    report(sys.argv[1], limit)


if __name__ == "__main__":
    main()
