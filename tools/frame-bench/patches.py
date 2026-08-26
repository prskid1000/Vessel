"""The column the bench was missing when it approved a change that broke the screen.

WHAT HAPPENED. A hypothesis-weighting change scored -2.3% mean error, -4.3% on
parallax blocks and below-baseline speckle on both captures, was installed, and
put black patches on the screen within minutes. Every column that approved it is
an average or a high-frequency statistic. A black patch is neither: it is a
connected region whose prediction fetched from somewhere with no content, which
moves a mean by very little and in a dark scene can improve it. This is the
third time in this project that a whole-frame number has read clean while the
picture was visibly broken.

So this file scores the two things the eye notices immediately and no mean can
see.

  fetched off the frame   Every prediction clamps its fetch to [0,1]. A vector
                          that is wrong in scale or sign does not displace a
                          pixel by a bit too much -- it pins the fetch to the
                          frame edge, which on a letterboxed guest is the black
                          bar. Counting the fetches that WOULD have gone outside
                          before the clamp catches this at its source, per
                          hypothesis, which is what says whether the fifth one
                          is to blame.
  black patches           Connected regions dark in the synthesised frame and
                          dark in NEITHER real frame. That is a patch that was
                          invented, by definition, and it is reported as an area
                          share and as a count of 32px cells so that one large
                          hole is told apart from a scatter of dark pixels.

WHAT IT IS RUN AGAINST. The reverted weighting, rebuilt here exactly as it
shipped -- per-block fit, 3x3 blurred on the field, inverse-variance weights,
and the sparse dominant vector as the fifth hypothesis -- beside the baseline it
replaced. If it reproduces the patches, the mechanism is in the maths and this
becomes the gate that any retry has to pass. If it does NOT, the fault is in the
GL plumbing rather than the model, and no amount of laptop scoring would ever
have found it -- which is worth knowing before the next attempt.

    python patches.py a.mp4 [b.mp4 ...] [--triples 16]
"""
import sys

import numpy as np

import candidates as CD
import carry as K
import partial as PA

FLOOR = 2.0 / 255.0
DARK = 0.06          # luma below which a pixel reads as black on this footage
CELL = 32


def offsets(v, phase=0.5):
    """Where the two fetches land, in texture coordinates, before any clamp."""
    H, W = v.shape[:2]
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    grid = np.stack([xx, yy], axis=-1)
    return grid - v * (1.0 - phase), grid + v * phase


def escaped(v, shape):
    """Share of pixels whose fetch would leave the frame and be clamped to it."""
    H, W = shape
    mn, mo = offsets(v)
    out = ((mn[..., 0] < 0) | (mn[..., 0] > W - 1) | (mn[..., 1] < 0) | (mn[..., 1] > H - 1)
           | (mo[..., 0] < 0) | (mo[..., 0] > W - 1) | (mo[..., 1] < 0) | (mo[..., 1] > H - 1))
    return float(out.mean() * 100.0), out


def invented_dark(shown, A, C):
    """Dark here, and dark in neither source. Area share and clumping."""
    ls, la, lc = K.luma(shown), K.luma(A), K.luma(C)
    bad = (ls < DARK) & (la >= DARK) & (lc >= DARK)
    H, W = bad.shape
    gh, gw = H // CELL, W // CELL
    cells = bad[:gh * CELL, :gw * CELL].reshape(gh, CELL, gw, CELL).mean(axis=(1, 3))
    return float(bad.mean() * 100.0), int((cells > 0.5).sum()), gh * gw


