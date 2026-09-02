import os, sys, time
import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bench
import interp

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "bench")

print("building the pair (real game frame, known shift, static text on both)...")
newer, older, truth = bench.build(shift=(48, 12))
h, w = newer.shape[:2]

t = time.time()
field = bench.estimate(newer, older)
print("8x8 block match over the pair: %.1fs, grid %dx%d"
      % (time.time() - t, field.shape[1], field.shape[0]))

# What the matcher thinks the subtitle rows are doing, versus the truth (zero).
rows = slice((h - 130) // 8, (h - 70) // 8)
print("field on the subtitle rows: mean %s   background rows: mean %s"
      % (np.round(field[rows].reshape(-1, 2).mean(axis=0), 1),
         np.round(field[:20].reshape(-1, 2).mean(axis=0), 1)))

mask = bench.textmask((h, w))

# The shader takes the field in luma pixels and applies fieldSign. The match here
# is measured from older to newer, which is the same convention the device's
# probe settles on, so sign is +1.
print("\nerror against the known-correct midpoint frame:")
results = {}
for mode in ("off", "scale", "blend", "fit"):
    out = interp.interpolate(newer, older, field, phase=0.5, sign=1.0,
                             static_mode=mode)
    results[mode] = out
    bench.report({"off": "no static suppression",
                  "scale": "carry scales the vectors",
                  "blend": "carry blends the predictions",
                  "fit": "per-pixel fit: still vs moving"}[mode],
                 out, truth, mask)

# A plain cross-fade, as the floor any motion compensation has to beat.
cross = older * 0.5 + newer * 0.5
bench.report("cross-fade, no compensation", cross, truth, mask)

for name, img in list(results.items()) + [("truth", truth)]:
    crop = img[h - 140:h - 60, 60:60 + 1160]
    Image.fromarray((np.clip(crop, 0, 1) * 255).astype(np.uint8)).save(
        os.path.join(OUT, "text_%s.png" % name))
print("\nwrote text_*.png crops to bench/")
