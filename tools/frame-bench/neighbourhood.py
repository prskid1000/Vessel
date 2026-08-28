"""Why the median's 3x3 cannot be widened, and what to do instead.

WHERE THIS STARTS. smear.py established that the soft edge photographed from
the device is the FIELD and not the blend: the same overlapped blend given a
correct field reads 0.84 edge sharpness against the shipped 0.62, and rms 2.58
against 6.36. So the headroom is in the field, and the filter is the last thing
that touches it.

An oracle allowed to pick, per block, the best of the ten candidates the median
already has reaches 3.05 px where the median itself reaches 5.88. Half the
remaining error is therefore a SELECTION failure rather than a missing answer,
and that is worth attacking.

WHAT WAS TRIED AND REFUTED. Scoring the candidates against the image, which is
the obvious way to break a consensus tie:

    consensus median (shipped)    5.88 px
    pure SAD pick                19.23 px
    consensus + 1.0 x SAD         9.84 px
    consensus + 2.0 x SAD        11.28 px

Monotonically worse with every gram of SAD added. That is the same conclusion
the shader-side experiments reached from the other end, and for the same
reason: in exactly the regions that are ambiguous, several offsets explain the
block equally well, so the score that chose the wrong vector cannot also detect
that it is wrong.

THE TRAP THIS FILE EXISTS TO RECORD. Widening the neighbourhood looks like the
answer, and on a parallax scene it is spectacular:

    3x3 x10 (shipped)      10 cands   5.88 px   sharp 0.622
    3x3 dilated x2         10 cands   5.17 px   sharp 0.640
    union of both          18 cands   4.37 px   sharp 0.685
    5x5                    26 cands   3.38 px   sharp 0.759

Halved field error, and the best edge sharpness anything has reached. Then the
same four filters on island.py's scene -- a 32-pixel object crossing a flat
field, which is the desktop cursor that reported ghosting:

    3x3 (shipped)      object error  5.25 px   88% of its blocks keep the motion
    3x3 dilated                24.00 px    0%
    union                      24.00 px    0%
    5x5                        24.00 px    0%

Every wider variant erases it completely. A 32-pixel object is four blocks
across; centre a 5x5 neighbourhood anywhere on it and background outnumbers
object, so it loses every vote, ten times over. The parallax scene has one
enormous smooth boundary and nothing small in it, and was structurally incapable
of showing this -- the same failure as measuring an overlap artefact on a bench
that only had a blocky warp.

**So the 3x3 is 3x3 for a reason, and the reason is not visible in any scene
that lacks a small moving object.**

WHAT WORKS INSTEAD. The temporal candidate -- what this block said one real
frame ago -- adds an eleventh candidate without widening anything, so it cannot
outvote a small object; the vector it offers a moving object IS that object's
own motion. Over a sequence with the exact middle frame known:

    3x3 x10 (shipped)   field 15.14 px   sharp 0.559   rms 6.88
    3x3 + temporal      field 10.52 px   sharp 0.598   rms 5.74
    TRUE field                 0.00 px   sharp 0.971   rms 2.36

    3x3 x10 (shipped)   field 10.12 px   sharp 0.603   rms 5.78
    3x3 + temporal      field  6.28 px   sharp 0.635   rms 4.63
    TRUE field                 0.00 px   sharp 0.970   rms 1.52

and on the cursor, 88% kept either way -- no regression at all.

**AND NOTE WHICH QUANTITY THAT IS.** temporal.py measured the same change as a
STEADINESS fix and concluded it was worthless: churn fell 60% and edge flicker
moved 45.13% to 44.70%, against a frozen-field floor of 44.77%. That conclusion
was right about churn and wrong about the change, because accuracy is a
different quantity and nobody had measured it. Field error falls 29-36%, rms
17-20%. It is worth keeping, for a reason unrelated to the one it was built for.

    python neighbourhood.py
"""
import numpy as np

import bench
import island as I
import pyramid as P
from consensus import vector_median

BLOCK = P.BLOCK

N3 = [(dy, dx) for dy in (-1, 0, 1) for dx in (-1, 0, 1)]
DILATED = [(dy * 2, dx * 2) for dy in (-1, 0, 1) for dx in (-1, 0, 1)]
UNION = sorted(set(N3) | set(DILATED))
N5 = [(dy, dx) for dy in range(-2, 3) for dx in range(-2, 3)]


def median_over(field, original, offsets, passes=10):
    """vector_median with the neighbourhood as a parameter."""
    current = field
    reach = max(max(abs(a), abs(b)) for a, b in offsets)
    for _ in range(passes):
        h, w = current.shape[:2]
        padded = np.pad(current, ((reach, reach), (reach, reach), (0, 0)), mode="edge")
        stack = np.stack([padded[reach + dy:reach + dy + h, reach + dx:reach + dx + w]
                          for dy, dx in offsets] + [original])
        d = np.abs(stack[:, None] - stack[None, :]).sum(axis=-1).sum(axis=1)
        current = np.take_along_axis(stack, np.argmin(d, axis=0)[None, ..., None],
                                     axis=0)[0]
    return current


def main():
    print(__doc__.split("\n\n")[0])
    print()
    print("a 32px object crossing a flat field -- island.py's cursor")
    print("  %-10s %-18s %14s %9s" % ("shift", "filter", "object err", "kept"))
    for shift in ((24, 0), (40, 8)):
        newer, older, _, s = I.scene(shift)
        raw = bench.estimate(newer, older, radius=P.WINDOW)
        obj = I.object_blocks(s)
        want = np.array([-s[0], -s[1]], dtype=np.float32)
        variants = [("3x3 (shipped)", median_over(raw, raw, N3)),
                    ("3x3 dilated x2", median_over(raw, raw, DILATED)),
                    ("union of both", median_over(raw, raw, UNION)),
                    ("5x5", median_over(raw, raw, N5))]
        base = variants[0][1]
        variants.append(("3x3 + temporal",
                         vector_median(raw, raw, extra=base)))
        for name, f in variants:
            err = np.linalg.norm(f[obj] - want, axis=-1).mean()
            kept = 100.0 * (np.linalg.norm(f[obj], axis=-1)
                            > 0.5 * np.linalg.norm(want)).mean()
            print("  %-10s %-18s %13.2f %8.0f%%" % (str(s), name, err, kept))
        print()


if __name__ == "__main__":
    main()
