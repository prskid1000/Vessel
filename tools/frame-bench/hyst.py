"""Does hysteresis stop the guard's decision flipping, without smearing?"""
import numpy as np
import bench, consensus
import pyramid as P
import island as I
BLOCK = P.BLOCK
NOISE = 0.004

def block_sad(newer, older, field, phase=0.5):
    ln, lo = bench.luma(newer), bench.luma(older)
    h, w = ln.shape; gh, gw = field.shape[:2]
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    fx = np.repeat(np.repeat(field[..., 0], BLOCK, 0), BLOCK, 1)[:h, :w]
    fy = np.repeat(np.repeat(field[..., 1], BLOCK, 0), BLOCK, 1)[:h, :w]
    def s(img, sx, sy):
        return img[np.clip(np.round(sy), 0, h - 1).astype(int),
                   np.clip(np.round(sx), 0, w - 1).astype(int)]
    d = np.abs(s(lo, xs + fx * phase, ys + fy * phase)
               - s(ln, xs - fx * (1 - phase), ys - fy * (1 - phase)))
    return d[:gh * BLOCK, :gw * BLOCK].reshape(gh, BLOCK, gw, BLOCK).mean(axis=(1, 3))

def shift_hist(hist, own):
    """Read the history where this content was one interval ago."""
    gh, gw = hist.shape
    ys, xs = np.mgrid[0:gh, 0:gw]
    bx = np.clip(np.round(xs + own[..., 0] / BLOCK).astype(int), 0, gw - 1)
    by = np.clip(np.round(ys + own[..., 1] / BLOCK).astype(int), 0, gh - 1)
    return hist[by, bx]

def run(shift, on=20.0, off=5.0, frames=10, seed=11, hysteresis=True):
    rng = np.random.default_rng(seed)
    hist = None
    seq, fields = [], []
    for i in range(frames):
        n, o, t, s = I.scene(shift)
        n = np.clip(n + rng.normal(0, NOISE, n.shape), 0, 1).astype(np.float32)
        o = np.clip(o + rng.normal(0, NOISE, o.shape), 0, 1).astype(np.float32)
        r = bench.estimate(n, o, radius=P.WINDOW)
        fl = consensus.vector_median(r, r, passes=10)
        ratio = block_sad(n, o, fl) / np.maximum(block_sad(n, o, r), 1e-5)
        if hysteresis and hist is not None:
            was = (shift_hist(hist, r) > 0.5).astype(np.float32)
            bar = on * (1.0 - was) + off * was
        else:
            bar = np.full(ratio.shape, on, dtype=np.float32)
        w = (ratio > bar).astype(np.float32)
        hist = w
        seq.append(w)
        f = fl.copy(); f[w > 0.5] = r[w > 0.5]
        fields.append(f)
    obj = I.object_blocks(shift)
    churn = float(np.abs(np.diff(np.array(seq)[:, obj], axis=0)).mean())
    n, o, t, s = I.scene(shift)
    tr, _ = I.ghost_ends(P.warp(n, o, fields[-1]), t, s)
    return churn, tr, float(np.array(seq).mean() * 100)

print("  %-10s %22s %24s" % ("speed", "no hysteresis", "hysteresis 20 on / 5 off"))
print("  %-10s %22s %24s" % ("", "churn / ghost / fired%", "churn / ghost / fired%"))
for shift in ((6, 0), (16, 0), (24, 0), (48, 0), (96, 0), (160, 0)):
    a = run(shift, hysteresis=False)
    b = run(shift, hysteresis=True)
    print("  %-10s %22s %24s"
          % ("%d px" % shift[0],
             "%.3f / %6.2f / %.2f" % a, "%.3f / %6.2f / %.2f" % b))
