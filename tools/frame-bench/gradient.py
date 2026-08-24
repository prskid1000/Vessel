"""The case both other bench scenes cannot show: motion that varies smoothly.

WHY THIS EXISTS. The anchored median keeps improving the straight-edge test right
up to ten passes, where it reaches the ground truth's own figure exactly. That
number is suspicious rather than triumphant: reaching it means the field
converged to piecewise constant, and both existing bench scenes contain only
rigid motions, for which piecewise constant is the correct answer.

A real camera pan is not that. Depth parallax makes near things sweep further
than far ones, so the field is a smooth gradient, and a filter that drives the
field towards locally-constant would staircase it -- replacing a ramp with a
flight of steps, each step a block wide. That would look like banding in the
motion, and neither existing scene could detect it.

So this one is a ground plane: horizontal displacement rising linearly from the
horizon to the bottom of the frame, which is what a floor does under a pan. The
question it answers is whether the pass count that wins on rigid motion is the
same one that wins here, or whether the two disagree.
"""
import os
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
W, H = 1280, 720
NEAR, FAR = 72.0, 8.0        # pixels of sweep at the bottom of frame and at the horizon
HORIZON = 180                # rows above this are sky and barely move


def _remap(img, shift_x):
    """Sample the image at x - shift_x(y), with clamp-to-edge."""
    ys, xs = np.mgrid[0:H, 0:W].astype(np.float32)
    sx = np.clip(xs - shift_x[:, None], 0, W - 1)
    x0 = np.floor(sx).astype(np.int32)
    x1 = np.minimum(x0 + 1, W - 1)
    f = (sx - x0)[..., None]
    rows = ys.astype(np.int32)
    return img[rows, x0] * (1 - f) + img[rows, x1] * f


def profile():
    """How far each row moves: zero in the sky, rising linearly to the front."""
    y = np.arange(H, dtype=np.float32)
    t = np.clip((y - HORIZON) / float(H - HORIZON), 0.0, 1.0)
    return FAR + (NEAR - FAR) * t


def compose(t):
    bg = np.asarray(Image.open(os.path.join(HERE, "bg.png"))
                    .convert("RGB"), dtype=np.float32) / 255.0
    return np.clip(_remap(bg, profile() * t), 0.0, 1.0)


def banding(field, sign=1.0):
    """How step-like the field's horizontal component is down the frame.

    The truth is a straight ramp, so its second difference down the rows is zero.
    A staircase has a spike at every step. Measuring the second difference of the
    per-row mean therefore reports exactly the artefact this scene exists to
    detect, and reports nothing for a field that is merely wrong-but-smooth.
    """
    rows = field[..., 0].mean(axis=1) * sign
    return float(np.abs(np.diff(rows, n=2)).mean())