def build(A, B, C):
    """Baseline and the reverted change, rebuilt as they shipped."""
    f = K.flow(A, C)
    H, W = f.shape[:2]
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    grid = np.stack([xx, yy], axis=-1)
    v, best = None, None
    for cand in (-f, f):
        rec = 0.5 * K.sample(C, grid - cand * 0.5) + 0.5 * K.sample(A, grid + cand * 0.5)
        e = float(np.sqrt(((rec - B) ** 2).mean()))
        if best is None or e < best:
            best, v = e, cand

    fld = CD.blocks(v).astype(np.float32)
    gy, gx = fld.shape[0], fld.shape[1]
    iy = np.linspace(0, gy - 1, 5).astype(int)
    ix = np.linspace(0, gx - 1, 5).astype(int)
    g25 = fld[np.ix_(iy, ix)]
    dom = np.array([np.mean(g25[..., 0]), np.mean(g25[..., 1])], dtype=np.float32)

    ln, lo = K.luma(C), K.luma(A)
    fldp = np.repeat(np.repeat(fld, CD.BLOCK, 0), CD.BLOCK, 1)[:H, :W]
    vs, ws = CD.four(fld, (H, W))
    preds = [CD.predict(x, A, C, grid) for x in vs]
    domv = np.broadcast_to(dom, (H, W, 2))
    dpred = CD.predict(domv, A, C, grid)

    own = np.abs(K.sample(ln, grid - fldp * 0.5) - K.sample(lo, grid + fldp * 0.5))
    fit = CD.box(CD.blocks(np.stack([own, own], axis=-1))[..., 0], 1)
    fvs, _ = CD.four(fit[..., None], (H, W))
    fdom = CD.box(CD.cost_of(domv, ln, lo, grid), CD.BLOCK)

    base = sum(w[..., 0][..., None] * p for w, p in zip(ws, preds))
    tw = [w[..., 0] / np.square(f_[..., 0] + FLOOR) for w, f_ in zip(ws, fvs)]
    tw = tw + [0.25 / np.square(fdom + FLOOR)]
    tot = sum(tw) + 1e-9
    changed = sum((s / tot)[..., None] * p for s, p in zip(tw, preds + [dpred]))

    # How much of the picture the fifth hypothesis actually won.
    share = float((tw[4] / tot).mean() * 100.0)
    return base, changed, v, domv, vs, (H, W), share


def main():
    limit = 16
    if "--triples" in sys.argv:
        limit = int(sys.argv[sys.argv.index("--triples") + 1])
    paths = [a for a in sys.argv[1:] if not a.startswith("--") and a != str(limit)]
    if not paths:
        sys.exit(__doc__)

    for path in paths:
        trips = [t for t in PA.real_triples(path)
                 if PA.X.rms(t[0], t[2]) > 0.05][:limit]
        rows = {"baseline (shipped OBMC)": [], "the reverted change": []}
        esc_block, esc_dom, dom_share = [], [], []
        for A, B, C in trips:
            base, changed, v, domv, vs, shape, share = build(A, B, C)
            for name, img in (("baseline (shipped OBMC)", base),
                              ("the reverted change", changed)):
                rows[name].append(invented_dark(img, A, C))
            esc_block.append(escaped(np.mean(np.stack(vs), axis=0), shape)[0])
            esc_dom.append(escaped(domv, shape)[0])
            dom_share.append(share)

        print("\n=== %s (%d triples) ===" % (path, len(trips)))
        print("  %-26s %12s %14s" % ("", "black area", "32px cells"))
        for name in ("baseline (shipped OBMC)", "the reverted change"):
            a = np.array([r[0] for r in rows[name]])
            c = np.array([r[1] for r in rows[name]])
            print("  %-26s %11.3f%% %9.1f of %d"
                  % (name, a.mean(), c.mean(), rows[name][0][2]))
        print()
        print("  %-40s %8.1f%%" % ("fetches leaving the frame, block vectors",
                                   np.mean(esc_block)))
        print("  %-40s %8.1f%%" % ("fetches leaving the frame, DOMINANT vector",
                                   np.mean(esc_dom)))
        print("  %-40s %8.1f%%" % ("weight the dominant hypothesis takes",
                                   np.mean(dom_share)))
    print()
    print("  If the reverted row shows black area the baseline does not, the")
    print("  mechanism is in the maths and this is the gate a retry must pass.")
    print("  If both rows are clean, the model cannot reproduce what the device")
    print("  did, the fault is in the GL plumbing -- a stale bind, the sign")
    print("  latch racing the fit pass -- and no laptop scoring would find it.")


if __name__ == "__main__":
    main()
