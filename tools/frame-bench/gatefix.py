"""Does loosening `carry` actually reconstruct the real frame better?

WHY THIS EXISTS AND WHY IT IS NOT OPTIONAL. carry.py shows the shipped gate
keeps 73% of a motion correction that partial.py independently measures as 73%
present on the panel. That identifies the leak. It does NOT license widening
the gate, because the gate is not a mistake -- it is what pulls the result back
towards a stationary reading where motion is refuted, and its stated job is to
keep a subtitle or a HUD from being dragged around by a vector that belongs to
the scene behind it. Two changes have already shipped this session on a
mechanism that looked sound and came back worse from the device.

So the only question worth asking is the one that has an answer: for each
candidate curve, does the reconstructed frame land CLOSER to the frame the
guest actually drew?

WHAT IS SCORED. The recording holds the truth: three consecutive real frames,
the middle one being the correct answer for the pair around it. Each curve
reconstructs that middle frame through a transcription of the shader -- the
same bilateral fetch, the same endpoint weights, the same still fallback -- and
is scored on how far it lands from the truth.

  overall     mean error over the frame
  moving      error where the scene actually moved, which is what the curve
              changes and where a whole-frame mean would dilute it
  static      error where NOTHING moved. **This is the safety column.** It is
              where overlays and HUD live, and it is what a looser gate risks.
              A curve that wins overall while losing here is trading a visible
              artefact for a worse one.

WHAT IS SIMPLIFIED, HONESTLY. The field is dense per-pixel DIS flow, so the
four-vector OBMC blend is not reproduced; the sign latch is not reproduced
either, since a validated flow has no sign ambiguity. Both affect all curves
equally, so the COMPARISON stands even though the absolute errors are not the
device's.

    python gatefix.py recording.mp4 [--triples 40]
"""
import sys

import numpy as np

import carry as K
import partial as PA


def reconstruct(A, C, v, ratio_gate, phase=0.5):
    """InterpolateMaterial, transcribed, with the gate curve left open."""
    lo, ln = K.luma(A), K.luma(C)
    H, W = lo.shape
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    grid = np.stack([xx, yy], axis=-1)
    mn = grid - v * (1.0 - phase)
    mo = grid + v * phase

    fit_still = np.abs(ln - lo)
    fit_moving = np.abs(K.sample(ln, mn) - K.sample(lo, mo))
    ratio = fit_still / (fit_still + fit_moving + 1.0 / 2550.0)
    carry = ratio_gate(ratio)[..., None]

    still_newer = 1.0 - K.smoothstep(2 / 255.0, 12 / 255.0,
                                     np.abs(K.sample(ln, mn) - K.sample(lo, mn)))
    still_older = 1.0 - K.smoothstep(2 / 255.0, 12 / 255.0,
                                     np.abs(K.sample(ln, mo) - K.sample(lo, mo)))
    moves = K.smoothstep(2 / 255.0, 12 / 255.0, fit_still)
    w_old = (1.0 - phase) * (1.0 - still_older * moves)
    w_new = phase * (1.0 - still_newer * moves)
    dead = (w_old + w_new) < 1e-3
    w_old = np.where(dead, 1.0 - phase, w_old)[..., None]
    w_new = np.where(dead, phase, w_new)[..., None]

    shown = (w_old * K.sample(A, mo) + w_new * K.sample(C, mn)) / (w_old + w_new)
    still = A * (1.0 - phase) + C * phase
    return still * (1.0 - carry) + shown * carry, fit_still


def main():
    limit = 40
    if "--triples" in sys.argv:
        limit = int(sys.argv[sys.argv.index("--triples") + 1])
    path = [a for a in sys.argv[1:] if not a.startswith("--") and a != str(limit)][0]

    trips = [t for t in PA.real_triples(path)
             if PA.X.rms(t[0], t[2]) > 0.05][:limit]
    if len(trips) < 6:
        sys.exit("not enough moving real triples")

    curves = [
        ("smoothstep(0.30, 0.70)  SHIPPED", lambda r: K.smoothstep(0.30, 0.70, r)),
        ("smoothstep(0.25, 0.62)", lambda r: K.smoothstep(0.25, 0.62, r)),
        ("smoothstep(0.20, 0.55)", lambda r: K.smoothstep(0.20, 0.55, r)),
        ("smoothstep(0.15, 0.50)", lambda r: K.smoothstep(0.15, 0.50, r)),
        ("smoothstep(0.10, 0.45)", lambda r: K.smoothstep(0.10, 0.45, r)),
        ("no gate at all (carry = 1)", lambda r: np.ones_like(r)),
    ]
    acc = {n: [[], [], []] for n, _ in curves}
    base = [[], [], []]

    for A, B, C in trips:
        v = None
        f = K.flow(A, C)
        H, W = f.shape[:2]
        yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
        grid = np.stack([xx, yy], axis=-1)
        best = None
        for cand in (-f, f):
            rec = 0.5 * K.sample(C, grid - cand * 0.5) + 0.5 * K.sample(A, grid + cand * 0.5)
            e = float(np.sqrt(((rec - B) ** 2).mean()))
            if best is None or e < best:
                best, v = e, cand

        for name, fn in curves:
            out, fit_still = reconstruct(A, C, v, fn)
            err = np.sqrt(((out - B) ** 2).mean(axis=2))
            mv = fit_still > 2 / 255.0
            acc[name][0].append(float(err.mean()))
            if mv.any():
                acc[name][1].append(float(err[mv].mean()))
            if (~mv).any():
                acc[name][2].append(float(err[~mv].mean()))
        blend = (A + C) * 0.5
        eb = np.sqrt(((blend - B) ** 2).mean(axis=2))
        mv = np.abs(K.luma(C) - K.luma(A)) > 2 / 255.0
        base[0].append(float(eb.mean()))
        if mv.any():
            base[1].append(float(eb[mv].mean()))
        if (~mv).any():
            base[2].append(float(eb[~mv].mean()))

    print("%d moving triples from %s\n" % (len(trips), path))
    print("  error against the frame the guest actually drew (lower is better)\n")
    print("  %-34s %10s %10s %10s %9s"
          % ("gate curve", "overall", "moving", "static", "vs ship"))
    ship = np.mean(acc[curves[0][0]][0])
    for name, _ in curves:
        v = acc[name]
        print("  %-34s %10.5f %10.5f %10.5f %8.1f%%"
              % (name, np.mean(v[0]), np.mean(v[1]), np.mean(v[2]),
                 100.0 * (np.mean(v[0]) / ship - 1.0)))
    print("  %-34s %10.5f %10.5f %10.5f" % ("(blend, for reference)",
                                            np.mean(base[0]), np.mean(base[1]),
                                            np.mean(base[2])))
    print()
    print("  Read the STATIC column before the others. It is where overlays and")
    print("  HUD live and it is what a looser gate puts at risk; a curve that")
    print("  wins overall while losing there is trading one artefact for a worse")
    print("  one, which is how two changes already came back from the device.")


if __name__ == "__main__":
    main()
