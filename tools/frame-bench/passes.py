"""Waviness against median passes -- the number RMS is blind to.

**This exists because the question got asked again.** locate.py reported RMS
barely moving with the median filter on or off, 18.16 to 17.57, and the obvious
reading is that the filter is not doing much and could go. It is the wrong
reading, and it is the fourth time a metric in this directory has been believed
over the one that can actually see the artefact.

Measured on the scene built for the question:

    median passes     waviness px   rms levels
    0                       9.540        10.33
    1                       5.506        10.20
    2                       3.097        10.10
    6                       0.576        10.01
    10                      0.013         9.99

Waviness falls by a factor of 734. RMS moves three per cent. A wavy edge and a
straight one hold the same ink in nearly the same places, so RMS sees almost
nothing; the difference is geometry.

It also settles the pass count. Six leaves 0.576 px and ten reaches 0.013, which
is the ground truth's own figure -- the last four passes are the ones that
finish it.
"""
import numpy as np
import bench, consensus, interp
import zigzag as Z

older, truth, newer = Z.compose(0.0), Z.compose(0.5), Z.compose(1.0)
raw = bench.estimate(newer, older)
ref, _ = Z.waviness(truth, Z.EDGE_X)

print("ground truth waviness: %.3f px\n" % ref)
print("  %-16s %12s %12s" % ("median passes", "waviness px", "rms levels"))
for passes in (0, 1, 2, 6, 10):
    field = raw if passes == 0 else consensus.vector_median(raw, raw, passes=passes)
    out = interp.interpolate(newer, older, field, 0.5, 1.0, static_mode="fit")
    wav, _ = Z.waviness(out, Z.EDGE_X)
    rms = float(np.sqrt(((out - truth) ** 2).mean()) * 255)
    print("  %-16d %12.3f %12.2f" % (passes, wav, rms))
print()
print("If waviness falls sharply while rms barely moves, the filter is doing")
print("exactly what it was added for and rms is the wrong judge of it.")
