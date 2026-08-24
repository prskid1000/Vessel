"""Offer the second field to the median as a candidate, not to an argmin.

THE PROBLEM THIS ADDRESSES. Aiming the matcher with one global vector fixes
motion the fine window cannot reach and damages whatever moves differently from
the camera -- on a two-depth scene the near layer improves from 172 px of error
to 46 and the far layer degrades from 13 to 26. On the device that reads as
motion close to the camera juddering while distant scenery is smooth.

WHAT WAS TRIED AND WHY IT FAILED. Scoring both fields per block and keeping the
cheaper one. It measured well on a frame pair -- 10.54 image RMS to 9.55 -- and
it flashed on screen. An argmin between two costs is a knife-edge decision
remade from scratch every frame: where the two fields explain a block almost
equally well, image noise decides, and the block alternates between two vectors
and therefore between two pictures. flicker.py puts a number on it: 14.3% of
blocks changed their answer on a sequence whose true motion never changed. A
decision margin cut the flips almost in half and left the flicker unmeasurably
different, on the laptop and on the phone alike.

WHAT THIS DOES INSTEAD. The median filter already runs ten passes over the eight
neighbours, the pixel itself, and -- anchored -- the matcher's own vector, which
is what took waviness from 9.54 px to 0.013. A vector median picks the candidate
with the least total distance to all the others: a consensus, not a contest.
Offering the unaimed field as one more candidate lets it win where it agrees
with the neighbourhood and be ignored where it does not, and there is no
threshold anywhere to flip.

It is also far cheaper than the chooser, which scored sixteen taps under two
hypotheses per block and cost several frames a second on a GPU already at 97%.
This is one more texture fetch per pass.

    python consensus.py
"""
import numpy as np

import bench
import pyramid as P
import flicker as F

BLOCK = P.BLOCK


def vector_median(field, original, extra=None, passes=10):
    """The device's filter: least total L1 distance to every candidate.

    Anchored -- `original` is on offer in every pass, not only the first -- which
    is what removes the ceiling on how many passes help. `extra` is the second
    field, offered the same way.
    """
    current = field
    for _ in range(passes):
        padded = np.pad(current, ((1, 1), (1, 1), (0, 0)), mode="edge")
        neighbours = [padded[dy:dy + current.shape[0], dx:dx + current.shape[1]]
                      for dy in range(3) for dx in range(3)]
        candidates = neighbours + [original]
        if extra is not None:
            candidates.append(extra)
        stack = np.stack(candidates, axis=0)
        # Sum of L1 distances from each candidate to all of them.
        cost = np.abs(stack[:, None] - stack[None, :]).sum(axis=(0, -1))
        best = np.argmin(cost, axis=0)
        current = np.take_along_axis(stack, best[None, ..., None], axis=0)[0]
    return current


def accuracy():
    print("ACCURACY, on two depths with an exact ground truth\n")
    for far, near in (((160, 48), (60, 20)), ((60, 20), (170, -40))):
        newer, older, truth, row = P.layered(far, near)
        band, gh = row // BLOCK, newer.shape[0] // BLOCK
        tex = P.textured(newer)
        plain = bench.estimate(newer, older, radius=P.WINDOW)
        aimed = P.refined(newer, older)

        rows = [
            ("fine only, filtered", vector_median(plain, plain)),
            ("aimed, filtered", vector_median(aimed, aimed)),
            ("aimed + plain as candidate", vector_median(aimed, aimed, plain)),
        ]
        print("distant %s, near %s" % (far, near))
        print("  %-30s %9s %9s %9s"
              % ("field", "far err", "near err", "image rms"))
        for name, f in rows:
            print("  %-30s %9.1f %9.1f %9.2f"
                  % (name, P.vec_err(f[:band], far, tex[:band]),
                     P.vec_err(f[band:gh], near, tex[band:gh]),
                     P.rms(P.warp(newer, older, f), truth)))
        print("  %-30s %9s %9s %9.2f"
              % ("no compensation", "-", "-", P.rms((newer + older) / 2.0, truth)))
        print()


def stability():
    """The same fields over a sequence whose true motion never changes."""
    print("STABILITY, on a sequence at constant velocity with per-frame noise\n")
    frames, _ = F.sequence((16, 5), (40, -9))
    kinds = {"aimed, filtered": [], "aimed + plain as candidate": [],
             "aimed + plain, argmin (flashed)": []}
    for i in range(1, len(frames)):
        newer, older = frames[i], frames[i - 1]
        plain = bench.estimate(newer, older, radius=P.WINDOW)
        aimed = P.refined(newer, older)
        picked, _ = F.choose(newer, older, aimed, plain, 1.0)
        kinds["aimed, filtered"].append(vector_median(aimed, aimed))
        kinds["aimed + plain as candidate"].append(
            vector_median(aimed, aimed, plain))
        kinds["aimed + plain, argmin (flashed)"].append(
            vector_median(picked, picked))

    print("  %-34s %12s" % ("field", "jitter px"))
    for name, seq in kinds.items():
        a = np.array(seq)
        print("  %-34s %12.2f"
              % (name, np.linalg.norm(np.diff(a, axis=0), axis=-1).mean()))
    print()
    print("  The true field is identical in every pair, so all of this is the")
    print("  algorithm's own instability. Measured AFTER the median passes, so")
    print("  unlike flicker.py these are comparable to what reaches the screen.")


def main():
    print(__doc__.split("\n\n")[0])
    print()
    accuracy()
    stability()


if __name__ == "__main__":
    main()
