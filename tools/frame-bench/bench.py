"""A ground-truthed test bench for the subtitle ghost, entirely on the laptop.

WHY. On the device the subtitle is up for two or three seconds, so it cannot be
captured on purpose; every recording catches a different scene at a different
camera speed; and one build-install-launch-play-record-analyse cycle costs ten
minutes to test one line of shader. The first attempt to measure the fix that way
read 10.6% ghost spill before and 27.0% after -- on recordings of different
lengths of different action, which means it measured nothing.

WHAT THIS IS INSTEAD. A real 1280x720 game frame, shifted by an exactly known
vector to make the pair, with static text composited on top of both at the same
place -- which is precisely what a subtitle is. Because the shift is known, the
correct interpolated frame is known too: the background at half the shift, and
the text exactly where it was. So the error is measurable rather than arguable,
separately inside the text and outside it, and two algorithms can be compared on
one input with only the algorithm changing.

The motion field is not invented. It is an 8x8 exhaustive block match on luma,
which is what GL_QCOM_motion_estimation does in hardware -- so blocks straddling
the subtitle arrive at the same compromise here that they do on the device, and
the artefact appears for the same reason rather than by construction.
"""
import os
import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
BENCH = os.path.join(HERE, "bench")
BLOCK = 8

TEXT = "That's nice for him - I had to spend two years as a cadet"


def build(shift=(48, 12)):
    """The pair, and the frame that should sit exactly between them."""
    bg = Image.open(os.path.join(BENCH, "bg.png")).convert("RGB")
    w, h = bg.size

    def shifted(dx, dy):
        out = Image.new("RGB", (w, h))
        out.paste(bg, (int(round(dx)), int(round(dy))))
        # Clamp-to-edge at the borders, the same as the shader's sampler, so the
        # margins do not contribute error that no algorithm could avoid.
        return out

    def with_text(img):
        out = img.copy()
        draw = ImageDraw.Draw(out)
        try:
            font = ImageFont.truetype("C:/Windows/Fonts/arialbd.ttf", 34)
        except OSError:
            font = ImageFont.load_default()
        # Metro's subtitle colour and position: saturated orange, low centre.
        draw.text((90, h - 120), TEXT, fill=(235, 110, 20), font=font)
        return out

    older = with_text(shifted(0, 0))
    newer = with_text(shifted(shift[0], shift[1]))
    truth = with_text(shifted(shift[0] / 2.0, shift[1] / 2.0))

    to = lambda im: np.asarray(im, dtype=np.float32) / 255.0
    return to(newer), to(older), to(truth)


def luma(rgb):
    return rgb @ np.array([0.299, 0.587, 0.114], dtype=np.float32)


def estimate(newer, older, radius=64):
    """An 8x8 exhaustive block match, standing in for the hardware matcher.

    Searched on integer pixels over a window wide enough to contain the shift,
    minimising SAD from `older` to `newer` -- the same quantity and the same
    block size the extension uses, so a block holding both a static glyph and a
    moving background reaches the same compromise it does on the device.
    """
    ln, lo = luma(newer), luma(older)
    h, w = ln.shape
    gh, gw = h // BLOCK, w // BLOCK
    field = np.zeros((gh, gw, 2), dtype=np.float32)

    # Candidates on a coarse-to-fine lattice; exhaustive at every integer offset
    # over +-64 in both axes would be 16k SADs per block and buys nothing here.
    offs = [(dx, dy)
            for dy in range(-radius, radius + 1, 4)
            for dx in range(-radius, radius + 1, 4)]

    pad = radius + BLOCK
    lop = np.pad(lo, pad, mode="edge")

    for by in range(gh):
        y0 = by * BLOCK
        target = ln[y0:y0 + BLOCK, :]
        best = np.full(gw, np.inf, dtype=np.float32)
        bestv = np.zeros((gw, 2), dtype=np.float32)
        for dx, dy in offs:
            ref = lop[y0 + pad + dy: y0 + pad + dy + BLOCK,
                      pad + dx: pad + dx + w]
            sad = np.abs(target - ref).reshape(BLOCK, gw, BLOCK).sum(axis=(0, 2))
            better = sad < best
            best = np.where(better, sad, best)
            bestv[better] = (dx, dy)
        field[by] = bestv
    return field


def report(name, out, truth, textmask):
    err = np.abs(out - truth).mean(axis=-1)
    inside = err[textmask].mean() * 255.0
    outside = err[~textmask].mean() * 255.0
    print("  %-34s text %6.2f   scene %6.2f   (levels of 255, lower is better)"
          % (name, inside, outside))
    return inside, outside


def textmask(shape):
    """Where the subtitle is, so error can be scored separately there."""
    h, w = shape
    m = np.zeros((h, w), dtype=bool)
    m[h - 130:h - 70, 80:w - 80] = True
    return m
