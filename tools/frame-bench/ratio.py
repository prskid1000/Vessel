"""Calibrate the replacement for `harm` before it ships.

The metric it replaces was never checked this way and read 10-28% for a month
while measuring nothing. The two rows that matter here are not opinions about
the pipeline, they are tests of the ruler:

  the real middle frame   must read 0%.   It IS the truth.
  hold the older frame    must read 100%. It is what the ratio is defined
                          against, so numerator and denominator are the same
                          quantity and any other answer means the formula is
                          wrong.
"""
import sys
import numpy as np, carry as K, partial as PA

CHANGED = 0.008

def closeness(shown, older, truth):
    d_synth = np.linalg.norm(shown - truth, axis=-1)
    d_base = np.linalg.norm(older - truth, axis=-1)
    moved = d_base > CHANGED
    if not moved.any():
        return None
    # Exactly what the shader accumulates and the Java divides.
    return float(np.minimum(d_synth[moved], 1.0).mean()
                 / max(np.minimum(d_base[moved], 1.0).mean(), 1e-6))

path = sys.argv[1]
trips = [t for t in PA.real_triples(path) if PA.X.rms(t[0], t[2]) > 0.05][:40]
rows = {"the real middle frame": [], "hold the older frame": [],
        "a plain blend": [], "hold the NEWER frame": []}
for A, B, C in trips:
    for name, img in (("the real middle frame", B), ("hold the older frame", A),
                      ("a plain blend", (A + C) * 0.5), ("hold the NEWER frame", C)):
        v = closeness(img, A, B)
        if v is not None:
            rows[name].append(v)

print("%d moving triples from %s\n" % (len(trips), path))
print("  %-26s %10s %10s" % ("answer shown", "reads", "should be"))
for name, want in (("the real middle frame", "0%"), ("hold the older frame", "100%"),
                   ("a plain blend", "<100%"), ("hold the NEWER frame", "-")):
    v = np.array(rows[name])
    if v.size:
        print("  %-26s %9.1f%% %10s" % (name, v.mean() * 100, want))
print()
print("  If the first two are not 0 and 100, the formula is wrong and nothing")
print("  measured with it would mean what it appears to mean.")
