"""Reproduce the waviness on straight edges, and measure it as waviness.

THE HYPOTHESIS. Under a camera pan everything in a 3D scene moves, so a block
cannot disagree with its neighbour about whether there is motion -- only about
how much. Depth parallax supplies that: something near the camera moves faster
than the wall behind it, and an 8x8 block straddling the two reports one
compromise. The next block along straddles them in a slightly different
proportion and reports a slightly different compromise. A straight edge
reconstructed through a row of blocks that each compromise differently is a
wavy edge.

WHY RMS ERROR CANNOT SEE IT. A wavy edge and a straight one contain the same ink
in almost the same places; de Haan's 1993 paper makes the same point about its own
metrics. So this measures the edge's *geometry*: find the edge's sub-pixel
crossing on every scanline, fit the straight line those crossings should form,
and report how far they deviate from it. A straight edge scores near zero however
wrong its position; a wavy one scores the amplitude of the wave, in pixels.
"""
import sys, os
import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bench, interp

HERE = os.path.dirname(os.path.abspath(__file__))
W, H = 1280, 720

# A near object and the wall behind it, moving at different rates because they
# are at different depths under the same camera pan.
FAR = (40, 0)     # the wall
NEAR = (68, 0)    # the beam, closer, so it sweeps further
EDGE_X = 620      # where the beam's straight vertical edge sits at the midpoint


def compose(t):
    """The scene at time t in [0,1], both layers part-way along their own motion."""
    bg = Image.open(os.path.join(HERE, "bg.png")).convert("RGB")
    out = Image.new("RGB", (W, H))
    out.paste(bg, (int(round(FAR[0] * t)), int(round(FAR[1] * t))))

    # The beam: a tall bright column with a hard vertical edge, the kind of thing
    # the ripple is actually reported on -- a girder, a panel edge, a stripe.
    beam = Image.new("RGB", (150, H), (150, 140, 120))
    d = np.asarray(beam).astype(np.float32)
    # Some texture, so the matcher has something to lock onto rather than a flat
    # card that matches equally well everywhere along its length.
    rng = np.random.default_rng(7)
    d += rng.normal(0, 9, d.shape)
    for y in range(0, H, 37):
        d[y:y + 5, :, :] *= 0.55
    beam = Image.fromarray(np.clip(d, 0, 255).astype(np.uint8))
    ox = int(round(EDGE_X - 150 + (NEAR[0] * (t - 0.5))))
    out.paste(beam, (ox, 0))
    return np.asarray(out, dtype=np.float32) / 255.0


def edge_positions(img, x_hint, half=40):
    """Sub-pixel x of the strongest vertical edge on each scanline."""
    g = interp.luma(img)
    lo, hi = max(1, x_hint - half), min(W - 1, x_hint + half)
    band = g[:, lo:hi]
    d = np.abs(np.diff(band, axis=1))
    k = np.argmax(d, axis=1)
    rows = np.arange(band.shape[0])
    # Parabolic refinement on the gradient peak, so a wave smaller than a pixel
    # is still measured rather than quantised away.
    k = np.clip(k, 1, d.shape[1] - 2)
    a, b, c = d[rows, k - 1], d[rows, k], d[rows, k + 1]
    denom = (a - 2 * b + c)
    shift = np.where(np.abs(denom) > 1e-6, 0.5 * (a - c) / denom, 0.0)
    return lo + k + np.clip(shift, -1, 1), d[rows, k]


def waviness(img, x_hint):
    """RMS deviation of the edge from the straight line it should be, in pixels."""
    xs, strength = edge_positions(img, x_hint)
    keep = strength > np.percentile(strength, 40)
    ys = np.arange(H)[keep]
    xs = xs[keep]
    fit = np.polyfit(ys, xs, 1)
    return float(np.sqrt(np.mean((xs - np.polyval(fit, ys)) ** 2))), keep.sum()


older, truth, newer = compose(0.0), compose(0.5), compose(1.0)
field = bench.estimate(newer, older)

gh, gw = field.shape[:2]
col = EDGE_X // 8
print("depth parallax: wall %s, beam %s (a %d px difference across the edge)"
      % (FAR, NEAR, NEAR[0] - FAR[0]))
print("field either side of the edge: left %s  right %s"
      % (np.round(field[H // 16, max(0, col - 3)], 1),
         np.round(field[H // 16, min(gw - 1, col + 3)], 1)))

print()
print("%-30s waviness(px)  edge error" % "")
ref, _ = waviness(truth, EDGE_X)
print("  %-28s %8.3f      %s" % ("ground truth", ref, "-"))
for mode in ("off", "fit"):
    out = interp.interpolate(newer, older, field, 0.5, 1.0, static_mode=mode)
    wav, n = waviness(out, EDGE_X)
    e = np.abs(out - truth).mean(-1) * 255
    band = np.zeros((H, W), bool)
    band[:, EDGE_X - 30:EDGE_X + 30] = True
    print("  %-28s %8.3f      %6.2f" % (mode, wav, e[band].mean()))
    Image.fromarray((np.clip(out[120:400, EDGE_X - 90:EDGE_X + 90], 0, 1) * 255)
                    .astype(np.uint8)).save(os.path.join(HERE, "bench", "zig_%s.png" % mode))
Image.fromarray((np.clip(truth[120:400, EDGE_X - 90:EDGE_X + 90], 0, 1) * 255)
                .astype(np.uint8)).save(os.path.join(HERE, "bench", "zig_truth.png"))
print("\nwrote zig_*.png")
